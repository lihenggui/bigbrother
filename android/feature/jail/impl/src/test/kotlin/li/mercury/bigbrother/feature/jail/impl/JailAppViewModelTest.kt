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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxPart
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestInstalledAppRepository
import li.mercury.bigbrother.core.testing.repository.TestNetworkRepository
import li.mercury.bigbrother.core.testing.repository.TestSandboxRepository
import li.mercury.bigbrother.feature.jail.impl.network.emptyActivity
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun TestScope.viewModel(
        uid: Int = UID,
        packageName: String = PACKAGE,
        sandboxRepository: SandboxRepository = this@JailAppViewModelTest.sandboxRepository,
    ): JailAppViewModel {
        val viewModel = JailAppViewModel(uid, packageName, sandboxRepository, TestAppInfoRepository(), installedAppRepository, TestNetworkRepository(emptyActivity()), currentUserId = 0)
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
        assertNull(viewModel.uiState.value.otherUser)
    }

    @Test
    fun givenAppOfOtherUserNotInstalledHere_whenLoaded_thenUserShownAndPermissionsUnknown() = runTest {
        val uid = 1_010_231
        val repository = TestSandboxRepository(apps = listOf(sandboxed(uid, "com.example.flashlight", enabled = true)))

        val viewModel = viewModel(uid, "com.example.flashlight", repository)

        assertEquals(10, viewModel.uiState.value.otherUser)
        assertNull(viewModel.uiState.value.requested)
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
    fun givenCameraOffGlobally_whenOnlyWhileInUseChosen_thenAppHasItsOwnForegroundAndItIsOn() = runTest {
        val viewModel = viewModel()

        viewModel.choose(SandboxItem.CAMERA, SandboxMode.FOREGROUND)

        assertEquals(mapOf(SandboxItem.CAMERA to SandboxMode.FOREGROUND), viewModel.app().settings)
        assertTrue(viewModel.app().modeOf(SandboxItem.CAMERA).isOn)
    }

    @Test
    fun givenForegroundGlobally_whenTurnedOffAndOnAgain_thenFollowsGlobalForegroundAgain() = runTest {
        sandboxRepository.setGlobal(mapOf(SandboxItem.MICROPHONE to SandboxMode.FOREGROUND))
        val viewModel = viewModel()
        viewModel.setOn(SandboxItem.MICROPHONE, on = false)

        viewModel.setOn(SandboxItem.MICROPHONE, on = true)

        assertTrue(viewModel.app().settings.isEmpty())
        assertEquals(SandboxMode.FOREGROUND, viewModel.app().modeOf(SandboxItem.MICROPHONE))
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
    fun givenGlobalAllowed_whenNetworkSwitchedOff_thenDaemonGetsNetworkOn() = runTest {
        val viewModel = viewModel()

        viewModel.setNetworkAllowed(false)

        assertEquals(mapOf(SandboxItem.NETWORK to SandboxMode.ON), viewModel.app().settings)
        viewModel.setNetworkAllowed(true)
        assertTrue(viewModel.app().settings.isEmpty())
        assertEquals(SandboxMode.OFF, viewModel.app().modeOf(SandboxItem.NETWORK))
    }

    @Test
    fun givenOwnNetworkAndRecording_whenReset_thenOnlyIsolationItemsFollowGlobalSettings() = runTest {
        val viewModel = viewModel()
        viewModel.setNetworkAllowed(false)
        viewModel.setRecording(true)
        viewModel.setOn(SandboxItem.CONTACTS, on = false)

        viewModel.reset()

        assertEquals(
            mapOf(SandboxItem.NETWORK to SandboxMode.ON, SandboxItem.NETWORK_RECORD to SandboxMode.ON),
            viewModel.app().settings,
        )
    }

    @Test
    fun givenIsolatedApp_whenReleased_thenPageLeavesStraightFromTheApp() = runTest {
        val viewModel = viewModel()
        val shown = mutableListOf<JailAppUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { shown += it } }

        viewModel.release()

        assertEquals(listOf(PACKAGE), sandboxRepository.released)
        assertTrue(viewModel.uiState.value.left)
        // Never a page without the app on the way out.
        assertTrue(shown.all { it.app?.packageName == PACKAGE })
    }

    @Test
    fun givenAppTakenOutElsewhere_whenShown_thenPageLeaves() = runTest {
        val viewModel = viewModel()

        // As Undo in the list does, beside this page.
        sandboxRepository.release(PACKAGE, userId = 0)

        assertTrue(viewModel.uiState.value.left)
        assertEquals(PACKAGE, viewModel.uiState.value.app?.packageName)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun givenAppOpsBeingChecked_whenTheyRecover_thenUpdatesWithoutReopeningThePage() = runTest {
        val recovered = sandboxed(UID, PACKAGE, enabled = true)
        val checking = recovered.copy(
            unmet = mapOf(SandboxItem.CAMERA to setOf(SandboxPart.APP_OPS)),
            appOpsChecking = true,
        )
        val observedApps = MutableStateFlow<List<SandboxedApp>?>(listOf(checking))
        var refreshes = 0
        val repository = object : SandboxRepository by sandboxRepository {
            override val apps = observedApps

            override suspend fun refresh() {
                refreshes++
                sandboxRepository.refresh()
                if (refreshes == 2) apps.value = listOf(recovered)
            }
        }
        val model = viewModel(sandboxRepository = repository)
        assertTrue(model.app().appOpsChecking)
        val watch = model.watchAppOps()
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(model.app().appOpsChecking)
        assertNull(model.app().unmet[SandboxItem.CAMERA])
        advanceTimeBy(60_000)
        assertEquals(2, refreshes)
        watch.cancel()
    }

    @Test
    fun givenStaleCachedList_whenSettingsOpenAndRefreshFails_thenShowsFailureWithoutLeaving() = runTest {
        val repository = TestSandboxRepository()
        repository.refresh()
        repository.refreshFailure = RecordsException.NoRoot()

        val model = viewModel(sandboxRepository = repository)

        assertEquals(LoadFailure.NoRoot, model.uiState.value.failure)
        assertFalse(model.uiState.value.left)
    }

    @Test
    fun givenAppNotIsolated_whenOpened_thenPageLeaves() = runTest {
        val viewModel = viewModel(uid = 10_231, packageName = "com.example.flashlight")

        assertTrue(viewModel.uiState.value.left)
        assertNull(viewModel.uiState.value.app)
    }

    @Test
    fun givenListUnreadableAfterwards_whenReleased_thenPageLeftWithoutError() = runTest {
        val viewModel = viewModel()
        sandboxRepository.refreshFailure = RecordsException.CommandFailed("cannot read")

        viewModel.release()

        assertEquals(listOf(PACKAGE), sandboxRepository.released)
        assertTrue(viewModel.uiState.value.left)
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
    fun givenPageLeaving_whenChanged_thenNothingAskedOfDaemon() = runTest {
        val viewModel = viewModel()
        viewModel.release()

        viewModel.setOn(SandboxItem.CONTACTS, on = false)
        viewModel.release()

        assertEquals(listOf(PACKAGE), sandboxRepository.released)
        assertTrue(sandboxRepository.apps.value.orEmpty().single().settings.isEmpty())
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
