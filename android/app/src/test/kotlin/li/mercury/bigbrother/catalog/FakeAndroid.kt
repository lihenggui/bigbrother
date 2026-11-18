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

import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.FLAG_REVOKED_COMPAT
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.FLAG_USER_FIXED
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.FLAG_USER_SET
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_ALLOWED
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_DEFAULT
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_FOREGROUND
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_IGNORED

/**
 * One app, as Android 15 and later keep its runtime permissions and the
 * uid modes of their app ops, for [AppOpsGuardHandler] to work on, and as
 * the user changes them, through the settings or through `pm`:
 *
 * - The permission policy sets the uid mode of each permission's op from
 *   the permission whenever a permission or its flags change: allowed, or
 *   in the foreground only while the background permission is not
 *   granted, for one granted and not marked `REVOKED_COMPAT`; ignored
 *   otherwise (`PermissionPolicyService`).
 * - A uid mode set from outside the policy (by the helper, `appops`, the
 *   settings) marks a granted permission `REVOKED_COMPAT` when the mode
 *   keeps it from the app, and a granted background permission when the
 *   mode is not allowed; a background permission not granted is left as
 *   it is (`AppOpsService.updatePermissionRevokedCompat`).
 * - Setting an op's default mode removes its uid mode; ops switched
 *   together share one.
 * - The settings take a permission marked `REVOKED_COMPAT` for one not
 *   granted: allowing it grants it, clears the mark and allows its op;
 *   denying it changes only its flags (`KotlinUtils` of the permission
 *   controller). `pm` grants and revokes, and changes no flag.
 * - An app uninstalled loses its grants, flags and uid modes.
 */
internal class FakeAndroid :
    AppOpsSystem,
    RuntimePermissions {
    /** What the helper printed, in order. */
    val printed = mutableListOf<String>()

    var installed = true
        private set

    /** The next change of a permission's flags is refused, without an error, as Android 15 does. */
    var refuseNextFlagChange = false

    /**
     * The permission policy's sync of the ops after a uid mode is set from
     * outside lands only after the next one is: worked out from the flags
     * as they were, it sets the modes from before over the newer one, as a
     * slow Android 13 does until the policy settles ([settle]).
     */
    var syncsLate = false

    /** The sync worked out and not landed yet, while [syncsLate]. */
    private var lateSync: Map<Int, Int>? = null

    /** The helper's revocations are refused, without an error, as for a permission fixed by policy. */
    var refuseToRevoke = false

    private val granted = mutableMapOf<String, Boolean>()
    private val permissionFlags = mutableMapOf<String, Int>()
    private val modes = mutableMapOf<Int, Int>()

    override val ops = listOf(
        GuardedOp("android:coarse_location", COARSE_LOCATION_OP, COARSE_LOCATION_OP, MODE_ALLOWED, COARSE),
        GuardedOp("android:fine_location", FINE_LOCATION_OP, FINE_LOCATION_OP, MODE_ALLOWED, FINE),
        GuardedOp("android:read_contacts", READ_CONTACTS_OP, READ_CONTACTS_OP, MODE_ALLOWED, CONTACTS),
        GuardedOp("android:read_calendar", READ_CALENDAR_OP, READ_CALENDAR_OP, MODE_ALLOWED, CALENDAR),
        // Kept under the coarse location op.
        GuardedOp("android:wifi_scan", WIFI_SCAN_OP, COARSE_LOCATION_OP, MODE_ALLOWED, COARSE),
        GuardedOp("android:camera", CAMERA_OP, CAMERA_OP, MODE_ALLOWED, CAMERA),
    )

    init {
        install()
    }

    override fun resolve(name: String): GuardedOp? = ops.find { it.name == name }

    // One installation throughout, and nothing to recover from.
    override fun requireOwner(uid: Int) = Unit

    override fun restoreItem(name: String, change: () -> Unit) = change()

    // No app here has a service a special access is given to.
    override fun specialAccess(): Taking = object : Taking {
        override fun state(uid: Int, name: String): AppOpsGuardProtocol.PermissionState? = null

        override fun hold(uid: Int, name: String) = false

        override fun putBack(uid: Int, name: String, state: AppOpsGuardProtocol.PermissionState?) = Unit
    }

    override fun uidModes(uid: Int, switchCodes: IntArray): Map<Int, Int> = modes.filterKeys { it in switchCodes }

    override fun setUidMode(code: Int, uid: Int, mode: Int) = setModeFromOutside(ops.first { it.code == code }.switchCode, mode)

    override val permissions: RuntimePermissions get() = this

    override val permissionsMissing = ""

    override fun emit(line: String) {
        printed += line
    }

    override fun warn(message: String) = Unit

    override fun describe(e: Throwable): String = e.toString()

    override fun requesting(uid: Int, permission: String): List<String> = if (installed && permission in REQUESTED) listOf(PACKAGE) else emptyList()

    override fun isGranted(uid: Int, permission: String): Boolean = installed && granted[permission] == true

    override fun flags(pkg: String, permission: String, uid: Int): Int = permissionFlags[permission] ?: 0

    override fun setFlags(pkg: String, permission: String, uid: Int, mask: Int, values: Int) {
        if (refuseNextFlagChange) {
            refuseNextFlagChange = false
            return
        }
        changeFlags(permission, mask, values)
        syncOps()
    }

    override fun grant(pkg: String, permission: String, uid: Int) = pmGrant(permission)

    override fun revoke(pkg: String, permission: String, uid: Int) {
        if (!refuseToRevoke) pmRevoke(permission)
    }

    override fun runtime(permission: String): RuntimePermissions.Runtime? = if (permission in RUNTIME) RuntimePermissions.Runtime(BACKGROUND_OF[permission]) else null

    /** `pm grant`: the grant alone, its flags as they are. */
    fun pmGrant(permission: String) {
        granted[permission] = true
        syncOps()
    }

    /** `pm revoke`: as [pmGrant]. */
    fun pmRevoke(permission: String) {
        granted[permission] = false
        syncOps()
    }

    /** Allowed in the settings, as the permission controller grants it. */
    fun settingsGrant(permission: String) {
        if (!isGrantedToTheSettings(permission)) {
            granted[permission] = true
            allowOp(permission)
            changeFlags(permission, FLAG_REVOKED_COMPAT, 0)
        }
        // The user's choice, which the app may ask to change.
        changeFlags(permission, FLAG_USER_FIXED or FLAG_USER_SET, FLAG_USER_SET)
        syncOps()
    }

    /** Denied in the settings, as the permission controller revokes it: only one it takes for granted. */
    fun settingsRevoke(permission: String) {
        if (isGrantedToTheSettings(permission)) {
            granted[permission] = false
            if (permission == BACKGROUND) {
                for (foreground in FOREGROUND_OF_BACKGROUND) if (isGrantedToTheSettings(foreground)) setOpFromTheSettings(foreground, MODE_FOREGROUND)
            } else {
                setOpFromTheSettings(permission, MODE_IGNORED)
            }
        }
        changeFlags(permission, FLAG_USER_FIXED or FLAG_USER_SET, FLAG_USER_SET)
        syncOps()
    }

    /** The op set to [mode] by the user (`appops set --uid`). */
    fun userSetsMode(op: String, mode: Int) = setModeFromOutside(ops.first { it.name == op }.switchCode, mode)

    fun uninstall() {
        installed = false
        granted.clear()
        permissionFlags.clear()
        modes.clear()
    }

    fun install() {
        installed = true
        syncOps()
    }

    fun isGranted(permission: String) = isGranted(UID, permission)

    fun hasFlag(permission: String, flag: Int) = flags(PACKAGE, permission, UID) and flag != 0

    /** The mode that applies to [op]: its uid mode, or its default. */
    fun mode(op: String): Int {
        val known = ops.first { it.name == op }
        return modes[known.switchCode] ?: known.defaultMode!!
    }

    /** What the settings show: granted, and not marked `REVOKED_COMPAT`. */
    private fun isGrantedToTheSettings(permission: String) = isGranted(permission) && !hasFlag(permission, FLAG_REVOKED_COMPAT)

    /** `allowAppOp` of the permission controller. */
    private fun allowOp(permission: String) {
        if (permission == BACKGROUND) {
            for (foreground in FOREGROUND_OF_BACKGROUND) if (isGrantedToTheSettings(foreground)) setOpFromTheSettings(foreground, MODE_ALLOWED)
            return
        }
        val background = BACKGROUND_OF[permission]
        val mode = when {
            background == null -> MODE_ALLOWED
            background in REQUESTED && isGrantedToTheSettings(background) -> MODE_ALLOWED
            else -> MODE_FOREGROUND
        }
        setOpFromTheSettings(permission, mode)
    }

    /** Only if it is another, as the permission controller does. */
    private fun setOpFromTheSettings(permission: String, mode: Int) {
        val switch = SWITCH_OF.getValue(permission)
        if ((modes[switch] ?: MODE_ALLOWED) != mode) setModeFromOutside(switch, mode)
    }

    /** `AppOpsService.setUidMode` called from outside the permission policy. */
    private fun setModeFromOutside(switch: Int, mode: Int) {
        updateRevokedCompat(switch, mode)
        store(switch, mode)
        if (!syncsLate) return syncOps()
        val landing = lateSync
        lateSync = synced()
        landing?.let(::apply)
    }

    /** The policy's syncs worked out before now land, and it settles. */
    fun settle() {
        lateSync?.let(::apply)
        lateSync = null
        syncOps()
    }

    /** `AppOpsService.updatePermissionRevokedCompat`. */
    private fun updateRevokedCompat(switch: Int, mode: Int) {
        val permission = PERMISSION_OF_SWITCH[switch] ?: return
        if (!isGranted(permission)) return
        val background = BACKGROUND_OF[permission]
        if (background == null) {
            changeFlags(permission, FLAG_REVOKED_COMPAT, if (mode != MODE_ALLOWED) FLAG_REVOKED_COMPAT else 0)
            return
        }
        if (isGranted(background)) changeFlags(background, FLAG_REVOKED_COMPAT, if (mode != MODE_ALLOWED) FLAG_REVOKED_COMPAT else 0)
        changeFlags(permission, FLAG_REVOKED_COMPAT, if (mode != MODE_ALLOWED && mode != MODE_FOREGROUND) FLAG_REVOKED_COMPAT else 0)
    }

    /** The permission policy's sync of every permission's op. */
    private fun syncOps() {
        if (installed) apply(synced())
    }

    /** The mode the policy gives each permission's op now. */
    private fun synced(): Map<Int, Int> = PERMISSION_OF_SWITCH.mapValues { (_, permission) ->
        val background = BACKGROUND_OF[permission]
        when {
            !isGrantedToTheSettings(permission) -> MODE_IGNORED
            background == null || isGrantedToTheSettings(background) -> MODE_ALLOWED
            else -> MODE_FOREGROUND
        }
    }

    private fun apply(modes: Map<Int, Int>) {
        if (installed) for ((switch, mode) in modes) store(switch, mode)
    }

    private fun store(switch: Int, mode: Int) {
        if (mode == MODE_DEFAULT || mode == MODE_ALLOWED) modes.remove(switch) else modes[switch] = mode
    }

    private fun changeFlags(permission: String, mask: Int, values: Int) {
        permissionFlags[permission] = (flags(PACKAGE, permission, UID) and mask.inv()) or (values and mask)
    }

    companion object {
        const val PACKAGE = "com.example.app"
        const val UID = 10123

        const val CONTACTS = "android.permission.READ_CONTACTS"
        const val CALENDAR = "android.permission.READ_CALENDAR"
        const val FINE = "android.permission.ACCESS_FINE_LOCATION"
        const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
        const val BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION"
        const val CAMERA = "android.permission.CAMERA"

        // Not one the app requests.
        private const val BACKGROUND_CAMERA = "android.permission.BACKGROUND_CAMERA"

        private val REQUESTED = setOf(CONTACTS, CALENDAR, FINE, COARSE, BACKGROUND, CAMERA)
        private val RUNTIME = REQUESTED + BACKGROUND_CAMERA
        private val BACKGROUND_OF = mapOf(FINE to BACKGROUND, COARSE to BACKGROUND, CAMERA to BACKGROUND_CAMERA)
        private val FOREGROUND_OF_BACKGROUND = listOf(FINE, COARSE)

        private const val COARSE_LOCATION_OP = 0
        private const val FINE_LOCATION_OP = 1
        private const val READ_CONTACTS_OP = 4
        private const val READ_CALENDAR_OP = 8
        private const val WIFI_SCAN_OP = 10
        private const val CAMERA_OP = 26

        /** The permission each switch op stands for (`AppOpsManager.opToPermission`). */
        private val PERMISSION_OF_SWITCH = mapOf(
            COARSE_LOCATION_OP to COARSE,
            FINE_LOCATION_OP to FINE,
            READ_CONTACTS_OP to CONTACTS,
            READ_CALENDAR_OP to CALENDAR,
            CAMERA_OP to CAMERA,
        )
        private val SWITCH_OF = PERMISSION_OF_SWITCH.entries.associate { (switch, permission) -> permission to switch }
    }
}
