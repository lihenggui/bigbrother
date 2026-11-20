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
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.system.Os
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

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
        reportView()
        completeAfterIsolatedProbe()
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
        view("sms") { contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)?.use { it.count } }
        view("call_log") { contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, null)?.use { it.count } }
        view("calendar") {
            contentResolver.query(CalendarContract.Events.CONTENT_URI, null, null, null, null)?.use { it.count }
        }
        view("accounts") { AccountManager.get(this).accounts.size }
        view("location") { lastLocation() }
        // A file picked through the system, and kept, before any sandbox:
        // whether it can still be read, through the grant.
        view("granted_file") {
            contentResolver.persistedUriPermissions.firstOrNull()?.uri?.let { uri ->
                contentResolver.openInputStream(uri)?.use { it.reader().readText().trim() }
            } ?: "none"
        }
        // The mode the system applies to each app op the check asks about
        // (the `ops` extra): what holds the rest of the personal data back.
        view("ops") {
            val appOps = getSystemService(AppOpsManager::class.java)
            intent.getStringExtra(EXTRA_OPS).orEmpty().split(',').filter { it.isNotEmpty() }.joinToString(",") { op ->
                val mode = try {
                    appOps.unsafeCheckOpNoThrow(op, Process.myUid(), packageName).toString()
                } catch (_: IllegalArgumentException) {
                    // An op this Android version does not have.
                    "none"
                }
                "$op=$mode"
            }
        }
        // The network: whether the address the check names (the `net`
        // extra, `host:port`) can be connected to, and whether this app
        // still reaches a socket of its own over loopback.
        view("network") {
            val (host, port) = intent.getStringExtra(EXTRA_NET).orEmpty().split(':').let { it[0] to it[1].toInt() }
            reach(InetSocketAddress(host, port))
        }
        view("loopback") {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { reach(it.localSocketAddress as InetSocketAddress) }
        }
        // The system's resolver, which looks names up for every app: whether
        // its socket can be connected to.
        view("resolver") {
            LocalSocket().use { it.connect(LocalSocketAddress(RESOLVER, LocalSocketAddress.Namespace.FILESYSTEM)) }
            "connected"
        }
        // The socket the sandbox's daemon relays lookups on, asked directly,
        // whatever is mounted for this app: `answered` if a lookup sent
        // there comes back with anything, `closed` if it is not taken.
        view("relay") {
            LocalSocket().use {
                it.connect(LocalSocketAddress(RELAY, LocalSocketAddress.Namespace.FILESYSTEM))
                it.soTimeout = REACH_TIMEOUT_MS
                it.outputStream.write("getaddrinfo $RELAY_NAME ^ 1024 0 1 0 0\u0000".toByteArray())
                if (it.inputStream.read() >= 0) "answered" else "closed"
            }
        }
        // Identity: the Android ID both ways to it, and the installed apps
        // that did not come with the system.
        view("android_id") { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) }
        view("android_id_query") {
            contentResolver.query(Uri.parse("content://settings/secure/android_id"), null, null, null, null)?.use { row ->
                if (row.moveToFirst()) row.getString(row.getColumnIndexOrThrow("value")) else "none"
            }
        }
        view("ad_id") { adId() }
        view("ad_services") { adServices() }
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

    private fun completeAfterIsolatedProbe() {
        val reported = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = reported.countDown()
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val bound = bindService(Intent(this, IsolatedProbe::class.java), connection, BIND_AUTO_CREATE)
        try {
            val status = when {
                !bound -> "probe failed: could not bind the isolated probe"
                !reported.await(ISOLATED_WAIT_S, TimeUnit.SECONDS) -> "probe failed: the isolated probe did not connect"
                else -> "done"
            }
            // Logcat can lose the last line. Publish only the outcome, atomically,
            // after the observations and before synchronous service teardown.
            val temp = File(filesDir, "probe-status.tmp")
            temp.writeText(status)
            Os.rename(temp.path, File(filesDir, "probe-status").path)
            report(status)
        } finally {
            unbindService(connection)
        }
    }

    /**
     * Whether Android's own advertising ID service can be bound: `bound`,
     * `not_bound`, or `none` on a device without it.
     */
    private fun adServices(): String {
        val action = Intent(AD_SERVICES_ACTION)
        val service = packageManager.queryIntentServices(action, 0).firstOrNull()?.serviceInfo ?: return "none"
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val bound = bindService(action.setClassName(service.packageName, service.name), connection, BIND_AUTO_CREATE)
        if (bound) unbindService(connection)
        return if (bound) "bound" else "not_bound"
    }

    /**
     * The advertising ID Google Play services gives this app, asked for as
     * its client library does; `none` without Play services.
     */
    private fun adId(): String {
        val intent = Intent(AD_ID_ACTION).setPackage(PLAY_SERVICES)
        val bound = LinkedBlockingQueue<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = bound.put(service)
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        if (!bindService(intent, connection, BIND_AUTO_CREATE)) return "none"
        try {
            val service = bound.poll(AD_ID_WAIT_S, TimeUnit.SECONDS) ?: return "timeout"
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(AD_ID_INTERFACE)
                service.transact(AD_ID_GET, data, reply, 0)
                reply.readException()
                return reply.readString() ?: "none"
            } finally {
                data.recycle()
                reply.recycle()
            }
        } finally {
            unbindService(connection)
        }
    }

    /**
     * How close the most accurate location the system last had is, as
     * `accuracy:<metres>`; `none` when it gives this app none.
     */
    @Suppress("MissingPermission")
    private fun lastLocation(): String {
        val manager = getSystemService(LocationManager::class.java)
        val location = manager.getProviders(true)
            .mapNotNull { manager.getLastKnownLocation(it) }
            .minByOrNull { it.accuracy }
        return location?.let { "accuracy:${it.accuracy.roundToInt()}" } ?: "none"
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

    /** What comes of connecting to [address]: `connected`, `refused` or `timeout`. */
    private fun reach(address: InetSocketAddress): String = try {
        Socket().use {
            it.connect(address, REACH_TIMEOUT_MS)
            // A request and whatever answers it, so that bytes go both
            // ways where something listens; what comes back is not looked at.
            try {
                it.soTimeout = REACH_TIMEOUT_MS
                it.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".toByteArray())
                it.getInputStream().read(ByteArray(4096))
            } catch (_: IOException) {
            }
        }
        "connected"
    } catch (_: ConnectException) {
        "refused"
    } catch (_: SocketTimeoutException) {
        "timeout"
    }

    private companion object {
        const val TAG = "BigBrotherCanary"
        const val CONNECT_TIMEOUT_MS = 500

        const val REACH_TIMEOUT_MS = 3_000

        /** The app ops `scripts/check-sandbox.sh` asks about, comma-separated. */
        const val EXTRA_OPS = "ops"

        /** The address `scripts/check-sandbox.sh` has the network tried at, `host:port`. */
        const val EXTRA_NET = "net"

        /** Where netd listens for the lookups of apps. */
        const val RESOLVER = "/dev/socket/dnsproxyd"

        /** Where the sandbox's daemon does (`SOCKET` in bbd/src/sandbox/names.rs), and a name to ask it for. */
        const val RELAY = "/dev/socket/dnsproxyd_relay"
        const val RELAY_NAME = "relay.sandbox-check.example"

        /** Set by `scripts/check-sandbox.sh` to have a file picked rather than the probes run. */
        const val EXTRA_PICK = "pick"
        const val PICK = 1
        val DOWNLOADS: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

        const val ISOLATED_WAIT_S = 10L

        /** How Google Play services' client library reaches the advertising ID. */
        const val PLAY_SERVICES = "com.google.android.gms"
        const val AD_ID_ACTION = "com.google.android.gms.ads.identifier.service.START"
        const val AD_ID_INTERFACE = "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService"
        const val AD_ID_GET = IBinder.FIRST_CALL_TRANSACTION
        const val AD_ID_WAIT_S = 10L

        /** Android's own advertising ID service (`AdIdManager`), from Android 13. */
        const val AD_SERVICES_ACTION = "android.adservices.ADID_SERVICE"

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
