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

package li.mercury.bigbrother.core.data.repository

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.bbd.BbdAccess
import li.mercury.bigbrother.core.data.bbd.BbdAccessPeriod
import li.mercury.bigbrother.core.data.bbd.BbdAppSummary
import li.mercury.bigbrother.core.data.bbd.BbdPathMatch
import li.mercury.bigbrother.core.data.bbd.BbdRecorderStatus
import li.mercury.bigbrother.core.data.bbd.BbdSensorSession
import li.mercury.bigbrother.core.data.bbd.BbdSubjectCount
import li.mercury.bigbrother.core.data.bbd.BbdTimelineEntry
import li.mercury.bigbrother.core.data.bbd.BbdTimelinePage
import li.mercury.bigbrother.core.data.bbd.TestBbdDataSource
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.SensorSession
import li.mercury.bigbrother.core.model.SubjectCount
import li.mercury.bigbrother.core.model.TimeRange
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class RootAccessRepositoryTest {
    @Test
    fun givenFailedAppsRead_whenRetried_thenMapsAndCachesTheValidAnswer() = runTest {
        var calls = 0
        val dataSource = object : TestBbdDataSource() {
            override suspend fun getApps(range: TimeRange): List<BbdAppSummary> {
                if (++calls == 1) throw RecordsException.CommandFailed()
                return listOf(BbdAppSummary(10234, "com.example.notes", emptyMap(), 1, 0, emptyList(), 0, emptyList()))
            }
        }
        val repository = RootAccessRepository(dataSource, StandardTestDispatcher(testScheduler), backgroundScope)

        assertFailsWith<RecordsException.CommandFailed> { repository.getApps(TimeRange.ALL) }

        val apps = repository.getApps(TimeRange.ALL)
        assertEquals("com.example.notes", apps.single().packageName)
        assertSame(apps, repository.getApps(TimeRange.ALL))
        assertEquals(2, calls)
    }

    @Test
    fun givenPublishedStatus_whenReadingFails_thenClearsTheStatus() = runTest {
        var fail = false
        val dataSource = object : TestBbdDataSource() {
            override suspend fun getRecorderStatus(): BbdRecorderStatus {
                if (fail) throw RecordsException.ModuleMissing()
                return BbdRecorderStatus(true, mapOf("sensors" to -1, "net" to 0))
            }
        }
        val repository = RootAccessRepository(dataSource, StandardTestDispatcher(testScheduler), backgroundScope)

        val status = repository.getRecorderStatus()
        assertSame(status, repository.recorderStatus.value)
        assertEquals(listOf("net"), status.unavailable)

        fail = true
        assertFailsWith<RecordsException.ModuleMissing> { repository.getRecorderStatus() }
        assertNull(repository.recorderStatus.value)
    }

    @Test
    fun givenCachedApps_whenRefreshIsForced_thenPublishesNewRecords() = runTest {
        var reads = 0
        val dataSource = object : TestBbdDataSource() {
            override suspend fun getApps(range: TimeRange): List<BbdAppSummary> = listOf(BbdAppSummary(UID, PACKAGE, emptyMap(), (++reads).toLong(), 0, emptyList(), 0, emptyList()))
        }
        val repository = RootAccessRepository(dataSource, StandardTestDispatcher(testScheduler), backgroundScope)

        assertEquals(1L, repository.getApps(TimeRange.ALL).single().lastSeen)
        assertEquals(2L, repository.getApps(TimeRange.ALL, forceRefresh = true).single().lastSeen)
        assertEquals(2L, repository.getApps(TimeRange.ALL).single().lastSeen)
        assertEquals(2, reads)
    }

    @Test
    fun givenDifferentAccessPages_whenInterleavedAndRefreshed_thenMapsAndCachesEachQuery() = runTest {
        val offsets = mutableListOf<Int>()
        val dataSource = object : TestBbdDataSource() {
            override suspend fun getAccesses(uid: Int, category: Category?, range: TimeRange, limit: Int, offset: Int): List<BbdAccess> {
                assertEquals(UID, uid)
                assertEquals(Category.STORAGE, category)
                assertEquals(TimeRange.WEEK, range)
                assertEquals(20, limit)
                offsets += offset
                return listOf(access.copy(lastSeen = offsets.size.toLong()))
            }
        }
        val repository = RootAccessRepository(dataSource, StandardTestDispatcher(testScheduler), backgroundScope)

        val first = repository.getAccesses(UID, Category.STORAGE, TimeRange.WEEK, 20, 0).single()
        repository.getAccesses(UID, Category.STORAGE, TimeRange.WEEK, 20, 20)
        assertSame(first, repository.getAccesses(UID, Category.STORAGE, TimeRange.WEEK, 20, 0).single())
        val refreshed = repository.getAccesses(UID, Category.STORAGE, TimeRange.WEEK, 20, 0, forceRefresh = true).single()

        assertEquals(PATH, first.path)
        assertEquals(Action.READ, first.action)
        assertEquals(3L, refreshed.lastSeen)
        assertEquals(listOf(0, 20, 0), offsets)
    }

    @Test
    fun givenSearchFilters_whenSearchingPaths_thenForwardsFiltersAndMapsMatch() = runTest {
        val dataSource = object : TestBbdDataSource() {
            override suspend fun searchPaths(
                text: String,
                categories: Collection<Category>,
                uids: Collection<Int>,
                range: TimeRange,
                limit: Int,
            ): List<BbdPathMatch> {
                assertEquals("notes", text)
                assertEquals(setOf(Category.STORAGE), categories)
                assertEquals(listOf(UID), uids)
                assertEquals(TimeRange.WEEK, range)
                assertEquals(6, limit)
                return listOf(BbdPathMatch(UID, PACKAGE, access))
            }
        }
        val repository = RootAccessRepository(dataSource, StandardTestDispatcher(testScheduler), backgroundScope)

        val result = repository.searchPaths("notes", setOf(Category.STORAGE), listOf(UID), TimeRange.WEEK, 6).single()

        assertEquals(UID, result.uid)
        assertEquals(PACKAGE, result.packageName)
        assertEquals(PATH, result.access.path)
    }

    @Test
    fun givenTimelineCursor_whenReadingPage_thenMapsSubjectsAndNextCursor() = runTest {
        val dataSource = object : TestBbdDataSource() {
            override suspend fun getTimeline(range: TimeRange, before: Long?, limit: Int): BbdTimelinePage {
                assertEquals(TimeRange.DAY, range)
                assertEquals(7_200L, before)
                assertEquals(12, limit)
                val entry = BbdTimelineEntry(
                    3_600, UID, PACKAGE, Category.STORAGE, null, 1, 1, 2, 1, 1, 0, null, 3_601, 3_602,
                    listOf(BbdSubjectCount(PATH, 2, 1)),
                )
                return BbdTimelinePage(listOf(entry), nextBefore = 3_600)
            }
        }
        val repository = RootAccessRepository(dataSource, StandardTestDispatcher(testScheduler), backgroundScope)

        val page = repository.getTimeline(TimeRange.DAY, before = 7_200, limit = 12)

        assertEquals(3_600L, page.nextBefore)
        assertEquals(UID, page.entries.single().uid)
        assertEquals(listOf(SubjectCount(PATH, 2, 1)), page.entries.single().subjects)
    }

    @Test
    fun givenImplicitAndExplicitSubjects_whenReadingHistory_thenUsesOnlyPathAsFallback() = runTest {
        val dataSource = object : TestBbdDataSource() {
            override suspend fun getHistory(uid: Int, paths: List<String>, range: TimeRange): List<BbdAccessPeriod> {
                assertEquals(UID, uid)
                assertEquals(PATH, paths.first())
                assertEquals(TimeRange.HOUR, range)
                return listOf(BbdAccessPeriod(1, 2, 0, 100, null), BbdAccessPeriod(1, 1, 0, 90, PATH))
            }
        }
        val repository = RootAccessRepository(dataSource, StandardTestDispatcher(testScheduler), backgroundScope)

        val single = repository.getHistory(UID, listOf(PATH), TimeRange.HOUR)
        val multiple = repository.getHistory(UID, listOf(PATH, "/sdcard/other"), TimeRange.HOUR)

        assertEquals(listOf(PATH, PATH), single.map { it.subject })
        assertEquals(listOf(null, PATH), multiple.map { it.subject })
        assertEquals(listOf(100L, 90L), single.map { it.time })
    }

    @Test
    fun givenActiveSensorSession_whenRead_thenMapsRateAndOpenEnd() = runTest {
        val dataSource = object : TestBbdDataSource() {
            override suspend fun getSensorSessions(uid: Int, range: TimeRange): List<BbdSensorSession> {
                assertEquals(UID, uid)
                assertEquals(TimeRange.DAY, range)
                return listOf(BbdSensorSession("accelerometer", true, 10_000_000, 100.0, 5, null))
            }
        }
        val repository = RootAccessRepository(dataSource, StandardTestDispatcher(testScheduler), backgroundScope)

        assertEquals(
            listOf(SensorSession("accelerometer", true, 10_000_000, 100.0, 5, null)),
            repository.getSensorSessions(UID, TimeRange.DAY),
        )
    }

    private val access = BbdAccess(Category.STORAGE, PATH, 1, items = 0, allowed = 3, denied = 0, lastSeen = 1, topic = null)

    private companion object {
        const val UID = 10234
        const val PACKAGE = "com.example.notes"
        const val PATH = "/sdcard/notes.txt"
    }
}
