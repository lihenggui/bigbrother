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
    private const val SERVICE_POLL_MS = 1000L

    /** How often every guarded uid is checked without being asked. */
    private const val CHECK_EVERY_MS = 60_000L

    /**
     * How long a check prompted by a reported change waits, and the least
     * time between two such checks: the system reports each op separately,
     * so changes come in bursts, and this program's own changes are
     * reported too.
     */
    private const val SETTLE_MS = 1000L

    private val changes = LinkedBlockingQueue<Unit>()

    private lateinit var service: Any

    /**
     * Methods are looked up on the public interface rather than on the
     * service's own class, a proxy class private to the framework.
     */
    private val serviceType by lazy { Class.forName("com.android.internal.app.IAppOpsService") }

    override lateinit var ops: List<GuardedOp>
        private set

    /** The runtime permissions, if this device lets them be reached. */
    override var permissions: Permissions? = null
        private set

    /** Why they cannot be, if they cannot. */
    override var permissionsMissing = ""
        private set

    /** Reached when first needed: by then the system has started. */
    private var specialAccess: SpecialAccess? = null

    fun connect() {
        val binder = systemService("appops") ?: exitProcess(0)
        service = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
        // A system server that restarted is another one: the daemon
        // starts this program again, for it.
        binder.linkToDeath({ exitProcess(1) }, 0)
        ops = resolve(GUARDED_OPS)
        watchModes()
        connectPermissions()
    }

    /**
     * The system service [name], or `null` if the input closed first. The
     * daemon starts this program while the system is still booting, before
     * the services are registered, so it waits for them rather than fail.
     */
    fun systemService(name: String): IBinder? {
        val getService = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
        var binder = getService.invoke(null, name) as IBinder?
        while (binder == null) {
            if (AppOpsGuard.inputClosed) return null
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
     * Reach the runtime permissions, and have their changes reported. A
     * device where that fails still holds ops; a command that names a
     * permission fails there.
     */
    private fun connectPermissions() {
        try {
            val permissionManager = systemService("permissionmgr") ?: exitProcess(0)
            val packageManager = systemService("package") ?: exitProcess(0)
            val connected = Permissions(
                Class.forName("android.permission.IPermissionManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, permissionManager)!!,
                Class.forName("android.content.pm.IPackageManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, packageManager)!!,
            )
            permissions = connected
            try {
                connected.watch(ChangeWatcher())
            } catch (e: Throwable) {
                System.err.println("not watching permission changes, checking every minute only: ${describe(e)}")
            }
        } catch (e: Throwable) {
            permissionsMissing = describe(e)
            System.err.println("runtime permissions cannot be reached: $permissionsMissing")
        }
    }

    fun packagesOf(uid: Int): List<String> {
        val connected = permissions ?: error("runtime permissions cannot be reached: $permissionsMissing")
        return connected.packagesOf(uid)
    }

    override fun requireOwner(uid: Int) = AppOpsGuard.requireOwner(uid)

    override fun restoreItem(name: String, change: () -> Unit) = AppOpsGuard.restoreItem(name, change)

    override fun specialAccess(): Taking = specialAccess ?: SpecialAccess(packageManager()).also { specialAccess = it }

    override fun resolve(name: String): GuardedOp? = resolve(listOf(name)).firstOrNull()

    override fun uidModes(uid: Int, switchCodes: IntArray): Map<Int, Int> {
        // Only ops with a uid mode of their own are listed, under their
        // switch op; `null` if there are none.
        val packages = serviceType
            .getMethod("getUidOps", Int::class.javaPrimitiveType, IntArray::class.java)
            .invoke(service, uid, switchCodes) as List<*>?
        val modes = mutableMapOf<Int, Int>()
        for (pkg in packages.orEmpty()) {
            for (entry in pkg?.call("getOps") as List<*>? ?: continue) {
                entry ?: continue
                modes[entry.call("getOp") as Int] = entry.call("getMode") as Int
            }
        }
        return modes
    }

    override fun setUidMode(code: Int, uid: Int, mode: Int) {
        serviceType
            .getMethod("setUidMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(service, code, uid, mode)
    }

    override fun emit(line: String) = AppOpsGuard.emit(line)

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
            val permission = (toPermission?.invokeOrNull(switchCode) as String?)
                ?.takeIf { permissionToCode?.invokeOrNull(it) == switchCode }
            GuardedOp(name, code, switchCode, toDefault?.invokeOrNull(switchCode) as Int?, permission)
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
            val startWatching = serviceType
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

    /** Check every guarded uid after a reported change, and every minute. */
    fun checkLoop(lock: Any, handler: AppOpsGuardHandler) {
        while (true) {
            if (changes.poll(CHECK_EVERY_MS, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(SETTLE_MS)
                changes.clear()
            }
            synchronized(lock) { handler.checkAll() }
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
