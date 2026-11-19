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

import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.Command
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.FLAG_USER_FIXED
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_DEFAULT
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.OpState
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.PermissionState
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.Targets
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.USER_FLAGS
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.Values

/**
 * An op this device knows, as the service names it, with the op it is
 * switched with, under which the service keeps its uid mode, and the
 * permission whose grant the system sets that uid mode by, if any.
 */
internal class GuardedOp(val name: String, val code: Int, val switchCode: Int, val defaultMode: Int?, val permission: String?)

/**
 * What [AppOpsGuardHandler] needs of the framework: the app ops service and
 * the runtime permissions. [AppOpsGuard] reaches them by reflection; the
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

    /** The runtime permissions, `null` if this device does not let them be reached. */
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

    /** Take [name] from [uid] where it is not taken; whether anything had to be changed. */
    fun hold(uid: Int, name: String): Boolean

    /** Give [uid] back [name] as it was ([state]); `null` where that is not known. */
    fun putBack(uid: Int, name: String, state: PermissionState?)
}

/** The calls on runtime permissions [AppOpsGuardHandler] makes. */
internal interface RuntimePermissions {
    /** The packages of [uid] that request [permission]. */
    fun requesting(uid: Int, permission: String): List<String>

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

/**
 * Carries out [AppOpsGuard]'s commands and checks, on what [system] reports
 * and changes. Not thread-safe: the caller runs one command or check at a
 * time.
 */
internal class AppOpsGuardHandler(private val system: AppOpsSystem) {
    /**
     * What a guarded uid is held to, the modes saved for it when known with
     * the grants they were saved at, and the states of the permissions this
     * program took from it itself, of which those [chosen] were found
     * changed by the user since. [held] are the permissions it is known
     * to have held revoked and fixed, and not given back since. [reported]
     * has the state last reported for each permission found changed.
     */
    private data class Guard(
        val saved: List<Pair<String, Int?>>?,
        val targets: Targets,
        val tookPermissions: Map<String, PermissionState> = emptyMap(),
        val savedGrants: Map<String, Int> = emptyMap(),
        val chosen: Set<String> = emptySet(),
        val held: Set<String> = emptySet(),
        val reported: Map<String, PermissionState> = emptyMap(),
    ) {
        /** What to give [permission] back as, the daemon having sent [sent]. */
        fun toGiveBack(permission: String, sent: PermissionState?): PermissionState? = AppOpsGuardProtocol.stateToGiveBack(sent, tookPermissions[permission], permission in chosen)

        fun savedValues(): Values = Values(checkNotNull(saved), tookPermissions.toList(), savedGrants)
    }

    /** The guarded uids. */
    private val guarded = mutableMapOf<Int, Guard>()

    /** The unheld ops last printed for each guarded uid. */
    private val reported = mutableMapOf<Int, List<String>>()

    private val ops get() = system.ops

    /** Runtime permissions, taken and given back here. */
    private val runtimePermissions = object : Taking {
        override fun state(uid: Int, name: String) = this@AppOpsGuardHandler.state(uid, name)

        override fun hold(uid: Int, name: String) = this@AppOpsGuardHandler.hold(uid, name)

        override fun putBack(uid: Int, name: String, state: PermissionState?) = this@AppOpsGuardHandler.putBack(uid, name, state)
    }

    /** What takes [name] from an app and gives it back. */
    private fun taking(name: String): Taking = if (AppOpsGuardProtocol.isSpecialAccess(name)) system.specialAccess() else runtimePermissions

    private fun beforeSet(uid: Int, targets: Targets): Guard {
        val guard = guarded[uid]
        // A uid taken over with verify already has recovery values at the daemon.
        check(guard == null || guard.saved != null) { "already guarded, with nothing saved" }
        return Guard(
            saved = guard?.saved ?: AppOpsGuardProtocol.savedModes(states(uid)),
            targets = targets,
            tookPermissions = statesBefore(uid, targets.permissions, guard?.tookPermissions.orEmpty()),
            savedGrants = guard?.savedGrants ?: grantModes(uid),
            chosen = guard?.chosen.orEmpty(),
            held = guard?.held.orEmpty() intersect targets.permissions,
            reported = guard?.reported.orEmpty(),
        )
    }

    fun handle(command: Command) {
        val uid = command.uid
        // What the check would find, had it run since the user's last
        // change: a command may come first.
        val chosen = if (command is Command.Verify || command is Command.Capture) emptySet() else takeChosen(uid)
        when (command) {
            is Command.Capture -> {
                val values = beforeSet(uid, command.targets).savedValues()
                system.emit(AppOpsGuardProtocol.capturedLine(uid, command.request, values))
            }

            is Command.Set -> {
                val targets = command.targets
                val guard = beforeSet(uid, targets)
                // Written down first, so that a `release` after a failure
                // below puts back whatever was done.
                guarded[uid] = guard
                val taken = targets.ops.count { (name, mode) -> setKnown(name, uid, mode) }
                // Ops this Android version doesn't have are nothing to hold.
                if (targets.ops.keys.any(::isKnown) && taken == 0) error("the system took none of the modes")
                // Reported first, as the check does: holding can fail.
                reportChosen(uid, chosen)
                for (permission in targets.permissions) holdKnown(uid, permission)
                reportUnheld(uid, verify(uid), always = true)
                system.emit(AppOpsGuardProtocol.savedLine(uid, guard.savedValues()))
            }

            is Command.Apply -> {
                val guard = guarded[uid] ?: error("not guarded")
                // Permissions taken only below are still as the user has them.
                val held = AppOpsGuardProtocol.heldWhileSettingBack(guard.heldPermissions(), command.resets)
                var taken = command.resets.ops.count { (name, mode) ->
                    val op = ops.find { it.name == name } ?: return@count false
                    check(setBack(op, uid, mode, command.resets.grants[name], held)) { "app op restore incomplete" }
                    true
                }
                for ((permission, state) in command.resets.permissions) {
                    taking(permission).putBack(uid, permission, guard.toGiveBack(permission, state))
                }
                val targets = command.targets
                // Found before they are taken; one held already was taken
                // by this program, and is as it left it.
                val found = statesBefore(uid, targets.permissions - guard.targets.permissions, emptyMap())
                val givenBack = command.resets.permissions.map { it.first }.toSet()
                guarded[uid] = guard.copy(
                    targets = targets,
                    tookPermissions = guard.tookPermissions - givenBack + found,
                    chosen = guard.chosen - givenBack - found.keys,
                    held = guard.held - givenBack,
                    reported = guard.reported - givenBack,
                )
                taken += targets.ops.count { (name, mode) -> setKnown(name, uid, mode) }
                val known = command.resets.ops.any { (name, _) -> isKnown(name) } || targets.ops.keys.any(::isKnown)
                if (known && taken == 0) error("the system took none of the modes")
                // Reported first, as the check does: holding can fail.
                reportChosen(uid, chosen)
                for (permission in targets.permissions) holdKnown(uid, permission)
                reportUnheld(uid, verify(uid), always = true)
                system.emit(AppOpsGuardProtocol.appliedLine(uid, found.toList()))
            }

            is Command.Restore -> {
                val guard = guarded[uid]
                // Nothing saved: every guarded op goes back to having no uid
                // mode, which is how the system has them before it is asked.
                val values = if (command.values.isEmpty()) Values(ops.map { it.name to null }, emptyList()) else command.values
                // Given back below, after the ops: their grants now are
                // this program's doing.
                val held = AppOpsGuardProtocol.heldWhileSettingBack(guard.heldPermissions(), values)
                for ((name, mode) in values.ops) {
                    val op = ops.find { it.name == name } ?: system.resolve(name)
                    if (op == null) {
                        system.warn("restore $uid: no op $name here")
                        continue
                    }
                    system.restoreItem(name) { check(setBack(op, uid, mode, values.grants[name], held)) { "app op restore incomplete" } }
                }
                // Still guarded if it fails: the daemon then keeps the app
                // sandboxed, and its ops must stay as they are held.
                for ((permission, state) in values.permissions) {
                    system.restoreItem(permission) { taking(permission).putBack(uid, permission, guard?.toGiveBack(permission, state) ?: state) }
                }
                forget(uid)
                system.emit(AppOpsGuardProtocol.restoredLine(uid))
            }

            is Command.Release -> {
                // The daemon gave up on a `set` it got no answer to, and does
                // not have what was saved: put that back from here.
                val guard = guarded[uid]
                for ((name, mode) in guard?.saved.orEmpty()) {
                    val op = ops.find { it.name == name } ?: continue
                    check(setBack(op, uid, mode, guard?.savedGrants?.get(name), guard.heldPermissions()))
                }
                for ((permission, state) in guard?.tookPermissions.orEmpty()) {
                    taking(permission).putBack(uid, permission, state)
                }
                forget(uid)
                system.emit(AppOpsGuardProtocol.restoredLine(uid))
            }

            // Answered apart from the commands: see AppOpsGuard.
            is Command.Kind -> error("not a command on what the uid is held to")

            is Command.Verify -> {
                val guard = guarded[uid]
                // A uid new here was held by the program before this one:
                // the daemon holds it to a permission only once a command
                // has taken it. Found no longer fixed, the user changed it
                // while neither was there to see; but not one the daemon
                // last heard was unheld, which that program may have left
                // half taken.
                guarded[uid] = guard?.copy(targets = command.targets) ?: Guard(null, command.targets, held = command.targets.permissions - command.targets.unheld)
                reportUnheld(uid, verify(uid), always = true)
                system.emit(AppOpsGuardProtocol.verifiedLine(uid))
            }
        }
    }

    /** Whether [uid] is guarded. */
    fun isGuarded(uid: Int): Boolean = uid in guarded

    /** Stop guarding [uid], forgetting all about it: it is another installation's now, or given back. */
    fun forget(uid: Int) {
        guarded.remove(uid)
        reported.remove(uid)
    }

    /**
     * Check every guarded uid, as after a reported change and every minute,
     * printing what is found changed, and what is unheld if that changed.
     */
    fun checkAll() {
        for (uid in guarded.keys.toList()) {
            try {
                reportUnheld(uid, verify(uid), always = false)
            } catch (e: Throwable) {
                system.warn("checking $uid: ${system.describe(e)}")
            }
        }
    }

    /**
     * Find the permissions held revoked for [uid] that the user changed
     * since the last check, and keep the state each was found in as the
     * one to give back, as the check does ([verify]). The system reports
     * the change, but the check waits for the burst of changes to settle,
     * and a command that comes first would otherwise give the permission
     * back, or take it again, as if the user had not chosen. Returns
     * their names.
     */
    private fun takeChosen(uid: Int): Set<String> {
        val guard = guarded[uid] ?: return emptySet()
        val found = guard.targets.permissions.mapNotNull { permission ->
            try {
                AppOpsGuardProtocol.chosenWhileHeld(taking(permission).state(uid, permission), permission in guard.held && !AppOpsGuardProtocol.isSpecialAccess(permission))
                    ?.let { permission to kept(guard.tookPermissions[permission], it) }
            } catch (e: Throwable) {
                system.warn("looking at $permission for $uid: ${system.describe(e)}")
                null
            }
        }.toMap()
        if (found.isNotEmpty()) guarded[uid] = guard.copy(tookPermissions = guard.tookPermissions + found, chosen = guard.chosen + found.keys)
        return found.keys
    }

    /**
     * What to keep of a permission found changed by the user ([found]),
     * [kept] being what was kept before: a special access given again to
     * one service was not taken from the others that had it.
     */
    private fun kept(kept: PermissionState?, found: PermissionState): PermissionState = if (found.granted) AppOpsGuardProtocol.regrantedToKeep(kept, found) else found

    /**
     * Report each of [chosen] that [uid] is still held to, and is to be
     * taken back once more, with the state it was found in, as the check
     * does: the daemon keeps that state to give back.
     */
    private fun reportChosen(uid: Int, chosen: Set<String>) {
        val guard = guarded[uid] ?: return
        for (permission in chosen intersect guard.targets.permissions) {
            guard.tookPermissions[permission]?.let { reportFound(uid, permission, it) }
        }
    }

    /**
     * Print [found] as the state to give [permission] back as, unless it was
     * the last one printed for it: a permission the system will not take
     * back is found granted again at every check, and each report has the
     * daemon write it down.
     */
    private fun reportFound(uid: Int, permission: String, found: PermissionState) {
        val guard = guarded[uid] ?: return
        if (guard.reported[permission] == found) return
        guarded[uid] = guard.copy(reported = guard.reported + (permission to found))
        system.emit(AppOpsGuardProtocol.revertedLine(uid, permission, found))
    }

    /**
     * The states of [names] for [uid] as they are now, or as [known] has
     * them; none for a permission no package of the uid requests.
     */
    private fun statesBefore(uid: Int, names: Set<String>, known: Map<String, PermissionState>): Map<String, PermissionState> = names.mapNotNull { name ->
        (known[name] ?: taking(name).state(uid, name))?.let { name to it }
    }.toMap()

    /** Whether this device knows the op [name]. */
    private fun isKnown(name: String): Boolean = ops.any { it.name == name }

    /** Set [name], if this device knows it, to [mode] for [uid]; whether it was set. */
    private fun setKnown(name: String, uid: Int, mode: Int): Boolean {
        val op = ops.find { it.name == name } ?: return false
        return setUidMode(op, uid, mode)
    }

    /**
     * Set [op] back for [uid] to [saved], or to the mode the system gives
     * it for its permission now if the grant is no longer [grantThen]
     * ([AppOpsGuardProtocol.modeToRestore]), unless the permission is among
     * those this program [held]; whether it was set.
     */
    private fun setBack(op: GuardedOp, uid: Int, saved: Int?, grantThen: Int?, held: Set<String>): Boolean {
        val compared = grantThen != null && AppOpsGuardProtocol.comparesGrant(op.permission, held)
        // Setting an op's default mode removes the uid mode.
        val default = op.defaultMode ?: MODE_DEFAULT
        return AppOpsGuardProtocol.restoreUidMode(
            desiredMode = {
                val grantNow = if (compared) grantMode(op, uid) else null
                AppOpsGuardProtocol.modeToRestore(saved, grantThen, grantNow) ?: default
            },
            writeMode = { mode ->
                setUidMode(op, uid, mode).also { set ->
                    // The marks change with the grant: one unchanged is as
                    // Android left it.
                    val grantNow = if (set && compared) grantMode(op, uid) else null
                    if (grantThen != null && grantNow != null && grantNow != grantThen) unmarkRevoked(op, uid, saved, grantThen)
                }
            },
            readMode = { states(uid, listOf(op)).single().uidMode ?: default },
        )
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
    private fun unmarkRevoked(op: GuardedOp, uid: Int, saved: Int?, grantThen: Int) {
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
        for (pkg in connected.requesting(uid, permission)) {
            system.requireOwner(uid)
            if (connected.flags(pkg, permission, uid) and AppOpsGuardProtocol.FLAG_REVOKED_COMPAT != value) {
                connected.setFlags(pkg, permission, uid, AppOpsGuardProtocol.FLAG_REVOKED_COMPAT, value)
            }
        }
    }

    /** The permissions this program holds revoked for a guarded uid, or took and has not given back. */
    private fun Guard?.heldPermissions(): Set<String> = if (this == null) emptySet() else targets.permissions + tookPermissions.keys

    /** The mode the system gives each guarded op of a runtime permission for its grant to [uid]. */
    private fun grantModes(uid: Int): Map<String, Int> = ops.mapNotNull { op -> grantMode(op, uid)?.let { op.name to it } }.toMap()

    /**
     * The mode the system gives [op] for its permission's grant to [uid],
     * or `null` where it has no runtime permission or the permissions
     * cannot be reached: its mode is then put back as saved.
     */
    private fun grantMode(op: GuardedOp, uid: Int): Int? {
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
     * Set what [uid] is held to again where it no longer is. Returns what
     * still is not after that: the ops the system did not keep at their
     * mode, and the permissions it did not keep revoked.
     */
    private fun verify(uid: Int): List<String> {
        system.requireOwner(uid)
        val targets = guarded[uid]?.targets ?: return emptyList()
        val again = AppOpsGuardProtocol.opsToSetAgain(states(uid), targets.ops)
        for (name in again) {
            if (setKnown(name, uid, targets.ops.getValue(name))) {
                system.emit(AppOpsGuardProtocol.revertedLine(uid, name))
            }
        }
        // Read again only when something was set again: otherwise every op
        // was found as it is held.
        val unheld = if (again.isEmpty()) mutableListOf() else AppOpsGuardProtocol.opsToSetAgain(states(uid), targets.ops).toMutableList()
        for (permission in targets.permissions) {
            try {
                // Changed by the user while held: their choice since it was
                // taken, which is what to give back from now on. Kept and
                // reported before it is taken again: holding it can revoke
                // it and then fail on its flags.
                // A special access has no mark of its own to lose.
                val wasHeld = guarded[uid]?.held.orEmpty().contains(permission) && !AppOpsGuardProtocol.isSpecialAccess(permission)
                val chosen = AppOpsGuardProtocol.chosenWhileHeld(taking(permission).state(uid, permission), wasHeld)
                    ?.let { kept(guarded[uid]?.tookPermissions?.get(permission), it) }
                if (chosen != null) {
                    guarded[uid]?.let { guarded[uid] = it.copy(tookPermissions = it.tookPermissions + (permission to chosen), chosen = it.chosen + permission) }
                    reportFound(uid, permission, chosen)
                }
                if (holdKnown(uid, permission) && chosen == null) system.emit(AppOpsGuardProtocol.revertedLine(uid, permission))
            } catch (e: Throwable) {
                system.warn("holding $permission for $uid: ${system.describe(e)}")
                unheld += permission
            }
        }
        return unheld
    }

    /** Print [unheld] for [uid]: [always], or if it changed since last printed. */
    private fun reportUnheld(uid: Int, unheld: List<String>, always: Boolean) {
        if (!always && reported[uid] == unheld) return
        reported[uid] = unheld
        system.emit(AppOpsGuardProtocol.unheldLine(uid, unheld))
    }

    /** The guarded ops' uid modes for [uid], as the service reports them. */
    private fun states(uid: Int, selected: List<GuardedOp> = ops): List<OpState> {
        val modes = system.uidModes(uid, selected.map { it.switchCode }.distinct().toIntArray())
        return selected.map { OpState(it.name, modes[it.switchCode], it.defaultMode) }
    }

    /** Whether the mode was set; a failure is reported and the rest carries on. */
    private fun setUidMode(op: GuardedOp, uid: Int, mode: Int): Boolean = try {
        system.requireOwner(uid)
        system.setUidMode(op.code, uid, mode)
        true
    } catch (e: Throwable) {
        system.warn("setting ${op.name} for $uid: ${system.describe(e)}")
        false
    }

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

    /** The state of [permission] for [uid], `null` if no package of it requests it. */
    private fun state(uid: Int, permission: String): PermissionState? {
        val connected = permissions()
        val first = connected.requesting(uid, permission).firstOrNull() ?: return null
        return PermissionState(connected.isGranted(uid, permission), connected.flags(first, permission, uid) and USER_FLAGS)
    }

    /**
     * [hold], and note whether [uid] has [permission] held: from then on,
     * the permission found no longer fixed was changed by the user. One
     * the system would not hold says nothing of the user.
     */
    private fun holdKnown(uid: Int, permission: String): Boolean {
        val changed = try {
            taking(permission).hold(uid, permission)
        } catch (e: Throwable) {
            guarded[uid]?.let { guarded[uid] = it.copy(held = it.held - permission) }
            throw e
        }
        guarded[uid]?.let { guarded[uid] = it.copy(held = it.held + permission) }
        return changed
    }

    /**
     * Revoke [permission] for [uid], fixed as the user's choice, where
     * it is not already; whether anything had to be changed.
     */
    private fun hold(uid: Int, permission: String): Boolean {
        val connected = permissions()
        val requesting = connected.requesting(uid, permission)
        var changed = false
        for (pkg in requesting) {
            system.requireOwner(uid)
            // Fixed first: held half way, the permission is granted and
            // fixed, which no one takes for the user's revocation.
            if (connected.flags(pkg, permission, uid) and FLAG_USER_FIXED == 0) {
                connected.setFlags(pkg, permission, uid, FLAG_USER_FIXED, FLAG_USER_FIXED)
                changed = true
            }
            if (connected.isGranted(uid, permission)) {
                connected.revoke(pkg, permission, uid)
                changed = true
            }
        }
        check(requesting.all { AppOpsGuardProtocol.isHeld(connected.isGranted(uid, permission), connected.flags(it, permission, uid)) }) {
            "the system did not take $permission away"
        }
        return changed
    }

    /**
     * Give [uid] back [permission] as it was ([state]), or, with no state
     * known, only let the app ask for it again.
     */
    private fun putBack(uid: Int, permission: String, state: PermissionState?) {
        val connected = permissions()
        val requesting = connected.requesting(uid, permission)
        for (pkg in requesting) {
            system.requireOwner(uid)
            if (state == null) {
                connected.setFlags(pkg, permission, uid, FLAG_USER_FIXED, 0)
                continue
            }
            val granted = connected.isGranted(uid, permission)
            if (state.granted && !granted) connected.grant(pkg, permission, uid)
            if (!state.granted && granted) connected.revoke(pkg, permission, uid)
            connected.setFlags(pkg, permission, uid, USER_FLAGS, state.flags)
        }
        check(requesting.all { AppOpsGuardProtocol.isPutBack(state, connected.isGranted(uid, permission), connected.flags(it, permission, uid)) }) {
            "the system did not give $permission back"
        }
    }
}
