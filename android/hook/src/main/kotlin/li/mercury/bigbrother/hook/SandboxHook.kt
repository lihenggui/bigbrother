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

package li.mercury.bigbrother.hook

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The identity part of the app sandbox, installed in system_server.
 *
 * Two things an app learns about the device are answered by system_server on
 * the app's behalf, so they can be changed there for sandboxed apps alone,
 * by the calling uid that binder reports and an app cannot forge:
 *
 * - `Settings.Secure.ANDROID_ID`, served by the settings provider, which runs
 *   in system_server. A sandboxed app gets the id the daemon chose for it.
 * - Which installed packages are visible, decided by the package manager's
 *   `shouldFilterApplication`. A sandboxed app sees the system's packages and
 *   its own, and no other installed app.
 * - Which files the app was granted through the system, decided by
 *   `UriPermission.getStrength` as a provider asks whether the app holds a
 *   grant. The providers that open such files run outside the sandbox and
 *   reach the real ones; an app whose storage is isolated is held to the
 *   grants of the moment, made as the user picks a file, and not to those
 *   it kept from before.
 * - Whether the app holds the advertising ID permission, which Google Play
 *   services asks the permission manager before it gives the ID. An app
 *   with an Android ID of its own is said not to, and gets an ID of zeros.
 * - Which services and providers the app reaches. One with an Android ID
 *   of its own finds none of those that give advertising and device
 *   identifiers, as on a device without them: Android's own advertising
 *   ID, the vendors' (OAID), and Play services' for an app it gives the ID
 *   to without asking for the permission.
 *
 * The daemon lists the sandboxed uids in [LIST_PATH] and reads [STATUS_PATH]
 * to learn which hooks attached. Nothing else is shared with it.
 *
 * Any failure here is a failure of system_server, which reboots the device;
 * a repeated one is a boot loop. Hence the start guard, and every hooker
 * falls back to the original method whenever its own work throws.
 */
internal class SandboxHook(private val module: XposedInterface) {
    private val listErrorLogged = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "BigBrother sandbox") }
    private val file = File(LIST_PATH)
    private val list = SandboxList(file, ::onListError, HookInstallations(File(BOOT_ID_PATH))::replace)
    private val watcher = SandboxListWatcher(file.toPath(), list, worker, ::onListReloaded, ::onWatchLost)
    private val androidId = Health(::onDegraded)
    private val appList = Health(::onDegraded)
    private val uriGrants = Health(::onDegraded)
    private val adId = Health(::onDegraded)

    // Accessed only by the worker, including status writes prompted by hook failures.
    private var waitingForInitialLoad = true

    fun install(classLoader: ClassLoader) {
        if (!StartGuard(File(GUARD_DIR)).allowStart()) {
            log(Log.WARN, "start guard tripped in system_server; sandbox hooks not installed")
            worker.execute {
                for (health in listOf(androidId, appList, uriGrants, adId)) health.degrade()
                waitingForInitialLoad = false
                writeStatus()
            }
            return
        }
        attach("ANDROID_ID", androidId, ::hookAndroidId)
        attach("app list", appList) { hookAppList(classLoader) }
        attach("file grants", uriGrants) { hookUriGrants(classLoader) }
        attach("advertising ID", adId) { hookAdId(classLoader) }
        worker.execute {
            try {
                Thread({ watcher.watch() }, "BigBrother sandbox events").start()
            } catch (_: Throwable) {
                onWatchLost()
            }
        }
    }

    private fun onWatchLost() {
        if (waitingForInitialLoad) {
            list.reload()
            waitingForInitialLoad = false
        }
        onListError()
        writeStatus()
    }

    private fun onListReloaded(previous: SandboxList.Snapshot) {
        waitingForInitialLoad = false
        val revision = list.snapshot.revision
        if (adId.active && (revision != permissionRevision || (revision != "none" && !previous.readable))) {
            forgetPermissionAnswers()
            permissionRevision = revision
        }
        writeStatus()
    }

    private var permissionRevision = "none"

    /** Partially installed hooks keep passing through if an attachment fails. */
    private inline fun attach(name: String, health: Health, install: () -> Boolean) {
        try {
            if (install()) return
            log(Log.WARN, "sandbox $name hook found no target")
        } catch (_: Throwable) {
            log(Log.ERROR, "could not install the sandbox $name hook")
        }
        health.markIncompatible()
    }

    @SuppressLint("PrivateApi")
    private fun hookAndroidId(): Boolean {
        val transport = Class.forName("android.content.ContentProvider\$Transport")
        val methods = HiddenApiBypass.getDeclaredMethods(transport).filterIsInstance<Method>()
        val calls = methods.targets("call", ::isSettingsCall) ?: return false
        val queries = methods.targets("query", ::isSettingsQuery) ?: return false
        for (method in calls) module.hook(method).intercept(AndroidIdHooker(androidId, list))
        for (method in queries) module.hook(method).intercept(AndroidIdQueryHooker(androidId, list))
        return true
    }

    private fun hookAppList(classLoader: ClassLoader): Boolean {
        val methods = FILTER_CLASSES.flatMap { className ->
            val filter = try {
                Class.forName(className, false, classLoader)
            } catch (_: ClassNotFoundException) {
                return@flatMap emptyList()
            }
            filter.declaredMethods.filter { it.name == "shouldFilterApplication" && !Modifier.isAbstract(it.modifiers) }
        }
        if (methods.isEmpty()) return false
        val hooks = methods.map { method ->
            if (Modifier.isStatic(method.modifiers)) return false
            val arguments = locateFilterArguments(method.signature()) ?: return false
            val target = method.parameterTypes[arguments.target]
            val appId = findGetter(target, "getAppId", Int::class.javaPrimitiveType!!) ?: return false
            val isSystem = findGetter(target, "isSystem", Boolean::class.javaPrimitiveType!!) ?: return false
            method to AppListHooker(appList, list, arguments, appId, isSystem)
        }
        for ((method, hooker) in hooks) module.hook(method).intercept(hooker)
        return true
    }

    @SuppressLint("PrivateApi")
    private fun hookUriGrants(classLoader: ClassLoader): Boolean {
        val grant = Class.forName(URI_PERMISSION_CLASS, false, classLoader)
        val method = grant.declaredMethods.asList().targets("getStrength") { it.matches("int", "int") }?.singleOrNull() ?: return false
        val field = { name: String ->
            grant.getDeclaredField(name).apply {
                check(type == Int::class.javaPrimitiveType && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
        }
        val hooker = UriGrantHooker(
            uriGrants,
            list,
            targetUid = field("targetUid"),
            ownedModeFlags = field("ownedModeFlags"),
            globalModeFlags = field("globalModeFlags"),
        )
        module.hook(method).intercept(hooker)
        return true
    }

    @SuppressLint("PrivateApi")
    private fun hookAdId(classLoader: ClassLoader): Boolean {
        val service = Class.forName(PERMISSION_MANAGER_CLASS, false, classLoader)
        val byPackage = service.declaredMethods.asList().targets("checkPermission") { locatePackagePermission(it) != null } ?: return false
        val byUid = service.declaredMethods.asList().targets("checkUidPermission") { locateUidPermission(it) != null } ?: return false
        val binds = Class.forName(ACTIVE_SERVICES_CLASS, false, classLoader)
            .declaredMethods.asList().targets("bindServiceLocked", ::isIdentifierBind) ?: return false
        val providers = Class.forName(PROVIDER_HELPER_CLASS, false, classLoader)
            .declaredMethods.asList().targets("getContentProvider", ::isIdentifierProvider) ?: return false
        val packageManagerClass = Class.forName("android.content.pm.IPackageManager")
        val packageUid = packageLookup(packageManagerClass, "getPackageUid", "int") ?: return false
        val applicationInfo = packageLookup(packageManagerClass, "getApplicationInfo", "android.content.pm.ApplicationInfo") ?: return false
        val packagesForUid = packageManagerClass.getMethod("getPackagesForUid", Int::class.javaPrimitiveType)
        val packageManager = Class.forName("android.app.AppGlobals").getMethod("getPackageManager")
        if (packagesForUid.returnType != Array<String>::class.java || packageManager.returnType != packageManagerClass ||
            !Modifier.isStatic(packageManager.modifiers)
        ) {
            return false
        }
        val targets = TargetSdks(packageManager, packagesForUid, applicationInfo)
        for (method in byPackage) {
            val args = checkNotNull(locatePackagePermission(method.signature()))
            module.hook(method).intercept(AdIdPackageHooker(adId, list, args, packageManager, packageUid))
        }
        for (method in byUid) {
            val args = checkNotNull(locateUidPermission(method.signature()))
            module.hook(method).intercept(AdIdUidHooker(adId, list, args))
        }
        for (method in binds) module.hook(method).intercept(IdServiceHooker(adId, list, targets))
        for (method in providers) module.hook(method).intercept(IdProviderHooker(adId, list))
        return true
    }

    private fun packageLookup(type: Class<*>, name: String, returns: String): Method? = type.methods.firstOrNull { method ->
        if (method.name != name) return@firstOrNull false
        val signature = method.signature()
        signature.matches(returns, "java.lang.String", "long", "int") || signature.matches(returns, "java.lang.String", "int", "int")
    }

    /** A recognized overload cannot establish coverage of a different one. */
    private fun List<Method>.targets(name: String, supported: (HookSignature) -> Boolean): List<Method>? = filter { it.name == name && !Modifier.isStatic(it.modifiers) }.takeIf { methods ->
        methods.isNotEmpty() && methods.all { supported(it.signature()) }
    }

    private fun Method.signature() = HookSignature(returnType.typeName, parameterTypes.map { it.typeName })

    /**
     * Has every process forget the answers to permission checks it keeps
     * (`PermissionManager`'s caches, keyed by the package information), as
     * the system does when a permission changes. Google Play services would
     * otherwise go on with the answer it got before the app was isolated or
     * released.
     */
    @SuppressLint("PrivateApi")
    private fun forgetPermissionAnswers() {
        try {
            HiddenApiBypass.invoke(Class.forName("android.content.pm.PackageManager"), null, "invalidatePackageInfoCache")
        } catch (_: Throwable) {
            adId.degrade("could not have the permission answers forgotten")
        }
    }

    /**
     * A getter of the declared target type: an interface on Android 13 and
     * later, a class whose getters may be inherited before.
     */
    private fun findGetter(type: Class<*>, name: String, returns: Class<*>): Method? {
        try {
            return type.getMethod(name).takeIf { it.returnType == returns }
        } catch (_: NoSuchMethodException) {
        }
        return generateSequence(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == 0 && it.returnType == returns }
            ?.apply { isAccessible = true }
    }

    private fun onListError() {
        if (listErrorLogged.compareAndSet(false, true)) {
            log(Log.ERROR, "could not read the sandbox list")
        }
    }

    /** Written by the worker by rename, so the daemon never reads half a file. */
    private fun writeStatus() {
        if (waitingForInitialLoad) return
        try {
            val status = File(STATUS_PATH)
            val temporary = File("$STATUS_PATH.tmp")
            val bootId = File(BOOT_ID_PATH).readText().trim()
            val snapshot = list.snapshot
            val ready = snapshot.readable && watcher.current
            val health = sandboxStatusText(
                androidId.state.withListReady(ready),
                appList.state.withListReady(ready),
                uriGrants.state.withListReady(ready),
                adId.state.withListReady(ready),
                Build.VERSION.SDK_INT,
                ready,
                bootId,
            )
            temporary.writeText(health + "revision=${snapshot.revision}\npid=${android.os.Process.myPid()}\n")
            if (!temporary.renameTo(status)) error("rename failed")
        } catch (_: Throwable) {
            log(Log.ERROR, "could not write the sandbox status")
        }
    }

    private fun onDegraded(message: String) {
        // Even the error path on a Binder thread must leave status I/O to the worker.
        worker.execute {
            log(Log.ERROR, message)
            writeStatus()
        }
    }

    private fun log(priority: Int, message: String) {
        // Diagnostics must not cause an uncaught exception in system_server.
        runCatching { module.log(priority, TAG, message) }
    }

    internal class Health(private val onDegraded: (String) -> Unit) {
        private val current = AtomicReference(HookState.WORKING)
        val state: HookState get() = current.get()
        val active: Boolean get() = state == HookState.WORKING

        fun markIncompatible() {
            current.set(HookState.INCOMPATIBLE)
        }

        fun degrade(message: String? = null) {
            if (current.compareAndSet(HookState.WORKING, HookState.DEGRADED) && message != null) {
                // A failed diagnostic must never keep a hook from calling the original method.
                runCatching { onDegraded(message) }
            }
        }
    }

    /**
     * Answers a sandboxed app's read of ANDROID_ID with its own id. Named
     * class, not a lambda: see `ProviderHook.RecordingHooker`.
     */
    private class AndroidIdHooker(
        health: Health,
        private val list: SandboxList,
    ) : FailSafeHooker(health, "ANDROID_ID") {
        override fun replacement(chain: XposedInterface.Chain): Any? {
            if (!isAndroidIdRequest(chain.getArg(1) as String?, chain.getArg(2) as String?, chain.getArg(3) as String?)) return null
            val androidId = list.androidIdOf(Binder.getCallingUid()) ?: return null
            return Bundle().apply { putString("value", androidId) }
        }
    }

    /**
     * Puts a sandboxed app's own id in what a query of the secure settings
     * returns to it. Unlike the others this works on the result, so it does
     * not go through [FailSafeHooker]: the query always runs, and its cursor
     * is returned as it is whenever anything here fails. Named class, not a
     * lambda: see `ProviderHook.RecordingHooker`.
     */
    internal class AndroidIdQueryHooker(
        private val health: Health,
        private val list: SandboxList,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!health.active) return chain.proceed()
            // Every provider in system_server comes through here: the
            // authority is looked at first, and little else for the others.
            val replacement = guarded {
                val uri = chain.getArg(1) as Uri?
                if (uri == null || !isSettingsAuthority(uri.authority)) return@guarded null
                val query = androidIdQuery(uri.pathSegments) ?: return@guarded null
                list.androidIdOf(Binder.getCallingUid())?.let { query to it }
            }
            val result = chain.proceed()
            if (replacement == null || result !is Cursor) return result
            val (query, androidId) = replacement
            return guarded {
                val position = result.position
                try {
                    withAndroidId(result, query, androidId)
                } catch (e: Throwable) {
                    result.moveToPosition(position)
                    throw e
                }
            } ?: result
        }

        /** A copy of [cursor] in which the ANDROID_ID row has [androidId] as its value. */
        internal fun withAndroidId(cursor: Cursor, query: AndroidIdQuery, androidId: String): Cursor {
            val columns = cursor.columnNames
            val name = columns.indexOf("name")
            val value = columns.indexOf("value")
            // The caller did not ask for the values: there is none to replace.
            if (value < 0) return cursor
            val copy = object : MatrixCursor(columns, cursor.count) {
                override fun close() {
                    try {
                        cursor.close()
                    } finally {
                        super.close()
                    }
                }
            }
            cursor.moveToPosition(-1)
            // Values of the whole table without their names: which of them
            // is the id cannot be told, so none is returned.
            while (!(query == AndroidIdQuery.TABLE && name < 0) && cursor.moveToNext()) {
                val rowName = if (query == AndroidIdQuery.TABLE) cursor.readValue(name) else null
                val replaceValue = query == AndroidIdQuery.ROW || rowName == "android_id"
                copy.addRow(
                    Array(columns.size) { column ->
                        when {
                            column == value && replaceValue -> androidId
                            column == name && query == AndroidIdQuery.TABLE -> rowName
                            else -> cursor.readValue(column)
                        }
                    },
                )
            }
            copy.extras = cursor.extras
            return copy
        }

        private fun Cursor.readValue(column: Int): Any? = when (getType(column)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(column)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(column)
            Cursor.FIELD_TYPE_STRING -> getString(column)
            Cursor.FIELD_TYPE_BLOB -> getBlob(column)
            else -> error("unknown cursor field type")
        }

        private inline fun <T> guarded(block: () -> T): T? = try {
            block()
        } catch (_: Throwable) {
            health.degrade("sandbox ANDROID_ID query hook failed; passing results through")
            null
        }
    }

    /**
     * Reports another installed app as filtered when a sandboxed app asks
     * about it. Named class, not a lambda: see `ProviderHook.RecordingHooker`.
     */
    private class AppListHooker(
        health: Health,
        private val list: SandboxList,
        private val arguments: FilterArguments,
        private val appIdGetter: Method,
        private val isSystemGetter: Method,
    ) : FailSafeHooker(health, "app list") {
        override fun replacement(chain: XposedInterface.Chain): Any? {
            val callingUid = chain.getArg(arguments.callingUid) as Int
            if (!list.hidesAppsFrom(callingUid)) return null
            val target = chain.getArg(arguments.target) ?: return null
            val targetAppId = appIdGetter.invoke(target) as Int
            val targetIsSystem = isSystemGetter.invoke(target) as Boolean
            return if (hideFromSandboxed(callingUid, targetAppId, targetIsSystem)) true else null
        }
    }

    /**
     * Answers how strong a grant to a file is, for an app whose storage is
     * isolated, from the grants of the moment alone. Named class, not a
     * lambda: see `ProviderHook.RecordingHooker`.
     */
    private class UriGrantHooker(
        health: Health,
        private val list: SandboxList,
        private val targetUid: Field,
        private val ownedModeFlags: Field,
        private val globalModeFlags: Field,
    ) : FailSafeHooker(health, "file grants") {
        override fun replacement(chain: XposedInterface.Chain): Any? {
            val grant = chain.thisObject ?: return null
            if (!list.isolatesStorageOf(targetUid.getInt(grant))) return null
            return strengthOfTheMoment(
                chain.getArg(0) as Int,
                ownedModeFlags.getInt(grant),
                globalModeFlags.getInt(grant),
            )
        }
    }

    /**
     * Says that a package whose uid gets no advertising ID does not hold
     * [AD_ID_PERMISSION]. Named class, not a lambda: see
     * `ProviderHook.RecordingHooker`.
     */
    private class AdIdPackageHooker(
        health: Health,
        private val list: SandboxList,
        private val arguments: PermissionArguments,
        private val packageManager: Method,
        private val packageUid: Method,
    ) : FailSafeHooker(health, "advertising ID") {
        private val flags: Any = if (packageUid.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0

        override fun replacement(chain: XposedInterface.Chain): Any? {
            if (chain.getArg(arguments.permission) != AD_ID_PERMISSION) return null
            val packageName = chain.getArg(arguments.subject) as String? ?: return null
            val userId = chain.getArg(arguments.user) as Int
            // As system_server, not as the caller: what the caller may see
            // is beside the point, and the caller is Play services.
            val identity = Binder.clearCallingIdentity()
            val uid = try {
                packageUid.invoke(packageManager.invoke(null), packageName, flags, userId) as Int
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
            return if (uid >= 0 && list.withholdsAdIdFrom(uid)) PackageManager.PERMISSION_DENIED else null
        }
    }

    /**
     * The releases the packages of a uid target, as system_server sees
     * them. `IPackageManager.getApplicationInfo(String, flags, userId)`
     * takes its flags as a `long` from Android 13 and an `int` before.
     */
    private class TargetSdks(
        private val packageManager: Method,
        private val packagesForUid: Method,
        private val applicationInfo: Method,
    ) {
        private val flags: Any = if (applicationInfo.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0

        fun of(uid: Int): List<Int> {
            val identity = Binder.clearCallingIdentity()
            try {
                val manager = packageManager.invoke(null)
                val packages = packagesForUid.invoke(manager, uid) as Array<*>? ?: return emptyList()
                return packages.mapNotNull { name ->
                    (applicationInfo.invoke(manager, name, flags, uid / PER_USER_RANGE) as ApplicationInfo?)?.targetSdkVersion
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    /**
     * Has a bind of an identifier service by an app with an Android ID of
     * its own fail as if there were no such service: `bindService` returns
     * false, which the identifier SDKs take for a device without it. Not
     * an error of the system's, which would throw in the app. Named class,
     * not a lambda: see `ProviderHook.RecordingHooker`.
     */
    private class IdServiceHooker(
        health: Health,
        private val list: SandboxList,
        private val targets: TargetSdks,
    ) : FailSafeHooker(health, "identifier services") {
        override fun replacement(chain: XposedInterface.Chain): Any? {
            val intent = chain.getArg(2) as Intent? ?: return null
            val component = intent.component
            val kind = idServiceBound(component?.packageName ?: intent.`package`, component?.className, intent.action) ?: return null
            // The app's own: the activity manager reads it the same way.
            val uid = Binder.getCallingUid()
            if (!list.withholdsAdIdFrom(uid)) return null
            if (kind == IdServiceKind.PLAY_SERVICES && !playServicesGivesIdUnasked(targets.of(uid))) return null
            return NOT_BOUND
        }
    }

    /**
     * Gives an app with an Android ID of its own no provider of a device
     * identifier, as if there were none: what it asks of it returns
     * nothing. Named class, not a lambda: see
     * `ProviderHook.RecordingHooker`.
     */
    private class IdProviderHooker(
        health: Health,
        private val list: SandboxList,
    ) : FailSafeHooker(health, "identifier providers") {
        override fun replacement(chain: XposedInterface.Chain): Any? {
            val name = chain.getArg(2) as String? ?: return null
            if (!isIdProvider(name) || !list.withholdsAdIdFrom(Binder.getCallingUid())) return null
            return NULL_ANSWER
        }
    }

    /** As [AdIdPackageHooker], for a check by uid. */
    private class AdIdUidHooker(
        health: Health,
        private val list: SandboxList,
        private val arguments: PermissionArguments,
    ) : FailSafeHooker(health, "advertising ID") {
        override fun replacement(chain: XposedInterface.Chain): Any? {
            if (chain.getArg(arguments.permission) != AD_ID_PERMISSION) return null
            val uid = chain.getArg(arguments.subject) as Int
            return if (list.withholdsAdIdFrom(uid)) PackageManager.PERMISSION_DENIED else null
        }
    }

    /**
     * Runs the original method unless [replacement] returns a value to
     * answer with instead. If [replacement] throws, the original runs as if
     * the hook were not there, and the first such failure is logged; the
     * original method's own exceptions pass through untouched.
     */
    internal abstract class FailSafeHooker(
        private val health: Health,
        private val name: String,
    ) : XposedInterface.Hooker {

        /**
         * The value to return in place of the original call, or null to let
         * it run; [NULL_ANSWER] to return null.
         */
        abstract fun replacement(chain: XposedInterface.Chain): Any?

        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!health.active) return chain.proceed()
            val replaced = try {
                replacement(chain)
            } catch (_: Throwable) {
                health.degrade("sandbox $name hook failed; passing calls through")
                null
            }
            return when {
                replaced === NULL_ANSWER -> null
                replaced != null -> replaced
                else -> chain.proceed()
            }
        }
    }

    private companion object {
        const val TAG = "BigBrother"

        const val LIST_PATH = "/data/system/big_brother_sandbox"
        const val STATUS_PATH = "/data/system/big_brother_sandbox.status"

        /** A different value at every boot. */
        const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"

        /** Start records for [StartGuard]; system_server has no app data directory. */
        const val GUARD_DIR = "/data/system/big_brother_hook"

        const val PERMISSION_MANAGER_CLASS = "com.android.server.pm.permission.PermissionManagerService"
        const val ACTIVE_SERVICES_CLASS = "com.android.server.am.ActiveServices"

        /** Android 12 and later. */
        const val PROVIDER_HELPER_CLASS = "com.android.server.am.ContentProviderHelper"

        /** What `bindServiceLocked` returns for a service not found; `bindService` then returns false. */
        const val NOT_BOUND = 0

        /** A [FailSafeHooker.replacement] that has the hooked method return null. */
        val NULL_ANSWER = Any()

        /** Android 11 and later. */
        const val URI_PERMISSION_CLASS = "com.android.server.uri.UriPermission"

        val FILTER_CLASSES = listOf(
            // Android 13 to 17.
            "com.android.server.pm.AppsFilterBase",
            "com.android.server.pm.AppsFilterImpl",
            "com.android.server.pm.AppsFilterSnapshotImpl",
            // Android 12 and 12L.
            "com.android.server.pm.AppsFilter",
        )
    }
}
