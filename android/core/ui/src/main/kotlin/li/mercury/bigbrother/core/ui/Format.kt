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

package li.mercury.bigbrother.core.ui

import android.content.Context
import android.icu.text.DateFormat
import android.icu.util.TimeZone
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import android.text.format.DateFormat as AndroidDateFormat

/** Wall clock for timestamps shown as elapsed time. */
val LocalWallClock = compositionLocalOf { Clock.systemUTC() }

/**
 * "5 minutes ago", localized by the platform. [epochSeconds] is a Unix
 * timestamp. Anything under a minute old reads "Moments ago" rather than the
 * platform's "0 minutes ago".
 */
fun relativeTime(epochSeconds: Long, nowMillis: Long): String {
    val then = epochSeconds * 1000
    val resolution = if (nowMillis - then < DateUtils.MINUTE_IN_MILLIS) {
        // At second resolution the platform says "Moments ago" for recent times.
        DateUtils.SECOND_IN_MILLIS
    } else {
        DateUtils.MINUTE_IN_MILLIS
    }
    return DateUtils.getRelativeTimeSpanString(then, nowMillis, resolution).toString()
}

/**
 * A local clock time with the app's locale and the user's clock preference.
 * [hourOnlyIn12HourCycle] shortens whole-hour labels while keeping the day period.
 */
fun formatClockTime(
    context: Context,
    time: Instant,
    includeSeconds: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    hourOnlyIn12HourCycle: Boolean = false,
): String = formatDateAndClock(
    time,
    context.resources.configuration.locales[0],
    zone,
    AndroidDateFormat.is24HourFormat(context),
    includeSeconds,
    hourOnlyIn12HourCycle = hourOnlyIn12HourCycle,
)

private fun formatDateAndClock(
    time: Instant,
    locale: Locale,
    zone: ZoneId,
    is24Hour: Boolean,
    includeSeconds: Boolean,
    dateSkeleton: String = "",
    hourOnlyIn12HourCycle: Boolean = false,
): String {
    val hours = if (is24Hour) "H" else "h"
    val minutesAndSeconds = when {
        includeSeconds -> "ms"
        hourOnlyIn12HourCycle && !is24Hour -> ""
        else -> "m"
    }
    val clockSkeleton = hours + minutesAndSeconds
    val formatter = DateFormat.getInstanceForSkeleton(dateSkeleton + clockSkeleton, locale)
    // Java supplies the GMT prefix that ICU requires for a fixed offset.
    formatter.timeZone = TimeZone.getTimeZone(java.util.TimeZone.getTimeZone(zone).id)
    return formatter.format(Date.from(time))
}

/** Date and time to the second, in the app's locale and device time zone. */
@Composable
fun exactDateTime(epochSeconds: Long): String = exactDateTime(
    epochSeconds,
    LocalConfiguration.current.locales[0],
    ZoneId.systemDefault(),
    AndroidDateFormat.is24HourFormat(LocalContext.current),
)

/** [exactDateTime] for UI that already has the formatting preferences. */
fun exactDateTime(epochSeconds: Long, locale: Locale, zone: ZoneId, is24Hour: Boolean): String = formatDateAndClock(
    Instant.ofEpochSecond(epochSeconds),
    locale,
    zone,
    is24Hour,
    includeSeconds = true,
    dateSkeleton = "yMMMd",
)

/** A storage volume's root: `/storage/emulated/<user>` or `/storage/<volume id>`. */
val VOLUME_ROOT = Regex("^/storage/(?:emulated/\\d+|[^/]+)")

/**
 * When something happened, for the end of a list row: the time of day for
 * today, otherwise the date, in the device's locale and time zone. The year
 * is shown only when it is not the current one.
 */
fun shortTime(context: Context, epochSeconds: Long): String {
    val millis = epochSeconds * 1000
    return if (DateUtils.isToday(millis)) timeOfDay(context, epochSeconds) else DateUtils.formatDateTime(context, millis, ABBREVIATED_DATE)
}

/** [shortTime] in composition. */
@Composable
fun shortTime(epochSeconds: Long): String = shortTime(LocalContext.current, epochSeconds)

/** Time of day, optionally with an abbreviated date, in the device's locale and time zone. */
fun timeOfDay(context: Context, epochSeconds: Long, includeDate: Boolean = false): String {
    val time = Instant.ofEpochSecond(epochSeconds)
    val zone = ZoneId.systemDefault()
    if (!includeDate) return formatClockTime(context, time, zone = zone)
    val dateSkeleton = if (time.atZone(zone).year == LocalDate.now(zone).year) "MMMd" else "yMMMd"
    return formatDateAndClock(
        time,
        context.resources.configuration.locales[0],
        zone,
        AndroidDateFormat.is24HourFormat(context),
        includeSeconds = false,
        dateSkeleton = dateSkeleton,
    )
}

/** Time of day, with the date added for days other than today. */
@Composable
fun timeWithDateIfNotToday(epochSeconds: Long): String = timeOfDay(
    LocalContext.current,
    epochSeconds,
    includeDate = !DateUtils.isToday(epochSeconds * 1000),
)

/** A date with its weekday, abbreviated in the device's locale. */
@Composable
fun dateWithWeekday(epochSeconds: Long): String = DateUtils.formatDateTime(
    LocalContext.current,
    epochSeconds * 1000,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
)

private const val ABBREVIATED_DATE = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
