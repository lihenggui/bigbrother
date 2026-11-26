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

package li.mercury.bigbrother.hook

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartGuardTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private var time = MINUTES.toMillis(10)
    private val record get() = File(temporary.root, "starts")
    private val guard get() = StartGuard(temporary.root) { time }

    @Test
    fun givenThreeStartsInTheWindow_whenStartingAgain_thenRefusalLeavesTheRecordUntouched() {
        repeat(3) {
            assertTrue(guard.allowStart())
            time++
        }
        val recorded = record.readLines()
        assertEquals(3, recorded.size)

        assertFalse(guard.allowStart())
        assertEquals(recorded, record.readLines())
    }

    @Test
    fun givenAFullWindow_whenItsStartsExpire_thenTheNextStartIsRecorded() {
        repeat(3) { assertTrue(guard.allowStart()) }
        time += MINUTES.toMillis(5)
        assertFalse(guard.allowStart())

        time++
        assertTrue(guard.allowStart())
        assertEquals(listOf(time.toString()), record.readLines())
    }

    @Test
    fun givenMalformedAndFutureTimestamps_whenStarting_thenOnlyRecentValidTimestampsCount() {
        val recent = time - 1
        record.writeText("broken\n-1\n${time + 1}\n9223372036854775808\n$recent")

        assertTrue(guard.allowStart())
        assertEquals(listOf(recent.toString(), time.toString()), record.readLines())
    }

    @Test
    fun givenAnUnreadableDirectoryPath_whenStarting_thenTheGuardFailsClosed() {
        val file = temporary.newFile()
        val inaccessible = File(file, "not-a-directory")

        assertFalse(StartGuard(inaccessible).allowStart())
        assertFalse(inaccessible.exists())
    }

    @Test
    fun givenConcurrentStartsInOneDirectory_whenAllTryToRecord_thenOnlyThreeAreAllowed() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val attempts = (1..4).map { executor.submit<Boolean> { guard.allowStart() } }

            assertEquals(3, attempts.count { it.get(5, SECONDS) })
            assertEquals(3, record.readLines().size)
        } finally {
            executor.shutdownNow()
        }
    }
}
