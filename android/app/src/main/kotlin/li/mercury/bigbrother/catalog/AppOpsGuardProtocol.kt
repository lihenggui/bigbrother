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

/**
 * The parts of [AppOpsGuard] that do not touch the framework: the line
 * protocol and the decisions taken on what the system reports. Kept apart so
 * that they can be tested on the JVM.
 */
internal object AppOpsGuardProtocol {
    /** `AppOpsManager.MODE_IGNORED`: the app is told no data, not refused. */
    const val MODE_IGNORED = 1

    /** `AppOpsManager.MODE_ERRORED`: the app is refused, with a permission error. */
    const val MODE_ERRORED = 2

    /** `AppOpsManager.MODE_DEFAULT`. */
    const val MODE_DEFAULT = 3

    /**
     * Written for an op that has no uid mode of its own, and read back as
     * "remove the uid mode again". Every mode that is set is written as its
     * number, so that an explicit `MODE_DEFAULT` stays distinct from none.
     */
    const val NO_UID_MODE = "default"

    /**
     * The ops kept ignored, by their public names (`AppOpsManager.OPSTR_*`).
     * Some exist only from a later Android version on; a name the device
     * doesn't know is skipped there.
     */
    val GUARDED_OPS = listOf(
        "android:read_contacts",
        "android:write_contacts",
        "android:read_calendar",
        "android:write_calendar",
        "android:read_sms",
        "android:receive_sms",
        "android:read_call_log",
        "android:read_phone_state",
        "android:read_phone_numbers",
        "android:get_accounts",
        "android:coarse_location",
        "android:fine_location",
        "android:read_clipboard",
        "android:camera",
        "android:record_audio",
        "android:body_sensors",
        "android:activity_recognition",
        "android:get_usage_stats",
        "android:read_external_storage",
        "android:write_external_storage",
        "android:manage_external_storage",
        "android:access_media_location",
        "android:read_media_images",
        "android:read_media_video",
        "android:read_media_audio",
        "android:read_media_visual_user_selected",
        "android:bluetooth_scan",
        "android:bluetooth_connect",
        "android:nearby_wifi_devices",
        "android:wifi_scan",
    )

    sealed interface Command {
        val uid: Int

        /** [targets] maps the ops to hold the uid to to their modes; `null` for every op ignored. */
        data class Set(override val uid: Int, val targets: Map<String, Int>? = null) : Command

        /** [modes] maps op names to uid modes, `null` for none. */
        data class Restore(override val uid: Int, val modes: List<Pair<String, Int?>>) : Command

        /** As [Set] for [targets]; null keeps the targets the uid has. */
        data class Verify(override val uid: Int, val targets: Map<String, Int>? = null) : Command

        data class Release(override val uid: Int) : Command

        /**
         * Hold a guarded uid to [targets] from now on, after setting the ops
         * in [resets] back to the modes given, which are not held.
         */
        data class Apply(override val uid: Int, val targets: Map<String, Int>, val resets: List<Pair<String, Int?>>) : Command
    }

    /**
     * What the system reports for one op of one uid: the uid mode set for it,
     * if any, and the mode that applies when none is, if known.
     */
    data class OpState(val name: String, val uidMode: Int?, val defaultMode: Int?)

    /** The command on [line], or `null` if it is not one. */
    fun parseCommand(line: String): Command? {
        val words = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
        val uid = words.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return when (words[0]) {
            "set" -> when (words.size) {
                2 -> Command.Set(uid)
                3 -> decodeTargets(words[2])?.let { Command.Set(uid, it) }
                else -> null
            }

            "verify" -> when (words.size) {
                2 -> Command.Verify(uid)
                3 -> decodeTargets(words[2])?.let { Command.Verify(uid, it) }
                else -> null
            }

            "apply" -> {
                if (words.size != 4) return null
                val targets = if (words[2] == NONE) emptyMap() else decodeTargets(words[2]) ?: return null
                val resets = if (words[3] == NONE) emptyList() else decodeModes(words[3]) ?: return null
                Command.Apply(uid, targets, resets)
            }

            "release" -> if (words.size == 2) Command.Release(uid) else null

            "restore" -> when (words.size) {
                // A device that knows none of the ops saved none.
                2 -> Command.Restore(uid, emptyList())

                3 -> decodeModes(words[2])?.let { Command.Restore(uid, it) }

                else -> null
            }

            else -> null
        }
    }

    /** `<op>=<mode>,...`, the form `saved` prints and `restore` takes. */
    fun encodeModes(modes: List<Pair<String, Int?>>): String = modes.joinToString(",") { (name, mode) -> "$name=${mode ?: NO_UID_MODE}" }

    /** The pairs [encodeModes] wrote, or `null` if [text] is not in that form. */
    fun decodeModes(text: String): List<Pair<String, Int?>>? = text.split(',').map { pair ->
        val name = pair.substringBefore('=', missingDelimiterValue = "")
        val value = pair.substringAfter('=')
        if (name.isEmpty() || name.any { it.isWhitespace() }) return null
        val mode = when (value) {
            NO_UID_MODE -> null
            else -> value.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        }
        name to mode
    }

    /** Written in place of an empty list of ops. */
    private const val NONE = "-"

    /**
     * The ops to hold a uid to, in the form [encodeModes] writes, each to a
     * mode that keeps its data from the app; `null` for anything else.
     */
    fun decodeTargets(text: String): Map<String, Int>? {
        val modes = decodeModes(text) ?: return null
        if (modes.any { (_, mode) -> mode != MODE_IGNORED && mode != MODE_ERRORED }) return null
        return modes.associate { (name, mode) -> name to mode!! }
    }

    /** What `set` saves: each op's own uid mode, `null` where it has none. */
    fun savedModes(states: List<OpState>): List<Pair<String, Int?>> = states.map { it.name to it.uidMode }

    /**
     * The ops held to a mode in [targets] that no longer have it. One without
     * a uid mode has its default; one whose default is not known is set
     * again, which does no harm if it had the mode already. Ops not held are
     * left to the system.
     */
    fun opsToSetAgain(states: List<OpState>, targets: Map<String, Int>): List<String> = states.filter { state -> targets[state.name]?.let { (state.uidMode ?: state.defaultMode) != it } ?: false }.map { it.name }

    fun savedLine(uid: Int, modes: List<Pair<String, Int?>>): String = if (modes.isEmpty()) "saved $uid" else "saved $uid ${encodeModes(modes)}"

    fun restoredLine(uid: Int): String = "restored $uid"

    fun appliedLine(uid: Int): String = "applied $uid"

    fun revertedLine(uid: Int, op: String): String = "reverted $uid $op"

    fun verifiedLine(uid: Int): String = "verified $uid"

    fun failedLine(uid: Int): String = "failed $uid"
}
