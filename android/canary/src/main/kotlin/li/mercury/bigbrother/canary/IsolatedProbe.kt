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
import android.os.Binder
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Runs in an isolated process: a uid of its own, outside the app's, as a
 * sandboxed renderer does. Reports, as `sandbox isolated_<key>=<value>`
 * lines, what such a process of the app can see, for
 * `scripts/check-sandbox.sh`.
 */
class IsolatedProbe : Service() {
    override fun onBind(intent: Intent): IBinder {
        view("cpu_named") {
            File("/proc/cpuinfo").readLines().any { line -> CPU_NAMING_KEYS.any { line.startsWith(it) } }
        }
        view("storage") { File(SHARED).list()?.sorted()?.joinToString(",") }
        view("marker") { File(SHARED, "Download/$MARKER").readText().trim() }
        view("android_id") { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) }
        return Binder()
    }

    private fun view(key: String, value: () -> Any?) {
        val seen = try {
            value().toString()
        } catch (e: Throwable) {
            "refused:${e.javaClass.simpleName}"
        }
        Log.i(TAG, "sandbox isolated_$key=$seen")
    }

    private companion object {
        const val TAG = "BigBrotherCanary"
        const val SHARED = "/storage/emulated/0"
        const val MARKER = "bb_sandbox_marker.txt"
        val CPU_NAMING_KEYS = listOf("model name", "Hardware", "CPU part", "vendor_id")
    }
}
