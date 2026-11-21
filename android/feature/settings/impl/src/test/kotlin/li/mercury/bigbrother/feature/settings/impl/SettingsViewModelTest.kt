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

package li.mercury.bigbrother.feature.settings.impl

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.DiagnosticsRepository
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.UserData
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestModuleRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import org.junit.Rule
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun givenStatusAndVersionReadFailures_whenRefreshing_thenStateShowsBothFailures() = runTest {
        val records = TestAccessRepository().apply { failure = RecordsException.NoRoot() }
        val module = TestModuleRepository().apply {
            versionFailure = RecordsException.CommandFailed(LoadFailure.Operation.MODULE_VERSION_UNREADABLE)
        }
        val viewModel = SettingsViewModel(
            records,
            module,
            TestUserDataRepository(),
            object : DiagnosticsRepository {
                override suspend fun exportLogs() = error("Logs are not exported by this test")
            },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(LoadFailure.Operation.MODULE_VERSION_UNREADABLE, viewModel.uiState.value.moduleVersionFailure)
        assertEquals(LoadFailure.NoRoot, viewModel.uiState.value.recorderStatusFailure)

        module.versionFailure = null
        records.failure = null
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.moduleVersionFailure)
        assertNull(viewModel.uiState.value.recorderStatusFailure)
        assertEquals("v0.1.6", viewModel.uiState.value.moduleVersion)
    }

    @Test
    fun givenBothSwitchWritesFail_whenRetried_thenEachChoiceIsSaved() = runTest {
        val settings = TestUserDataRepository()
        val viewModel = SettingsViewModel(
            accessRepository = TestAccessRepository(),
            moduleRepository = TestModuleRepository(),
            userDataRepository = settings,
            diagnosticsRepository = object : DiagnosticsRepository {
                override suspend fun exportLogs() = error("Logs are not exported by this test")
            },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        settings.writeFailure = IOException("Storage unavailable")

        viewModel.setShowSystemApps(true)
        viewModel.setUseDynamicColor(false)
        advanceUntilIdle()

        val systemAppsFailure = assertNotNull(viewModel.uiState.value.message)
        assertEquals(UserData(), viewModel.uiState.value.userData)

        settings.writeFailure = null
        viewModel.onMessageShown(systemAppsFailure, retry = true)
        advanceUntilIdle()

        assertEquals(UserData(showSystemApps = true), viewModel.uiState.value.userData)
        val dynamicColorFailure = assertNotNull(viewModel.uiState.value.message)

        viewModel.onMessageShown(dynamicColorFailure, retry = true)
        advanceUntilIdle()

        assertEquals(UserData(showSystemApps = true, useDynamicColor = false), viewModel.uiState.value.userData)
        assertNull(viewModel.uiState.value.message)
    }
}
