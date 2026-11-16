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
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import org.lsposed.hiddenapibypass.HiddenApiBypass

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
 */
class ProviderHook : XposedModule() {
    private val installed = AtomicBoolean(false)

    override fun onPackageReady(param: PackageReadyParam) {
        // One process can host several packages; the hooked class is shared.
        if (!installed.compareAndSet(false, true)) return
        try {
            // Device-protected storage is usable before the first unlock.
            val directory = File(param.applicationInfo.deviceProtectedDataDir, SPOOL_DIR)
            if (!StartGuard(directory).allowStart()) {
                log(Log.WARN, TAG, "start guard tripped in ${param.packageName}; hooks not installed")
                return
            }
            val spool = EventSpool(directory)
            val hooked = hookTransport(spool)
            log(Log.INFO, TAG, "hooked $hooked provider entry points in ${param.packageName}")
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "could not install hooks in ${param.packageName}", e)
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
        for (method in HiddenApiBypass.getDeclaredMethods(transport).filterIsInstance<Method>()) {
            val hooker = when (method.name) {
                "query" -> RecordingHooker { args, result ->
                    record(args) { uid, uri -> spool.addQuery(uid, uri, rowCount(result)) }
                }
                "openFile", "openAssetFile" -> RecordingHooker { args, result ->
                    record(args) { uid, uri ->
                        spool.addOpen(uid, uri, openMode(args), filePath(result))
                    }
                }
                // Takes a MIME type where the others take a mode; always read-only.
                "openTypedAssetFile" -> RecordingHooker { args, result ->
                    record(args) { uid, uri -> spool.addOpen(uid, uri, "r", filePath(result)) }
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
    private class RecordingHooker(
        private val onReturn: (args: List<Any?>, result: Any?) -> Unit,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            onReturn(chain.args, result)
            return result
        }
    }

    /**
     * Runs [write] with the caller's uid and the request's URI, if the call
     * came from an app. Never throws: a failure here must not break the
     * provider call that is being observed.
     */
    private inline fun record(args: List<Any?>, write: (uid: Int, uri: String) -> Unit) {
        try {
            val uid = Binder.getCallingUid()
            // Skip the provider calling itself and calls from system components.
            if (uid == Process.myUid() || uid % PER_USER_RANGE < Process.FIRST_APPLICATION_UID) return
            val uri = args.firstNotNullOfOrNull { it as? Uri } ?: return
            write(uid, uri.toString())
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "could not record a provider call", e)
        }
    }

    /** The mode argument of `openFile(source, uri, mode, signal)`: the string after the URI. */
    private fun openMode(args: List<Any?>): String {
        val uriIndex = args.indexOfFirst { it is Uri }
        return args.drop(uriIndex + 1).firstNotNullOfOrNull { it as? String } ?: "r"
    }

    /**
     * Rows in a query result. The framework counts them itself right after
     * this method returns, to send the result across binder, so asking here
     * adds no work.
     */
    private fun rowCount(result: Any?): Int = (result as? Cursor)?.count ?: 0

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

        /** Android multi-user layout: uid = user * 100000 + app id. */
        const val PER_USER_RANGE = 100_000
    }
}
