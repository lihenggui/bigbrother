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

package li.mercury.bigbrother.feature.jail.impl.network

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.TimeRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

private const val UNIT = 1024.0

/**
 * An amount of data as B, KB, MB or GB, by powers of 1024: whole bytes and
 * kilobytes, larger amounts to one decimal.
 */
internal fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < UNIT) return "$bytes B"
    val kilobytes = (bytes / UNIT).roundToLong()
    if (kilobytes < UNIT) return "$kilobytes KB"
    val megabytes = bytes / (UNIT * UNIT)
    // Rounded to one decimal, 1023.96 MB would read 1024.0 MB.
    if (megabytes < UNIT - 0.05) return String.format(locale, "%.1f MB", megabytes)
    return String.format(locale, "%.1f GB", megabytes / UNIT)
}

/** The service usually on [port], for a port shown with its number; null where there is none worth naming. */
internal fun portService(port: Int): String? = when (port) {
    21 -> "FTP"
    22 -> "SSH"
    25, 465, 587 -> "SMTP"
    53 -> "DNS"
    80 -> "HTTP"
    110 -> "POP3"
    123 -> "NTP"
    143 -> "IMAP"
    443 -> "HTTPS"
    853 -> "DNS over TLS"
    993 -> "IMAPS"
    995 -> "POP3S"
    1883, 8883 -> "MQTT"
    3478 -> "STUN"
    else -> null
}

/** Whether a destination's port goes without saying in its row: the web's. */
internal fun isWebPort(port: Int): Boolean = port == 443 || port == 80

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** A period of [seconds] from [start], as "03:00–04:00" by the clock of [zone]; one ending at midnight ends at "00:00". */
internal fun hourRange(start: Long, seconds: Int, zone: ZoneId = ZoneId.systemDefault()): String {
    val from = Instant.ofEpochSecond(start).atZone(zone)
    val to = Instant.ofEpochSecond(start + seconds).atZone(zone)
    return CLOCK.format(from) + "–" + CLOCK.format(to)
}

/** The time of day, "15:00", by the clock of [zone]. */
internal fun clockTime(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String = CLOCK.format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

/** How far back a range of the activity reaches from [now]: "all" is as far as records are kept. */
internal fun TimeRange.since(now: Long): Long = now - when (this) {
    TimeRange.HOUR -> 3_600L
    TimeRange.DAY -> 86_400L
    TimeRange.WEEK -> 7 * 86_400L
    TimeRange.ALL -> RECORDS_KEPT_DAYS * 86_400L
}

/** How long the chart's bars are for a range: hours for a day, days beyond. */
internal val TimeRange.bucketSize: BucketSize get() = if (this == TimeRange.DAY || this == TimeRange.HOUR) BucketSize.HOUR else BucketSize.DAY

/** How long records are kept. */
internal const val RECORDS_KEPT_DAYS = 30

/** The ranges the activity can be shown for: a single hour is a single bar. */
internal val ACTIVITY_RANGES = listOf(TimeRange.DAY, TimeRange.WEEK, TimeRange.ALL)

/** When something happened: the time of day for today, the date and time otherwise, in the device's locale. */
internal fun dateTime(context: Context, epochSeconds: Long): String {
    val millis = epochSeconds * 1000
    val date = if (DateUtils.isToday(millis)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateTime(context, millis, date or DateUtils.FORMAT_SHOW_TIME)
}

@Composable
internal fun dateTime(epochSeconds: Long): String = dateTime(LocalContext.current, epochSeconds)

/** A day, "Nov 16", in the device's locale. */
@Composable
internal fun shortDate(epochSeconds: Long): String = DateUtils.formatDateTime(
    LocalContext.current,
    epochSeconds * 1000,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
)

/** A bar of the chart: its hours, after its day if that is not today; or its day. */
@Composable
internal fun periodLabel(start: Long, seconds: Int): String = when {
    seconds >= BucketSize.DAY.seconds -> shortDate(start)
    DateUtils.isToday(start * 1000) -> hourRange(start, seconds)
    else -> shortDate(start) + " " + hourRange(start, seconds)
}
