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

import android.content.pm.PackageInfo
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.InstalledApp
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestSandboxRepository
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sandboxRepository = TestSandboxRepository(
        apps = listOf(
            sandboxed(10156, "com.example.notes", enabled = true),
            sandboxed(10187, "com.example.weather", enabled = true),
            sandboxed(10204, "com.example.shopping", enabled = false),
        ),
    )
    private val appInfoRepository = TestAppInfoRepository(
        apps = mapOf(
            "com.example.notes" to AppInfo("Notes", PackageInfo(), isSystem = false),
            "com.example.weather" to AppInfo("Arctic Weather", PackageInfo(), isSystem = false),
        ),
    )

    private fun viewModel(sandboxRepository: TestSandboxRepository = this.sandboxRepository) = JailViewModel(sandboxRepository, appInfoRepository, currentUserId = 0)

    @Test
    fun givenIsolatedAndReleasedApps_whenRefreshed_thenOnlyIsolatedAppsListedByName() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.refresh()

        assertEquals(listOf("Arctic Weather", "Notes"), viewModel.uiState.value.apps?.map { it.name })
        assertNotNull(viewModel.uiState.value.status)
    }

    @Test
    fun givenSamePackageInTwoUsers_whenListed_thenOtherUsersCopyMarked() = runTest {
        val repository = TestSandboxRepository(
            apps = listOf(sandboxed(10156, "com.example.notes", enabled = true), sandboxed(1_010_156, "com.example.notes", enabled = true)),
        )
        val viewModel = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.refresh()

        assertEquals(listOf(null, 10), viewModel.uiState.value.apps?.map { it.otherUser })
        assertEquals(listOf("Notes", "Notes"), viewModel.uiState.value.apps?.map { it.name })
    }

    @Test
    fun givenAppOpsNotHeld_whenAppsIsolated_thenToldNotHeld() = runTest {
        sandboxRepository.status = sandboxRepository.status.copy(appOps = false)
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.refresh()

        assertEquals(false, viewModel.uiState.value.status?.appOps)
    }

    @Test
    fun givenAppOpsNotHeld_whenNoAppIsolated_thenNotTold() = runTest {
        // The daemon holds app ops only for an app in the sandbox.
        val repository = TestSandboxRepository(apps = listOf(sandboxed(10204, "com.example.shopping", enabled = false)))
        repository.status = repository.status.copy(appOps = false)
        val viewModel = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.refresh()

        assertEquals(true, viewModel.uiState.value.status?.appOps)
    }

    @Test
    fun givenStatusReadWithNoAppIsolated_whenAppIsolated_thenAppOpsNotTold() = runTest {
        val repository = TestSandboxRepository()
        repository.status = repository.status.copy(appOps = false)
        val viewModel = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.refresh()

        viewModel.isolate(listOf(installed(10204, "com.example.shopping", "Shopping")))

        assertEquals(listOf("com.example.shopping"), viewModel.uiState.value.apps?.map { it.app.packageName })
        assertEquals(true, viewModel.uiState.value.status?.appOps)
    }

    @Test
    fun givenDaemonFailing_whenRefreshed_thenFailureShown() = runTest {
        sandboxRepository.failure = RecordsException.CommandFailed("")
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.refresh()

        assertNotNull(viewModel.uiState.value.failure)
        assertNull(viewModel.uiState.value.apps)
    }

    @Test
    fun givenAppsChosen_whenIsolated_thenToldOnceAndUndoReleasesThem() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val chosen = listOf(installed(10204, "com.example.shopping", "Shopping"), installed(10231, "com.example.flashlight", "Flashlight"))

        viewModel.isolate(chosen)

        val message = assertIs<JailMessage.Isolated>(viewModel.uiState.value.message)
        assertEquals(listOf("Shopping", "Flashlight"), message.names)
        assertEquals(listOf("com.example.shopping", "com.example.flashlight"), sandboxRepository.isolated)

        viewModel.onMessageShown(message)
        viewModel.undoIsolate(message.apps)

        assertNull(viewModel.uiState.value.message)
        assertEquals(listOf("com.example.shopping", "com.example.flashlight"), sandboxRepository.released)
    }

    @Test
    fun givenOneAppFailing_whenSeveralIsolated_thenOthersIsolatedWithUndoAndFailedOneNamed() = runTest {
        sandboxRepository.failsToIsolate = mapOf("com.example.shopping" to RecordsException.CommandFailed("not installed"))
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.refresh()

        viewModel.isolate(threeApps)

        assertEquals(listOf("com.example.flashlight", "com.example.keyboard"), sandboxRepository.isolated)
        val message = assertIs<JailMessage.Isolated>(viewModel.uiState.value.message)
        assertEquals(listOf("Flashlight", "Keyboard"), message.names)
        assertEquals(listOf("Shopping"), message.notIsolated)

        viewModel.onMessageShown(message)
        viewModel.undoIsolate(message.apps)

        assertEquals(listOf("com.example.flashlight", "com.example.keyboard"), sandboxRepository.released)
    }

    @Test
    fun givenModuleOutdated_whenSeveralIsolated_thenStopsAndLeftOnesNotIsolated() = runTest {
        sandboxRepository.failsToIsolate = mapOf("com.example.shopping" to RecordsException.ModuleOutdated())
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.isolate(threeApps)

        // Keyboard would have been isolated, had it been tried.
        assertEquals(listOf("com.example.flashlight"), sandboxRepository.isolated)
        val message = assertIs<JailMessage.Isolated>(viewModel.uiState.value.message)
        assertEquals(listOf("Flashlight"), message.names)
        assertEquals(listOf("Shopping", "Keyboard"), message.notIsolated)
    }

    @Test
    fun givenNoRoot_whenSeveralIsolated_thenEveryAppNamedWithReason() = runTest {
        sandboxRepository.failure = RecordsException.NoRoot()
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.isolate(threeApps)

        assertEquals(JailMessage.NotIsolated(listOf("Flashlight", "Shopping", "Keyboard"), LoadFailure.NoRoot), viewModel.uiState.value.message)
    }

    @Test
    fun givenDaemonFailing_whenIsolated_thenAppNamedWithReason() = runTest {
        sandboxRepository.failure = RecordsException.CommandFailed("no gate")
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.isolate(listOf(installed(10204, "com.example.shopping", "Shopping")))

        assertEquals(JailMessage.NotIsolated(listOf("Shopping"), LoadFailure.Other("no gate")), viewModel.uiState.value.message)
    }

    @Test
    fun givenListUnreadableAfterwards_whenIsolated_thenToldIsolatedWithUndo() = runTest {
        sandboxRepository.refreshFailure = RecordsException.CommandFailed("cannot read")
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.isolate(listOf(installed(10204, "com.example.shopping", "Shopping")))

        val message = assertIs<JailMessage.Isolated>(viewModel.uiState.value.message)
        assertEquals(listOf("Shopping"), message.names)
        assertEquals(emptyList(), message.notIsolated)
    }

    @Test
    fun givenOneAppFailingToRelease_whenUndone_thenOthersReleasedAndFailedOneNamed() = runTest {
        sandboxRepository.failsToRelease = mapOf("com.example.flashlight" to RecordsException.CommandFailed("busy"))
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.isolate(threeApps)
        val message = assertIs<JailMessage.Isolated>(viewModel.uiState.value.message)
        viewModel.onMessageShown(message)

        viewModel.undoIsolate(message.apps)

        assertEquals(listOf("com.example.shopping", "com.example.keyboard"), sandboxRepository.released)
        assertEquals(JailMessage.NotReleased(listOf("Flashlight"), LoadFailure.Other("busy")), viewModel.uiState.value.message)
    }

    private val threeApps = listOf(
        installed(10231, "com.example.flashlight", "Flashlight"),
        installed(10204, "com.example.shopping", "Shopping"),
        installed(10120, "com.example.keyboard", "Keyboard"),
    )
}

internal fun sandboxed(uid: Int, packageName: String, enabled: Boolean) = SandboxedApp(uid, packageName, enabled, created = 0, settings = emptyMap(), items = emptyMap())

internal fun installed(uid: Int, packageName: String, label: String, isSystem: Boolean = false) = InstalledApp(packageName, uid, AppInfo(label, PackageInfo(), isSystem))
