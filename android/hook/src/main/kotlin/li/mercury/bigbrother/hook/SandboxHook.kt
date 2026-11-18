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
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

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

    fun install(classLoader: ClassLoader) {
        if (!StartGuard(File(GUARD_DIR)).allowStart()) {
            module.log(Log.WARN, TAG, "start guard tripped in system_server; sandbox hooks not installed")
            writeStatus(androidId = false, appList = false, listReadable = false)
            return
        }
        val list = SandboxList(File(LIST_PATH), ::onListError)
        val androidId = attach("ANDROID_ID") { hookAndroidId(list) }
        val appList = attach("app list") { hookAppList(classLoader, list) }
        writeStatus(androidId, appList, list.readable)
    }

    /** Runs one hook installation; a failure leaves the other hook unaffected. */
    private inline fun attach(name: String, install: () -> Boolean): Boolean = try {
        install().also { if (!it) module.log(Log.WARN, TAG, "sandbox $name hook found no target") }
    } catch (e: Throwable) {
        module.log(Log.ERROR, TAG, "could not install the sandbox $name hook", e)
        false
    }

    /**
     * `Settings.Secure.getString` reaches the settings provider through
     * `ContentProvider.Transport.call`, and a query of the secure table
     * through `query`; their overloads differ between releases, so every
     * method of either name is hooked. Both ways have to be covered for the
     * hook to count: one left open gives the real id away.
     */
    @SuppressLint("PrivateApi")
    private fun hookAndroidId(list: SandboxList): Boolean {
        val transport = Class.forName("android.content.ContentProvider\$Transport")
        var calls = 0
        var queries = 0
        for (method in HiddenApiBypass.getDeclaredMethods(transport).filterIsInstance<Method>()) {
            when (method.name) {
                "call" -> {
                    module.hook(method).intercept(AndroidIdHooker(module, list))
                    calls++
                }

                "query" -> {
                    module.hook(method).intercept(AndroidIdQueryHooker(module, list))
                    queries++
                }
            }
        }
        return calls > 0 && queries > 0
    }

    /**
     * The package visibility check lives in `AppsFilterBase` from Android 13
     * and in `AppsFilter` before. Only overloads whose arguments can be
     * located are hooked; the first class that yields one wins.
     */
    private fun hookAppList(classLoader: ClassLoader, list: SandboxList): Boolean {
        for (className in FILTER_CLASSES) {
            val filter = try {
                Class.forName(className, false, classLoader)
            } catch (_: ClassNotFoundException) {
                continue
            }
            var hooked = 0
            for (method in filter.declaredMethods) {
                if (method.name != "shouldFilterApplication") continue
                val types = method.parameterTypes
                val arguments = locateFilterArguments(types.map { it.name }) ?: continue
                val appId = findGetter(types[arguments.target], "getAppId", Int::class.javaPrimitiveType!!) ?: continue
                // Without it every package with an app's id would be hidden,
                // the system's own among them: rather no hook than that.
                val isSystem = findGetter(types[arguments.target], "isSystem", Boolean::class.javaPrimitiveType!!) ?: continue
                module.hook(method).intercept(AppListHooker(module, list, arguments, appId, isSystem))
                hooked++
            }
            if (hooked > 0) return true
        }
        return false
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

    private fun onListError(e: Throwable) {
        // The list is checked every second; one report is enough.
        if (listErrorLogged.compareAndSet(false, true)) {
            module.log(Log.ERROR, TAG, "could not read the sandbox list", e)
        }
    }

    /** Written by rename, so the daemon never reads half a file. */
    private fun writeStatus(androidId: Boolean, appList: Boolean, listReadable: Boolean) {
        try {
            val status = File(STATUS_PATH)
            val temporary = File("$STATUS_PATH.tmp")
            val bootId = File(BOOT_ID_PATH).readText().trim()
            temporary.writeText(sandboxStatusText(androidId, appList, Build.VERSION.SDK_INT, listReadable, bootId))
            if (!temporary.renameTo(status)) error("rename failed")
        } catch (e: Throwable) {
            module.log(Log.ERROR, TAG, "could not write the sandbox status", e)
        }
    }

    /**
     * Answers a sandboxed app's read of ANDROID_ID with its own id. Named
     * class, not a lambda: see `ProviderHook.RecordingHooker`.
     */
    private class AndroidIdHooker(
        module: XposedInterface,
        private val list: SandboxList,
    ) : FailSafeHooker(module, "ANDROID_ID") {
        override fun replacement(chain: XposedInterface.Chain): Any? {
            val args = chain.args
            if (!isAndroidIdRequest(args)) return null
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
    private class AndroidIdQueryHooker(
        private val module: XposedInterface,
        private val list: SandboxList,
    ) : XposedInterface.Hooker {
        private val failureLogged = AtomicBoolean(false)

        override fun intercept(chain: XposedInterface.Chain): Any? {
            // Every provider in system_server comes through here: the
            // authority is looked at first, and little else for the others.
            val replacement = guarded {
                val uri = chain.args.firstOrNull { it is Uri } as Uri?
                if (uri == null || !isSettingsAuthority(uri.authority)) return@guarded null
                val query = androidIdQuery(uri.pathSegments) ?: return@guarded null
                list.androidIdOf(Binder.getCallingUid())?.let { query to it }
            }
            val result = chain.proceed()
            if (replacement == null || result !is Cursor) return result
            val (query, androidId) = replacement
            return guarded { withAndroidId(result, query, androidId) } ?: result.also {
                // Left wherever the copying stopped.
                guarded { it.moveToPosition(-1) }
            }
        }

        /** A copy of [cursor] in which the ANDROID_ID row has [androidId] as its value. */
        private fun withAndroidId(cursor: Cursor, query: AndroidIdQuery, androidId: String): Cursor {
            val columns = cursor.columnNames
            val name = columns.indexOf("name")
            val value = columns.indexOf("value")
            // The caller did not ask for the values: there is none to replace.
            if (value < 0) return cursor
            val copy = MatrixCursor(columns, cursor.count)
            cursor.moveToPosition(-1)
            // Values of the whole table without their names: which of them
            // is the id cannot be told, so none is returned.
            while (!(query == AndroidIdQuery.TABLE && name < 0) && cursor.moveToNext()) {
                val row = arrayOfNulls<Any>(columns.size)
                for (column in columns.indices) row[column] = cursor.getString(column)
                if (query == AndroidIdQuery.ROW || row[name] == "android_id") row[value] = androidId
                copy.addRow(row)
            }
            copy.extras = cursor.extras
            cursor.close()
            return copy
        }

        private inline fun <T> guarded(block: () -> T): T? = try {
            block()
        } catch (e: Throwable) {
            if (failureLogged.compareAndSet(false, true)) {
                module.log(Log.ERROR, TAG, "sandbox ANDROID_ID query hook failed; passing results through", e)
            }
            null
        }
    }

    /**
     * Reports another installed app as filtered when a sandboxed app asks
     * about it. Named class, not a lambda: see `ProviderHook.RecordingHooker`.
     */
    private class AppListHooker(
        module: XposedInterface,
        private val list: SandboxList,
        private val arguments: FilterArguments,
        private val appIdGetter: Method,
        private val isSystemGetter: Method,
    ) : FailSafeHooker(module, "app list") {
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
     * Runs the original method unless [replacement] returns a value to
     * answer with instead. If [replacement] throws, the original runs as if
     * the hook were not there, and the first such failure is logged; the
     * original method's own exceptions pass through untouched.
     */
    private abstract class FailSafeHooker(
        private val module: XposedInterface,
        private val name: String,
    ) : XposedInterface.Hooker {
        private val failureLogged = AtomicBoolean(false)

        /** The value to return in place of the original call, or null to let it run. */
        abstract fun replacement(chain: XposedInterface.Chain): Any?

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val replaced = try {
                replacement(chain)
            } catch (e: Throwable) {
                if (failureLogged.compareAndSet(false, true)) {
                    module.log(Log.ERROR, TAG, "sandbox $name hook failed; passing calls through", e)
                }
                null
            }
            return replaced ?: chain.proceed()
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

        val FILTER_CLASSES = listOf(
            // Android 13 to 17.
            "com.android.server.pm.AppsFilterBase",
            // Android 12 and 12L.
            "com.android.server.pm.AppsFilter",
        )
    }
}
