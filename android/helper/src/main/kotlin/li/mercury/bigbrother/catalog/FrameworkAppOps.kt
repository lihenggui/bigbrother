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

@file:Suppress("ktlint:standard:filename")

package li.mercury.bigbrother.catalog

import android.annotation.SuppressLint
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.GUARDED_OPS
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** The app-ops service and the change notifications that prompt the guard to check again. */
@SuppressLint("PrivateApi")
internal object Framework : AppOpsSystem {
    /** How often to look again for a system service not registered yet. */
    private const val SERVICE_POLL_MS = 250L
    private const val SERVICE_WAIT_MS = 1500L

    /** The wait before the second attempt to reach the runtime permissions; it doubles from there. */
    private val RECONNECT_FIRST_NANOS = TimeUnit.MILLISECONDS.toNanos(250)

    /** How often every guarded uid is checked without being asked. */
    private const val CHECK_EVERY_MS = 60_000L

    /**
     * How long a check prompted by a reported change waits, and the least
     * time between two such checks: the system reports each op separately,
     * so changes come in bursts, and this program's own changes are
     * reported too.
     */
    private const val SETTLE_MS = 1000L

    private val changes = LinkedBlockingQueue<Unit>(1)

    private lateinit var guard: AppOpsGuard
    private lateinit var service: Any

    private val getUidOps by lazy { appOpsServiceType.getMethod("getUidOps", INT, IntArray::class.java) }
    private val setUidModeMethod by lazy { appOpsServiceType.getMethod("setUidMode", INT, INT, INT) }
    private val packageOps by lazy { Class.forName("android.app.AppOpsManager\$PackageOps").getMethod("getOps") }
    private val opEntry by lazy { Class.forName("android.app.AppOpsManager\$OpEntry") }
    private val entryOp by lazy { opEntry.getMethod("getOp") }
    private val entryMode by lazy { opEntry.getMethod("getMode") }

    private val activityManagerClass by lazy { Class.forName("android.app.ActivityManager") }
    private val activityManager by lazy { activityManagerClass.getMethod("getService").invoke(null)!! }
    private val uidProcessState by lazy { Class.forName("android.app.IActivityManager").getMethod("getUidProcessState", INT, STRING) }
    private val nonexistentProcessState by lazy { activityManagerClass.getField("PROCESS_STATE_NONEXISTENT").getInt(null) }
    private val userManager by lazy {
        val binder = systemService("user") ?: error("no user service")
        Class.forName("android.os.IUserManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }
    private val users by lazy { Class.forName("android.os.IUserManager").getMethod("getUsers", BOOLEAN, BOOLEAN, BOOLEAN) }
    private val userId by lazy { Class.forName("android.content.pm.UserInfo").getField("id") }

    override lateinit var ops: List<GuardedOp>
        private set

    /**
     * The runtime permissions, if they can be reached. The package manager
     * starts well after the app-ops service: a program started as the
     * system boots finds them later ([checkLoop]).
     */
    override val permissions: Permissions? get() = permissionService.get()

    private var permissionsSought = false

    private val permissionService = Reconnecting(RECONNECT_FIRST_NANOS, TimeUnit.MILLISECONDS.toNanos(CHECK_EVERY_MS)) {
        // Only the first attempt waits: the later ones are made during checks and commands.
        connectPermissions(if (permissionsSought) 0 else SERVICE_WAIT_MS).also { permissionsSought = true }
    }

    /** Why they cannot be, if they cannot. */
    @Volatile
    override var permissionsMissing = ""
        private set

    /** Reached when first needed: by then the system has started. */
    private val specialAccess by lazy { SpecialAccess(packageManager()) }

    fun connect(guard: AppOpsGuard) {
        this.guard = guard
        val binder = systemService("appops") ?: error("no app ops service")
        service = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
        // A system server that restarted is another one: the daemon
        // starts this program again, for it.
        binder.linkToDeath({ exitProcess(1) }, 0)
        ops = resolve(GUARDED_OPS)
        watchModes()
        permissionService.get()
    }

    /**
     * The system service [name], or `null` if the input closed first. The
     * daemon starts this program while the system is still booting, before
     * the services are registered, so it waits [waitMs] for them rather
     * than fail.
     */
    fun systemService(name: String, waitMs: Long = SERVICE_WAIT_MS): IBinder? {
        val getService = Class.forName("android.os.ServiceManager")
            .getMethod("checkService", String::class.java)
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        var binder = getService.invoke(null, name) as IBinder?
        while (binder == null) {
            if (guard.inputClosed || System.nanoTime() >= until) return null
            Thread.sleep(SERVICE_POLL_MS)
            binder = getService.invoke(null, name) as IBinder?
        }
        return binder
    }

    fun packageManager(): Any {
        val binder = systemService("package") ?: error("no package service")
        return Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    /**
     * Reach the runtime permissions, waiting [waitMs] for their services,
     * and have their changes reported. Until that works, ops are still
     * held and a command that names a permission fails; it is tried again
     * when they are next asked for.
     */
    private fun connectPermissions(waitMs: Long): Permissions? {
        try {
            val permissionManager = systemService("permissionmgr", waitMs) ?: error("no permission service")
            val packageManager = systemService("package", waitMs) ?: error("no package service")
            val connected = Permissions(
                Class.forName("android.permission.IPermissionManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, permissionManager)!!,
                Class.forName("android.content.pm.IPackageManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, packageManager)!!,
            )
            permissionsMissing = ""
            try {
                connected.watch(ChangeWatcher())
            } catch (e: Throwable) {
                System.err.println("not watching permission changes, checking every minute only: ${describe(e)}")
            }
            return connected
        } catch (e: Throwable) {
            val reason = describe(e)
            // Asked again at every check: said once per reason.
            if (reason != permissionsMissing) System.err.println("runtime permissions cannot be reached: $reason")
            permissionsMissing = reason
            return null
        }
    }

    fun packagesOf(uid: Int): List<String> {
        val connected = permissions ?: error("runtime permissions cannot be reached: $permissionsMissing")
        return connected.packagesOf(uid)
    }

    override fun requireOwner(uid: Int) = guard.requireOwner(uid)

    override fun restoreItem(name: String, change: () -> Unit) = guard.restoreItem(name, change)

    override fun specialAccess(): Taking = specialAccess

    override fun resolve(name: String): GuardedOp? = resolve(listOf(name)).firstOrNull()

    override fun uidModes(uid: Int, switchCodes: IntArray): Map<Int, Int> {
        // Only ops with a uid mode of their own are listed, under their
        // switch op; `null` if there are none.
        val packages = getUidOps.invoke(service, uid, switchCodes) as List<*>?
        val modes = mutableMapOf<Int, Int>()
        for (pkg in packages.orEmpty()) {
            pkg ?: continue
            for (entry in packageOps.invoke(pkg) as List<*>? ?: continue) {
                entry ?: continue
                modes[entryOp.invoke(entry) as Int] = entryMode.invoke(entry) as Int
            }
        }
        return modes
    }

    override fun setUidMode(code: Int, uid: Int, mode: Int) {
        setUidModeMethod.invoke(service, code, uid, mode)
    }

    override fun canChangeAllFilesAccess(uid: Int): Boolean = runCatching {
        val user = userOf(uid)
        // The running-user list leaves out stopping profiles whose processes may still be alive.
        val profiles = (users.call(userManager, false, false, false) as List<*>).map { userId.getInt(it) }
        user in profiles && profiles.all { other ->
            other == user || uidProcessState.call(activityManager, uidInUser(uid, other), "root") == nonexistentProcessState
        }
    }.getOrDefault(false)

    override fun emit(line: String) = guard.emit(line)

    override fun warn(message: String) = System.err.println(message)

    override fun describe(e: Throwable): String = describeFailure(e)

    private fun resolve(names: List<String>): List<GuardedOp> {
        val manager = Class.forName("android.app.AppOpsManager")
        val toCode = manager.getMethod("strOpToOp", String::class.java)
        val toSwitch = manager.optionalMethod("opToSwitch", Int::class.javaPrimitiveType!!)
        val toDefault = manager.optionalMethod("opToDefaultMode", Int::class.javaPrimitiveType!!)
        val toPermission = manager.optionalMethod("opToPermission", Int::class.javaPrimitiveType!!)
        val permissionToCode = manager.optionalMethod("permissionToOpCode", String::class.java)
        return names.mapNotNull { name ->
            // A name this Android version doesn't know is left out.
            val code = try {
                toCode.invoke(null, name) as Int
            } catch (_: Throwable) {
                return@mapNotNull null
            }
            // The service keeps uid modes under the op that switches a group
            // of ops together, and reports them under it.
            val switchCode = toSwitch?.invokeOrNull(code) as Int? ?: code
            // Several ops can name one permission: the system sets by its
            // grant only the op the permission maps back to.
            val declaredBy = toPermission?.invokeOrNull(switchCode) as String?
            val permission = declaredBy?.takeIf { permissionToCode?.invokeOrNull(it) == switchCode }
            GuardedOp(name, code, switchCode, toDefault?.invokeOrNull(switchCode) as Int?, permission, declaredBy)
        }
    }

    /**
     * Ask the service to report changes to the guarded ops. Its callback
     * interface is internal, so a plain [Binder] stands in for an
     * implementation of it: every call it receives is taken as "something
     * changed", whatever its arguments. If the service will not take it, the
     * checks every minute remain.
     */
    private fun watchModes() {
        try {
            val callbackType = Class.forName("com.android.internal.app.IAppOpsCallback")
            val asInterface = Class.forName("com.android.internal.app.IAppOpsCallback\$Stub")
                .getMethod("asInterface", IBinder::class.java)
            val startWatching = appOpsServiceType
                .getMethod("startWatchingMode", Int::class.javaPrimitiveType, String::class.java, callbackType)
            // One callback per op: the service files a callback under the op
            // it was first registered for.
            for (code in ops.map { it.switchCode }.distinct()) {
                startWatching.invoke(service, code, null, asInterface.invoke(null, ChangeWatcher()))
            }
        } catch (e: Throwable) {
            System.err.println("not watching mode changes, checking every minute only: ${describe(e)}")
        }
    }

    /**
     * Check every guarded uid after a reported change, and every minute.
     * While the runtime permissions are not reached, also wake to look
     * for them when the next attempt is due, and check as soon as they
     * are: what a check reports as unheld without them is held with them.
     */
    fun checkLoop() {
        val every = TimeUnit.MILLISECONDS.toNanos(CHECK_EVERY_MS)
        var checkAt = System.nanoTime() + every
        while (true) {
            try {
                val untilCheck = maxOf(0, checkAt - System.nanoTime())
                val wait = minOf(untilCheck, permissionService.retryInNanos() ?: untilCheck)
                if (changes.poll(wait, TimeUnit.NANOSECONDS) != null) {
                    Thread.sleep(SETTLE_MS)
                    changes.clear()
                } else if (System.nanoTime() - checkAt < 0 && permissions == null) {
                    continue
                }
                guard.checkAll()
                checkAt = System.nanoTime() + every
            } catch (_: InterruptedException) {
                return
            } catch (e: Throwable) {
                System.err.println("checking app ops: ${describeFailure(e)}")
                checkAt = System.nanoTime()
                try {
                    Thread.sleep(SETTLE_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** Stands in for the system's callback interfaces: see [watchModes]. */
    private class ChangeWatcher : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            changes.offer(Unit)
            return true
        }
    }
}
