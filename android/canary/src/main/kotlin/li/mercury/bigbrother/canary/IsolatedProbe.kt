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

package li.mercury.bigbrother.canary

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.provider.Settings
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Runs in an isolated process: a uid of its own, outside the app's, as a
 * sandboxed renderer does. Its observations reach the activity over Binder,
 * so the activity can publish them with the rest of the test result.
 */
class IsolatedProbe : Service() {
    override fun onBind(intent: Intent): IBinder {
        val observations = Bundle().apply {
            view("cpu_named") {
                File("/proc/cpuinfo").readLines().any { line -> CPU_NAMING_KEYS.any { line.startsWith(it) } }
            }
            view("storage") { File(SHARED).list()?.sorted()?.joinToString(",") }
            view("marker") { File(SHARED, "Download/$MARKER").readText().trim() }
            view("android_id") { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) }
        }
        return Messenger(
            Handler(mainLooper) { request ->
                try {
                    request.replyTo?.send(Message.obtain().apply { data = observations })
                } catch (_: RemoteException) {
                    // The requesting activity has already gone away.
                }
                true
            },
        ).binder
    }

    private fun Bundle.view(key: String, value: () -> Any?) {
        val seen = try {
            value().toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            "refused:${e.javaClass.simpleName}"
        }
        putString("isolated_$key", seen)
    }

    private companion object {
        const val SHARED = "/storage/emulated/0"
        const val MARKER = "bb_sandbox_marker.txt"
        val CPU_NAMING_KEYS = listOf("model name", "Hardware", "CPU part", "vendor_id")
    }
}
