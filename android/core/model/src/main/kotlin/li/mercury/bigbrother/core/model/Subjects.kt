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

package li.mercury.bigbrother.core.model

/*
 * What the records of the clipboard and of sensors say, read from their
 * subjects (`bbd/src/binder.rs`, `bbd/src/appops.rs`). A record of these
 * kinds says more than that the app asked: which way the clipboard went,
 * which sensor was turned on and how fast.
 */

/** Which way an app used the clipboard. */
enum class ClipboardUse {
    READ,

    /** Asked to be told when the clipboard changes. */
    LISTEN,

    /** Put something on the clipboard or cleared it. */
    WRITE,
}

private const val CLIPBOARD_CALL = "binder:android.content.IClipboard/"

/** The clipboard calls and permission records, and which way each went. */
private val CLIPBOARD_SUBJECTS: Map<String, ClipboardUse> = mapOf(
    "${CLIPBOARD_CALL}getPrimaryClip" to ClipboardUse.READ,
    "${CLIPBOARD_CALL}addPrimaryClipChangedListener" to ClipboardUse.LISTEN,
    "${CLIPBOARD_CALL}setPrimaryClip" to ClipboardUse.WRITE,
    "${CLIPBOARD_CALL}setPrimaryClipAsPackage" to ClipboardUse.WRITE,
    "${CLIPBOARD_CALL}clearPrimaryClip" to ClipboardUse.WRITE,
    "appop:android:read_clipboard" to ClipboardUse.READ,
    "appop:android:write_clipboard" to ClipboardUse.WRITE,
)

/** Which way a record used the clipboard; null if it is not a clipboard record. */
fun clipboardUse(subject: String): ClipboardUse? = CLIPBOARD_SUBJECTS[subject]

/** How often an app used the clipboard each way, as allowed and refused. */
data class ClipboardCounts(
    val read: Int = 0,
    val readDenied: Int = 0,
    val listened: Int = 0,
    val written: Int = 0,
    val writeDenied: Int = 0,
)

/** The clipboard use among [records]; null if none of them says which way. */
fun clipboardCounts(records: List<SubjectCount>): ClipboardCounts? {
    var counts: ClipboardCounts? = null
    for (record in records) {
        val use = clipboardUse(record.path) ?: continue
        val sum = counts ?: ClipboardCounts()
        counts = when (use) {
            ClipboardUse.READ -> sum.copy(read = sum.read + record.allowed, readDenied = sum.readDenied + record.denied)
            ClipboardUse.LISTEN -> sum.copy(listened = sum.listened + record.allowed)
            ClipboardUse.WRITE -> sum.copy(written = sum.written + record.allowed, writeDenied = sum.writeDenied + record.denied)
        }
    }
    return counts
}

private const val SENSOR_ENABLE = "binder:android.gui.SensorEventConnection/enableDisable"

/** Marks a sensor turned on at a high rate: `...enableDisable@<type>:fast`. */
private const val FAST = "fast"

/** A sensor an app turned on. */
data class SensorUse(
    /**
     * The sensor's type as the system names it (`android.sensor.gyroscope`),
     * a vendor's name, a number, or empty if it could not be identified.
     */
    val type: String,
    /** Whether the app asked for 200 events a second or more. */
    val highRate: Boolean,
)

/**
 * The sensor a record turned on, from `...enableDisable@<type>[:fast]`;
 * null if the record is not of a sensor being turned on. The type follows
 * an `@` rather than the `#` of calls about another app, after which the
 * app reads a package name.
 */
fun sensorUse(subject: String): SensorUse? {
    val rest = subject.removePrefix(SENSOR_ENABLE).takeIf { it.length < subject.length } ?: return null
    if (rest.isEmpty()) return SensorUse(type = "", highRate = false)
    if (!rest.startsWith('@')) return null
    val parts = rest.drop(1).split(':')
    return SensorUse(type = parts.first(), highRate = FAST in parts.drop(1))
}

/** The sensors an app turned on, and how many times in all. */
data class SensorsUsed(
    /** Each sensor once, the most often turned on first; high rate if it ever was. */
    val sensors: List<SensorUse>,
    val times: Int,
)

/** The sensors [records] turned on; null if none of them turned one on. */
fun sensorsUsed(records: List<SubjectCount>): SensorsUsed? {
    val used = records.mapNotNull { record -> sensorUse(record.path)?.let { it to record.allowed } }
    if (used.isEmpty()) return null
    val sensors = used.groupBy { it.first.type }
        .map { (type, uses) -> Triple(type, uses.any { it.first.highRate }, uses.sumOf { it.second }) }
        // A sensor that could not be identified goes last.
        .sortedWith(compareBy<Triple<String, Boolean, Int>> { it.first.isEmpty() }.thenByDescending { it.third }.thenBy { it.first })
        .map { (type, highRate, _) -> SensorUse(type, highRate) }
    return SensorsUsed(sensors, times = used.sumOf { it.second })
}
