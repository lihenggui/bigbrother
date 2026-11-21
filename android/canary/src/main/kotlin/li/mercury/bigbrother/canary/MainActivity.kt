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
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.Settings
import android.provider.Telephony
import android.system.Os
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    private val observations = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setPadding(32, 96, 32, 32) }
        setContentView(ScrollView(this).apply { addView(output) })
        if (intent.getBooleanExtra(EXTRA_PICK, false)) {
            // Asked for a file instead of the probes: the check picks the
            // marker, and the app keeps the grant to it.
            started = true
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS),
                PICK,
            )
        }
    }

    @Deprecated("The probes need nothing newer.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK && uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Read at once, through the grant of the moment.
            view("picked_file") { contentResolver.openInputStream(uri)?.use { it.reader().readText().trim() } }
        } else {
            report("not picked")
        }
        publishResult("done")
        finish()
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
        reportSandboxView(::view)
        completeAfterIsolatedProbe()
    }

    private fun completeAfterIsolatedProbe() {
        val result = CompletableFuture<Bundle>()
        val receiver = Messenger(
            Handler(mainLooper) { message ->
                result.complete(message.data)
                true
            },
        )
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try {
                    Messenger(service).send(Message.obtain().apply { replyTo = receiver })
                } catch (e: RemoteException) {
                    result.completeExceptionally(IOException("could not request the isolated observations", e))
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                result.completeExceptionally(IOException("the isolated probe disconnected before replying"))
            }

            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)

            override fun onNullBinding(name: ComponentName) {
                result.completeExceptionally(IOException("the isolated probe returned no binder"))
            }
        }
        val bound = bindService(Intent(this, IsolatedProbe::class.java), connection, BIND_AUTO_CREATE)
        if (!bound) {
            publishResult("probe failed: could not bind the isolated probe")
            return
        }
        try {
            val observed = result.get(ISOLATED_WAIT_S, TimeUnit.SECONDS)
            for (key in observed.keySet()) {
                observe(key, observed.getString(key).orEmpty())
            }
            publishResult("done")
        } catch (e: ExecutionException) {
            publishResult("probe failed: ${e.cause?.message}")
        } catch (_: TimeoutException) {
            publishResult("probe failed: the isolated probe did not reply")
        } finally {
            unbindService(connection)
        }
    }

    private fun publishResult(status: String) {
        // The script needs the outcome and its observations together, including
        // when logcat drops a line or Android takes time to unbind the service.
        val temp = File(filesDir, "probe-result.tmp")
        temp.writeText((listOf(status) + observations).joinToString("\n", postfix = "\n"))
        Os.rename(temp.path, File(filesDir, "probe-result").path)
        report(status)
    }

    /** Reports one thing this app sees; `refused` if it may not look. */
    private fun view(key: String, value: () -> Any?) {
        val seen = try {
            value().toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            "refused:${e.javaClass.simpleName}"
        }
        observe(key, seen)
    }

    private fun observe(key: String, value: String) {
        val line = "$key=$value"
        observations += line
        report("sandbox $line")
    }

    /** Runs [action], reporting what came of it. A probe that fails has still probed. */
    private fun probe(name: String, action: () -> Any?) {
        val outcome = try {
            "ok: ${action()}"
        } catch (cancelled: CancellationException) {
            throw cancelled
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

        /** Set by `scripts/check-sandbox.sh` to have a file picked rather than the probes run. */
        const val EXTRA_PICK = "pick"
        const val PICK = 1
        val DOWNLOADS: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

        const val ISOLATED_WAIT_S = 10L

        /** How long the sensors stay on: a known length for the recorded sessions. */
        const val SENSORS_ON_MS = 10_000L
    }
}
