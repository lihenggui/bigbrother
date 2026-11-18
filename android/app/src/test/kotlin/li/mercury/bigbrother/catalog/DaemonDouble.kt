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
 * with: contacts, calendar, location and camera, all on at first. The
 * on-device check of the same matrix runs the daemon itself.
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

    /** The permissions the helper found changed, not yet kept in the entry. */
    private val found = mutableListOf<String>()

    fun enable() {
        command()
        enableEffect(items)
        sandboxed = true
    }

    fun disable() {
        command()
        if (!sandboxed) return
        disableEffect(items)
        sandboxed = false
    }

    fun set(item: String, mode: String) {
        command()
        val before = items.toMap()
        items[item] = mode
        if (sandboxed) update(before, items)
    }

    /** Taken out by the next command, as an app whose uid no longer has its package. */
    fun uninstalled() {
        command()
        if (!sandboxed || android.installed) return
        // What was saved was the app's that is gone.
        savedOps = ""
        savedPermissions = ""
        disableEffect(items)
        sandboxed = false
    }

    /** The helper ends and is started again, and told of the uid held. */
    fun restartHelper() {
        helper = AppOpsGuardHandler(android)
        tellGuarded()
    }

    /**
     * The daemon is killed: what the helper found and was not kept in the
     * entry yet is gone with it, and so is the helper. The daemon that
     * starts takes the entry up again.
     */
    fun restartDaemon() {
        found.clear()
        helper = AppOpsGuardHandler(android)
        guarded = if (sandboxed) targetsOf(items).ifEmpty { null } else null
        tellGuarded()
    }

    /** The helper's check, as after a reported change. */
    fun check() {
        val from = android.printed.size
        helper.checkAll()
        read(from)
    }

    private fun tellGuarded() {
        guarded?.let { send("verify $UID $it") }
    }

    /** What `Sandbox::answer` does first: keep what was found. */
    private fun command() {
        keepRegranted(found)
        found.clear()
    }

    private fun enableEffect(items: Map<String, String>) {
        val targets = targetsOf(items)
        if (targets.isEmpty()) return
        val saved = answer(send("set $UID $targets"), "saved")
        guarded = targets
        val (ops, permissions) = saved.split(',').filter { it.isNotEmpty() }.partition { !isPermission(it.substringBefore('=')) }
        savedOps = ops.joinToString(",")
        keepFound(permissions.joinToString(","))
    }

    private fun disableEffect(items: Map<String, String>) {
        val held = guarded ?: targetsOf(items)
        val names = toGiveBack(held)
        if (names.isEmpty()) return
        answer(send("restore $UID ${savedValuesOf(names)}"), "restored")
        guarded = null
        savedPermissions = ""
    }

    private fun update(before: Map<String, String>, after: Map<String, String>) {
        val (held, holding) = targetsOf(before) to targetsOf(after)
        if (held == holding) return
        if (held.isEmpty()) return enableEffect(after)
        if (holding.isEmpty()) return disableEffect(before)
        val letGo = namesIn(held) - namesIn(holding).toSet()
        val resets = if (letGo.isEmpty()) "-" else savedValuesOf(letGo)
        val found = answer(send("apply $UID $holding $resets"), "applied")
        guarded = holding
        savedPermissions = pairs(savedPermissions).filter { (name, _) -> name !in letGo }.joinToString(",") { (name, state) -> "$name=$state" }
        keepFound(found)
    }

    /** Send [line] and return what the helper printed for it. */
    private fun send(line: String): List<String> {
        val from = android.printed.size
        val command = checkNotNull(AppOpsGuardProtocol.parseCommand(line)) { line }
        try {
            helper.handle(command)
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
        }
        return lines
    }

    /** The values in the answer of [kind] among [lines]; it fails the case if the command failed. */
    private fun answer(lines: List<String>, kind: String): String {
        val answer = lines.lastOrNull { it.startsWith("$kind $UID") || it.startsWith("failed $UID") }
        check(answer != null && answer.startsWith(kind)) { "the helper answered ${answer ?: "nothing"} to the daemon" }
        return answer.split(' ').getOrElse(2) { "" }
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

            // The Wi-Fi scan op is held through the coarse location one.
            "foreground" -> ops += listOf("android:coarse_location=4", "android:fine_location=4")

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
