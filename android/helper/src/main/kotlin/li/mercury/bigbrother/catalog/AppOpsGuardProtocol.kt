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
    /** `AppOpsManager.MODE_ALLOWED`. */
    const val MODE_ALLOWED = 0

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
        "android:read_heart_rate",
        "android:read_skin_temperature",
        "android:read_oxygen_saturation",
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

    /**
     * `PackageManager.FLAG_PERMISSION_REVOKED_COMPAT`: granted, with its op
     * kept from the app. The permission policy then keeps the op ignored.
     */
    const val FLAG_REVOKED_COMPAT = 8

    /**
     * Whether Android had a runtime permission marked [FLAG_REVOKED_COMPAT]
     * before its uid was held, by the uid mode [saved] then and the mode
     * the system gave the op for its grant ([grantThen], as [grantMode]
     * has it): granted, with the op set by the user to a mode that keeps
     * it from the app, as `AppOpsService.updatePermissionRevokedCompat`
     * marks it: any but allowed, or, for a permission that has a
     * [background] one, any but allowed and foreground. No uid mode is
     * the op's default, allowed.
     */
    fun revokedCompatBefore(saved: Int?, grantThen: Int, background: Boolean): Boolean = grantThen != MODE_IGNORED &&
        saved != null &&
        saved != MODE_ALLOWED &&
        !(background && saved == MODE_FOREGROUND)

    /**
     * As [revokedCompatBefore], for the background permission of the op's
     * permission: granted then ([grantThen] allowed), with the op set to
     * anything but allowed.
     */
    fun backgroundRevokedCompatBefore(saved: Int?, grantThen: Int): Boolean = grantThen == MODE_ALLOWED && saved != null && saved != MODE_ALLOWED

    /** What a permission is held to: revoked, and fixed as the user's choice. */
    const val REVOKED = "revoked"

    /**
     * In a `verify`, in place of [REVOKED]: held to it all the same, but
     * last reported unheld, so perhaps not as held now.
     */
    const val UNHELD = "unheld"

    private const val GRANTED = "granted"
    private const val DENIED = "denied"
    private const val USER_SET = "user_set"
    private const val USER_FIXED = "user_fixed"

    /** Starts a word of a state that names a service the access was given to. */
    private const val SERVICE = "service:"

    /** Whether [name] names a permission rather than an op (`android:camera`). */
    fun isPermission(name: String): Boolean = name.startsWith("android.permission.")

    /**
     * The access to what the system hands an app's services itself: the
     * notifications, to its notification listeners, and the screen, to its
     * accessibility services. Named after the permission the system binds
     * those services with, and taken and given back as a permission is,
     * though the user grants it service by service: granted is the access
     * given to some of the app's services, named in the state.
     */
    val SPECIAL_ACCESS = setOf(
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
    )

    fun isSpecialAccess(name: String): Boolean = name in SPECIAL_ACCESS

    /**
     * What to hold a uid to: [ops] at their modes, and [permissions] revoked.
     * In a `verify`, [unheld] are those of the permissions the daemon last
     * heard were not held: a helper that has not held them itself cannot
     * take their state for the user's.
     */
    data class Targets(val ops: Map<String, Int>, val permissions: Set<String>, val unheld: Set<String> = emptySet()) {
        companion object {
            val NONE = Targets(emptyMap(), emptySet())
        }
    }

    /**
     * A runtime permission's state for a uid: whether it is granted, and
     * the flags among [USER_FLAGS] it has. For a special access
     * ([SPECIAL_ACCESS]), the [services] it is given to, as
     * `<package>/<class>`: granted if there are any, without flags.
     */
    data class PermissionState(val granted: Boolean, val flags: Int, val services: Set<String> = emptySet()) {
        companion object {
            /** A special access given to [services], or to none. */
            fun ofServices(services: Set<String>): PermissionState = PermissionState(services.isNotEmpty(), 0, services)
        }
    }

    /**
     * What to set back: [ops] to uid modes, `null` for none; [permissions]
     * to states, `null` where the state is not known. [grants] has, for an
     * op of a runtime permission, the mode the system gave it for the
     * permission's grant when its uid mode was saved ([grantMode]); none
     * where it has no such permission, or the mode was saved without it.
     */
    data class Values(
        val ops: List<Pair<String, Int?>>,
        val permissions: List<Pair<String, PermissionState?>>,
        val grants: Map<String, Int> = emptyMap(),
    ) {
        fun isEmpty(): Boolean = ops.isEmpty() && permissions.isEmpty()

        companion object {
            val NONE = Values(emptyList(), emptyList())
        }
    }

    sealed interface Command {
        val uid: Int

        /** Read the recovery values for [targets] before [Set] changes them. */
        data class Capture(override val uid: Int, val request: Long, val targets: Targets) : Command

        data class Set(override val uid: Int, val targets: Targets) : Command

        data class Restore(override val uid: Int, val values: Values) : Command

        /** As [Set]. */
        data class Verify(override val uid: Int, val targets: Targets) : Command

        data class Release(override val uid: Int) : Command

        data class Undo(override val uid: Int, val values: Values, val mayWrite: Boolean = true) : Command

        data class Persisted(override val uid: Int, val permission: String, val state: PermissionState) : Command

        /**
         * Hold a guarded uid to [targets] from now on, after setting what
         * [resets] names back to the values given, which is not held.
         */
        data class Apply(override val uid: Int, val targets: Targets, val resets: Values) : Command

        /** Whether [packageName], a package of [uid], is a library rather than an app. */
        data class Kind(override val uid: Int, val request: Long, val packageName: String) : Command
    }

    /**
     * What the system reports for one op of one uid: the uid mode set for it,
     * if any, and the mode that applies when none is, if known.
     */
    data class OpState(val name: String, val uidMode: Int?, val defaultMode: Int?)

    private val BOUND_PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    private val INSTALLATION_TOKEN = Regex("[0-9a-f]{32}")

    data class Bound(val packageName: String, val token: String, val commandLine: String)

    /** Read the installation binding separately so an invalid command can still be reported. */
    fun parseBound(line: String): Bound? {
        val parts = line.split(' ', limit = 4)
        if (parts.size != 4 || parts[0] != "bound" || !isPackageName(parts[1]) || !INSTALLATION_TOKEN.matches(parts[2])) return null
        return Bound(parts[1], parts[2], parts[3])
    }

    data class Request(val number: Long, val commandLine: String)

    fun parseRequest(line: String): Request? {
        val words = line.split(' ', limit = 3)
        if (words.size != 3 || words[0] != "request") return null
        val number = words[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return Request(number, words[2])
    }

    fun replyLine(request: Request, token: String, line: String): String = "reply ${request.number} $token $line"

    /** The command on [line], or `null` if it is not one. */
    fun parseCommand(line: String): Command? {
        val words = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
        val uid = words.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return when (words[0]) {
            "capture" -> {
                if (words.size != 4) return null
                val request = words[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
                val targets = decodeTargets(words[3]) ?: return null
                Command.Capture(uid, request, targets)
            }

            "set" -> if (words.size == 3) decodeTargets(words[2])?.let { Command.Set(uid, it) } else null

            "verify" -> if (words.size == 3) decodeTargets(words[2], unheldAllowed = true)?.let { Command.Verify(uid, it) } else null

            "apply" -> {
                if (words.size != 4) return null
                val targets = if (words[2] == NONE) Targets.NONE else decodeTargets(words[2]) ?: return null
                val resets = if (words[3] == NONE) Values.NONE else decodeValues(words[3]) ?: return null
                Command.Apply(uid, targets, resets)
            }

            "undo", "check-undo" -> {
                if (words.size != 3) return null
                val values = if (words[2] == NONE) Values.NONE else decodeValues(words[2]) ?: return null
                Command.Undo(uid, values, mayWrite = words[0] == "undo")
            }

            "persisted" -> {
                if (words.size != 3) return null
                val (name, state) = decodePairs(words[2])?.singleOrNull() ?: return null
                if (!isPermission(name)) return null
                Command.Persisted(uid, name, decodeState(state) ?: return null)
            }

            "release" -> if (words.size == 2) Command.Release(uid) else null

            "kind" -> {
                if (words.size != 4 || !isPackageName(words[3])) return null
                val request = words[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
                Command.Kind(uid, request, words[3])
            }

            "restore" -> when (words.size) {
                // A device that knows none of the ops saved none.
                2 -> Command.Restore(uid, Values.NONE)

                3 -> decodeValues(words[2])?.let { Command.Restore(uid, it) }

                else -> null
            }

            else -> null
        }
    }

    /** Whether [name] has only what Android allows in a package name. */
    fun isPackageName(name: String): Boolean = BOUND_PACKAGE.matches(name)

    /**
     * `<name>=<value>,...`, the form `saved` prints and `restore` takes: the
     * ops first, each at its uid mode's number or `default` for none,
     * followed by `/` and its grant's mode where there is one, then the
     * permissions, each at its state, or `default` where that is not known.
     */
    fun encodeValues(values: Values): String = (
        values.ops.map { (name, mode) -> "$name=${mode ?: NO_UID_MODE}${values.grants[name]?.let { "$GRANT_SEPARATOR$it" }.orEmpty()}" } +
            values.permissions.map { (name, state) -> "$name=${state?.let(::encodeState) ?: NO_UID_MODE}" }
        ).joinToString(",")

    /** What [encodeValues] wrote, or `null` if [text] is not in that form. */
    fun decodeValues(text: String): Values? {
        val ops = mutableListOf<Pair<String, Int?>>()
        val permissions = mutableListOf<Pair<String, PermissionState?>>()
        val grants = mutableMapOf<String, Int>()
        for ((name, value) in decodePairs(text) ?: return null) {
            if (isPermission(name)) {
                permissions += name to if (value == NO_UID_MODE) null else decodeState(value) ?: return null
            } else {
                // Some ops have no runtime permission, or its grant could not be read.
                val mode = value.substringBefore(GRANT_SEPARATOR)
                ops += name to if (mode == NO_UID_MODE) null else mode.toIntOrNull()?.takeIf { it >= 0 } ?: return null
                if (GRANT_SEPARATOR in value) {
                    grants[name] = value.substringAfter(GRANT_SEPARATOR).toIntOrNull()?.takeIf { it >= 0 } ?: return null
                }
            }
        }
        return Values(ops, permissions, grants)
    }

    /** Between an op's saved mode and its grant's. */
    private const val GRANT_SEPARATOR = '/'

    /**
     * The mode the system gives the op of a runtime permission: allowed
     * with the permission [granted], or only in the foreground for one
     * whose background permission is not ([background] `false`; `null`
     * for one without a background permission); ignored when it is not
     * granted. Android sets the op so as the grant changes, and from
     * Android 15 on reports it so whatever uid mode was set.
     */
    fun grantMode(granted: Boolean, background: Boolean?): Int = when {
        !granted -> MODE_IGNORED
        background == false -> MODE_FOREGROUND
        else -> MODE_ALLOWED
    }

    /**
     * The uid mode to set an op back to, or `null` for none: the one
     * [saved], unless its permission's grant changed since ([grantThen] to
     * [grantNow], each as [grantMode] has it). A mode saved for a
     * permission that was not granted only followed from that, and one
     * saved for a granted permission may be the user's own; but a grant
     * that changed is the user's choice made since, and the system's mode
     * for it is the one to have. Without both grants known, what was saved.
     */
    fun modeToRestore(saved: Int?, grantThen: Int?, grantNow: Int?): Int? = if (grantThen != null && grantNow != null && grantNow != grantThen) grantNow else saved

    fun restoreUidMode(desiredMode: () -> Int, writeMode: (Int) -> Boolean, readMode: () -> Int): Boolean {
        val expected = desiredMode()
        if (readMode() == expected) return true
        if (!writeMode(expected)) return false
        if (readMode() == expected) return true

        // Android 13 and 14 can still be applying an earlier snapshot of
        // the permission flags when setUidMode returns. The first write
        // updated those flags. Recheck the grant and correct a stale mode
        // once, keeping the readback check on the corrected value.
        val current = desiredMode()
        return readMode() == current || (writeMode(current) && readMode() == current)
    }

    /**
     * Whether the grant of [permission] now says what the user chose: not
     * for an op without one, nor for a permission this program holds
     * revoked or is about to give back ([held]), whose grant is its own
     * doing. Such an op is set back to its mode saved.
     */
    fun comparesGrant(permission: String?, held: Set<String>): Boolean = permission != null && permission !in held

    /**
     * The permissions whose grant is this program's doing while ops are set
     * back: those it holds or took ([holding]), and those it gives back
     * along with the ops ([givenBack]). Not those it is about to take: their
     * grant is still the user's.
     */
    fun heldWhileSettingBack(holding: Set<String>, givenBack: Values): Set<String> = holding + givenBack.permissions.map { it.first }

    /**
     * `granted` or `denied`, then `+user_set` and `+user_fixed` for the
     * flags it has, and `+service:<package>:<class>` for each service.
     */
    fun encodeState(state: PermissionState): String = buildString {
        append(if (state.granted) GRANTED else DENIED)
        if (state.flags and FLAG_USER_SET != 0) append("+$USER_SET")
        if (state.flags and FLAG_USER_FIXED != 0) append("+$USER_FIXED")
        // `/` is not used in the line: a component's own separator is.
        for (service in state.services.sorted()) append("+$SERVICE").append(service.replaceFirst('/', ':'))
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
        val services = mutableSetOf<String>()
        for (word in words.drop(1)) {
            if (word.startsWith(SERVICE)) {
                val service = word.removePrefix(SERVICE)
                if (':' !in service) return null
                services += service.replaceFirst(':', '/')
                continue
            }
            flags = flags or when (word) {
                USER_SET -> FLAG_USER_SET
                USER_FIXED -> FLAG_USER_FIXED
                else -> return null
            }
        }
        // Granted with nothing given the access, or the other way round,
        // is not a state of one.
        if (services.isNotEmpty() && !granted) return null
        return PermissionState(granted, flags, services)
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
     * form [encodeValues] writes, and permissions each `revoked`, or, where
     * [unheldAllowed], `unheld`; `null` for anything else.
     */
    fun decodeTargets(text: String, unheldAllowed: Boolean = false): Targets? {
        val ops = mutableMapOf<String, Int>()
        val permissions = mutableSetOf<String>()
        val unheld = mutableSetOf<String>()
        for ((name, value) in decodePairs(text) ?: return null) {
            if (isPermission(name)) {
                when {
                    value == REVOKED -> Unit
                    value == UNHELD && unheldAllowed -> unheld += name
                    else -> return null
                }
                permissions += name
            } else {
                ops[name] = value.toIntOrNull()?.takeIf { it in HELD_MODES } ?: return null
            }
        }
        return Targets(ops, permissions, unheld)
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

    fun capturedLine(uid: Int, request: Long, values: Values): String = if (values.isEmpty()) "captured $uid $request" else "captured $uid $request ${encodeValues(values)}"

    fun captureFailedLine(uid: Int, request: Long): String = "capture-failed $uid $request"

    fun savedLine(uid: Int, values: Values): String = if (values.isEmpty()) "saved $uid" else "saved $uid ${encodeValues(values)}"

    fun restoredLine(uid: Int): String = "restored $uid"

    /** With the states of the permissions taken anew, in the form [encodeValues] writes. */
    fun appliedLine(uid: Int, found: List<Pair<String, PermissionState>>): String = if (found.isEmpty()) "applied $uid" else "applied $uid ${encodeValues(Values(emptyList(), found))}"

    /**
     * [name] is an op's or a permission's. A permission found changed by
     * the user ([chosen]) is followed by the state it was found in, in the
     * form [encodeValues] writes: the state to give back from now on.
     */
    fun revertedLine(uid: Int, name: String, chosen: PermissionState? = null): String = if (chosen == null) "reverted $uid $name" else "reverted $uid $name=${encodeState(chosen)}"

    /**
     * The state of a permission held revoked, as found ([found]), if the
     * user changed it while the app was held, and that is the state to
     * give back: granted again; or, for one this program had held
     * ([wasHeld]), no longer fixed, as the settings leave a permission
     * the user revokes. `null` for one as this program holds it, or not
     * requested. A grant keeps the fixed mark this program set unless the
     * settings made it, which clear it: granted with `pm`, the permission
     * is not the user's fixed choice, and goes back without it.
     */
    fun chosenWhileHeld(found: PermissionState?, wasHeld: Boolean): PermissionState? = when {
        found == null -> null
        found.granted -> found.copy(flags = found.flags and FLAG_USER_FIXED.inv())
        wasHeld && found.flags and FLAG_USER_FIXED == 0 -> found
        else -> null
    }

    /**
     * What to keep for a permission taken back after it was found granted
     * again ([found]), [kept] being what was kept before. A special access
     * is given service by service: the user giving one service the access
     * again took it from none of the others that had it, so they keep it
     * too.
     */
    fun regrantedToKeep(kept: PermissionState?, found: PermissionState): PermissionState = if (kept != null && kept.granted && found.services.isNotEmpty()) {
        found.copy(services = kept.services + found.services)
    } else {
        found
    }

    /**
     * What to give a permission back as: what the daemon [sent], or, where
     * it does not know, the state [kept] here. A permission found changed
     * by the user ([chosen]) goes back as found, which the daemon may not
     * have written down by the time it sends the command.
     */
    fun stateToGiveBack(sent: PermissionState?, kept: PermissionState?, chosen: Boolean): PermissionState? = if (chosen && kept != null) kept else sent ?: kept

    fun verifiedLine(uid: Int): String = "verified $uid"

    fun unheldLine(uid: Int, ops: List<String>): String = "unheld $uid ${ops.joinToString(",").ifEmpty { NONE }}"

    fun failedLine(uid: Int): String = "failed $uid"

    /** The answer to `kind`: `null` where it cannot be told. */
    fun kindLine(uid: Int, request: Long, library: Boolean?): String = when (library) {
        true -> "library $uid $request"
        false -> "app $uid $request"
        null -> "unknown $uid $request"
    }
}
