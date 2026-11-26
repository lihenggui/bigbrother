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
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.formatClockTime
import li.mercury.bigbrother.feature.jail.impl.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import kotlin.math.roundToLong

private const val UNIT = 1024.0

/**
 * An amount of data as B, KB, MB or GB, by powers of 1024: whole bytes and
 * kilobytes, larger amounts to one decimal.
 */
@Composable
internal fun formatBytes(bytes: Long): String = formatBytes(LocalContext.current, bytes)

internal fun formatBytes(context: Context, bytes: Long): String {
    if (bytes < UNIT) return context.getString(R.string.jail_net_size_bytes, bytes)
    val kilobytes = (bytes / UNIT).roundToLong()
    if (kilobytes < UNIT) return context.getString(R.string.jail_net_size_kilobytes, kilobytes)
    val megabytes = bytes / (UNIT * UNIT)
    // Rounded to one decimal, 1023.96 MB would read 1024.0 MB.
    if (megabytes < UNIT - 0.05) return context.getString(R.string.jail_net_size_megabytes, megabytes)
    return context.getString(R.string.jail_net_size_gigabytes, megabytes / UNIT)
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

/** A period of [seconds] from [start], in the user's hour cycle and [zone]. */
internal fun hourRange(context: Context, start: Long, seconds: Int, zone: ZoneId): String {
    val from = formatClockTime(context, Instant.ofEpochSecond(start), zone = zone)
    val to = formatClockTime(context, Instant.ofEpochSecond(start + seconds), zone = zone)
    return context.getString(R.string.jail_net_hour_range, from, to)
}

/** How far back a range of the activity reaches from [now]: "all" is as far as records are kept. */
internal fun TimeRange.since(now: Long): Long = now - when (this) {
    TimeRange.HOUR -> 3_600L
    TimeRange.DAY -> 86_400L
    TimeRange.WEEK -> 7 * 86_400L
    TimeRange.ALL -> RECORDS_KEPT_DAYS * 86_400L
}

/** How the chart groups a range: hours for a day, local days beyond. */
internal val TimeRange.bucketSize: BucketSize get() = if (this == TimeRange.DAY || this == TimeRange.HOUR) BucketSize.HOUR else BucketSize.DAY

/** How long records are kept. */
internal const val RECORDS_KEPT_DAYS = 30

/** The ranges the activity can be shown for: a single hour is a single bar. */
internal val ACTIVITY_RANGES = listOf(TimeRange.DAY, TimeRange.WEEK, TimeRange.ALL)

/** A day, "Nov 16", in the device's locale. */
@Composable
internal fun shortDate(epochSeconds: Long, zone: ZoneId): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale, zone) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
            .withDecimalStyle(DecimalStyle.of(locale))
            .withZone(zone)
    }
    return formatter.format(Instant.ofEpochSecond(epochSeconds))
}

/** A bar of the chart: its hours, after its day if that is not today; or its day. */
@Composable
internal fun periodLabel(start: Long, bucketSeconds: Int, zone: ZoneId): String = when {
    bucketSeconds == BucketSize.DAY.seconds -> shortDate(start, zone)

    Instant.ofEpochSecond(start).atZone(zone).toLocalDate() == LocalDate.now(zone) -> hourRange(LocalContext.current, start, bucketSeconds, zone)

    else -> stringResource(
        R.string.jail_net_dated_hour_range,
        shortDate(start, zone),
        hourRange(LocalContext.current, start, bucketSeconds, zone),
    )
}
