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
import li.mercury.bigbrother.core.data.bbd.BbdSandboxGlobal
import li.mercury.bigbrother.core.data.bbd.BbdSandboxReleaseResult
import li.mercury.bigbrother.core.data.bbd.BbdSandboxedApp
import li.mercury.bigbrother.core.data.bbd.TestBbdDataSource
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RootSandboxRepositoryTest {
    @Test
    fun givenSandboxState_whenRefreshed_thenPublishesListAndGlobal() = runTest {
        val dataSource = SandboxDataSource(failing = emptySet())
        val repository = RootSandboxRepository(dataSource, StandardTestDispatcher(testScheduler))

        repository.refresh()

        assertEquals("com.example.notes", repository.apps.value?.single()?.packageName)
        assertEquals(2, repository.apps.value?.single()?.networkRules)
        assertEquals(SandboxMode.OFF, repository.global.value?.get(SandboxItem.CAMERA))
        assertEquals(true, repository.autoIsolate.value)
        assertEquals(listOf("list", "get global"), dataSource.calls)
    }

    @Test
    fun givenGlobalUnreadable_whenRefreshed_thenKeepsPublishedAppList() = runTest {
        val repository = RootSandboxRepository(SandboxDataSource(setOf("get global")), StandardTestDispatcher(testScheduler))

        assertFailsWith<RecordsException.CommandFailed> { repository.refresh() }

        assertEquals("com.example.notes", repository.apps.value?.single()?.packageName)
        assertNull(repository.global.value)
        assertNull(repository.autoIsolate.value)
    }

    @Test
    fun givenListUnreadable_whenIsolated_thenChangeSucceedsAndListIsKept() = runTest {
        val dataSource = SandboxDataSource()
        val repository = RootSandboxRepository(dataSource, StandardTestDispatcher(testScheduler))

        repository.isolate("com.example.notes", userId = 0)

        assertEquals(listOf("enable", "list"), dataSource.calls)
        assertNull(repository.apps.value)
    }

    @Test
    fun givenListUnreadable_whenReleasedOrChanged_thenRefreshFailureIsTolerated() = runTest {
        val dataSource = SandboxDataSource()
        val repository = RootSandboxRepository(dataSource, StandardTestDispatcher(testScheduler))

        repository.release("com.example.notes", userId = 0)
        repository.setItems("com.example.notes", userId = 0, mapOf(SandboxItem.CAMERA to SandboxMode.OFF))
        repository.setGlobal(mapOf(SandboxItem.LOCATION to SandboxMode.COARSE))

        assertEquals(listOf("disable", "list", "set", "list", "set global", "list"), dataSource.calls)
    }

    @Test
    fun givenListUnreadable_whenAllReleased_thenReturnsTheRemainingCount() = runTest {
        val repository = RootSandboxRepository(SandboxDataSource(), StandardTestDispatcher(testScheduler))

        assertEquals(0, repository.releaseAll())
    }

    @Test
    fun givenMalformedReleaseCount_whenAllReleased_thenRefreshesBeforeReportingFailure() = runTest {
        val failure = RecordsException.BadResponse()
        val dataSource = SandboxDataSource(failing = emptySet(), releaseFailure = failure)
        val repository = RootSandboxRepository(dataSource, StandardTestDispatcher(testScheduler))

        assertEquals(failure, assertFailsWith<RecordsException.BadResponse> { repository.releaseAll() })
        assertEquals(listOf("disable all", "list", "get global"), dataSource.calls)
    }

    @Test
    fun givenCommandFailing_whenIsolated_thenFailsWithoutReadingList() = runTest {
        val dataSource = SandboxDataSource(failing = setOf("enable"))
        val repository = RootSandboxRepository(dataSource, StandardTestDispatcher(testScheduler))

        assertFailsWith<RecordsException.CommandFailed> { repository.isolate("com.example.notes", userId = 0) }
        assertEquals(listOf("enable"), dataSource.calls)
    }

    @Test
    fun givenGlobalUnreadable_whenAutoIsolateChanged_thenSwitchReflectsSuccessfulCommand() = runTest {
        val dataSource = SandboxDataSource(failing = setOf("get global"))
        val repository = RootSandboxRepository(dataSource, StandardTestDispatcher(testScheduler))

        repository.setAutoIsolate(on = true)
        assertEquals(true, repository.autoIsolate.value)
        repository.setAutoIsolate(on = false)
        assertEquals(false, repository.autoIsolate.value)

        assertEquals(listOf("auto true", "get global", "auto false", "get global"), dataSource.calls)
    }

    @Test
    fun givenReturnedGlobalSettings_whenChanged_thenReadsOnlyTheAppList() = runTest {
        val dataSource = SandboxDataSource(failing = emptySet())
        val repository = RootSandboxRepository(dataSource, StandardTestDispatcher(testScheduler))

        repository.setGlobal(mapOf(SandboxItem.LOCATION to SandboxMode.COARSE))

        assertEquals(SandboxMode.COARSE, repository.global.value?.get(SandboxItem.LOCATION))
        assertEquals(listOf("set global", "list"), dataSource.calls)
    }

    @Test
    fun givenMultipleChanges_whenRefreshIsDeferred_thenOneReadBackServesTheBatch() = runTest {
        val dataSource = SandboxDataSource(failing = emptySet())
        val repository = RootSandboxRepository(dataSource, StandardTestDispatcher(testScheduler))

        repository.isolate("com.example.notes", userId = 0, refresh = false)
        repository.isolate("com.example.media", userId = 0, refresh = false)
        repository.refresh()

        assertEquals(listOf("enable", "enable", "list", "get global"), dataSource.calls)
    }

    @Test
    fun givenLoadedGlobalSettings_whenAutoIsolateReturnsNewValue_thenNoAdditionalReadIsNeeded() = runTest {
        val dataSource = SandboxDataSource(failing = emptySet(), reportsAuto = true)
        val repository = RootSandboxRepository(dataSource, StandardTestDispatcher(testScheduler))
        repository.refresh()

        repository.setAutoIsolate(on = false)

        assertEquals(false, repository.autoIsolate.value)
        assertEquals(listOf("list", "get global", "auto false"), dataSource.calls)
    }

    private class SandboxDataSource(
        private val failing: Set<String> = setOf("list"),
        private val releaseFailure: RecordsException? = null,
        private val reportsAuto: Boolean = false,
    ) : TestBbdDataSource() {
        val calls = mutableListOf<String>()

        override suspend fun listSandboxApps(): List<BbdSandboxedApp> {
            record("list")
            return listOf(BbdSandboxedApp(10234, "com.example.notes", true, 1, emptyMap(), emptyMap(), emptyMap(), false, 2, null))
        }

        override suspend fun getSandboxGlobal(): BbdSandboxGlobal {
            record("get global")
            return BbdSandboxGlobal(mapOf(SandboxItem.CAMERA to SandboxMode.OFF), true)
        }

        override suspend fun enableSandbox(packageName: String, userId: Int) = record("enable")

        override suspend fun disableSandbox(packageName: String, userId: Int) = record("disable")

        override suspend fun disableAllSandboxes(): BbdSandboxReleaseResult {
            record("disable all")
            releaseFailure?.let { throw it }
            return BbdSandboxReleaseResult(failedCount = 0)
        }

        override suspend fun setSandboxItems(packageName: String, userId: Int, changes: Map<SandboxItem, SandboxMode?>) = record("set")

        override suspend fun setSandboxGlobal(changes: Map<SandboxItem, SandboxMode>): BbdSandboxGlobal {
            record("set global")
            return BbdSandboxGlobal(changes, true)
        }

        override suspend fun setSandboxAuto(on: Boolean): Boolean? {
            record("auto $on")
            return on.takeIf { reportsAuto }
        }

        private fun record(call: String) {
            calls += call
            if (call in failing) throw RecordsException.CommandFailed()
        }
    }
}
