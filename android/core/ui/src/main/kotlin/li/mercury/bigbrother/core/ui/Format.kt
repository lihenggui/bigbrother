package li.mercury.bigbrother.core.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
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

private val dateTimeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)

private fun localTime(epochSeconds: Long) =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())

/** Date and time to the second, in the device's locale and time zone. */
fun exactDateTime(epochSeconds: Long): String = dateTimeFormat.format(localTime(epochSeconds))

/** Time of day to the second, in the device's locale and time zone. */
fun exactTime(epochSeconds: Long): String = timeFormat.format(localTime(epochSeconds))

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
