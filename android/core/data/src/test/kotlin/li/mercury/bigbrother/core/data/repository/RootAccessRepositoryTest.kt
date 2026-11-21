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
import li.mercury.bigbrother.core.data.bbd.BbdAppSummary
import li.mercury.bigbrother.core.data.bbd.BbdRecorderStatus
import li.mercury.bigbrother.core.data.bbd.TestBbdDataSource
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
                if (++calls == 1) throw RecordsException.CommandFailed("read failed")
                return listOf(BbdAppSummary(10234, "com.example.notes", emptyMap(), 1, 0, emptyList(), 0, emptyList()))
            }
        }
        val repository = RootAccessRepository(dataSource, StandardTestDispatcher(testScheduler))

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
        val repository = RootAccessRepository(dataSource, StandardTestDispatcher(testScheduler))

        val status = repository.getRecorderStatus()
        assertSame(status, repository.recorderStatus.value)
        assertEquals(listOf("net"), status.unavailable)

        fail = true
        assertFailsWith<RecordsException.ModuleMissing> { repository.getRecorderStatus() }
        assertNull(repository.recorderStatus.value)
    }
}
