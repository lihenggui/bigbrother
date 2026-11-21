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

package li.mercury.bigbrother.core.data.bbd

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.CommandResult
import li.mercury.bigbrother.core.data.root.ModulePaths.BBD
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.TimeRange
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class BbdAccessTest {
    @Test
    fun givenFiles_whenRead_thenRunsAndDecodesOnIoDispatcher() = runTest {
        val shell = TestBbdRootShell(bbdFixture("files"))
        val source = RootShellBbdDataSource(shell, StandardTestDispatcher(testScheduler))
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            source.getAccesses(10234, Category.STORAGE, TimeRange.HOUR, limit = 100, offset = 5)
        }

        // The shell fake can answer immediately, so the dispatch comes from the data source.
        assertFalse(read.isCompleted)
        assertEquals(emptyList(), shell.lines)
        assertEquals("/storage/emulated/0/Download/file.txt", read.await().single().path)
        assertContains(shell.lines.single(), "bbd files --uid 10234 --limit 100 --offset 5 --cat storage --hours 1;")
        assertEquals("bbd files", shell.labels.single())
    }

    @Test
    fun givenApps_whenRead_thenDecodesCountsAndOptionalFields() = runTest {
        val shell = TestBbdRootShell(bbdFixture("apps"))

        val app = RootShellBbdDataSource(shell, Dispatchers.Unconfined).getApps(TimeRange.ALL).single()

        assertEquals("com.example.notes", app.packageName)
        assertEquals(3, app.counts[Category.STORAGE])
        assertEquals(2, app.filesRead)
        assertEquals(listOf("Download"), app.folders)
        assertEquals(listOf("camera"), app.topics)
        assertContains(shell.lines.single(), "bbd apps;")
    }

    @Test
    fun givenMalformedApps_whenRead_thenReportsBadResponse() = runTest {
        val source = RootShellBbdDataSource(TestBbdRootShell(bbdFixture("apps-malformed")), Dispatchers.Unconfined)

        assertFailsWith<RecordsException.BadResponse> { source.getApps(TimeRange.ALL) }
    }

    @Test
    fun givenPathQuery_whenSearched_thenQuotesTextAndPassesFiltersAndRange() = runTest {
        val shell = TestBbdRootShell(bbdFixture("files"))
        val source = RootShellBbdDataSource(shell, Dispatchers.Unconfined)

        val match = source.searchPaths("-it's a file", listOf(Category.STORAGE, Category.PROVIDER), listOf(1, 10234), TimeRange.DAY, 20).single()

        assertEquals(10234, match.uid)
        assertEquals("com.example.notes", match.packageName)
        assertContains(shell.lines.single(), """files --contains='-it'\''s a file' --cat storage --cat provider --uid 1 --uid 10234 --limit 20 --hours 24;""")
        assertEquals("bbd files", shell.labels.single())
    }

    @Test
    fun givenNoUids_whenSearchingPaths_thenSkipsDaemonCall() = runTest {
        val shell = TestBbdRootShell("")
        val source = RootShellBbdDataSource(shell, Dispatchers.Unconfined)

        assertEquals(emptyList(), source.searchPaths("file", emptyList(), emptyList(), TimeRange.ALL, 20))
        assertEquals(emptyList(), shell.lines)
    }

    @Test
    fun givenHistoryAndQuotedPath_whenRead_thenKeepsExplicitAndImplicitSubjects() = runTest {
        val shell = TestBbdRootShell(bbdFixture("history"))
        val source = RootShellBbdDataSource(shell, Dispatchers.Unconfined)

        val periods = source.getHistory(10234, listOf("/sdcard/it's here.txt"), TimeRange.DAY)

        assertEquals(listOf(null, "/sdcard/specified.txt"), periods.map { it.subject })
        assertEquals(listOf("/sdcard/it's here.txt", "/sdcard/specified.txt"), periods.map { it.asExternalModel("/sdcard/it's here.txt").subject })
        assertContains(shell.lines.single(), """history --uid 10234 --path='/sdcard/it'\''s here.txt' --hours 24;""")
    }

    @Test
    fun givenSensorSessions_whenRead_thenPreservesOpenEndAndUnreportedRate() = runTest {
        val shell = TestBbdRootShell(bbdFixture("sensors"))
        val source = RootShellBbdDataSource(shell, Dispatchers.Unconfined)

        val sessions = source.getSensorSessions(10234, TimeRange.WEEK)

        assertEquals(BbdSensorSession("android.sensor.accelerometer", true, 1_000_000, 1000.0, 10, null), sessions.first())
        assertEquals(BbdSensorSession("android.sensor.light", false, null, null, 5, 9), sessions.last())
        assertContains(shell.lines.single(), "sensors --uid 10234 --hours 168;")
    }

    @Test
    fun givenPagedTimeline_whenRead_thenPreservesCursorAndSubjects() = runTest {
        val shell = TestBbdRootShell(bbdFixture("timeline"))
        val source = RootShellBbdDataSource(shell, Dispatchers.Unconfined)

        val page = source.getTimeline(TimeRange.DAY, before = 7200, limit = 40)

        assertEquals(3600L, page.nextBefore)
        assertEquals(listOf(BbdSubjectCount("example", 1, 0)), page.entries.single().subjects)
        assertContains(shell.lines.single(), "timeline --limit 40 --before 7200 --hours 24;")
    }

    @Test
    fun givenRecorderCoverage_whenStatsRead_thenOnlyUnsupportedKindsAreShown() = runTest {
        val shell = TestBbdRootShell(bbdFixture("stats"))
        val source = RootShellBbdDataSource(shell, Dispatchers.Unconfined)

        val status = source.getRecorderStatus().asExternalModel()

        assertEquals(true, status.recording)
        assertEquals(listOf("binder", "sensors"), status.unavailable)
        assertContains(shell.lines.single(), "bbd stats;")
    }

    @Test
    fun givenStatsCommandFailed_whenGatheringDiagnostics_thenKeepsRawOutput() = runTest {
        val shell = TestBbdRootShell { label ->
            if (label == "daemon log") throw RecordsException.NoRoot()
            CommandResult(isSuccess = false, code = 1, output = "cannot read stats", error = "")
        }

        val diagnostics = RootShellBbdDataSource(shell, Dispatchers.Unconfined).getDiagnostics()

        assertNull(diagnostics.log)
        assertEquals("cannot read stats", diagnostics.stats)
        assertEquals(listOf("tail -n 5000 /data/adb/big_brother/bbd.log 2>&1", "$BBD stats 2>&1"), shell.lines)
    }
}
