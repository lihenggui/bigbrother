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

import android.accounts.AccountManager
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Does, once each, the things Big Brother is meant to notice.
 *
 * Every probe runs whether or not the system lets it succeed: what is being
 * tested is that the attempt is recorded. `scripts/check-canary.sh` starts
 * this activity and compares the records with the list of probes; keep the
 * two in step.
 *
 * It then reports what it can see of the device, one `sandbox <key>=<value>`
 * line each, for `scripts/check-sandbox.sh`, which runs it in the sandbox
 * and outside and checks each value against what the sandbox promises.
 */
class MainActivity : Activity() {
    private lateinit var output: TextView

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setPadding(32, 96, 32, 32) }
        setContentView(ScrollView(this).apply { addView(output) })
    }

    // Only an app with focus may read the clipboard, so the probes start
    // once the window has it.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !started) {
            started = true
            thread(name = "probes") { runProbes() }
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun runProbes() {
        // Files and kernel information.
        probe("read /proc/cpuinfo") { File("/proc/cpuinfo").readText().length }
        probe("read the Wi-Fi hardware address") { File("/sys/class/net/wlan0/address").readText() }
        probe("list shared storage") { File("/storage/emulated/0/Download").list()?.size }

        // Commands.
        probe("run getprop") { run("getprop", "ro.serialno") }
        probe("run su") { run("su", "-c", "id") }

        // Network.
        probe("connect to a local port") { connect("127.0.0.1", 5555) }
        probe("connect to the local network") { connect("192.168.255.254", 9) }
        probe("send a discovery datagram") {
            DatagramSocket().use { socket ->
                val message = "M-SEARCH * HTTP/1.1\r\n\r\n".toByteArray()
                socket.send(DatagramPacket(message, message.size, InetAddress.getByName("239.255.255.250"), 1900))
            }
        }
        probe("list network interfaces") { NetworkInterface.getNetworkInterfaces().toList().size }

        // System services.
        probe("list installed apps") { packageManager.getInstalledPackages(0).size }
        probe("look for one app") { packageManager.getPackageInfo("com.android.vending", 0).versionName }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clipListener = ClipboardManager.OnPrimaryClipChangedListener { }
        probe("listen to the clipboard") { clipboard.addPrimaryClipChangedListener(clipListener) }
        probe("write the clipboard") { clipboard.setPrimaryClip(ClipData.newPlainText("canary", "canary")) }
        probe("read the clipboard") { clipboard.primaryClip?.itemCount }
        clipboard.removePrimaryClipChangedListener(clipListener)
        probe("read the Android ID") { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) }
        probe("read the last location") {
            getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }
        probe("read the Wi-Fi connection") { getSystemService(WifiManager::class.java).connectionInfo.ssid }
        probe("list accounts") { AccountManager.get(this).accounts.size }
        probe("list running apps") { getSystemService(ActivityManager::class.java).runningAppProcesses.size }
        probe("listen to sensors for ${SENSORS_ON_MS / 1000} s") {
            // One listener each, so each sensor has a connection of its own.
            val sensors = getSystemService(SensorManager::class.java)
            val accelerometer = sensorListener()
            val gyroscope = sensorListener()
            sensors.registerListener(accelerometer, sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)
            sensors.registerListener(gyroscope, sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_FASTEST)
            Thread.sleep(SENSORS_ON_MS)
            sensors.unregisterListener(accelerometer)
            sensors.unregisterListener(gyroscope)
        }

        // Personal data.
        probe("query contacts") {
            contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)?.use { it.count }
        }
        probe("query the calendar") {
            contentResolver.query(CalendarContract.Events.CONTENT_URI, null, null, null, null)?.use { it.count }
        }
        probe("query text messages") {
            contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)?.use { it.count }
        }
        reportView()
        report("done")
    }

    /**
     * What this app can see of the device. The keys and the form of the
     * values are what `scripts/check-sandbox.sh` reads; keep the two in step.
     */
    @Suppress("DEPRECATION")
    private fun reportView() {
        val shared = File("/storage/emulated/0")
        // Shared storage: what is there, whether a file the check put in the
        // real one can be read, and whether this app can write.
        view("storage") { shared.list()?.sorted()?.joinToString(",") }
        view("volumes") { File("/storage").list()?.sorted()?.joinToString(",") }
        view("marker") { File(shared, "Download/$MARKER").readText().trim() }
        // The other paths the same storage is mounted or linked at: those of
        // them through which the file can be read.
        view("other_ways") {
            OTHER_WAYS.filter { way ->
                try {
                    File(way, "Download/$MARKER").readText().isNotEmpty()
                } catch (_: Throwable) {
                    false
                }
            }.joinToString(",")
        }
        view("write") { File(shared, "Download/bb_canary.txt").apply { writeText("written") }.readText() }
        view("own_dir") { File(getExternalFilesDir(null), "bb_canary.txt").apply { writeText("written") }.readText() }
        // Kernel information: whether the chip is named, and the memory.
        view("cpu_named") {
            File("/proc/cpuinfo").readLines().any { line ->
                CPU_NAMING_KEYS.any { line.startsWith(it) }
            }
        }
        view("cpu_count") { File("/proc/cpuinfo").readLines().count { it.startsWith("processor") } }
        view("mem_total_kb") {
            File("/proc/meminfo").readLines().first { it.startsWith("MemTotal:") }.filter { it.isDigit() }
        }
        // Personal data: how much of it this app is given.
        view("contacts") {
            contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)?.use { it.count }
        }
        view("clipboard") { getSystemService(ClipboardManager::class.java).primaryClip?.itemCount ?: 0 }
        view("images") {
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), null, null, null)?.use { it.count }
        }
        // Identity: the Android ID both ways to it, and the installed apps
        // that did not come with the system.
        view("android_id") { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) }
        view("android_id_query") {
            contentResolver.query(Uri.parse("content://settings/secure/android_id"), null, null, null, null)?.use { row ->
                if (row.moveToFirst()) row.getString(row.getColumnIndexOrThrow("value")) else "none"
            }
        }
        view("apps") {
            packageManager.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { it.packageName }
                .sorted()
                .joinToString(",")
        }
        // What must keep working in the sandbox: packages of the system.
        view("system_apps") {
            packageManager.getInstalledApplications(0).count { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
        }
    }

    /** Reports one thing this app sees; `refused` if it may not look. */
    private fun view(key: String, value: () -> Any?) {
        val seen = try {
            value().toString()
        } catch (e: Throwable) {
            "refused:${e.javaClass.simpleName}"
        }
        report("sandbox $key=$seen")
    }

    /** Runs [action], reporting what came of it. A probe that fails has still probed. */
    private fun probe(name: String, action: () -> Any?) {
        val outcome = try {
            "ok: ${action()}"
        } catch (e: Throwable) {
            "refused: ${e.javaClass.simpleName}"
        }
        report("$name -> $outcome")
    }

    private fun sensorListener() = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = Unit
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        runOnUiThread { output.append(line + "\n") }
    }

    private fun run(vararg command: String): String {
        val process = Runtime.getRuntime().exec(command)
        return process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
    }

    private fun connect(host: String, port: Int) {
        Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
    }

    private companion object {
        const val TAG = "BigBrotherCanary"
        const val CONNECT_TIMEOUT_MS = 500

        /** How long the sensors stay on: a known length for the recorded sessions. */
        const val SENSORS_ON_MS = 10_000L

        /** A file `scripts/check-sandbox.sh` puts in the real shared storage. */
        const val MARKER = "bb_sandbox_marker.txt"

        /** Where else than `/storage/emulated/0` a device has its shared storage. */
        val OTHER_WAYS = listOf(
            "/sdcard",
            "/storage/self/primary",
            "/mnt/sdcard",
            "/mnt/user/0/emulated/0",
            "/mnt/user/0/self/primary",
            "/mnt/pass_through/0/emulated/0",
            "/mnt/androidwritable/0/emulated/0",
            "/mnt/installer/0/emulated/0",
            "/mnt/runtime/default/emulated/0",
            "/mnt/runtime/full/emulated/0",
            "/data/media/0",
        )

        /** The lines of `/proc/cpuinfo` that name the chip. */
        val CPU_NAMING_KEYS = listOf("model name", "Hardware", "CPU part", "vendor_id")
    }
}
