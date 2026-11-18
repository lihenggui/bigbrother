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

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.GUARDED_OPS
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.PermissionState
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Keeps a set of permissions ("app ops") ignored for given apps, for the
 * daemon's sandbox: an app whose ops are ignored is given no data, while its
 * permissions still show as granted. Ops can be kept in the foreground
 * instead, where the app gets the data only while it is in use. It also
 * keeps runtime permissions revoked, for what no op can do: the precise
 * location permission, whose loss gives an app the system's approximate
 * location. And it keeps from an app what the system hands its services
 * itself, past any op: the notifications, given to notification listeners,
 * and the screen, given to accessibility services
 * ([AppOpsGuardProtocol.SPECIAL_ACCESS]). These go by the permission the
 * system binds such services with, and are taken and given back as a
 * permission is ([SpecialAccess]).
 *
 * Not part of the app. The daemon runs it as root, outside any app process,
 * and keeps it running:
 *
 *     CLASSPATH=<this apk> app_process /system/bin \
 *         li.mercury.bigbrother.catalog.AppOpsGuard
 *
 * It reads one command per line and answers each, one line per item. What a
 * uid is held to (`<targets>`) is `<op>=<mode>` for each op, with the mode
 * ignored (1) or foreground (4), and `<permission>=revoked` for each runtime
 * permission, joined by commas:
 *
 * - `set <uid> <targets>`: prints `saved <uid> <values>`, the uid's current
 *   modes for the guarded ops and the states of the permissions in
 *   `targets`, then sets the ops listed to the modes given, revokes the
 *   permissions, and keeps them so.
 * - `apply <uid> <targets> <resets>`: for a guarded uid, sets what `resets`
 *   names (`<values>`, or `-`) back to the values given and leaves it to
 *   the system, then holds the uid to `targets` (or `-`) instead of what it
 *   was held to. Prints `applied <uid>`, followed by the states of the
 *   permissions it was not holding before, if any.
 * - `restore <uid> <values>`: sets what is named back, stops holding the
 *   uid and prints `restored <uid>`. Without values, the uid modes of every
 *   guarded op are removed: what there is to go back to when the daemon
 *   lost what `set` saved.
 * - `release <uid>`: undoes a `set` whose answer the daemon did not get:
 *   sets the modes and states saved here back and prints `restored <uid>`.
 * - `verify <uid> <targets>`: holds the uid to the targets and sets again
 *   each op that no longer has its mode, and each permission no longer
 *   revoked, printing `reverted <uid> <name>` for each, then `verified
 *   <uid>`. A uid not guarded yet is guarded from then on, so that the
 *   daemon can resume after this program was restarted without saving what
 *   it set itself. A permission found granted again, which only the user
 *   or the system does, is printed as `reverted <uid> <permission>=<state>`
 *   with the state it was found in: from then on the state to give back,
 *   here and in what the daemon keeps.
 * - `kind <uid> <request> <package>`: prints `library <uid> <request>` for
 *   a static shared library or SDK library, `app <uid> <request>` for
 *   another package of the uid, and `unknown <uid> <request>` if it cannot
 *   tell. This command reads from the package manager without changing
 *   the uid's permissions or its installation cookie.
 *
 * In `<values>` an op has a mode, the framework's number for it, or
 * `default` for an op that had no uid mode, which `restore` removes again.
 * `set` follows the mode of an op of a runtime permission with `/` and the
 * mode the system gives the op for the permission's grant at that time
 * (`android:read_contacts=1/1`: ignored, as the permission was not
 * granted). An op given back with one is set to the system's mode for the
 * grant as it is then if that is another, so that a permission granted or
 * revoked in the meantime takes effect; with the same, or without one, it
 * is set to the mode saved, which may be the user's own.
 *
 * A permission has a state, `granted` or `denied` followed by `+user_set`
 * and `+user_fixed` for the user's flags it had, or `default` where the
 * daemon does not know it: then the state saved here is put back, and
 * where there is none the permission only loses its `user_fixed` flag. A
 * special access is `granted` followed by `+service:<package>:<class>` for
 * each service that had it, or `denied`; with none known, it stays taken.
 *
 * Before the answer to `set`, `apply` and `verify`, and whenever a check
 * finds it changed, it prints `unheld <uid> <name>,...` (`-` for none):
 * what the uid is held to that the system did not keep even after it was
 * set again, ops at their mode and permissions revoked. Ops this device
 * does not have are not named.
 *
 * A command that fails is answered with `failed <uid>`, and the reason goes
 * to the error output. So is one the system took none of: a `set` after
 * which no op has its mode must not pass for one that worked, and a
 * permission the system still grants after it was revoked fails the
 * command. Op names are the public ones, such as `android:camera`, and
 * permissions go by their full names.
 *
 * The modes are the uid's, not its packages': the content providers check
 * the uid mode. The system sets uid modes itself whenever permissions
 * change, so the guarded uids are checked whenever the system reports a
 * change to a guarded op or to a permission, and every minute in case those
 * reports do not arrive. What is found changed then is printed as
 * `reverted` lines too.
 *
 * A permission is revoked for every package of the uid that requests it,
 * and marked as the user's choice not to be asked again
 * (`FLAG_PERMISSION_USER_FIXED`), as the system's own settings do when the
 * user turns precise location off for good. The system keeps one state for
 * the packages of a uid, and ends the uid's processes when it revokes a
 * permission they had.
 *
 * It ends when its input is closed: the daemon holds the other end.
 *
 * Everything used here is internal to the framework and reached by
 * reflection, so that a device where it differs fails a command instead of
 * failing to load. The system's own error messages name packages, so only
 * their types are reported.
 */
@SuppressLint("PrivateApi")
object AppOpsGuard {
    /** How often to look again for a system service not registered yet. */
    private const val SERVICE_POLL_MS = 1000L

    /** `PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES`. */
    private const val MATCH_STATIC_SHARED_AND_SDK_LIBRARIES = 0x04000000

    /** `PackageManager.MATCH_UNINSTALLED_PACKAGES`. */
    private const val MATCH_UNINSTALLED_PACKAGES = 0x00002000

    /** How often every guarded uid is checked without being asked. */
    private const val CHECK_EVERY_MS = 60_000L

    /**
     * How long a check prompted by a reported change waits, and the least
     * time between two such checks: the system reports each op separately,
     * so changes come in bursts, and this program's own changes are
     * reported too.
     */
    private const val SETTLE_MS = 1000L

    /** Held by each command and each check, so that they run one at a time and their output lines stay whole. */
    private val lock = Any()

    private val handler = AppOpsGuardHandler(Framework)

    private data class Installation(val pkg: String, val token: String) {
        fun matches(uid: Int): Boolean {
            val directory = "/data/user_de/${uid / 100000}/$pkg"
            val stat = android.system.Os.lstat(directory)
            return android.system.OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == uid &&
                android.system.Os.getxattr(directory, "trusted.big_brother.install").joinToString("") { "%02x".format(it) } == token
        }
    }

    private var journal: java.io.File? = null

    private fun restoreItem(name: String, change: () -> Unit) {
        val file = journal
        if (file != null && file.exists() && name in file.readLines()) return
        change()
        if (file != null) {
            java.io.FileOutputStream(file, true).use { out ->
                out.write("$name\n".toByteArray())
                out.fd.sync()
            }
        }
    }

    private val owners = mutableMapOf<Int, Installation>()

    private fun requireOwner(uid: Int) {
        check(owners[uid]?.matches(uid) == true) { "installation changed" }
    }

    private val changes = LinkedBlockingQueue<Unit>()

    @Volatile
    private var inputClosed = false

    private lateinit var service: Any

    /**
     * Methods are looked up on the public interface rather than on the
     * service's own class, a proxy class private to the framework.
     */
    private val serviceType by lazy { Class.forName("com.android.internal.app.IAppOpsService") }

    private lateinit var ops: List<GuardedOp>

    /** The runtime permissions, if this device lets them be reached. */
    private var permissions: Permissions? = null

    /** Why they cannot be, if they cannot. */
    private var permissionsMissing = ""

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 2 && args[0] == "--recover") journal = java.io.File(args[1])
        val lines = LinkedBlockingQueue<Line>()
        thread(isDaemon = true, name = "input") {
            try {
                System.`in`.bufferedReader().forEachLine { lines.put(Line.Text(it)) }
            } catch (e: Throwable) {
                System.err.println("reading input: $e")
            }
            inputClosed = true
            lines.put(Line.End)
        }
        try {
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
            thread(isDaemon = true, name = "check") { checkLoop() }
        } catch (e: Throwable) {
            System.err.println(describe(e))
            exitProcess(1)
        }
        while (true) {
            val line = lines.take() as? Line.Text ?: break
            if (line.text.startsWith("kind ")) {
                val query = AppOpsGuardProtocol.parseCommand(line.text) as? AppOpsGuardProtocol.Command.Kind ?: continue
                synchronized(lock) { answerKind(query) }
                continue
            }
            val parts = line.text.split(' ', limit = 4)
            if (parts.size != 4 || parts[0] != "bound" || !parts[1].matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) || !parts[2].matches(Regex("[0-9a-f]{32}"))) continue
            val owner = Installation(parts[1], parts[2])
            val command = AppOpsGuardProtocol.parseCommand(parts[3])
            if (command == null) {
                System.err.println("invalid helper command")
                continue
            }
            synchronized(lock) {
                try {
                    val current = try {
                        owner.matches(command.uid)
                    } catch (e: android.system.ErrnoException) {
                        if (e.errno == android.system.OsConstants.ENODATA ||
                            (e.errno == android.system.OsConstants.ENOENT && java.io.File("/data/user_de/${command.uid / 100000}").isDirectory && permissions().packagesOf(command.uid).none { it == owner.pkg })
                        ) {
                            false
                        } else {
                            throw e
                        }
                    }
                    if (!current && journal != null && command is AppOpsGuardProtocol.Command.Restore) {
                        emit("retired ${command.uid}")
                        return@synchronized
                    }
                    check(current) { "installation changed" }
                    if (owners.put(command.uid, owner) != owner) handler.forget(command.uid)
                    handler.handle(command)
                    if (!handler.isGuarded(command.uid)) owners.remove(command.uid)
                } catch (e: Throwable) {
                    System.err.println("${line.text.trim().substringBefore(' ')} ${command.uid}: ${describe(e)}")
                    emit(AppOpsGuardProtocol.failedLine(command.uid))
                }
            }
        }
        exitProcess(0)
    }

    private sealed interface Line {
        class Text(val text: String) : Line

        data object End : Line
    }

    /**
     * The system service [name], or `null` if the input closed first. The
     * daemon starts this program while the system is still booting, before
     * the services are registered, so it waits for them rather than fail.
     */
    private fun systemService(name: String): IBinder? {
        val getService = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
        var binder = getService.invoke(null, name) as IBinder?
        while (binder == null) {
            if (inputClosed) return null
            Thread.sleep(SERVICE_POLL_MS)
            binder = getService.invoke(null, name) as IBinder?
        }
        return binder
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

    private fun permissions(): Permissions = permissions ?: error("runtime permissions cannot be reached: $permissionsMissing")

    /** Reached when first needed: by then the system has started. */
    private var specialAccess: SpecialAccess? = null

    private fun specialAccess(): SpecialAccess = specialAccess ?: SpecialAccess(
        Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, systemService("package") ?: error("no package service"))!!,
    ).also { specialAccess = it }

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
     * Answer `kind`, apart from the commands, `unknown` where it cannot be
     * told: never taken for a command's answer.
     */
    private fun answerKind(command: AppOpsGuardProtocol.Command.Kind) {
        val library = try {
            isLibrary(command.packageName, command.uid)
        } catch (e: Throwable) {
            System.err.println("kind ${command.uid}: ${describe(e)}")
            null
        }
        emit(AppOpsGuardProtocol.kindLine(command.uid, command.request, library))
    }

    /**
     * Whether [packageName], one of [uid]'s packages, is a library rather
     * than an app, by the flags the package manager keeps for it.
     */
    private fun isLibrary(packageName: String, uid: Int): Boolean {
        val manager = packageManager()
        val managerType = Class.forName("android.content.pm.IPackageManager")

        // Libraries are left out of what the package manager answers unless
        // asked for.
        val flags = MATCH_STATIC_SHARED_AND_SDK_LIBRARIES or MATCH_UNINSTALLED_PACKAGES
        val userId = uid / PER_USER_RANGE
        // The flags are a long from Android 13 on.
        val int = Int::class.javaPrimitiveType!!
        val withLong = managerType.optionalMethod("getApplicationInfo", String::class.java, Long::class.javaPrimitiveType!!, int)
        val info = if (withLong != null) {
            withLong.invoke(manager, packageName, flags.toLong(), userId)
        } else {
            managerType.getMethod("getApplicationInfo", String::class.java, int, int).invoke(manager, packageName, flags, userId)
        } as ApplicationInfo? ?: error("no such package")
        check(info.uid == uid) { "not a package of the uid" }
        return info.isFlagged("isStaticSharedLibrary") || info.isFlagged("isSdkLibrary")
    }

    /** Whether [method], a flag this Android version may not have, is set. */
    private fun ApplicationInfo.isFlagged(method: String): Boolean = javaClass.optionalMethod(method)?.invoke(this) as Boolean? ?: false

    private fun packageManager(): Any {
        val binder = systemService("package") ?: error("no package service")
        return Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    /** The framework, as [AppOpsGuardHandler] is given it. */
    private object Framework : AppOpsSystem {
        override val ops: List<GuardedOp> get() = AppOpsGuard.ops

        override fun requireOwner(uid: Int) = AppOpsGuard.requireOwner(uid)

        override fun restoreItem(name: String, change: () -> Unit) = AppOpsGuard.restoreItem(name, change)

        override fun specialAccess(): Taking = AppOpsGuard.specialAccess()

        override fun resolve(name: String): GuardedOp? = AppOpsGuard.resolve(listOf(name)).firstOrNull()

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

        override val permissions: RuntimePermissions? get() = AppOpsGuard.permissions

        override val permissionsMissing: String get() = AppOpsGuard.permissionsMissing

        override fun emit(line: String) = AppOpsGuard.emit(line)

        override fun warn(message: String) = System.err.println(message)

        override fun describe(e: Throwable): String = AppOpsGuard.describe(e)
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

    /** Check every guarded uid after a reported change, and every minute. */
    private fun checkLoop() {
        while (true) {
            if (changes.poll(CHECK_EVERY_MS, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(SETTLE_MS)
                changes.clear()
            }
            synchronized(lock) { handler.checkAll() }
        }
    }

    private fun emit(line: String) {
        synchronized(lock) {
            println(line)
            System.out.flush()
        }
    }

    /**
     * The runtime permissions of apps, through the system's permission
     * service ([service], an `IPermissionManager`) and package manager
     * ([packages], an `IPackageManager`).
     *
     * The permission service's calls took a device in Android 14: an int
     * device id in QPR2, a persistent device id from QPR3 on, right before
     * the user. Each is called for the default device in whichever form this
     * version has ([DeviceCall]). The package manager's calls are the same
     * from Android 12 on, but for `getPackageInfo`, whose flags became a
     * long in Android 13.
     *
     * The permission service of Android 15 and later does not throw when it
     * refuses a change (a permission fixed by the system or by policy, an
     * app too old for runtime permissions): every change is looked at again
     * afterwards, and fails if it did not take.
     */
    private class Permissions(private val service: Any, private val packages: Any) : RuntimePermissions {
        private val permissionType = Class.forName("android.permission.IPermissionManager")
        private val packageType = Class.forName("android.content.pm.IPackageManager")

        private val getFlags = DeviceCall(permissionType, "getPermissionFlags", listOf(STRING, STRING), listOf(INT))
        private val updateFlags = DeviceCall(permissionType, "updatePermissionFlags", listOf(STRING, STRING, INT, INT, BOOLEAN), listOf(INT))
        private val grantCall = DeviceCall(permissionType, "grantRuntimePermission", listOf(STRING, STRING), listOf(INT))
        private val revokeCall = DeviceCall(permissionType, "revokeRuntimePermission", listOf(STRING, STRING), listOf(INT, STRING))
        private val packagesForUid = packageType.getMethod("getPackagesForUid", INT)
        private val checkUidPermission = packageType.getMethod("checkUidPermission", STRING, INT)
        private val packageInfo = packageType.optionalMethod("getPackageInfo", STRING, LONG, INT)
        private val packageInfoInt = if (packageInfo == null) packageType.getMethod("getPackageInfo", STRING, INT, INT) else null
        private val addListener = permissionType.getMethod("addOnPermissionsChangeListener", Class.forName("android.permission.IOnPermissionsChangeListener"))

        // Without these, ops are put back as saved, whatever the grants.
        private val permissionInfo = permissionType.optionalMethod("getPermissionInfo", STRING, STRING, INT)
        private val backgroundField = try {
            // Part of the system API, not of the SDK.
            PermissionInfo::class.java.getField("backgroundPermission")
        } catch (_: Throwable) {
            null
        }

        override fun runtime(permission: String): RuntimePermissions.Runtime? {
            val method = permissionInfo ?: error("no getPermissionInfo")
            val field = backgroundField ?: error("no backgroundPermission")
            // Asked for as the system, which is shown every permission.
            val info = method.call(service, permission, "android", 0) as PermissionInfo? ?: return null
            return if (info.protection == PermissionInfo.PROTECTION_DANGEROUS) RuntimePermissions.Runtime(field.get(info) as String?) else null
        }

        /** Have [watcher] called on every change of a permission. */
        fun watch(watcher: Binder) {
            val listener = Class.forName("android.permission.IOnPermissionsChangeListener\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, watcher)
            addListener.call(service, listener)
        }

        override fun grant(pkg: String, permission: String, uid: Int) {
            grantCall.call(service, listOf(pkg, permission), listOf(userOf(uid)))
        }

        override fun revoke(pkg: String, permission: String, uid: Int) {
            // The system's own reason, as when the user revokes it: the app
            // can read why it was ended.
            revokeCall.call(service, listOf(pkg, permission), listOf(userOf(uid), null))
        }

        /** The packages of [uid]. */
        fun packagesOf(uid: Int): List<String> = (packagesForUid.call(packages, uid) as Array<*>?).orEmpty().filterIsInstance<String>()

        override fun requesting(uid: Int, permission: String): List<String> {
            val names = packagesForUid.call(packages, uid) as Array<*>? ?: return emptyList()
            return names.filterIsInstance<String>().filter { name ->
                val info = if (packageInfo != null) {
                    packageInfo.call(packages, name, PackageManager.GET_PERMISSIONS.toLong(), userOf(uid))
                } else {
                    packageInfoInt!!.call(packages, name, PackageManager.GET_PERMISSIONS, userOf(uid))
                } as PackageInfo?
                info?.requestedPermissions?.contains(permission) == true
            }
        }

        override fun isGranted(uid: Int, permission: String): Boolean = checkUidPermission.call(packages, permission, uid) == PackageManager.PERMISSION_GRANTED

        override fun flags(pkg: String, permission: String, uid: Int): Int = getFlags.call(service, listOf(pkg, permission), listOf(userOf(uid))) as Int

        // Policy flags are never among them, so the check of the right to
        // change those is asked for.
        override fun setFlags(pkg: String, permission: String, uid: Int, mask: Int, values: Int) {
            updateFlags.call(service, listOf(pkg, permission, mask, values, true), listOf(userOf(uid)))
        }

        private fun userOf(uid: Int): Int = uid / PER_USER_RANGE
    }

    /**
     * The access to the notifications and to the screen the user gave an
     * app's services ([AppOpsGuardProtocol.SPECIAL_ACCESS]): notification
     * listeners through the notification service ([notifications], an
     * `INotificationManager`), as the system's settings grant it;
     * accessibility services through the setting the accessibility service
     * reads, changed with the `settings` command, as the system's settings
     * change it. A service is one or the other by the permission it is
     * bound with, which it declares; [packages] is an `IPackageManager`.
     *
     * Taken from every service of the uid that has it, and given back to
     * those that had it, and to no other: one never given the access must
     * not get it. Neither service reports to a process such as this one
     * when the user gives the access again: that is found by the checks
     * every minute. With no state known, which ones had it cannot be told,
     * and none gets it back.
     *
     * Most apps have no such service: for them only the package manager is
     * asked, and neither the notification service nor the setting is
     * reached, which then cannot fail them.
     */
    private class SpecialAccess(private val packages: Any) : Taking {
        private val packageType = Class.forName("android.content.pm.IPackageManager")
        private val packagesForUid = packageType.getMethod("getPackagesForUid", INT)
        private val packageInfo = packageType.optionalMethod("getPackageInfo", STRING, LONG, INT)
        private val packageInfoInt = if (packageInfo == null) packageType.getMethod("getPackageInfo", STRING, INT, INT) else null

        private val notificationType by lazy { Class.forName("android.app.INotificationManager") }
        private val notifications by lazy {
            Class.forName("android.app.INotificationManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, systemService("notification") ?: error("no notification service"))!!
        }
        private val listeners by lazy { notificationType.getMethod("getEnabledNotificationListeners", INT) }

        // Whether the user set it, from Android 12 on.
        private val grantListener by lazy { notificationType.optionalMethod("setNotificationListenerAccessGrantedForUser", ComponentName::class.java, INT, BOOLEAN, BOOLEAN) }
        private val grantListenerBefore by lazy { notificationType.getMethod("setNotificationListenerAccessGrantedForUser", ComponentName::class.java, INT, BOOLEAN) }

        override fun state(uid: Int, name: String): PermissionState? {
            val services = services(uid, name)
            if (services.isEmpty()) return null
            return PermissionState.ofServices(given(uid, name, services))
        }

        override fun hold(uid: Int, name: String): Boolean {
            val services = services(uid, name)
            if (services.isEmpty()) return false
            val given = given(uid, name, services)
            if (given.isEmpty()) return false
            set(uid, name, given, false)
            check(given(uid, name, services).isEmpty()) { "the system did not take $name away" }
            return true
        }

        override fun putBack(uid: Int, name: String, state: PermissionState?) {
            state ?: return
            val services = services(uid, name)
            if (services.isEmpty()) return
            // A service the app no longer has is given nothing.
            val wanted = state.services intersect services
            val given = given(uid, name, services)
            set(uid, name, wanted - given, true)
            set(uid, name, given - wanted, false)
            check(given(uid, name, services) == wanted) { "the system did not give $name back" }
        }

        /** The services of [uid]'s packages the system binds with [name], as `<package>/<class>`. */
        private fun services(uid: Int, name: String): Set<String> {
            val names = packagesForUid.call(packages, uid) as Array<*>? ?: return emptySet()
            return names.filterIsInstance<String>().flatMap { pkg ->
                val info = if (packageInfo != null) {
                    packageInfo.call(packages, pkg, PackageManager.GET_SERVICES.toLong(), userOf(uid))
                } else {
                    packageInfoInt!!.call(packages, pkg, PackageManager.GET_SERVICES, userOf(uid))
                } as PackageInfo?
                info?.services.orEmpty().filter { it.permission == name }.map { "${it.packageName}/${it.name}" }
            }.toSet()
        }

        /** Those of [services] that have [name] now. */
        private fun given(uid: Int, name: String, services: Set<String>): Set<String> = (if (name == NOTIFICATION_ACCESS) notificationListeners(uid) else accessibilityServices(uid)).toSet() intersect services

        private fun notificationListeners(uid: Int): List<String> = (listeners.call(notifications, userOf(uid)) as List<*>?).orEmpty()
            .filterIsInstance<ComponentName>()
            .map { it.flattenToString() }

        /** As the setting lists them, in its order, each in its long form. */
        private fun accessibilityServices(uid: Int): List<String> = settings("get", "--user", userOf(uid).toString(), "secure", ACCESSIBILITY_SERVICES)
            .takeUnless { it == "null" }.orEmpty()
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it)?.flattenToString() }

        /** Give [services] [name], or take it from them. */
        private fun set(uid: Int, name: String, services: Set<String>, given: Boolean) {
            if (services.isEmpty()) return
            requireOwner(uid)
            if (name == NOTIFICATION_ACCESS) {
                for (service in services) {
                    val component = ComponentName.unflattenFromString(service) ?: continue
                    val withUserSet = grantListener
                    if (withUserSet != null) {
                        withUserSet.call(notifications, component, userOf(uid), given, true)
                    } else {
                        grantListenerBefore.call(notifications, component, userOf(uid), given)
                    }
                }
                return
            }
            // The other services keep their place.
            val now = accessibilityServices(uid)
            val after = if (given) now + (services - now.toSet()) else now - services
            settings("put", "--user", userOf(uid).toString(), "secure", ACCESSIBILITY_SERVICES, after.joinToString(":"))
        }

        /** Run the `settings` command; what it printed. */
        private fun settings(vararg args: String): String {
            val process = ProcessBuilder(listOf(CMD, "settings") + args).redirectErrorStream(true).start()
            val printed = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "settings ${args.first()} failed" }
            return printed
        }

        private fun userOf(uid: Int): Int = uid / PER_USER_RANGE
    }

    /**
     * A method of the permission service called for the default device:
     * with the parameters [before], the device in whichever form this
     * version takes one, and [after].
     */
    private class DeviceCall(type: Class<*>, name: String, before: List<Class<*>>, after: List<Class<*>>) {
        private val method: Method
        private val device: List<Any>

        init {
            val forms: List<List<Pair<Class<*>, Any>>> = listOf(
                // A persistent device id, from Android 14 QPR3 on.
                listOf(STRING to PERSISTENT_DEVICE_ID_DEFAULT),
                // A device id, in Android 14 QPR2.
                listOf(INT to DEVICE_ID_DEFAULT),
                // None, before.
                emptyList(),
            )
            val (method, device) = forms.firstNotNullOfOrNull { form ->
                val types = before + form.map { (parameter, _) -> parameter } + after
                type.optionalMethod(name, *types.toTypedArray())?.let { found -> found to form.map { (_, value) -> value } }
            } ?: throw NoSuchMethodException(name)
            this.method = method
            this.device = device
        }

        fun call(target: Any, before: List<Any?>, after: List<Any?>): Any? = method.call(target, *(before + device + after).toTypedArray())
    }

    /** `UserHandle.PER_USER_RANGE`. */
    private const val PER_USER_RANGE = 100_000

    private const val NOTIFICATION_ACCESS = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"

    /** `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. */
    private const val ACCESSIBILITY_SERVICES = "enabled_accessibility_services"

    private const val CMD = "/system/bin/cmd"

    /** `Context.DEVICE_ID_DEFAULT`. */
    private const val DEVICE_ID_DEFAULT = 0

    /**
     * `VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT`: "default:" and
     * [DEVICE_ID_DEFAULT]. A constant of the framework, so it is the same in
     * every app that was built with it.
     */
    private const val PERSISTENT_DEVICE_ID_DEFAULT = "default:0"

    private val STRING = String::class.java
    private val INT = Int::class.javaPrimitiveType!!
    private val LONG = Long::class.javaPrimitiveType!!
    private val BOOLEAN = Boolean::class.javaPrimitiveType!!

    /**
     * Call the method, reporting what the system throws by its type only:
     * its messages name packages.
     */
    private fun Method.call(target: Any, vararg args: Any?): Any? = try {
        invoke(target, *args)
    } catch (e: InvocationTargetException) {
        throw IllegalStateException("$name: ${e.cause?.javaClass?.simpleName}")
    }

    /**
     * What [e] says without what the system put in it, which may name
     * packages: its kind, and the message only of this program's own. A
     * call made by reflection is told by the exception the system threw
     * (say, DeadObjectException when system_server has gone), not the
     * wrapper around it.
     */
    private fun describe(e: Throwable): String = when {
        e is InvocationTargetException -> (e.targetException ?: e).javaClass.simpleName
        e is IllegalStateException || e is NoSuchMethodException -> e.toString()
        else -> e.javaClass.simpleName
    }

    private fun Class<*>.optionalMethod(name: String, vararg types: Class<*>) = try {
        getMethod(name, *types)
    } catch (_: Throwable) {
        null
    }

    private fun java.lang.reflect.Method.invokeOrNull(vararg args: Any?): Any? = try {
        invoke(null, *args)
    } catch (_: Throwable) {
        null
    }

    private fun Any.call(method: String): Any? = javaClass.getMethod(method).invoke(this)
}
