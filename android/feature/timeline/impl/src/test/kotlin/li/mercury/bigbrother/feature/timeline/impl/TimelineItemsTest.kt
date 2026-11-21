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

import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelineEntry
import li.mercury.bigbrother.core.ui.DataCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineItemsTest {
    private val day = 86_400L

    private fun entry(
        hour: Long,
        uid: Int = 10100,
        category: Category = Category.STORAGE,
        topic: String? = null,
        filesRead: Int = 1,
    ) = TimelineEntry(
        hourStart = hour,
        uid = uid,
        packageName = "com.example",
        category = category,
        topic = topic,
        paths = 1,
        filesRead = filesRead,
        allowed = 1,
        denied = 0,
        modes = 1,
        items = 0,
        folder = null,
        firstSeen = hour,
        lastSeen = hour + 10,
    )

    private fun items(entries: List<TimelineEntry>, filter: TimelineFilter = TimelineFilter()) = timelineItems(entries, filter) { it - it % day }

    @Test
    fun givenEntriesOverSeveralDays_whenListed_thenGroupedByDayThenHour() {
        val keys = items(
            listOf(
                entry(day + 7200),
                entry(day + 7200, uid = 10200),
                entry(day + 3600),
                entry(3600),
            ),
        ).map { it::class.simpleName }
        assertEquals(listOf("Day", "Hour", "Row", "Row", "Hour", "Row", "Day", "Hour", "Row"), keys)
    }

    @Test
    fun givenAppEntriesInOneHour_whenListed_thenShareOneRowMostSensitiveFirst() {
        val rows = items(
            listOf(
                entry(3600, category = Category.DEVICE, topic = "android_id"),
                entry(3600, uid = 10200),
                entry(3600),
            ),
        ).filterIsInstance<TimelineItem.Row>()
        assertEquals(listOf(10100, 10200), rows.map { it.first.uid })
        assertEquals(listOf(DataCategory.FILES, DataCategory.DEVICE_IDS), rows[0].entries.map { it.dataCategory })
    }

    @Test
    fun givenLookups_whenListed_thenShownOnlyOnRequest() {
        val entries = listOf(
            entry(3600, filesRead = 0),
            entry(3600, uid = 10300, category = Category.DEVICE, topic = "android_id", filesRead = 0),
        )
        assertEquals(1, items(entries).count { it is TimelineItem.Row })
        assertEquals(2, items(entries, TimelineFilter(includeLookups = true)).count { it is TimelineItem.Row })
    }

    @Test
    fun givenCategoryFilter_whenListed_thenOnlyEntriesOfThatCategory() {
        val entries = listOf(
            entry(3600),
            entry(3600, uid = 10300, category = Category.DEVICE, topic = "android_id"),
        )
        val rows = items(entries, TimelineFilter(category = DataCategory.DEVICE_IDS)).filterIsInstance<TimelineItem.Row>()
        assertEquals(listOf(10300), rows.map { it.first.uid })
    }

    @Test
    fun givenEntryAge_whenOpened_thenSmallestRangeCoversIt() {
        val now = 100 * day
        assertEquals(TimeRange.HOUR, rangeCovering(entry(now - 1800), now))
        assertEquals(TimeRange.DAY, rangeCovering(entry(now - 5 * 3600), now))
        assertEquals(TimeRange.WEEK, rangeCovering(entry(now - 3 * day), now))
        assertEquals(TimeRange.ALL, rangeCovering(entry(now - 20 * day), now))
    }
}
