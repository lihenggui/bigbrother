package li.mercury.bigbrother.feature.timeline.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelineEntry
import li.mercury.bigbrother.core.ui.DataCategory

class TimelineItemsTest {
    private val day = 86_400L

    private fun entry(
        hour: Long,
        uid: Int = 10100,
        category: Category = Category.STORAGE,
        topic: String? = null,
        filesRead: Int = 1,
        pkg: String = "com.example",
    ) = TimelineEntry(
        hourStart = hour,
        uid = uid,
        packageName = pkg,
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

    private fun items(entries: List<TimelineEntry>, filter: TimelineFilter = TimelineFilter()) =
        timelineItems(entries, filter, emptyMap(), showSystemApps = false, ownPackage = "me") { it - it % day }

    @Test
    fun entries_are_grouped_by_day_then_hour() {
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
    fun an_apps_entries_in_an_hour_share_a_row_most_sensitive_first() {
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
    fun lookups_and_own_records_are_left_out_unless_asked_for() {
        val entries = listOf(
            entry(3600, filesRead = 0),
            entry(3600, uid = 10200, pkg = "me"),
            entry(3600, uid = 10300, category = Category.DEVICE, topic = "android_id", filesRead = 0),
        )
        assertEquals(1, items(entries).count { it is TimelineItem.Row })
        assertEquals(2, items(entries, TimelineFilter(includeLookups = true)).count { it is TimelineItem.Row })
    }

    @Test
    fun category_filter_matches_the_entries_data_category() {
        val entries = listOf(
            entry(3600),
            entry(3600, uid = 10300, category = Category.DEVICE, topic = "android_id"),
        )
        val rows = items(entries, TimelineFilter(category = DataCategory.DEVICE_IDS)).filterIsInstance<TimelineItem.Row>()
        assertEquals(listOf(10300), rows.map { it.first.uid })
    }

    @Test
    fun the_range_opened_covers_the_entry() {
        val now = 100 * day
        assertEquals(TimeRange.HOUR, rangeCovering(entry(now - 1800), now))
        assertEquals(TimeRange.DAY, rangeCovering(entry(now - 5 * 3600), now))
        assertEquals(TimeRange.WEEK, rangeCovering(entry(now - 3 * day), now))
        assertEquals(TimeRange.ALL, rangeCovering(entry(now - 20 * day), now))
    }
}
