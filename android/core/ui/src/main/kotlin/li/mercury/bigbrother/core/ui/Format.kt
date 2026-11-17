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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "5 minutes ago", localized by the platform. [epochSeconds] is a Unix
 * timestamp. Anything under a minute old reads "Moments ago" rather than the
 * platform's "0 minutes ago".
 */
fun relativeTime(epochSeconds: Long): String {
    val now = System.currentTimeMillis()
    val then = epochSeconds * 1000
    val resolution = if (now - then < DateUtils.MINUTE_IN_MILLIS) {
        // At second resolution the platform says "Moments ago" for recent times.
        DateUtils.SECOND_IN_MILLIS
    } else {
        DateUtils.MINUTE_IN_MILLIS
    }
    return DateUtils.getRelativeTimeSpanString(then, now, resolution).toString()
}

// Made for each use: a formatter keeps the locale it was made in, and the
// user can change the language while the app runs.
private val dateTimeFormat: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
private val timeFormat: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)

private fun localTime(epochSeconds: Long) = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())

/** Date and time to the second, in the device's locale and time zone. */
fun exactDateTime(epochSeconds: Long): String = dateTimeFormat.format(localTime(epochSeconds))

/** Time of day to the second, in the device's locale and time zone. */
fun exactTime(epochSeconds: Long): String = timeFormat.format(localTime(epochSeconds))

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
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    }
    return DateUtils.formatDateTime(context, millis, flags)
}

/** [shortTime] in composition. */
@Composable
fun shortTime(epochSeconds: Long): String = shortTime(LocalContext.current, epochSeconds)

/**
 * How long ago, in as few characters as possible ("5m", "3h", "2d"), for
 * places where the time shares a row with other text.
 */
@Composable
fun compactTime(epochSeconds: Long): String {
    val seconds = (System.currentTimeMillis() / 1000 - epochSeconds).coerceAtLeast(0)
    return when {
        seconds < 60 -> stringResource(R.string.time_now)
        seconds < 3600 -> stringResource(R.string.time_minutes, seconds / 60)
        seconds < 86_400 -> stringResource(R.string.time_hours, seconds / 3600)
        else -> stringResource(R.string.time_days, seconds / 86_400)
    }
}
