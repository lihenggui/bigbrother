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

package li.mercury.bigbrother.feature.jail.impl

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxPart
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestSandboxRepository
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JailSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sandboxRepository = TestSandboxRepository(
        apps = listOf(
            sandboxed(10156, "com.example.notes", enabled = true),
            sandboxed(10187, "com.example.weather", enabled = true),
            sandboxed(10204, "com.example.shopping", enabled = false),
        ),
    )

    private fun TestScope.viewModel(): JailSettingsViewModel {
        val viewModel = JailSettingsViewModel(sandboxRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.refresh()
        return viewModel
    }

    @Test
    fun givenNothingSet_whenLoaded_thenEveryItemOnAndIsolatedAppsCounted() = runTest {
        val viewModel = viewModel()

        assertEquals(SandboxItem.entries.associateWith { SandboxMode.ON }, viewModel.uiState.value.global)
        assertEquals(2, viewModel.uiState.value.isolated)
    }

    @Test
    fun givenItemOn_whenTurnedOffAndChosenAgain_thenGlobalModeFollows() = runTest {
        val viewModel = viewModel()

        viewModel.setOn(SandboxItem.CLIPBOARD_ACCOUNTS, on = false)
        assertEquals(SandboxMode.OFF, viewModel.uiState.value.global?.get(SandboxItem.CLIPBOARD_ACCOUNTS))

        viewModel.choose(SandboxItem.LOCATION, SandboxMode.COARSE)
        assertEquals(SandboxMode.COARSE, viewModel.uiState.value.global?.get(SandboxItem.LOCATION))
    }

    @Test
    fun givenMicrophoneOn_whenOnlyWhileInUseChosenThenTurnedOff_thenGlobalModeFollows() = runTest {
        val viewModel = viewModel()

        viewModel.choose(SandboxItem.MICROPHONE, SandboxMode.FOREGROUND)
        assertEquals(SandboxMode.FOREGROUND, viewModel.uiState.value.global?.get(SandboxItem.MICROPHONE))

        viewModel.setOn(SandboxItem.MICROPHONE, on = false)
        assertEquals(SandboxMode.OFF, viewModel.uiState.value.global?.get(SandboxItem.MICROPHONE))
    }

    @Test
    fun givenIsolatedApps_whenAllReleased_thenNoneIsolated() = runTest {
        val viewModel = viewModel()

        viewModel.releaseAll()

        assertEquals(0, viewModel.uiState.value.isolated)
        assertEquals(0, viewModel.uiState.value.notReleased)
    }

    @Test
    fun givenAppDaemonCannotRelease_whenAllReleased_thenToldHowManyAreLeft() = runTest {
        sandboxRepository.releaseAllLeaves = 1
        val viewModel = viewModel()

        viewModel.releaseAll()

        assertEquals(1, viewModel.uiState.value.isolated)
        assertEquals(1, viewModel.uiState.value.notReleased)
        viewModel.dismissError()
        assertEquals(0, viewModel.uiState.value.notReleased)
    }

    @Test
    fun givenListUnreadableAfterwards_whenAllReleased_thenToldHowManyAreLeftWithoutError() = runTest {
        sandboxRepository.releaseAllLeaves = 1
        val viewModel = viewModel()
        sandboxRepository.refreshFailure = RecordsException.CommandFailed("cannot read")

        viewModel.releaseAll()

        assertEquals(1, viewModel.uiState.value.notReleased)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun givenAppOpsNotHeld_whenAllReleased_thenNoLongerTold() = runTest {
        sandboxRepository.status = sandboxRepository.status.copy(appOps = false)
        val viewModel = viewModel()
        assertEquals(setOf(SandboxPart.APP_OPS), viewModel.uiState.value.status?.unmet(SandboxItem.CONTACTS))

        viewModel.releaseAll()

        assertEquals(true, viewModel.uiState.value.status?.appOps)
        assertEquals(emptySet(), viewModel.uiState.value.status?.unmet(SandboxItem.CONTACTS))
    }
}
