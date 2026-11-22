/*
 * Copyright (C) 2026 Mercury Li
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package li.mercury.bigbrother.catalog

import li.mercury.bigbrother.catalog.FakeAndroid.Companion.UID

/**
 * What the daemon tells the app ops helper about one app, and keeps of its
 * answers, as `bbd/src/sandbox/appops.rs` does (`enable`, `update`,
 * `disable`, `found`, and `Sandbox::release_reassigned` for an app
 * uninstalled while sandboxed), for the items the permission matrix plays
 * with: contacts, calendar, location and camera, all on at first. What the
 * helper reports found is kept in the entry once the command or check that
 * found it is over, as the daemon's main loop is woken to do. The on-device
 * check of the same matrix runs the daemon itself.
 */
internal class DaemonDouble(private val android: FakeAndroid) {
    private var helper = AppOpsGuardHandler(android)

    private val items = linkedMapOf("contacts" to "on", "calendar" to "on", "location" to "on", "camera" to "on")

    var sandboxed = false
        private set

    /** The entry's `saved_ops` and `saved_permissions`. */
    private var savedOps = ""
    private var savedPermissions = ""

    /** What the helper is told the uid is held to (`State::guarded`). */
    private var guarded: String? = null

    /** The permissions a helper last reported unheld (`State::unsure`), kept while the daemon runs. */
    private var unsure = emptySet<String>()

    /** The permissions the helper found changed, not yet kept in the entry. */
    private val found = mutableListOf<String>()

    private var pendingUndo: String? = null
    private var delayedReply: String? = null

    fun enterWithInterruptedReply(targets: String, delayed: Boolean) {
        check(pendingUndo == null)
        val from = android.printed.size
        sendTransaction("capture $UID 1 $targets")
        val saved = android.printed.drop(from).single().removePrefix("captured $UID 1 ")
        pendingUndo = "undo $UID $saved"
        sendTransaction("set $UID $targets")
        if (delayed) delayedReply = android.printed.last()
    }

    fun recoverInterruptedChange() {
        val command = checkNotNull(pendingUndo)
        val from = android.printed.size
        sendTransaction(command)
        val replies = listOfNotNull(delayedReply) + android.printed.drop(from)
        check(replies.last() == "restored $UID")
        pendingUndo = null
        delayedReply = null
    }

    private fun sendTransaction(line: String) {
        helper.handle(checkNotNull(AppOpsGuardProtocol.parseCommand(line)), transaction = true)
    }

    fun enable() = command {
        enableEffect(items)
        sandboxed = true
    }

    fun disable() = command {
        if (sandboxed) {
            disableEffect(items)
            sandboxed = false
        }
    }

    fun set(item: String, mode: String) = command {
        val before = items.toMap()
        items[item] = mode
        if (sandboxed) update(before, items)
    }

    /** Taken out by the next command, as an app whose uid no longer has its package. */
    fun uninstalled() = command {
        if (sandboxed && !android.installed) {
            // What was saved was the app's that is gone.
            savedOps = ""
            savedPermissions = ""
            disableEffect(items)
            sandboxed = false
        }
    }

    /** The helper ends and is started again, and told of the uid held. */
    fun restartHelper() {
        helper = AppOpsGuardHandler(android)
        tellGuarded()
        keepWhatWasFound()
    }

    /**
     * The daemon is killed, and the helper with it; what the helper found
     * is in the entry already. The daemon that starts takes the entry up
     * again.
     */
    fun restartDaemon() {
        unsure = emptySet()
        helper = AppOpsGuardHandler(android)
        guarded = if (sandboxed) targetsOf(items).ifEmpty { null } else null
        tellGuarded()
        keepWhatWasFound()
    }

    /** The helper's check, as after a reported change. */
    fun check() {
        val from = android.printed.size
        helper.checkAll()
        read(from)
        keepWhatWasFound()
    }

    /** `State::verify`, with the permissions last reported unheld so marked (`marking_unheld`). */
    private fun tellGuarded() {
        val targets = guarded ?: return
        send("verify $UID " + pairs(targets).joinToString(",") { (name, value) -> "$name=${if (name in unsure) "unheld" else value}" })
    }

    /** A command from `bbd sandbox`, which `Sandbox::answer` begins by keeping what was found. */
    private fun command(run: () -> Unit) {
        keepWhatWasFound()
        run()
        keepWhatWasFound()
    }

    /** `Sandbox::keep_found`, which the daemon is woken for when the helper finds something. */
    private fun keepWhatWasFound() {
        keepRegranted(found)
        found.clear()
    }

    /** The daemon saves newly held modes from the transaction preimage. */
    private fun prepareChange(held: String, holding: String) {
        val capture = (pairs(held) + pairs(holding)).toMap().toMutableMap()
        if (holding.isEmpty()) {
            for (permission in namesIn(savedPermissions)) capture[permission] = "revoked"
        }
        val targets = capture.entries.joinToString(",") { (name, mode) -> "$name=$mode" }
        val reply = answer(send("capture $UID 1 $targets", transaction = true), "captured")
        val preimage = pairs(reply.substringAfter(' ', missingDelimiterValue = ""))
        val old = namesIn(held).toSet()
        val next = namesIn(holding).toSet()
        savedOps = (
            pairs(savedOps).filter { (name, _) -> name in old } +
                preimage.filter { (name, _) -> !isPermission(name) && name in next && name !in old }
            )
            .joinToString(",") { (name, mode) -> "$name=$mode" }
    }

    private fun enableEffect(items: Map<String, String>) {
        val targets = targetsOf(items)
        if (targets.isEmpty()) return
        prepareChange("", targets)
        val saved = answer(send("set $UID $targets", transaction = true), "saved")
        guarded = targets
        keepFound(saved)
        tellGuarded()
    }

    private fun disableEffect(items: Map<String, String>) {
        val held = guarded ?: targetsOf(items)
        val names = toGiveBack(held)
        if (names.isEmpty()) return
        prepareChange(held, "")
        answer(send("restore $UID ${savedValuesOf(names)}", transaction = true), "restored")
        guarded = null
        unsure = emptySet()
        savedPermissions = ""
    }

    private fun update(before: Map<String, String>, after: Map<String, String>) {
        val (held, holding) = targetsOf(before) to targetsOf(after)
        if (held == holding) return
        if (held.isEmpty()) return enableEffect(after)
        if (holding.isEmpty()) return disableEffect(before)
        prepareChange(held, holding)
        val letGo = namesIn(held) - namesIn(holding).toSet()
        val resets = if (letGo.isEmpty()) "-" else savedValuesOf(letGo)
        val found = answer(send("apply $UID $holding $resets", transaction = true), "applied")
        guarded = holding
        savedPermissions = pairs(savedPermissions).filter { (name, _) -> name !in letGo }.joinToString(",") { (name, state) -> "$name=$state" }
        keepFound(found)
        tellGuarded()
    }

    /** Send [line] and return what the helper printed for it. */
    private fun send(line: String, transaction: Boolean = false): List<String> {
        val from = android.printed.size
        val command = checkNotNull(AppOpsGuardProtocol.parseCommand(line)) { line }
        try {
            helper.handle(command, transaction)
        } catch (e: Throwable) {
            android.emit(AppOpsGuardProtocol.failedLine(command.uid))
        }
        return read(from)
    }

    /** Take in the lines printed since [from]: a found state is kept for later. */
    private fun read(from: Int): List<String> {
        val lines = android.printed.drop(from)
        for (line in lines) {
            val words = line.split(' ')
            if (words[0] == "reverted" && words.size == 3 && isPermission(words[2].substringBefore('=')) && '=' in words[2]) found += words[2]
            if (words[0] == "unheld" && words.size == 3) unsure = words[2].split(',').filter(::isPermission).toSet()
        }
        return lines
    }

    /** The values in the answer of [kind] among [lines]; it fails the case if the command failed. */
    private fun answer(lines: List<String>, kind: String): String {
        val answer = lines.lastOrNull { it.startsWith("$kind $UID") || it.startsWith("failed $UID") }
        check(answer != null && answer.startsWith(kind)) { "the helper answered ${answer ?: "nothing"} to the daemon" }
        return answer.split(' ', limit = 3).getOrElse(2) { "" }
    }

    /** `targets_of` and `Settings::op_targets`, for the items played with. */
    private fun targetsOf(items: Map<String, String>): String {
        val ops = mutableListOf<String>()
        val permissions = mutableListOf<String>()
        when (items["contacts"]) {
            "on" -> ops += "android:read_contacts=1"
        }
        when (items["calendar"]) {
            "on" -> ops += "android:read_calendar=1"
        }
        when (items["location"]) {
            "on" -> ops += listOf("android:coarse_location=1", "android:fine_location=1", "android:wifi_scan=1")
            "foreground" -> ops += listOf("android:coarse_location=4", "android:fine_location=4", "android:wifi_scan=4")
            "coarse" -> permissions += "${FakeAndroid.FINE}=revoked"
        }
        when (items["camera"]) {
            "on" -> ops += "android:camera=1"
            "foreground" -> ops += "android:camera=4"
        }
        return (ops + permissions).joinToString(",")
    }

    private fun savedValuesOf(names: List<String>): String {
        val saved = (pairs(savedOps) + pairs(savedPermissions)).toMap()
        return names.joinToString(",") { "$it=${saved[it] ?: "default"}" }
    }

    private fun toGiveBack(held: String): List<String> = namesIn(held) + namesIn(savedPermissions).filter { it !in namesIn(held) }

    /** `keep_found`: only for permissions the entry does not name yet. */
    private fun keepFound(states: String) {
        val named = namesIn(savedPermissions)
        val kept = pairs(states).filter { (name, _) -> isPermission(name) && name !in named }
        if (kept.isEmpty()) return
        savedPermissions = (pairs(savedPermissions) + kept).joinToString(",") { (name, state) -> "$name=$state" }
    }

    /** `keep_regranted`: in place of what the entry names. */
    private fun keepRegranted(states: List<String>) {
        val found = states.flatMap(::pairs).toMap()
        savedPermissions = pairs(savedPermissions).joinToString(",") { (name, saved) -> "$name=${found[name] ?: saved}" }
    }

    private fun pairs(list: String): List<Pair<String, String>> = list.split(',').mapNotNull { pair ->
        pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }

    private fun namesIn(list: String): List<String> = pairs(list).map { it.first }

    private fun isPermission(name: String) = AppOpsGuardProtocol.isPermission(name)
}
