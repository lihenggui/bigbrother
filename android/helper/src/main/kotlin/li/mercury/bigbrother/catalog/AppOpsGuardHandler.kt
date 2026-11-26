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
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_DEFAULT
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.OpState
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.PermissionState
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.Targets
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.Values
import li.mercury.bigbrother.catalog.Taking.HoldResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Carries out [AppOpsGuard]'s commands and checks on what [system] reports
 * and changes. The caller serializes access to each app's state.
 */
internal class AppOpsGuardHandler(
    private val system: AppOpsSystem,
    private val supervised: Boolean = false,
    private val now: () -> Long = System::nanoTime,
) {
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
        val persisted: Map<String, PermissionState> = emptyMap(),
        val retries: Retries = Retries(),
    ) {
        /** What to give [permission] back as, the daemon having sent [sent]. */
        fun toGiveBack(permission: String, sent: PermissionState?): PermissionState? {
            val local = tookPermissions[permission]
            val changedHere = permission in chosen
            // A replacement helper may only know the services granted since it started.
            if (changedHere && sent != null && local != null && AppOpsGuardProtocol.isSpecialAccess(permission)) {
                return PermissionState.ofServices(sent.services + local.services)
            }
            return AppOpsGuardProtocol.stateToGiveBack(sent, local, changedHere)
        }

        fun savedValues(): Values = Values(checkNotNull(saved), tookPermissions.toList(), savedGrants)

        fun retriesFor(next: Targets): Retries = if (targets.ops == next.ops && targets.permissions == next.permissions) retries else Retries()

        fun findChoice(permission: String, found: PermissionState?): PermissionState? {
            val runtime = !AppOpsGuardProtocol.isSpecialAccess(permission)
            if (runtime && found?.granted == true) retries.noteSuspendedGrant(permission)
            val wasHeld = runtime && (permission in held || retries.wasGrantedWhileSuspended(permission))
            return AppOpsGuardProtocol.chosenWhileHeld(found, wasHeld)
        }
    }

    private class Retries {
        private val attempts = mutableMapOf<String, ArrayDeque<Long>>()
        private val suspended = mutableMapOf<String, Boolean>()

        fun suspended(name: String): Boolean = name in suspended

        // After suspension only a revocation from a known grant can be
        // distinguished from an earlier partial hold by this helper.
        fun noteSuspendedGrant(name: String) {
            if (suspended(name)) suspended[name] = true
        }

        fun wasGrantedWhileSuspended(name: String): Boolean = suspended[name] == true

        fun observedHeld(name: String) {
            attempts.getOrPut(name) { ArrayDeque() }
        }

        fun mayWrite(name: String, now: Long): Boolean {
            if (suspended(name)) return false
            val previous = attempts[name]
            if (previous == null) {
                observedHeld(name)
                return true
            }
            while (previous.firstOrNull()?.let { now - it >= RETRY_WINDOW } == true) previous.removeFirst()
            // Allow brief policy syncs, but do not periodically resume writes
            // once they have been killing processes or unbinding services.
            if (previous.size == RETRY_LIMIT) {
                suspended[name] = false
                return false
            }
            previous.addLast(now)
            return true
        }
    }

    /** The guarded uids. */
    private val guarded = ConcurrentHashMap<Int, Guard>()

    private data class Prepared(val targets: Targets, val values: Values)

    private val prepared = ConcurrentHashMap<Int, Prepared>()

    /** The unheld ops last printed for each guarded uid. */
    private val reported = ConcurrentHashMap<Int, List<String>>()

    private val ops get() = system.ops

    private val runtimePermissions = RuntimePermissionChanges(system)

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
            retries = guard?.retriesFor(targets) ?: Retries(),
        )
    }

    fun handle(command: Command, transaction: Boolean = false) {
        if (transaction && (command is Command.Set || command is Command.Apply || command is Command.Restore)) {
            val before = checkNotNull(prepared[command.uid]) { "missing transaction preimage" }
            check(before.values == captureValues(command.uid, before.targets)) { "transaction preimage changed" }
        }
        when (command) {
            is Command.Capture -> handleCapture(command, transaction)

            is Command.Set -> handleSet(command)

            is Command.Apply -> handleApply(command, transaction)

            is Command.Restore -> handleRestore(command)

            is Command.Release -> handleRelease(command)

            is Command.Verify -> handleVerify(command)

            is Command.Undo -> handleUndo(command)

            is Command.Persisted -> {
                val guard = guarded[command.uid]
                if (guard?.reported?.get(command.permission) == command.state) {
                    guarded[command.uid] = guard.copy(persisted = guard.persisted + (command.permission to command.state))
                }
            }

            // Answered apart from the commands: see AppOpsGuard.
            is Command.Kind -> error("not a command on what the uid is held to")
        }
    }

    private fun captureValues(uid: Int, targets: Targets): Values {
        // Changing a precise-location grant can also make Android update its location switches.
        val location = "android.permission.ACCESS_FINE_LOCATION" in targets.permissions
        val related = ops.filter { it.name in targets.ops || (location && it.name in LOCATION_OPS) }
        val modes = system.uidModes(uid, related.map { it.switchCode }.distinct().toIntArray())
        return Values(
            related.map { it.name to modes[it.switchCode] },
            statesBefore(uid, targets.permissions, emptyMap()).toList(),
            related.mapNotNull { op -> runtimePermissions.grantMode(op, uid)?.let { op.name to it } }.toMap(),
        )
    }

    private fun handleCapture(command: Command.Capture, transaction: Boolean) {
        val uid = command.uid
        val values = if (transaction) {
            captureValues(uid, command.targets).also { prepared[uid] = Prepared(command.targets, it) }
        } else {
            beforeSet(uid, command.targets).savedValues()
        }
        system.emit(AppOpsGuardProtocol.capturedLine(uid, command.request, values))
    }

    private fun handleUndo(command: Command.Undo) {
        val uid = command.uid
        // Stop the failed policy before restoring, even if readback fails.
        guarded.remove(uid)
        reported.remove(uid)
        val modes = command.values.ops.mapNotNull { (name, mode) ->
            val op = ops.find { it.name == name } ?: system.resolve(name) ?: return@mapNotNull null
            op to mode
        }.distinctBy { (op, _) -> op.switchCode }
        if (command.mayWrite) {
            for ((name, state) in command.values.permissions) {
                system.restoreItem(name) { taking(name).putBack(uid, name, state) }
            }
            for ((op, mode) in modes) {
                system.restoreItem(op.name) {
                    check(setBack(op, uid, mode, null, emptySet())) { "app op recovery incomplete" }
                }
            }
        }
        // Restoring a grant can trigger a permission-policy sync. Check
        // the full preimage after the last write before acknowledging.
        for ((name, state) in command.values.permissions) {
            check(taking(name).state(uid, name) == state) { "permission recovery readback changed" }
        }
        for ((op, mode) in modes) {
            check(readMode(op, uid) == (mode ?: op.defaultMode ?: MODE_DEFAULT)) { "app op recovery readback changed" }
        }
        forget(uid)
        system.emit(AppOpsGuardProtocol.restoredLine(uid))
    }

    private fun handleSet(command: Command.Set) {
        val uid = command.uid
        val chosen = takeChosen(uid)
        val targets = command.targets
        val captured = prepared[uid]?.values
        val guard = if (captured == null) {
            beforeSet(uid, targets)
        } else {
            Guard(
                saved = captured.ops,
                targets = targets,
                tookPermissions = captured.permissions.mapNotNull { (name, state) -> state?.let { name to it } }.toMap(),
                savedGrants = captured.grants,
            )
        }
        // Written down first, so that a `release` after a failure
        // below puts back whatever was done.
        guarded[uid] = guard
        holdOps(uid, initial = true, requireAccepted = true)
        // Reported first, as the check does: holding can fail.
        reportChosen(uid, chosen)
        val unheld = holdPermissions(uid, observeChoices = false)
        reportUnheld(uid, holdOps(uid) + unheld, always = true)
        system.emit(AppOpsGuardProtocol.savedLine(uid, guard.savedValues()))
    }

    private fun handleApply(command: Command.Apply, transaction: Boolean) {
        val uid = command.uid
        val chosen = takeChosen(uid)
        val guard = guarded[uid] ?: if (transaction) Guard(null, Targets.NONE) else error("not guarded")
        // Permissions taken only below are still as the user has them.
        val held = AppOpsGuardProtocol.heldWhileSettingBack(guard.heldPermissions(), command.resets)
        val restored = command.resets.ops.count { (name, mode) ->
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
            retries = guard.retriesFor(targets),
        )
        holdOps(uid, initial = true, requireAccepted = restored == 0)
        // Reported first, as the check does: holding can fail.
        reportChosen(uid, chosen)
        val unheld = holdPermissions(uid, observeChoices = false)
        reportUnheld(uid, holdOps(uid) + unheld, always = true)
        system.emit(AppOpsGuardProtocol.appliedLine(uid, found.toList()))
    }

    private fun handleRestore(command: Command.Restore) {
        val uid = command.uid
        takeChosen(uid)
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
                system.warn("restore $uid: app op unavailable")
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

    private fun handleRelease(command: Command.Release) {
        val uid = command.uid
        takeChosen(uid)
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

    private fun handleVerify(command: Command.Verify) {
        val uid = command.uid
        prepared.remove(uid)
        val guard = guarded[uid]
        // A uid new here was held by the program before this one:
        // the daemon holds it to a permission only once a command
        // has taken it. Found no longer fixed, the user changed it
        // while neither was there to see; but not one the daemon
        // last heard was unheld, which that program may have left
        // half taken.
        guarded[uid] = guard?.copy(targets = command.targets, retries = guard.retriesFor(command.targets))
            ?: Guard(null, command.targets, held = command.targets.permissions - command.targets.unheld)
        reportUnheld(uid, verify(uid), always = true)
        system.emit(AppOpsGuardProtocol.verifiedLine(uid))
    }

    /** Whether [uid] is guarded. */
    fun isGuarded(uid: Int): Boolean = guarded.containsKey(uid)

    fun needsOwner(uid: Int): Boolean = guarded.containsKey(uid) || prepared.containsKey(uid)

    /** Stop guarding [uid], forgetting all about it: it is another installation's now, or given back. */
    fun forget(uid: Int) {
        prepared.remove(uid)
        guarded.remove(uid)
        reported.remove(uid)
    }

    /**
     * Check every guarded uid, as after a reported change and every minute,
     * printing what is found changed, and what is unheld if that changed.
     */
    fun checkAll(withAppLock: (Int, () -> Unit) -> Unit = { _, check -> check() }) {
        for (uid in guarded.keys) withAppLock(uid) { check(uid) }
    }

    private fun check(uid: Int) {
        if (!guarded.containsKey(uid) || prepared.containsKey(uid)) return
        if (supervised) system.emit("checking $uid")
        try {
            reportUnheld(uid, verify(uid), always = supervised)
        } catch (e: Throwable) {
            reported.remove(uid)
            if (supervised) system.emit("unknown-readback $uid")
            system.warn("checking $uid: ${system.describe(e)}")
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
                guard.findChoice(permission, taking(permission).state(uid, permission))
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

    /**
     * Set [op] back for [uid] to [saved], or to the mode the system gives
     * it for its permission now if the grant is no longer [grantThen]
     * ([AppOpsGuardProtocol.modeToRestore]), unless the permission is among
     * those this program [held]; whether it was set.
     */
    private fun setBack(op: GuardedOp, uid: Int, saved: Int?, grantThen: Int?, held: Set<String>): Boolean {
        val compared = grantThen != null && AppOpsGuardProtocol.comparesGrant(op.permission, held)
        val destructive = op.name in DESTRUCTIVE_OPS
        // Setting an op's default mode removes the uid mode.
        val default = op.defaultMode ?: MODE_DEFAULT
        var unrequested: Boolean? = null
        val restored = AppOpsGuardProtocol.restoreUidMode(
            desiredMode = {
                if (destructive || (saved != null && saved != default)) {
                    unrequested = runtimePermissions.isUnrequestedAppOp(op, uid)
                }
                // Android drops a non-default mode if an update removed the
                // last request for its app-op permission.
                if (unrequested == true) {
                    default
                } else {
                    val grantNow = if (compared) runtimePermissions.grantMode(op, uid) else null
                    AppOpsGuardProtocol.modeToRestore(saved, grantThen, grantNow) ?: default
                }
            },
            writeMode = { mode ->
                if (destructive && unrequested == null) {
                    val unheld = reported[uid].orEmpty()
                    if (op.name !in unheld) reportUnheld(uid, unheld + op.name, always = false)
                    false
                } else {
                    (!destructive || system.canChangeAllFilesAccess(uid)) && setUidMode(op, uid, mode)
                }
            },
            readMode = { readMode(op, uid) ?: default },
        )
        // The marks can need restoring even if the mode was already right.
        val grantNow = if (restored && compared) runtimePermissions.grantMode(op, uid) else null
        if (grantThen != null && grantNow != null && grantNow != grantThen) runtimePermissions.unmarkRevoked(op, uid, saved, grantThen)
        return restored
    }

    /** The permissions this program holds revoked for a guarded uid, or took and has not given back. */
    private fun Guard?.heldPermissions(): Set<String> = if (this == null) emptySet() else targets.permissions + tookPermissions.keys

    /** The mode the system gives each guarded op of a runtime permission for its grant to [uid]. */
    private fun grantModes(uid: Int): Map<String, Int> = ops.mapNotNull { op -> runtimePermissions.grantMode(op, uid)?.let { op.name to it } }.toMap()

    /**
     * Set what [uid] is held to again where it no longer is. Returns what
     * still is not after that: the ops the system did not keep at their
     * mode, and the permissions it did not keep revoked.
     */
    private fun verify(uid: Int): List<String> {
        system.requireOwner(uid)
        return holdOps(uid) + holdPermissions(uid, observeChoices = true)
    }

    private fun holdOps(uid: Int, initial: Boolean = false, requireAccepted: Boolean = false): List<String> {
        val guard = guarded.getValue(uid)
        val unheld = mutableListOf<String>()
        val holding = ops.filter { op ->
            if (op.name !in guard.targets.ops) return@filter false
            // Check again after a package update, which can change requests.
            when (runtimePermissions.isUnrequestedAppOp(op, uid)) {
                true -> false

                null if op.name in DESTRUCTIVE_OPS -> {
                    unheld += op.name
                    false
                }

                else -> true
            }
        }.groupBy { it.switchCode }
        if (holding.isEmpty()) return unheld
        val switches = holding.keys.toIntArray()
        val modes = system.uidModes(uid, switches)
        var attempted = false
        var accepted = false
        // Aliases share the switch's mode and any retry budget.
        for ((switch, aliases) in holding) {
            val op = aliases.first()
            val destructive = op.name in DESTRUCTIVE_OPS
            val mode = guard.targets.ops.getValue(op.name)
            val current = modes[switch] ?: op.defaultMode
            if (current == mode || (attempted && readMode(op, uid) == mode)) {
                if (destructive) guard.retries.observedHeld(op.name)
                accepted = true
            } else if (!destructive ||
                (!guard.retries.suspended(op.name) && system.canChangeAllFilesAccess(uid) && guard.retries.mayWrite(op.name, now()))
            ) {
                attempted = true
                if (setUidMode(op, uid, mode)) {
                    accepted = true
                    if (!initial) system.emit(AppOpsGuardProtocol.revertedLine(uid, op.name))
                }
            }
        }
        if (initial) {
            check(!requireAccepted || !attempted || accepted) { "the system took none of the modes" }
            return unheld
        }
        val readback = if (attempted) system.uidModes(uid, switches) else modes
        for ((switch, aliases) in holding) {
            val op = aliases.first()
            val actual = readback[switch] ?: op.defaultMode
            for (alias in aliases) {
                if (guard.retries.suspended(op.name) || actual != guard.targets.ops.getValue(alias.name)) unheld += alias.name
            }
        }
        return unheld
    }

    private fun holdPermissions(uid: Int, observeChoices: Boolean): List<String> = guarded.getValue(uid).targets.permissions.filter { permission ->
        try {
            val guard = guarded.getValue(uid)
            val access = taking(permission)
            var chosen: PermissionState? = null
            if (observeChoices) {
                // Changed by the user while held: their choice since it was
                // taken, which is what to give back from now on. Kept and
                // reported before it is taken again: holding it can revoke
                // it and then fail on its flags.
                val current = access.state(uid, permission)
                chosen = guard.findChoice(permission, current)
                    ?.let { kept(guard.tookPermissions[permission], it) }
                if (supervised && chosen == null && permission !in guard.held && current != null) {
                    chosen = current
                }
                if (chosen != null) {
                    guarded[uid] = guard.copy(tookPermissions = guard.tookPermissions + (permission to chosen), chosen = guard.chosen + permission)
                    reportFound(uid, permission, chosen)
                    if (supervised && guard.persisted[permission] != chosen) return@filter true
                }
            }
            val result = holdKnown(uid, permission, access)
            if (observeChoices && result == HoldResult.CHANGED && chosen == null) system.emit(AppOpsGuardProtocol.revertedLine(uid, permission))
            result == HoldResult.SUSPENDED
        } catch (e: Throwable) {
            if (supervised && observeChoices) throw e
            system.warn("holding $permission for $uid: ${system.describe(e)}")
            true
        }
    }

    /** Print [unheld] for [uid]: [always], or if it changed since last printed. */
    private fun reportUnheld(uid: Int, unheld: List<String>, always: Boolean) {
        if (!always && reported[uid] == unheld) return
        reported[uid] = unheld
        system.emit(AppOpsGuardProtocol.unheldLine(uid, unheld))
    }

    /** The guarded ops' uid modes for [uid], as the service reports them. */
    private fun states(uid: Int): List<OpState> {
        val modes = system.uidModes(uid, ops.map { it.switchCode }.distinct().toIntArray())
        return ops.map { OpState(it.name, modes[it.switchCode], it.defaultMode) }
    }

    private fun readMode(op: GuardedOp, uid: Int): Int? = system.uidModes(uid, intArrayOf(op.switchCode))[op.switchCode] ?: op.defaultMode

    /** Whether the mode was set; a failure is reported and the rest carries on. */
    private fun setUidMode(op: GuardedOp, uid: Int, mode: Int): Boolean = try {
        system.requireOwner(uid)
        system.setUidMode(op.code, uid, mode)
        true
    } catch (e: Throwable) {
        system.warn("setting ${op.name} for $uid: ${system.describe(e)}")
        false
    }

    /**
     * [Taking.hold], and note whether [uid] has [permission] held: from then on,
     * the permission found no longer fixed was changed by the user. One
     * the system would not hold says nothing of the user.
     */
    private fun holdKnown(uid: Int, permission: String, access: Taking): HoldResult {
        val guard = guarded.getValue(uid)
        if (guard.retries.suspended(permission)) return HoldResult.SUSPENDED
        val result = try {
            access.hold(uid, permission) { guard.retries.mayWrite(permission, now()) }
        } catch (e: Throwable) {
            guarded[uid] = guard.copy(held = guard.held - permission)
            throw e
        }
        if (result != HoldResult.SUSPENDED) {
            guard.retries.observedHeld(permission)
            if (permission !in guard.held) guarded[uid] = guard.copy(held = guard.held + permission)
        } else if (!AppOpsGuardProtocol.isSpecialAccess(permission) && access.state(uid, permission)?.granted == true) {
            guard.retries.noteSuspendedGrant(permission)
        }
        return result
    }

    private companion object {
        val LOCATION_OPS = setOf("android:coarse_location", "android:fine_location", "android:wifi_scan")
        val DESTRUCTIVE_OPS = setOf(
            // StorageManagerService can end this app ID's processes across users.
            "android:manage_external_storage",
        )
        const val RETRY_LIMIT = 3
        val RETRY_WINDOW = TimeUnit.MINUTES.toNanos(10)
    }
}
