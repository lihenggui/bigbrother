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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
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
        report("done")
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
    }
}
