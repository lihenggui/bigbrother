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

package li.mercury.bigbrother.feature.timeline.impl

import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelineEntry
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.dataCategoryOf
import java.time.Instant
import java.time.ZoneId

/** What the timeline shows. */
data class TimelineFilter(
    /** Only this kind of data; null for all. */
    val category: DataCategory? = null,
    /**
     * Also entries in which an app only looked for files: listed folders,
     * probed paths, watched them, or was refused. Nearly every app does it,
     * so it is left out unless asked for.
     */
    val includeLookups: Boolean = false,
)

/** The data category of a timeline entry. */
val TimelineEntry.dataCategory: DataCategory get() = dataCategoryOf(topic, category)

/** The app of an entry, as the list rows show it. */
fun TimelineEntry.toAppSummary(): AppSummary = AppSummary(uid = uid, packageName = packageName, counts = emptyMap(), lastSeen = lastSeen)

/** One row of the timeline, in order: a day, an hour within it, or an entry. */
sealed interface TimelineItem {
    val key: String

    data class Day(val dayStart: Long) : TimelineItem {
        override val key: String get() = "day:$dayStart"
    }

    data class Hour(val hourStart: Long) : TimelineItem {
        override val key: String get() = "hour:$hourStart"
    }

    /**
     * What one app did within the hour: its entries, the most sensitive
     * kind of data first.
     */
    data class Row(val entries: List<TimelineEntry>, val index: Int, val count: Int) : TimelineItem {
        val first: TimelineEntry get() = entries.first()
        val lastSeen: Long get() = entries.maxOf { it.lastSeen }

        override val key: String get() = "app:${first.hourStart}:${first.uid}"
    }
}

/**
 * The visible entries [filter] lets through, arranged under a header per
 * day and per hour. [dayStartOf] gives the start of the local day of a time.
 */
fun timelineItems(
    entries: List<TimelineEntry>,
    filter: TimelineFilter,
    dayStartOf: (Long) -> Long,
): List<TimelineItem> {
    val shown = entries.filter { entry ->
        (filter.category == null || entry.dataCategory == filter.category) &&
            (filter.includeLookups || !entry.onlyLookedForFiles)
    }
    return buildList {
        var day: Long? = null
        for ((hour, inHour) in shown.groupBy { it.hourStart }) {
            val dayStart = dayStartOf(hour)
            if (dayStart != day) {
                add(TimelineItem.Day(dayStart))
                day = dayStart
            }
            add(TimelineItem.Hour(hour))
            // One row per app, in the order of its latest activity.
            val apps = inHour.groupBy { it.uid }.values
                .map { mine -> mine.sortedWith(compareBy<TimelineEntry> { it.dataCategory }.thenByDescending { it.lastSeen }) }
                .sortedByDescending { mine -> mine.maxOf { it.lastSeen } }
            apps.forEachIndexed { index, mine -> add(TimelineItem.Row(mine, index, apps.size)) }
        }
    }
}

/** The shortest time range that still covers an entry, for opening its app. */
fun rangeCovering(entry: TimelineEntry, now: Long): TimeRange {
    val age = now - entry.hourStart
    return TimeRange.entries
        .filter { it != TimeRange.ALL }
        .firstOrNull { age <= it.hours * 3600L }
        ?: TimeRange.ALL
}

internal data class TimelinePresentation(
    val state: TimelineUiState = TimelineUiState(),
    val filter: TimelineFilter = TimelineFilter(),
    val items: List<TimelineItem>? = null,
)

internal fun TimelineUiState.present(filter: TimelineFilter, zone: ZoneId): TimelinePresentation = TimelinePresentation(
    state = this,
    filter = filter,
    items = entries?.let { records ->
        timelineItems(records, filter) { ts ->
            Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
        }
    },
)
