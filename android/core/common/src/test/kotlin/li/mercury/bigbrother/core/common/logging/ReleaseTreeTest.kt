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

package li.mercury.bigbrother.core.common.logging

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseTreeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val day = LocalDate.of(2026, 11, 16)

    // The writer runs for as long as the tree lives: in a test, until it ends.
    private fun TestScope.tree() = ReleaseTree(
        filesDir = folder.root,
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        today = { day },
        now = { day.atTime(9, 30) },
    )

    private fun logFile(date: LocalDate = day) = File(folder.root, "$LOG_DIR/$date.log")

    @Test
    fun givenSeveralLines_whenLogged_thenWrittenInOrderToTodaysFile() = runTest {
        val tree = tree()
        tree.i("first")
        tree.w("second %d", 2)
        // The writer is background work, which advanceUntilIdle() leaves alone.
        runCurrent()

        assertEquals(
            listOf("2026-11-16 09:30:00.000 I first", "2026-11-16 09:30:00.000 W second 2"),
            logFile().readLines(),
        )
    }

    @Test
    fun givenVerboseLine_whenLogged_thenLeftOut() = runTest {
        val tree = tree()
        tree.v("verbose")
        tree.d("debug")
        runCurrent()

        assertEquals(listOf("2026-11-16 09:30:00.000 D debug"), logFile().readLines())
    }

    @Test
    fun givenMoreFilesThanKept_whenStarted_thenOldestDeleted() = runTest {
        val directory = File(folder.root, LOG_DIR).apply { mkdirs() }
        repeat(ReleaseTree.DAYS_KEPT + 2) { File(directory, "${day.minusDays(it.toLong() + 1)}.log").writeText("x") }

        tree()
        runCurrent()

        val kept = directory.list().orEmpty().sorted()
        assertEquals(ReleaseTree.DAYS_KEPT, kept.size)
        assertEquals("${day.minusDays(1)}.log", kept.last())
    }

    @Test
    fun givenQueuedLines_whenCrashLogged_thenWrittenAtOnceAfterThem() = runTest {
        val tree = tree()
        tree.i("before")

        // Nothing has run the writer: the crash writes the queue, then itself.
        tree.logCrash(Thread.currentThread(), IllegalStateException("boom"))

        val lines = logFile().readLines()
        assertEquals("2026-11-16 09:30:00.000 I before", lines[0])
        assertTrue(lines[1].startsWith("2026-11-16 09:30:00.000 E Crash on thread"))
        assertTrue(lines.any { "IllegalStateException: boom" in it })
    }
}
