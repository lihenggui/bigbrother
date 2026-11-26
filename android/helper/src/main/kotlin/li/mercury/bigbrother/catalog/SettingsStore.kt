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
import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal interface SettingsStore {
    fun getSecure(userId: Int, key: String): String?
    fun putSecure(userId: Int, key: String, value: String)
}

private const val SETTINGS_AUTHORITY = "settings"
private const val USER_KEY = "_user"
private const val VALUE_KEY = "value"

internal class BinderSettingsStore(private val client: BinderSettingsClient = FrameworkSettingsClient) : SettingsStore {
    override fun getSecure(userId: Int, key: String): String? = call(userId, "GET_secure", key, mapOf(USER_KEY to userId))

    override fun putSecure(userId: Int, key: String, value: String) {
        call(userId, "PUT_secure", key, mapOf(USER_KEY to userId, VALUE_KEY to value))
    }

    private fun call(userId: Int, method: String, key: String, extras: Map<String, Any>): String? {
        val token = client.newToken()
        val holder = checkNotNull(client.acquire(SETTINGS_AUTHORITY, userId, token)) { "no settings provider" }
        try {
            return client.call(holder, SETTINGS_AUTHORITY, method, key, extras)
        } finally {
            client.release(SETTINGS_AUTHORITY, userId, token)
        }
    }
}

/** The framework calls and conversion of the SettingsProvider arguments to a Bundle. */
internal interface BinderSettingsClient {
    fun newToken(): Any
    fun acquire(authority: String, userId: Int, token: Any): Any?
    fun call(holder: Any, authority: String, method: String, key: String, extras: Map<String, Any>): String?
    fun release(authority: String, userId: Int, token: Any)
}

@SuppressLint("PrivateApi")
private object FrameworkSettingsClient : BinderSettingsClient {
    private val manager by lazy {
        checkNotNull(Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)) { "no activity service" }
    }
    private val methods by lazy { ProviderMethods() }
    private val attribution by lazy {
        // SettingsService also attributes requests by UID 0 to "root".
        AttributionSource.Builder(Process.myUid()).setPackageName("root").build()
    }

    override fun newToken(): Any = Binder()

    override fun acquire(authority: String, userId: Int, token: Any): Any? = methods.acquire.call(manager, authority, userId, token, authority)

    override fun call(holder: Any, authority: String, method: String, key: String, extras: Map<String, Any>): String? {
        val provider = checkNotNull(methods.provider.get(holder)) { "no settings provider interface" }
        val bundle = Bundle(extras.size)
        for ((name, value) in extras) {
            when (value) {
                is Int -> bundle.putInt(name, value)
                is String -> bundle.putString(name, value)
                else -> error("unsupported settings argument")
            }
        }
        val result = methods.call.call(provider, attribution, authority, method, key, bundle) as Bundle?
        return result?.getString(VALUE_KEY)
    }

    override fun release(authority: String, userId: Int, token: Any) {
        val asUser = methods.releaseAsUser
        if (asUser != null) {
            asUser.call(manager, authority, token, userId)
        } else {
            checkNotNull(methods.release).call(manager, authority, token)
        }
    }

    // Resolve the release method before acquiring a provider reference.
    private class ProviderMethods {
        private val managerType = Class.forName("android.app.IActivityManager")
        private val binderType = IBinder::class.java
        val acquire = managerType.getMethod("getContentProviderExternal", STRING, INT, binderType, STRING)
        val releaseAsUser = managerType.optionalMethod("removeContentProviderExternalAsUser", STRING, binderType, INT)
        val release = if (releaseAsUser == null) managerType.getMethod("removeContentProviderExternal", STRING, binderType) else null
        val provider = Class.forName("android.app.ContentProviderHolder").getField("provider")
        val call = Class.forName("android.content.IContentProvider")
            .getMethod("call", AttributionSource::class.java, STRING, STRING, STRING, Bundle::class.java)
    }
}

internal class CmdSettingsStore : SettingsStore {
    override fun getSecure(userId: Int, key: String): String? = settings("get", "--user", userId.toString(), "secure", key)
        .takeUnless { it == "null" }

    override fun putSecure(userId: Int, key: String, value: String) {
        settings("put", "--user", userId.toString(), "secure", key, value)
    }

    private fun settings(vararg args: String): String {
        val process = ProcessBuilder(listOf("/system/bin/cmd", "settings") + args).redirectErrorStream(true).start()
        // Drain the pipe while waiting so a full buffer cannot hold the child open.
        val printed = FutureTask { process.inputStream.bufferedReader().use { it.readText().trim() } }
        thread(isDaemon = true, name = "settings-output") { printed.run() }
        try {
            check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) { "settings ${args.first()} failed" }
            return printed.get(250, TimeUnit.MILLISECONDS)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

internal class FallbackSettingsStore(
    private val primary: SettingsStore,
    private val fallback: SettingsStore,
    private val warn: (String) -> Unit,
) : SettingsStore {
    @Volatile
    private var usingFallback = false
    private val switchLock = Any()

    override fun getSecure(userId: Int, key: String): String? = withStore { it.getSecure(userId, key) }

    override fun putSecure(userId: Int, key: String, value: String) = withStore { it.putSecure(userId, key, value) }

    private inline fun <T> withStore(operation: (SettingsStore) -> T): T {
        if (!usingFallback) {
            try {
                return operation(primary)
            } catch (e: Throwable) {
                if (e !is Exception && e !is LinkageError) throw e
                synchronized(switchLock) {
                    if (!usingFallback) {
                        usingFallback = true
                        warn("binder settings unavailable, falling back to cmd: ${describeFailure(e)}")
                    }
                }
            }
        }
        return operation(fallback)
    }
}
