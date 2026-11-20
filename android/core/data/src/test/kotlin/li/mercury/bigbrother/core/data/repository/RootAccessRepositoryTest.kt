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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.CommandResult
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.model.TimeRange
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class RootAccessRepositoryTest {
    @Test
    fun givenManyAccesses_whenRead_thenMappingWaitsForDefaultDispatcher() = runTest {
        val output = (1..5_000).joinToString(prefix = "[", postfix = "]") { i ->
            """{"cat":"storage","path":"/storage/emulated/0/Download/file-$i.txt","mode":1,"ok":1,"fail":0,"last_ts":1}"""
        }
        val repository = RootAccessRepository(TestRootShell(output), StandardTestDispatcher(testScheduler))
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            repository.getAccesses(10234, null, TimeRange.ALL, limit = 5_000, offset = 0)
        }

        // The shell fake returns immediately, so the repository must dispatch the work.
        assertFalse(read.isCompleted)
        val accesses = read.await()
        assertEquals(5_000, accesses.size)
        assertEquals("/storage/emulated/0/Download/file-5000.txt", accesses.last().path)
    }

    @Test
    fun givenMalformedApps_whenReadRetried_thenMapsAndCachesTheValidAnswer() = runTest {
        val shell = TestRootShell("""{"apps":[]}""")
        val repository = RootAccessRepository(shell, StandardTestDispatcher(testScheduler))

        assertFailsWith<RecordsException.CommandFailed> { repository.getApps(TimeRange.ALL) }

        shell.output = """[{"uid":10234,"pkg":"com.example.notes","last_ts":1,"files_read":0}]"""
        val apps = repository.getApps(TimeRange.ALL)
        assertEquals("com.example.notes", apps.single().packageName)
        assertSame(apps, repository.getApps(TimeRange.ALL))
        assertEquals(2, shell.calls)
    }

    private class TestRootShell(var output: String) : RootShell(Dispatchers.Unconfined) {
        var calls = 0
            private set

        override suspend fun run(command: String, label: String): CommandResult {
            calls++
            return CommandResult(isSuccess = true, code = 0, output = output, error = "")
        }
    }
}
