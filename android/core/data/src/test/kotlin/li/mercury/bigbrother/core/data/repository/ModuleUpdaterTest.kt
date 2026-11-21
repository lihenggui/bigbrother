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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.RecorderStatus
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestModuleRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModuleUpdaterTest {
    private val module = TestModuleRepository()
    private val access = TestAccessRepository()
    private val updater = ModuleUpdater(module, access)

    @Test
    fun givenCurrentModule_whenCheckedAgain_thenUsesPreviousComparisonUntilDaemonRejectsAQuery() = runTest {
        assertFalse(updater.shouldUpdate(LoadFailure.NoRoot))
        assertEquals(0, module.comparisons)
        assertFalse(updater.shouldUpdate(null))
        assertFalse(updater.shouldUpdate(null))
        assertEquals(1, module.comparisons)

        assertTrue(updater.shouldUpdate(LoadFailure.ModuleOutdated))
        assertFalse(updater.shouldUpdate(LoadFailure.ModuleOutdated))
    }

    @Test
    fun givenDaemonUnavailableDuringRestart_whenRecordingResumes_thenReturnsToIdle() = runTest {
        access.failure = RecordsException.ModuleMissing()
        val update = launch { updater.updateInPlace() }
        runCurrent()

        assertEquals(ModuleUpdate.Updating, updater.state.value)
        assertEquals(1, module.updates)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ModuleUpdate.Updating, updater.state.value)

        access.failure = null
        advanceTimeBy(1_000)
        update.join()

        assertEquals(ModuleUpdate.Idle, updater.state.value)
    }

    @Test
    fun givenOutdatedModuleThatNeverResumes_whenUpdated_thenReportsTimeoutAndAllowsManualRetry() = runTest {
        module.installed = InstalledModule.OUTDATED
        access.status = RecorderStatus(recording = false)

        assertTrue(updater.shouldUpdate(null))
        updater.updateInPlace()

        assertEquals(ModuleUpdate.Failed(LoadFailure.Operation.DAEMON_NOT_REPORTING), updater.state.value)
        assertFalse(updater.shouldUpdate(LoadFailure.ModuleOutdated))

        access.status = RecorderStatus(recording = true)
        updater.updateInPlace()

        assertEquals(ModuleUpdate.Idle, updater.state.value)
        assertEquals(2, module.updates)
    }
}
