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

import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.FLAG_USER_FIXED
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.PermissionState
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.USER_FLAGS
import li.mercury.bigbrother.catalog.Taking.HoldResult

internal class RuntimePermissionChanges(private val system: AppOpsSystem) : Taking {
    private fun permissions(): RuntimePermissions = system.permissions ?: error("runtime permissions cannot be reached: ${system.permissionsMissing}")

    /**
     * The runtime permissions asked about, each with its background
     * permission if it has one, and `null` for one that is not runtime.
     */
    private val runtime = mutableMapOf<String, RuntimePermissions.Runtime?>()

    /**
     * The mode the system gives the op of [permission] for its grant to
     * [uid] ([AppOpsGuardProtocol.grantMode]), `null` if it is not a
     * runtime permission. The grant alone decides: the flag that marks
     * the op of a granted permission ignored (`REVOKED_COMPAT`) is what
     * this program's own `setUidMode` leaves while it holds the op.
     */
    private fun permissionGrantMode(connected: RuntimePermissions, uid: Int, permission: String): Int? {
        val info = runtimeOf(connected, permission) ?: return null
        return AppOpsGuardProtocol.grantMode(connected.isGranted(uid, permission), info.background?.let { connected.isGranted(uid, it) })
    }

    private fun runtimeOf(connected: RuntimePermissions, permission: String): RuntimePermissions.Runtime? = if (runtime.containsKey(permission)) runtime[permission] else connected.runtime(permission).also { runtime[permission] = it }

    /**
     * The mode the system gives [op] for its permission's grant to [uid],
     * or `null` where it has no runtime permission or the permissions
     * cannot be reached: its mode is then put back as saved.
     */
    fun grantMode(op: GuardedOp, uid: Int): Int? {
        val permission = op.permission ?: return null
        val connected = system.permissions ?: return null
        return try {
            permissionGrantMode(connected, uid, permission)
        } catch (e: Throwable) {
            system.warn("grant of ${op.name} for $uid: ${system.describe(e)}")
            null
        }
    }

    /**
     * Whether [op] stands for a permission that is not a runtime one, such
     * as all files access, and that no package of [uid] requests. Its op
     * at the default keeps the data from the app already, and Android 16
     * drops a uid mode set on it at once: holding it would only change it
     * back and forth, and the system ends the app's processes on every
     * change of all files access. `null` where that cannot be told.
     */
    fun isUnrequestedAppOp(op: GuardedOp, uid: Int): Boolean? {
        val permission = op.declaredBy ?: return null
        val connected = system.permissions ?: return null
        return try {
            if (runtimeOf(connected, permission) != null) return false
            val requests = connected.requesting(uid, permission)
            if (requests.complete) requests.packages.isEmpty() else null
        } catch (e: Throwable) {
            system.warn("requests of ${op.name} for $uid: ${system.describe(e)}")
            null
        }
    }

    /**
     * Leave the permission of [op], and its background permission, marked
     * `REVOKED_COMPAT` for [uid] only where the user had them so before
     * the uid was held ([saved] and [grantThen]), its grant having changed
     * since. Android marks a granted
     * permission so while its op is held ignored, and a granted background
     * permission while the op is not allowed, but when the op is set back
     * it updates the mark only on a permission still granted: one revoked
     * meanwhile keeps it, and with it its op ignored, or in the foreground
     * only, should it be granted again by any means but the settings.
     */
    fun unmarkRevoked(op: GuardedOp, uid: Int, saved: Int?, grantThen: Int) {
        val permission = op.permission ?: return
        val connected = system.permissions ?: return
        try {
            val background = runtimeOf(connected, permission)?.background
            markRevoked(connected, uid, permission, AppOpsGuardProtocol.revokedCompatBefore(saved, grantThen, background != null))
            background?.let {
                markRevoked(connected, uid, it, AppOpsGuardProtocol.backgroundRevokedCompatBefore(saved, grantThen))
            }
        } catch (e: Throwable) {
            system.warn("marks of ${op.name} for $uid: ${system.describe(e)}")
        }
    }

    /** Mark [permission], if it is not granted to [uid], `REVOKED_COMPAT` or not as [marked] says. */
    private fun markRevoked(connected: RuntimePermissions, uid: Int, permission: String, marked: Boolean) {
        if (connected.isGranted(uid, permission)) return
        val value = if (marked) AppOpsGuardProtocol.FLAG_REVOKED_COMPAT else 0
        for (pkg in connected.requesting(uid, permission).packages) {
            system.requireOwner(uid)
            if (connected.flags(pkg, permission, uid) and AppOpsGuardProtocol.FLAG_REVOKED_COMPAT != value) {
                connected.setFlags(pkg, permission, uid, AppOpsGuardProtocol.FLAG_REVOKED_COMPAT, value)
            }
        }
    }

    /** The state of [name] for [uid], `null` if no package of it requests it. */
    override fun state(uid: Int, name: String): PermissionState? {
        val connected = permissions()
        val first = connected.requesting(uid, name).packages.firstOrNull() ?: return null
        return PermissionState(connected.isGranted(uid, name), connected.flags(first, name, uid) and USER_FLAGS)
    }

    /**
     * Fix [name] first, so that if revocation fails it stays granted and fixed,
     * which no one takes for the user's revocation.
     */
    override fun hold(uid: Int, name: String, mayChange: () -> Boolean): HoldResult {
        val connected = permissions()
        val requesting = connected.requesting(uid, name).packages
        val first = requesting.firstOrNull() ?: return HoldResult.HELD
        var changed = false
        for (pkg in requesting) {
            if (connected.flags(pkg, name, uid) and FLAG_USER_FIXED == 0) {
                system.requireOwner(uid)
                connected.setFlags(pkg, name, uid, FLAG_USER_FIXED, FLAG_USER_FIXED)
                changed = true
            }
        }
        // Android keeps one grant for the uid, so revoking through a second
        // package could only fight a regrant that landed during this hold.
        if (connected.isGranted(uid, name)) {
            system.requireOwner(uid)
            if (!mayChange()) return HoldResult.SUSPENDED
            connected.revoke(first, name, uid)
            changed = true
        }
        check(requesting.all { AppOpsGuardProtocol.isHeld(connected.isGranted(uid, name), connected.flags(it, name, uid)) }) {
            "the system did not take $name away"
        }
        return if (changed) HoldResult.CHANGED else HoldResult.HELD
    }

    /**
     * Give [uid] back [name] as it was ([state]), or, with no state
     * known, only let the app ask for it again.
     */
    override fun putBack(uid: Int, name: String, state: PermissionState?) {
        val connected = permissions()
        val requesting = connected.requesting(uid, name).packages
        for (pkg in requesting) {
            system.requireOwner(uid)
            if (state == null) {
                if (connected.flags(pkg, name, uid) and FLAG_USER_FIXED != 0) connected.setFlags(pkg, name, uid, FLAG_USER_FIXED, 0)
                continue
            }
            val granted = connected.isGranted(uid, name)
            if (state.granted && !granted) connected.grant(pkg, name, uid)
            if (!state.granted && granted) connected.revoke(pkg, name, uid)
            if (connected.flags(pkg, name, uid) and USER_FLAGS != state.flags) connected.setFlags(pkg, name, uid, USER_FLAGS, state.flags)
        }
        check(requesting.all { AppOpsGuardProtocol.isPutBack(state, connected.isGranted(uid, name), connected.flags(it, name, uid)) }) {
            "the system did not give $name back"
        }
    }
}
