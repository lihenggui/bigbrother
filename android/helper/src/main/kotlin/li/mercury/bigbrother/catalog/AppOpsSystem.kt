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

import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.PermissionState

/**
 * An op this device knows, as the service names it, with the op it is
 * switched with, under which the service keeps its uid mode, and the
 * permission whose grant the system sets that uid mode by, if any
 * ([permission]: only when the system maps that permission back to this
 * op). [declaredBy] is the permission an app must request to be allowed the
 * op at all; Android 13 and 14 do not map some of these back (usage
 * access), so it is kept apart.
 */
internal class GuardedOp(
    val name: String,
    val code: Int,
    val switchCode: Int,
    val defaultMode: Int?,
    val permission: String?,
    val declaredBy: String? = permission,
)

/**
 * What [AppOpsGuardHandler] needs of the framework: the app ops service and
 * the runtime permissions. [Framework] reaches them by reflection; the
 * tests stand in for them with a model of how Android keeps them.
 */
internal interface AppOpsSystem {
    /** The guarded ops this device knows. */
    val ops: List<GuardedOp>

    /**
     * Throw unless [uid] is still the installation the daemon bound it to:
     * nothing is changed for an app that is not the one that was held.
     */
    fun requireOwner(uid: Int)

    /**
     * Carry out [change], the giving back of [name], once: when run to
     * recover, a change already made before is not made again.
     */
    fun restoreItem(name: String, change: () -> Unit)

    /** What takes a special access from an app and gives it back. */
    fun specialAccess(): Taking

    /** The op [name], if this device knows it. */
    fun resolve(name: String): GuardedOp?

    /** The uid modes of [uid], by switch op, for those of [switchCodes] that have one. */
    fun uidModes(uid: Int, switchCodes: IntArray): Map<Int, Int>

    /** Set the uid mode of the op [code]; throws if the service refuses. */
    fun setUidMode(code: Int, uid: Int, mode: Int)

    /** Whether the app ID has no running process in another user as of this check. */
    fun canChangeAllFilesAccess(uid: Int): Boolean

    /** The runtime permissions, `null` while they cannot be reached: asked again each time. */
    val permissions: RuntimePermissions?

    /** Why they cannot be, if they cannot. */
    val permissionsMissing: String

    /** Print a line of the protocol. */
    fun emit(line: String)

    /** Report a failure that does not fail the command. */
    fun warn(message: String)

    /** What [e] says, without what the system put in it. */
    fun describe(e: Throwable): String
}

/**
 * What is taken from a uid by name, and given back: a runtime permission,
 * or a special access ([AppOpsGuardProtocol.SPECIAL_ACCESS]).
 */
internal interface Taking {
    /** The state of [name] for [uid], `null` if no package of it has it to take. */
    fun state(uid: Int, name: String): PermissionState?

    /** Ask [mayChange] before a permission revocation or a service disable that can interrupt the app. */
    fun hold(uid: Int, name: String, mayChange: () -> Boolean): HoldResult

    /** Give [uid] back [name] as it was ([state]); `null` where that is not known. */
    fun putBack(uid: Int, name: String, state: PermissionState?)

    enum class HoldResult { HELD, CHANGED, SUSPENDED }
}

/** The calls on runtime permissions [AppOpsGuardHandler] makes. */
internal interface RuntimePermissions {
    /** The packages of [uid] that request [permission], and whether every package could be read. */
    fun requesting(uid: Int, permission: String): Requests

    class Requests(val packages: List<String>, val complete: Boolean)

    /** The system keeps one grant for every package of a uid. */
    fun isGranted(uid: Int, permission: String): Boolean

    fun flags(pkg: String, permission: String, uid: Int): Int

    /** Set the flags in [mask] to [values]. */
    fun setFlags(pkg: String, permission: String, uid: Int, mask: Int, values: Int)

    fun grant(pkg: String, permission: String, uid: Int)

    fun revoke(pkg: String, permission: String, uid: Int)

    /**
     * Whether [permission] is a runtime one, with its background permission
     * if it has one; `null` for one that is not. Throws if that cannot be
     * told here.
     */
    fun runtime(permission: String): Runtime?

    class Runtime(val background: String?)
}
