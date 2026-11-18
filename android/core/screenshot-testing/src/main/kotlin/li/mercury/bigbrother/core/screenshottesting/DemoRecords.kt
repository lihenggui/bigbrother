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

package li.mercury.bigbrother.core.screenshottesting

import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PackageDetails
import li.mercury.bigbrother.core.model.SensorSession
import li.mercury.bigbrother.core.model.SubjectCount
import li.mercury.bigbrother.core.model.TimelineEntry
import li.mercury.bigbrother.core.screenshottesting.DemoApp.BACKUP
import li.mercury.bigbrother.core.screenshottesting.DemoApp.CHAT
import li.mercury.bigbrother.core.screenshottesting.DemoApp.FLASHLIGHT
import li.mercury.bigbrother.core.screenshottesting.DemoApp.KEYBOARD
import li.mercury.bigbrother.core.screenshottesting.DemoApp.MEDIA_INDEXER
import li.mercury.bigbrother.core.screenshottesting.DemoApp.NEWS
import li.mercury.bigbrother.core.screenshottesting.DemoApp.NOTES
import li.mercury.bigbrother.core.screenshottesting.DemoApp.PHOTO_EDITOR
import li.mercury.bigbrother.core.screenshottesting.DemoApp.PUZZLE
import li.mercury.bigbrother.core.screenshottesting.DemoApp.SHOPPING
import li.mercury.bigbrother.core.screenshottesting.DemoApp.STEP_COUNTER
import li.mercury.bigbrother.core.screenshottesting.DemoApp.WEATHER
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A day of records on the demo device, in the models the daemon's records
 * become. The story: the flashlight read the contacts and the device's
 * identifiers; the weather app read photos in DCIM, asked for the location
 * and looked up other apps; the shopping app read the clipboard and the
 * installed apps; the keyboard read and wrote the clipboard; the step counter
 * turned motion sensors on, the gyroscope once at a high rate; the notes app
 * read a document in Download. System apps and an isolated process have
 * records too, which the screens leave out.
 *
 * Everything is derived from one list of accesses, so that the overview, an
 * app's page, its files and the timeline agree. [clock] places them today,
 * between 2 AM and 2:41 PM; app names are in [locale]'s language.
 */
class DemoRecords(
    private val clock: DemoClockRule,
    locale: Locale = RuntimeEnvironment.getApplication().resources.configuration.locales[0],
) {
    private val events: List<Event> = buildList { record() }

    /** Names and icons of the apps, by package name, as AppInfoRepository gives them. */
    val appInfo: Map<String, AppInfo> = DemoApp.entries.associate { it.packageName to it.appInfo(locale) }

    /** Every app with records, most recent first, as AccessRepository.getApps gives them. */
    val apps: List<AppSummary> = events.groupBy { it.uid to it.packageName }
        .map { (app, events) -> summaryOf(app.first, app.second, events) }
        .sortedByDescending { it.lastSeen }

    /** The timeline, most recent hour first, as AccessRepository.getTimeline gives it. */
    val timeline: List<TimelineEntry> = events
        .groupBy { TimelineKey(hourOf(it.time), it.uid, it.packageName, it.category, it.topic) }
        .map { (key, events) -> timelineEntryOf(key, events) }
        .sortedWith(compareByDescending<TimelineEntry> { it.hourStart }.thenByDescending { it.lastSeen })

    fun summary(app: DemoApp): AppSummary = apps.first { it.uid == app.uid }

    /** Every path [app] touched, most recent first, as AccessRepository.getAccesses gives them. */
    fun accesses(app: DemoApp): List<Access> = events.filter { it.uid == app.uid }
        .groupBy { AccessKey(it.category, it.path, it.action, it.viaProvider) }
        .map { (key, events) ->
            Access(
                category = key.category,
                path = key.path,
                action = key.action,
                viaProvider = key.viaProvider,
                items = events.maxOf { it.items },
                allowed = events.sumOf { it.allowed },
                denied = events.sumOf { it.denied },
                lastSeen = events.maxOf { it.time },
                topic = events.first().topic,
            )
        }
        .sortedByDescending { it.lastSeen }

    /** Each access of one kind by [app], most recent first, as AccessRepository.getHistory gives them. */
    fun history(app: DemoApp, topic: String): List<AccessPeriod> = events.filter { it.uid == app.uid && it.topic == topic }
        .sortedByDescending { it.time }
        // The daemon names the record of each access when several are asked
        // for; only the clipboard's rows show it.
        .map { AccessPeriod(it.action, it.viaProvider, it.allowed, it.denied, it.time, it.time, it.path.takeIf { topic == "clipboard" }) }

    /**
     * [history] at the same times of [day]. For screens that show the date
     * of each access: today's would read differently every day.
     */
    fun history(app: DemoApp, topic: String, day: LocalDate): List<AccessPeriod> {
        val shift = clock.at(day.atStartOfDay()) - clock.todayAt(0, 0)
        return history(app, topic).map { it.copy(firstSeen = it.firstSeen + shift, lastSeen = it.lastSeen + shift) }
    }

    /**
     * When [app] had each sensor on, most recent first, as
     * AccessRepository.getSensorSessions gives them: the step counter's.
     */
    fun sensorSessions(app: DemoApp): List<SensorSession> = if (app == STEP_COUNTER) {
        listOf(
            session(ACCELEROMETER, 14, 2, 14, 17, periodNs = GAME_RATE_NS),
            session(GYROSCOPE, 12, 50, 12, 53, periodNs = 0),
            session(ACCELEROMETER, 12, 50, 13, 5, periodNs = GAME_RATE_NS),
            // Reports on change, for which a rate means nothing; still on.
            SensorSession(STEP_COUNTER_SENSOR, highRate = false, periodNs = null, rateHz = null, start = clock.todayAt(8, 5), end = null),
            session(ACCELEROMETER, 8, 5, 8, 32, periodNs = GAME_RATE_NS),
        )
    } else {
        emptyList()
    }

    private fun session(sensor: String, fromHour: Int, fromMinute: Int, toHour: Int, toMinute: Int, periodNs: Long) = SensorSession(
        sensor = sensor,
        highRate = periodNs <= HIGH_RATE_PERIOD_NS,
        periodNs = periodNs,
        rateHz = if (periodNs > 0) 1e9 / periodNs else null,
        start = clock.todayAt(fromHour, fromMinute),
        end = clock.todayAt(toHour, toMinute),
    )

    /**
     * What the package manager says about the app whose page is shown. The
     * dates are fixed, so that they read the same in every screenshot.
     */
    val packageDetails: PackageDetails = PackageDetails(
        versionName = "5.12.0",
        versionCode = 51200,
        targetSdk = 35,
        minSdk = 28,
        firstInstallTime = clock.at(LocalDateTime.of(2026, 3, 2, 9, 14)) * 1000,
        lastUpdateTime = clock.at(LocalDateTime.of(2026, 11, 9, 21, 37)) * 1000,
    )

    /** The day's accesses, app by app. Times are of today. */
    private fun MutableList<Event>.record() {
        // The weather app checks the location all day; after noon it read photos.
        at(WEATHER, 8, 30) {
            call(LOCATION, "location", times = 2)
            permission("android:coarse_location", "location")
        }
        at(WEATHER, 10, 15) {
            call(LOCATION, "location", times = 3)
            permission("android:fine_location", "location", times = 2)
        }
        at(WEATHER, 12, 38) {
            list("$STORAGE/DCIM")
            list("$STORAGE/DCIM/Camera")
            list("$STORAGE/Pictures")
            CAMERA_EARLIER.forEach { read("$STORAGE/DCIM/Camera/$it") }
            SCREENSHOTS.forEach { read("$STORAGE/Pictures/Screenshots/$it") }
            call(ANDROID_ID, "android_id", times = 2)
            readDevice(MAC_ADDRESS, "mac_address", allowed = 0, denied = 2)
            call(WIFI_SCAN, "wifi_info", times = 2)
            listOf(SHOPPING, CHAT, NEWS, STEP_COUNTER, NOTES, PUZZLE, PHOTO_EDITOR).forEach {
                call("$PACKAGE_INFO#${it.packageName}", "installed_apps")
            }
        }
        at(WEATHER, 13, 52) {
            CAMERA_LATER.forEach { read("$STORAGE/DCIM/Camera/$it") }
            read("$STORAGE/DCIM/Camera/${CAMERA_EARLIER[1]}")
            read("$STORAGE/DCIM/Camera/${CAMERA_EARLIER[4]}", times = 2)
        }
        at(WEATHER, 14, 41) {
            call(LOCATION, "location", times = 5)
            permission("android:fine_location", "location")
            query(IMAGES, items = 1284, times = 2)
            call(WIFI_SCAN, "wifi_info", times = 2)
            call(ANDROID_ID, "android_id", times = 3)
        }

        // A flashlight has no need for any of this.
        at(FLASHLIGHT, 9, 12) {
            personal(CONTACTS, "contacts", items = 412)
            call(ANDROID_ID, "android_id")
            call(INSTALLED_PACKAGES, "installed_apps")
        }
        at(FLASHLIGHT, 14, 22) {
            list("$STORAGE/DCIM")
            list("$STORAGE/Download")
            personal(CONTACTS, "contacts", items = 412, times = 2)
            call(ANDROID_ID, "android_id", times = 2)
            call("binder:android.accounts.IAccountManager/getAccountsAsUser", "accounts")
            readDevice(MAC_ADDRESS, "mac_address", allowed = 0, denied = 1)
        }

        at(SHOPPING, 11, 5) { call(CLIPBOARD_READ, "clipboard") }
        at(SHOPPING, 13, 30) {
            probe("$STORAGE/.device_id")
            call(INSTALLED_PACKAGES, "installed_apps", times = 2)
            call(CLIPBOARD_READ, "clipboard", times = 3)
            // One of the reads was in the background, which the system refused.
            permission("android:read_clipboard", "clipboard", times = 2, denied = 1)
            call(ANDROID_ID, "android_id")
            connect("net://224.0.0.251:5353", "device_discovery", times = 4)
        }

        at(NOTES, 13, 48) {
            list("$STORAGE/Download")
            read("$STORAGE/Download/tax_return_2025.pdf", times = 2)
        }

        // Photos picked through the media library, as a photo editor should.
        at(PHOTO_EDITOR, 11, 40) {
            query(IMAGES, items = 1284)
            read("$STORAGE/DCIM/Camera/${CAMERA_LATER.last()}", viaProvider = true)
        }
        at(PHOTO_EDITOR, 12, 5) {
            query(IMAGES, items = 1284)
            read("$STORAGE/DCIM/Camera/${CAMERA_LATER[1]}", viaProvider = true, times = 2)
        }

        at(CHAT, 10, 40) { personal(CONTACTS_LIST, "contacts", items = 412) }
        at(CHAT, 14, 5) {
            personal(CONTACTS_LIST, "contacts", items = 412)
            read("$STORAGE/Download/dinner_menu.pdf")
            read("$STORAGE/Pictures/Chat/IMG_20261114_190211.jpg")
            permission("android:camera", "camera")
            permission("android:record_audio", "microphone")
        }

        at(STEP_COUNTER, 8, 5) {
            call(SENSOR_CONNECTION, "sensors")
            call("$SENSOR_ON@$ACCELEROMETER", "sensors")
            call("$SENSOR_ON@$STEP_COUNTER_SENSOR", "sensors")
        }
        at(STEP_COUNTER, 12, 50) {
            call(SENSOR_CONNECTION, "sensors", times = 2)
            call("$SENSOR_ON@$ACCELEROMETER", "sensors")
            call("$SENSOR_ON@$GYROSCOPE:fast", "sensors")
            call(LOCATION, "location", times = 3)
        }
        at(STEP_COUNTER, 14, 2) { call("$SENSOR_ON@$ACCELEROMETER", "sensors") }

        // A keyboard offers what was copied, and copies too.
        at(KEYBOARD, 11, 20) {
            call(CLIPBOARD_LISTEN, "clipboard")
            call(CLIPBOARD_READ, "clipboard", times = 2)
        }
        at(KEYBOARD, 14, 5) {
            call(CLIPBOARD_READ, "clipboard", times = 2)
            call(CLIPBOARD_WRITE, "clipboard")
        }

        at(NEWS, 12, 20) {
            list("$STORAGE/Download")
            call(ANDROID_ID, "android_id", times = 2)
            call(INSTALLED_PACKAGES, "installed_apps")
            call("binder:android.app.IActivityManager/getRunningAppProcesses", "running_apps", times = 3)
            call("binder:android.net.IConnectivityManager/getActiveNetwork", "network_state", times = 2)
            exec("exec:getprop ro.product.model", "system_properties")
        }

        // A game that wants to know whether the device is rooted or emulated.
        at(PUZZLE, 10, 2) {
            exec("exec:which su", "root_check")
            probe("/system/xbin/su", Category.SYSTEM, "root_check")
            probe("/dev/qemu_pipe", Category.DEVICE, "emulator_probe")
            readDevice("/sys/class/power_supply/battery/capacity", "battery", allowed = 3)
            exec("exec:getprop ro.build.fingerprint", "system_properties")
            call(ANDROID_ID, "android_id")
            call("binder:android.app.IActivityManager/getRunningAppProcesses", "running_apps")
        }

        // Hidden by default: system apps, which read files as their job, and
        // an isolated process that could not be traced to its app.
        at(MEDIA_INDEXER, 14, 30) {
            (CAMERA_EARLIER + CAMERA_LATER).forEach { read("$STORAGE/DCIM/Camera/$it") }
        }
        at(BACKUP, 2, 0) { read("$STORAGE/Documents/passport_scan.pdf") }
        add(Event(ISOLATED_UID, "", clock.todayAt(13, 10), Category.SYSTEM, "/proc/cpuinfo", Action.READ, topic = "cpu_model"))
    }

    private fun MutableList<Event>.at(app: DemoApp, hour: Int, minute: Int, block: Moment.() -> Unit) {
        Moment(app, clock.todayAt(hour, minute), this).block()
    }

    /** Accesses one app made at one time. */
    private class Moment(private val app: DemoApp, private val time: Long, private val events: MutableList<Event>) {
        private fun add(
            category: Category,
            path: String,
            action: Action,
            topic: String? = null,
            allowed: Int = 1,
            denied: Int = 0,
            items: Int = 0,
            viaProvider: Boolean = false,
        ) {
            events += Event(app.uid, app.packageName, time, category, path, action, allowed, denied, topic, items, viaProvider)
        }

        fun read(path: String, times: Int = 1, viaProvider: Boolean = false) = add(Category.STORAGE, path, Action.READ, allowed = times, viaProvider = viaProvider)

        fun list(path: String) = add(Category.STORAGE, path, Action.LIST)

        fun probe(path: String, category: Category = Category.STORAGE, topic: String? = null) = add(category, path, Action.PROBE, topic)

        fun query(uri: String, items: Int, times: Int = 1) = add(Category.PROVIDER, uri, Action.QUERY, allowed = times, items = items)

        fun personal(uri: String, topic: String, items: Int, times: Int = 1) = add(Category.PERSONAL, uri, Action.QUERY, topic, allowed = times, items = items)

        fun call(path: String, topic: String, times: Int = 1) = add(Category.SERVICE, path, Action.CALL, topic, allowed = times)

        fun connect(path: String, topic: String, times: Int) = add(Category.NETWORK, path, Action.CONNECT, topic, allowed = times)

        fun exec(path: String, topic: String) = add(Category.COMMAND, path, Action.EXEC, topic)

        fun permission(op: String, topic: String, times: Int = 1, denied: Int = 0) = add(Category.PERMISSION, "appop:$op", Action.READ, topic, allowed = times, denied = denied)

        fun readDevice(path: String, topic: String, allowed: Int = 1, denied: Int = 0) = add(Category.DEVICE, path, Action.READ, topic, allowed, denied)
    }

    /**
     * The start of [time]'s hour on the local clock. The daemon counts hours
     * in Unix time, which is the same in the whole-hour zones of real
     * devices, but not in the demo's.
     */
    private fun hourOf(time: Long): Long = Instant.ofEpochSecond(time).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).toEpochSecond()

    private data class AccessKey(val category: Category, val path: String, val action: Action, val viaProvider: Boolean)

    private data class TimelineKey(
        val hourStart: Long,
        val uid: Int,
        val packageName: String,
        val category: Category,
        val topic: String?,
    )

    private fun summaryOf(uid: Int, packageName: String, events: List<Event>): AppSummary {
        val storage = events.filter { it.category == Category.STORAGE }
        val read = storage.filter { it.isSuccessfulOpen }
        val topics = events.mapNotNull { it.topic }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
        return AppSummary(
            uid = uid,
            packageName = packageName,
            counts = events.groupBy { it.category }.mapValues { (_, events) -> events.distinctBy { it.path }.size },
            lastSeen = events.maxOf { it.time },
            filesRead = read.distinctBy { it.path }.size,
            folders = topFolders(read.ifEmpty { storage }),
            folderCount = storage.mapNotNull { topFolderOf(it.path) }.distinct().size,
            topics = topics,
        )
    }

    private fun timelineEntryOf(key: TimelineKey, events: List<Event>): TimelineEntry {
        val read = events.filter { it.category == Category.STORAGE && it.isSuccessfulOpen }
        return TimelineEntry(
            hourStart = key.hourStart,
            uid = key.uid,
            packageName = key.packageName,
            category = key.category,
            topic = key.topic,
            paths = events.distinctBy { it.path }.size,
            filesRead = read.distinctBy { it.path }.size,
            allowed = events.sumOf { it.allowed },
            denied = events.sumOf { it.denied },
            modes = events.fold(0) { modes, event -> modes or event.mode },
            items = events.maxOf { it.items },
            folder = topFolders(read).firstOrNull(),
            firstSeen = events.minOf { it.time },
            lastSeen = events.maxOf { it.time },
            // As the daemon reports them: for the kinds whose records differ.
            subjects = if (key.topic in LISTED_TOPICS) {
                events.groupBy { it.path }.map { (path, events) ->
                    SubjectCount(path, events.sumOf { it.allowed }, events.sumOf { it.denied })
                }
            } else {
                emptyList()
            },
        )
    }

    /** The top-level shared-storage folders of [events]' paths, the most paths first. */
    private fun topFolders(events: List<Event>): List<String> = events.distinctBy { it.path }
        .mapNotNull { topFolderOf(it.path) }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

    private fun topFolderOf(path: String): String? = path.removePrefix("$STORAGE/").takeIf { it != path && '/' in it }?.substringBefore('/')

    private companion object {
        /** An isolated process, which has a uid of its own and no package. */
        const val ISOLATED_UID = 99042

        const val STORAGE = "/storage/emulated/0"
        const val IMAGES = "content://media/external/images/media"
        const val CONTACTS = "content://com.android.contacts/data/phones"
        const val CONTACTS_LIST = "content://com.android.contacts/contacts"
        const val LOCATION = "binder:android.location.ILocationManager/getLastLocation"
        const val WIFI_SCAN = "binder:android.net.wifi.IWifiManager/getScanResults"
        const val ANDROID_ID = "setting:secure/android_id"
        const val MAC_ADDRESS = "/sys/class/net/wlan0/address"
        const val CLIPBOARD_READ = "binder:android.content.IClipboard/getPrimaryClip"
        const val CLIPBOARD_LISTEN = "binder:android.content.IClipboard/addPrimaryClipChangedListener"
        const val CLIPBOARD_WRITE = "binder:android.content.IClipboard/setPrimaryClip"
        const val SENSOR_CONNECTION = "binder:android.gui.SensorServer/createSensorEventConnection"
        const val SENSOR_ON = "binder:android.gui.SensorEventConnection/enableDisable"
        const val ACCELEROMETER = "android.sensor.accelerometer"
        const val GYROSCOPE = "android.sensor.gyroscope"
        const val STEP_COUNTER_SENSOR = "android.sensor.step_counter"

        /** SensorManager.SENSOR_DELAY_GAME: 50 events a second. */
        const val GAME_RATE_NS = 20_000_000L

        /** The daemon's line for a high rate: 200 events a second. */
        const val HIGH_RATE_PERIOD_NS = 5_000_000L

        /** Topics whose timeline entries list their records. */
        val LISTED_TOPICS = setOf("clipboard", "sensors")
        const val INSTALLED_PACKAGES = "binder:android.content.pm.IPackageManager/getInstalledPackages"
        const val PACKAGE_INFO = "binder:android.content.pm.IPackageManager/getPackageInfo"

        /** Photos the weather app read, taken over the past weeks. */
        val CAMERA_EARLIER = listOf(
            "PXL_20261002_174512.jpg",
            "PXL_20261005_081133.jpg",
            "PXL_20261011_193020.jpg",
            "PXL_20261018_120455.jpg",
            "PXL_20261024_165802.jpg",
            "PXL_20261101_090317.jpg",
        )
        val CAMERA_LATER = listOf(
            "PXL_20261109_181540.jpg",
            "PXL_20261112_073905.jpg",
            "PXL_20261115_201218.jpg",
        )
        val SCREENSHOTS = listOf(
            "Screenshot_20261012-221045.png",
            "Screenshot_20261030-083512.png",
            "Screenshot_20261108-140233.png",
        )
    }
}

/** One access, or several alike, at one time: what the daemon records. */
private class Event(
    val uid: Int,
    val packageName: String,
    val time: Long,
    val category: Category,
    val path: String,
    val action: Action,
    val allowed: Int = 1,
    val denied: Int = 0,
    val topic: String? = null,
    val items: Int = 0,
    val viaProvider: Boolean = false,
) {
    /** The file was opened, as opposed to looked for. */
    val isSuccessfulOpen: Boolean get() = allowed > 0 && action == Action.READ

    /** The daemon's mode bits for it, which [Action.fromMode] decodes. */
    val mode: Int
        get() = when (action) {
            Action.READ -> 1
            Action.WRITE -> 2
            Action.READ_WRITE -> 3
            Action.LIST -> 4
            Action.PROBE -> 8
            Action.WATCH -> 16
            Action.QUERY -> 64
            Action.CONNECT -> 128
            Action.EXEC -> 256
            Action.CALL -> 512
        } or if (viaProvider) 32 else 0
}
