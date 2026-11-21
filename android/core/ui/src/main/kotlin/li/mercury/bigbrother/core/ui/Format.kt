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
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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

// Made for each use: a formatter keeps the locale it was made in, and the
// user can change the language while the app runs.
private val dateTimeFormat: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)

/** Date and time to the second, in the device's locale and time zone. */
fun exactDateTime(epochSeconds: Long): String = dateTimeFormat.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))

/** A storage volume's root: `/storage/emulated/<user>` or `/storage/<volume id>`. */
val VOLUME_ROOT = Regex("^/storage/(?:emulated/\\d+|[^/]+)")

/**
 * When something happened, for the end of a list row: the time of day for
 * today, otherwise the date, in the device's locale and time zone. The year
 * is shown only when it is not the current one.
 */
fun shortTime(context: Context, epochSeconds: Long): String {
    val millis = epochSeconds * 1000
    val flags = if (DateUtils.isToday(millis)) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        ABBREVIATED_DATE
    }
    return DateUtils.formatDateTime(context, millis, flags)
}

/** [shortTime] in composition. */
@Composable
fun shortTime(epochSeconds: Long): String = shortTime(LocalContext.current, epochSeconds)

/** Time of day, optionally with an abbreviated date, in the device's locale and time zone. */
fun timeOfDay(context: Context, epochSeconds: Long, includeDate: Boolean = false): String {
    val flags = DateUtils.FORMAT_SHOW_TIME or if (includeDate) ABBREVIATED_DATE else 0
    return DateUtils.formatDateTime(context, epochSeconds * 1000, flags)
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
