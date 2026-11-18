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
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestInstalledAppRepository
import li.mercury.bigbrother.core.testing.repository.TestSandboxRepository
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JailAppViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sandboxRepository = TestSandboxRepository(
        apps = listOf(sandboxed(UID, PACKAGE, enabled = true)),
        global = mapOf(SandboxItem.LOCATION to SandboxMode.COARSE, SandboxItem.CAMERA to SandboxMode.OFF),
    )
    private val installedAppRepository = TestInstalledAppRepository(
        permissions = mapOf(PACKAGE to setOf("android.permission.CAMERA")),
    )

    private fun TestScope.viewModel(): JailAppViewModel {
        val viewModel = JailAppViewModel(UID, PACKAGE, sandboxRepository, TestAppInfoRepository(), installedAppRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.refresh()
        return viewModel
    }

    private fun JailAppViewModel.app() = uiState.value.app!!

    @Test
    fun givenIsolatedApp_whenLoaded_thenGlobalModesApplyAndPermissionsKnown() = runTest {
        val viewModel = viewModel()

        assertEquals(SandboxMode.COARSE, viewModel.app().modeOf(SandboxItem.LOCATION))
        assertEquals(SandboxMode.ON, viewModel.app().modeOf(SandboxItem.CONTACTS))
        assertEquals(setOf("android.permission.CAMERA"), viewModel.uiState.value.requested)
    }

    @Test
    fun givenItemOnGlobally_whenTurnedOff_thenAppHasItsOwnOff() = runTest {
        val viewModel = viewModel()

        viewModel.setOn(SandboxItem.CONTACTS, on = false)

        assertEquals(mapOf(SandboxItem.CONTACTS to SandboxMode.OFF), viewModel.app().settings)
    }

    @Test
    fun givenOwnOff_whenTurnedOnAgain_thenFollowsGlobalSettingsAgain() = runTest {
        val viewModel = viewModel()
        viewModel.setOn(SandboxItem.LOCATION, on = false)

        viewModel.setOn(SandboxItem.LOCATION, on = true)

        assertTrue(viewModel.app().settings.isEmpty())
        assertEquals(SandboxMode.COARSE, viewModel.app().modeOf(SandboxItem.LOCATION))
    }

    @Test
    fun givenItemOffGlobally_whenTurnedOn_thenAppHasItsOwnOn() = runTest {
        val viewModel = viewModel()

        viewModel.setOn(SandboxItem.CAMERA, on = true)

        assertEquals(mapOf(SandboxItem.CAMERA to SandboxMode.ON), viewModel.app().settings)
    }

    @Test
    fun givenOwnSettings_whenReset_thenEveryItemFollowsGlobalSettings() = runTest {
        val viewModel = viewModel()
        viewModel.choose(SandboxItem.LOCATION, SandboxMode.COARSE)
        viewModel.setOn(SandboxItem.CONTACTS, on = false)

        viewModel.reset()

        assertTrue(viewModel.app().settings.isEmpty())
    }

    @Test
    fun givenIsolatedApp_whenReleased_thenTakenOutAndPageLeft() = runTest {
        val viewModel = viewModel()

        viewModel.release()

        assertEquals(listOf(PACKAGE), sandboxRepository.released)
        assertTrue(viewModel.uiState.value.released)
        assertNull(viewModel.uiState.value.app)
    }

    @Test
    fun givenListUnreadableAfterwards_whenReleased_thenPageLeftWithoutError() = runTest {
        val viewModel = viewModel()
        sandboxRepository.refreshFailure = RecordsException.CommandFailed("cannot read")

        viewModel.release()

        assertEquals(listOf(PACKAGE), sandboxRepository.released)
        assertTrue(viewModel.uiState.value.released)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun givenListUnreadableAfterwards_whenChanged_thenNoError() = runTest {
        val viewModel = viewModel()
        sandboxRepository.refreshFailure = RecordsException.CommandFailed("cannot read")

        viewModel.setOn(SandboxItem.CONTACTS, on = false)

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun givenDaemonFailing_whenChanged_thenErrorToldOnce() = runTest {
        val viewModel = viewModel()
        sandboxRepository.failure = RecordsException.ModuleOutdated()

        viewModel.setOn(SandboxItem.CONTACTS, on = false)

        assertEquals(LoadFailure.ModuleOutdated, viewModel.uiState.value.error)
        viewModel.dismissError()
        assertNull(viewModel.uiState.value.error)
    }

    private companion object {
        const val UID = 10156
        const val PACKAGE = "com.example.notes"
    }
}
