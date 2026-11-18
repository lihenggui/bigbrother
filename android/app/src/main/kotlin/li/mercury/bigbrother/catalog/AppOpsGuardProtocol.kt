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

    /**
     * `AppOpsManager.MODE_FOREGROUND`: allowed while the app is in the
     * foreground, ignored in the background. The system keeps it for the
     * ops of runtime permissions.
     */
    const val MODE_FOREGROUND = 4

    /** The modes an op can be held to. */
    private val HELD_MODES = setOf(MODE_IGNORED, MODE_FOREGROUND)

    /** `AppOpsManager.MODE_DEFAULT`. */
    const val MODE_DEFAULT = 3

    /**
     * Written for an op that has no uid mode of its own, and read back as
     * "remove the uid mode again". Every mode that is set is written as its
     * number, so that an explicit `MODE_DEFAULT` stays distinct from none.
     * For a permission, it stands for a state that is not known.
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

    /**
     * `PackageManager.FLAG_PERMISSION_USER_SET`: the user chose the
     * permission's state, and the app may ask again.
     */
    const val FLAG_USER_SET = 1

    /**
     * `PackageManager.FLAG_PERMISSION_USER_FIXED`: the user chose the state
     * for good. The system refuses the app's requests without asking.
     */
    const val FLAG_USER_FIXED = 2

    /** The flags of a permission that are the user's choice, saved and put back. */
    const val USER_FLAGS = FLAG_USER_SET or FLAG_USER_FIXED

    /** What a permission is held to: revoked, and fixed as the user's choice. */
    const val REVOKED = "revoked"

    private const val GRANTED = "granted"
    private const val DENIED = "denied"
    private const val USER_SET = "user_set"
    private const val USER_FIXED = "user_fixed"

    /** Whether [name] names a permission rather than an op (`android:camera`). */
    fun isPermission(name: String): Boolean = name.startsWith("android.permission.")

    /** What to hold a uid to: [ops] at their modes, and [permissions] revoked. */
    data class Targets(val ops: Map<String, Int>, val permissions: Set<String>) {
        companion object {
            val NONE = Targets(emptyMap(), emptySet())
        }
    }

    /**
     * A runtime permission's state for a uid: whether it is granted, and
     * the flags among [USER_FLAGS] it has.
     */
    data class PermissionState(val granted: Boolean, val flags: Int)

    /**
     * What to set back: [ops] to uid modes, `null` for none; [permissions]
     * to states, `null` where the state is not known.
     */
    data class Values(val ops: List<Pair<String, Int?>>, val permissions: List<Pair<String, PermissionState?>>) {
        fun isEmpty(): Boolean = ops.isEmpty() && permissions.isEmpty()

        companion object {
            val NONE = Values(emptyList(), emptyList())
        }
    }

    sealed interface Command {
        val uid: Int

        data class Set(override val uid: Int, val targets: Targets) : Command

        data class Restore(override val uid: Int, val values: Values) : Command

        /** As [Set]. */
        data class Verify(override val uid: Int, val targets: Targets) : Command

        data class Release(override val uid: Int) : Command

        /**
         * Hold a guarded uid to [targets] from now on, after setting what
         * [resets] names back to the values given, which is not held.
         */
        data class Apply(override val uid: Int, val targets: Targets, val resets: Values) : Command
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
            "set" -> if (words.size == 3) decodeTargets(words[2])?.let { Command.Set(uid, it) } else null

            "verify" -> if (words.size == 3) decodeTargets(words[2])?.let { Command.Verify(uid, it) } else null

            "apply" -> {
                if (words.size != 4) return null
                val targets = if (words[2] == NONE) Targets.NONE else decodeTargets(words[2]) ?: return null
                val resets = if (words[3] == NONE) Values.NONE else decodeValues(words[3]) ?: return null
                Command.Apply(uid, targets, resets)
            }

            "release" -> if (words.size == 2) Command.Release(uid) else null

            "restore" -> when (words.size) {
                // A device that knows none of the ops saved none.
                2 -> Command.Restore(uid, Values.NONE)

                3 -> decodeValues(words[2])?.let { Command.Restore(uid, it) }

                else -> null
            }

            else -> null
        }
    }

    /**
     * `<name>=<value>,...`, the form `saved` prints and `restore` takes: the
     * ops first, each at its uid mode's number or `default` for none, then
     * the permissions, each at its state, or `default` where that is not
     * known.
     */
    fun encodeValues(values: Values): String = (
        values.ops.map { (name, mode) -> "$name=${mode ?: NO_UID_MODE}" } +
            values.permissions.map { (name, state) -> "$name=${state?.let(::encodeState) ?: NO_UID_MODE}" }
        ).joinToString(",")

    /** What [encodeValues] wrote, or `null` if [text] is not in that form. */
    fun decodeValues(text: String): Values? {
        val ops = mutableListOf<Pair<String, Int?>>()
        val permissions = mutableListOf<Pair<String, PermissionState?>>()
        for ((name, value) in decodePairs(text) ?: return null) {
            if (isPermission(name)) {
                permissions += name to if (value == NO_UID_MODE) null else decodeState(value) ?: return null
            } else {
                ops += name to if (value == NO_UID_MODE) null else value.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            }
        }
        return Values(ops, permissions)
    }

    /** `granted` or `denied`, then `+user_set` and `+user_fixed` for the flags it has. */
    fun encodeState(state: PermissionState): String = buildString {
        append(if (state.granted) GRANTED else DENIED)
        if (state.flags and FLAG_USER_SET != 0) append("+$USER_SET")
        if (state.flags and FLAG_USER_FIXED != 0) append("+$USER_FIXED")
    }

    /** The state [encodeState] wrote, or `null` for anything else. */
    fun decodeState(text: String): PermissionState? {
        val words = text.split('+')
        val granted = when (words[0]) {
            GRANTED -> true
            DENIED -> false
            else -> return null
        }
        var flags = 0
        for (word in words.drop(1)) {
            flags = flags or when (word) {
                USER_SET -> FLAG_USER_SET
                USER_FIXED -> FLAG_USER_FIXED
                else -> return null
            }
        }
        return PermissionState(granted, flags)
    }

    /** The `<name>=<value>` pairs of [text], or `null` if one is not a pair. */
    private fun decodePairs(text: String): List<Pair<String, String>>? = text.split(',').map { pair ->
        val name = pair.substringBefore('=', missingDelimiterValue = "")
        if (name.isEmpty() || name.any { it.isWhitespace() }) return null
        name to pair.substringAfter('=')
    }

    /** Written in place of an empty list. */
    private const val NONE = "-"

    /**
     * What to hold a uid to: ops each ignored or in the foreground, in the
     * form [encodeValues] writes, and permissions each `revoked`; `null` for
     * anything else.
     */
    fun decodeTargets(text: String): Targets? {
        val ops = mutableMapOf<String, Int>()
        val permissions = mutableSetOf<String>()
        for ((name, value) in decodePairs(text) ?: return null) {
            if (isPermission(name)) {
                if (value != REVOKED) return null
                permissions += name
            } else {
                ops[name] = value.toIntOrNull()?.takeIf { it in HELD_MODES } ?: return null
            }
        }
        return Targets(ops, permissions)
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

    /** Whether a permission with these [flags] is held: revoked, and fixed so. */
    fun isHeld(granted: Boolean, flags: Int): Boolean = !granted && flags and FLAG_USER_FIXED != 0

    /**
     * Whether a permission with these [flags] was put back to [state]; with
     * no state known, whether it is no longer fixed.
     */
    fun isPutBack(state: PermissionState?, granted: Boolean, flags: Int): Boolean = if (state == null) {
        flags and FLAG_USER_FIXED == 0
    } else {
        granted == state.granted && flags and USER_FLAGS == state.flags
    }

    fun savedLine(uid: Int, values: Values): String = if (values.isEmpty()) "saved $uid" else "saved $uid ${encodeValues(values)}"

    fun restoredLine(uid: Int): String = "restored $uid"

    /** With the states of the permissions taken anew, in the form [encodeValues] writes. */
    fun appliedLine(uid: Int, found: List<Pair<String, PermissionState>>): String = if (found.isEmpty()) "applied $uid" else "applied $uid ${encodeValues(Values(emptyList(), found))}"

    /** [name] is an op's or a permission's. */
    fun revertedLine(uid: Int, name: String): String = "reverted $uid $name"

    fun verifiedLine(uid: Int): String = "verified $uid"

    fun failedLine(uid: Int): String = "failed $uid"
}
