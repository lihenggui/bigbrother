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
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed entry point, loaded into the system's content provider processes:
 * those that serve user files (the media provider, the external storage
 * documents provider, the download provider) and those that hold personal
 * data (contacts and the call log, messages, the calendar). See
 * `META-INF/xposed/scope.list`.
 *
 * An app that reads user files or personal data through one of these
 * providers never touches the file system itself: it asks the provider over
 * binder, and the provider queries its database or opens the file on the
 * app's behalf. This hook sits where those requests arrive and records which
 * app asked for what. The daemon decides what to keep of it.
 *
 * Hooking the providers, rather than the apps being observed, keeps the hook
 * out of those apps' processes entirely, and the caller identity comes from
 * binder, which an app cannot forge.
 *
 * The same entry point is loaded into system_server (scope `system`), where
 * it installs the sandbox's identity hooks instead; see [SandboxHook].
 */
class ProviderHook : XposedModule() {
    private val installed = AtomicBoolean(false)

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            SandboxHook(this).install(param.classLoader)
        } catch (_: Throwable) {
            log(Log.ERROR, TAG, "could not install the sandbox hooks")
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // None of the providers in scope runs as the system uid. Should the
        // framework report a package ready in system_server too, the provider
        // hooks stay out of it: the settings provider lives there.
        if (Process.myUid() == Process.SYSTEM_UID) return
        // One process can host several packages; the hooked class is shared.
        if (!installed.compareAndSet(false, true)) return
        try {
            // Device-protected storage is usable before the first unlock.
            val directory = File(param.applicationInfo.deviceProtectedDataDir, SPOOL_DIR)
            if (!StartGuard(directory).allowStart()) {
                log(Log.WARN, TAG, "provider start guard tripped")
                return
            }
            val spool = EventSpool(directory)
            val hooked = hookTransport(spool)
            log(Log.INFO, TAG, "hooked $hooked provider entry points")
        } catch (_: Throwable) {
            log(Log.ERROR, TAG, "could not install provider hooks")
        }
    }

    /**
     * `ContentProvider.Transport` is the binder object every provider exposes;
     * all cross-process calls arrive through it with the caller's identity
     * still attached. It is not public API, hence the reflection.
     */
    @SuppressLint("PrivateApi")
    private fun hookTransport(spool: EventSpool): Int {
        val transport = Class.forName("android.content.ContentProvider\$Transport")
        var hooked = 0
        val errorLogged = AtomicBoolean(false)
        val onFailure = {
            if (errorLogged.compareAndSet(false, true)) log(Log.WARN, TAG, "could not record a provider call")
        }
        for (method in HiddenApiBypass.getDeclaredMethods(transport).filterIsInstance<Method>()) {
            val hooker = when (method.name) {
                "query" -> RecordingHooker(onFailure) { uid, uri, _, result ->
                    spool.addQuery(uid, uri, rowCount(result))
                }

                "openFile", "openAssetFile" -> RecordingHooker(onFailure) { uid, uri, args, result ->
                    spool.addOpen(uid, uri, openMode(args), filePath(result))
                }

                // Takes a MIME type where the others take a mode; always read-only.
                "openTypedAssetFile" -> RecordingHooker(onFailure) { uid, uri, _, result ->
                    spool.addOpen(uid, uri, "r", filePath(result))
                }

                else -> continue
            }
            hook(method).intercept(hooker)
            hooked++
        }
        return hooked
    }

    /**
     * Lets the hooked method run, then hands its arguments and result to
     * [onReturn]. The call's outcome, including an exception, is passed on
     * untouched.
     *
     * This is a named class rather than a lambda on purpose. The Xposed API
     * is not available when the app is built, so R8 cannot see which method
     * of a lambda implements `Hooker.intercept`; it renames it, and the
     * framework then fails to call it. A declared class is covered by the
     * keep rule for this package (see `consumer-rules.pro`).
     */
    internal class RecordingHooker(
        private val onFailure: () -> Unit,
        private val onReturn: (uid: Int, uri: String, args: List<Any?>, result: Any?) -> Unit,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val uid = try {
                val caller = Binder.getCallingUid()
                if (isProviderCaller(caller, Process.myUid())) caller else -1
            } catch (_: Throwable) {
                reportFailure()
                -1
            }
            val result = chain.proceed()
            if (uid == -1) return result
            try {
                val args = chain.args
                val uri = args.firstNotNullOfOrNull { it as? Uri } ?: return result
                onReturn(uid, uri.toString(), args, result)
            } catch (_: Throwable) {
                reportFailure()
            }
            return result
        }

        private fun reportFailure() {
            // Logging itself must not affect the provider's result.
            runCatching { onFailure() }
        }
    }

    /** The file behind a returned descriptor, or "" if it isn't a regular file. */
    private fun filePath(result: Any?): String {
        val descriptor = when (result) {
            is ParcelFileDescriptor -> result
            is AssetFileDescriptor -> result.parcelFileDescriptor
            else -> return ""
        }
        return try {
            Os.readlink("/proc/self/fd/${descriptor.fd}").takeIf { it.startsWith('/') }.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private companion object {
        const val TAG = "BigBrother"
        const val SPOOL_DIR = "big_brother"
    }
}

internal fun isProviderCaller(uid: Int, providerUid: Int): Boolean = uid != providerUid && uid % PER_USER_RANGE >= Process.FIRST_APPLICATION_UID

/** The mode argument of `openFile(source, uri, mode, signal)`: the string after the URI. */
internal fun openMode(args: List<Any?>): String {
    val uriIndex = args.indexOfFirst { it is Uri }
    if (uriIndex < 0) return "r"
    for (index in uriIndex + 1 until args.size) {
        val mode = args[index] as? String
        if (mode != null) return mode
    }
    return "r"
}

/** The framework already counts query rows to send them across Binder. */
internal fun rowCount(result: Any?): Int = (result as? Cursor)?.count ?: 0
