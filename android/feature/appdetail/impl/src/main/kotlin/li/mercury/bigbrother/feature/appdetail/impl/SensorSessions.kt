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

package li.mercury.bigbrother.feature.appdetail.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.model.SensorSession
import li.mercury.bigbrother.core.ui.sensorName
import li.mercury.bigbrother.core.ui.timeOfDay
import li.mercury.bigbrother.core.ui.timeWithDateIfNotToday
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import li.mercury.bigbrother.core.ui.R as UiR

/** The sessions of one sensor, most recent first. */
internal class SensorSessionGroup(val sensor: String, val sessions: List<SensorSession>) {
    /** Whether the app ever asked this sensor for a high rate. */
    val highRate: Boolean get() = sessions.any { it.highRate }

    /** How long the sensor was on in all, in seconds; sessions still going count up to [now]. */
    fun totalSeconds(now: Long): Long = sessions.sumOf { it.seconds(now) }
}

/** How long a session lasted, in seconds, as [shownDuration] counts it; one still going, up to [now]. */
internal fun SensorSession.seconds(now: Long): Long = shownDuration(start, end ?: now)

/**
 * How long from [start] to [end] reads, in seconds: under a minute, as it
 * was; from a minute on, the minutes between the times as they are shown,
 * to the minute, so that "2:02 PM – 2:17 PM" lasts 15 minutes.
 */
internal fun shownDuration(start: Long, end: Long): Long {
    val seconds = (end - start).coerceAtLeast(0)
    if (seconds < SECONDS_PER_MINUTE) return seconds
    return (Math.floorDiv(end, SECONDS_PER_MINUTE) - Math.floorDiv(start, SECONDS_PER_MINUTE)) * SECONDS_PER_MINUTE
}

/**
 * Sessions grouped by sensor: the sensor used most recently first, each
 * group's sessions in the order given, which is most recent first.
 */
internal fun groupSessions(sessions: List<SensorSession>): List<SensorSessionGroup> = sessions
    .sortedByDescending { it.start }
    .groupBy { it.sensor }
    .map { (sensor, group) -> SensorSessionGroup(sensor, group) }

/** A length of time in the units it reads best in: seconds, minutes, hours and minutes, or days and hours. */
internal data class DurationParts(val days: Long = 0, val hours: Long = 0, val minutes: Long = 0, val seconds: Long = 0)

/** [totalSeconds] split for reading; past a minute to the minute, past a day rounded to the hour. */
internal fun durationParts(totalSeconds: Long): DurationParts {
    val seconds = totalSeconds.coerceAtLeast(0)
    if (seconds < SECONDS_PER_MINUTE) return DurationParts(seconds = seconds)
    val minutes = seconds / SECONDS_PER_MINUTE
    if (minutes < MINUTES_PER_HOUR) return DurationParts(minutes = minutes)
    if (minutes < MINUTES_PER_DAY) return DurationParts(hours = minutes / MINUTES_PER_HOUR, minutes = minutes % MINUTES_PER_HOUR)
    val hours = (minutes + MINUTES_PER_HOUR / 2) / MINUTES_PER_HOUR
    return DurationParts(days = hours / HOURS_PER_DAY, hours = hours % HOURS_PER_DAY)
}

/** A rate as a number: whole above 10 Hz, to a tenth below ("50", "0.5"), in [locale]'s digits. */
internal fun formatRate(hz: Double, locale: Locale): String {
    val format = NumberFormat.getNumberInstance(locale)
    format.maximumFractionDigits = if (hz >= WHOLE_RATE_FROM_HZ) 0 else 1
    return format.format(hz)
}

private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val MINUTES_PER_DAY = MINUTES_PER_HOUR * HOURS_PER_DAY
private const val WHOLE_RATE_FROM_HZ = 10.0

/** Sessions listed per sensor before the rest are asked for. */
private const val SESSIONS_SHOWN = 10

/** "15 min", "1 h 12 min", "45 s". */
@Composable
internal fun durationText(totalSeconds: Long): String {
    val parts = durationParts(totalSeconds)
    return when {
        parts.days > 0 && parts.hours > 0 -> stringResource(R.string.duration_days_hours, parts.days, parts.hours)
        parts.days > 0 -> stringResource(R.string.duration_days, parts.days)
        parts.hours > 0 && parts.minutes > 0 -> stringResource(R.string.duration_hours_minutes, parts.hours, parts.minutes)
        parts.hours > 0 -> stringResource(R.string.duration_hours, parts.hours)
        parts.minutes > 0 -> stringResource(R.string.duration_minutes, parts.minutes)
        else -> stringResource(R.string.duration_seconds, parts.seconds)
    }
}

/** The rate a session asked for: "50 Hz", "Fastest"; null for sensors that report on change or once. */
@Composable
private fun rateText(session: SensorSession): String? {
    val period = session.periodNs ?: return null
    val hz = session.rateHz
    return when {
        period == 0L -> stringResource(R.string.rate_fastest)
        hz != null -> stringResource(R.string.rate_hz, formatRate(hz, LocalLocale.current.platformLocale))
        else -> null
    }
}

/**
 * One sensor and when it was on: the sensor, how many times and how long in
 * all; tapped, each time it was on.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SensorGroup(group: SensorSessionGroup, now: Long, initiallyExpanded: Boolean, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(group.sensor) { mutableStateOf(initiallyExpanded) }
    var showAll by rememberSaveable(group.sensor) { mutableStateOf(false) }
    val shown = when {
        !expanded -> emptyList()
        showAll -> group.sessions
        else -> group.sessions.take(SESSIONS_SHOWN)
    }
    val separator = stringResource(R.string.time_separator)
    val state = stringResource(if (expanded) R.string.expanded else R.string.collapsed)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        ListItem(
            onClick = { expanded = !expanded },
            modifier = Modifier.semantics { stateDescription = state },
            shapes = ListItemDefaults.segmentedShapes(0, 1 + shown.size),
            colors = ListItemDefaults.segmentedColors(),
            supportingContent = {
                val times = pluralStringResource(UiR.plurals.sensor_times, group.sessions.size, group.sessions.size)
                Text(times + separator + stringResource(R.string.sensor_total, durationText(group.totalSeconds(now))))
            },
            trailingContent = {
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            },
        ) {
            Text(sensorName(group.sensor, group.highRate))
        }
        shown.forEachIndexed { index, session -> SessionItem(session, now, index + 1, 1 + shown.size) }
        if (expanded && shown.size < group.sessions.size) {
            TextButton(onClick = { showAll = true }, contentPadding = PaddingValues(horizontal = 16.dp)) {
                Text(stringResource(R.string.show_all_details, group.sessions.size))
            }
        }
    }
}

/** One time a sensor was on: from when to when, how long, at what rate. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SessionItem(session: SensorSession, now: Long, index: Int, count: Int) {
    val end = session.end
    val details = listOfNotNull(
        if (end == null) stringResource(R.string.sensor_still_on) else durationText(session.seconds(now)),
        rateText(session),
        if (session.highRate) stringResource(R.string.high_rate) else null,
    )
    ListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        trailingContent = if (session.highRate) {
            { Icon(Icons.Outlined.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) }
        } else {
            null
        },
        supportingContent = { Text(details.joinToString(stringResource(R.string.time_separator))) },
    ) {
        Text(sessionSpan(session.start, end))
    }
}

/**
 * When a session was: "2:02 PM – 2:17 PM", just "2:02 PM" when it began and
 * ended within that minute (its duration says how long), or "Since 2:02 PM"
 * while it goes on. A day other than today is given with its date, as is an
 * end on another day than the start.
 */
@Composable
private fun sessionSpan(start: Long, end: Long?): String {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    fun day(time: Long) = Instant.ofEpochSecond(time).atZone(zone).toLocalDate()
    val from = timeWithDateIfNotToday(start)
    if (end == null) return stringResource(R.string.sensor_since, from)
    val to = timeOfDay(context, end, includeDate = day(end) != day(start))
    return if (to == from) from else stringResource(R.string.session_span, from, to)
}

/** Under the sessions: the rates are those the app asked for. */
@Composable
internal fun SensorSessionsNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.sensor_sessions_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
