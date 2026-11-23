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

package li.mercury.bigbrother.feature.overview.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.InstalledModule
import li.mercury.bigbrother.core.data.repository.ModuleUpdate
import li.mercury.bigbrother.core.data.repository.ModuleUpdater
import li.mercury.bigbrother.core.data.repository.RecordingAutoStart
import li.mercury.bigbrother.core.domain.GetAppRecordsUseCase
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.AppIconSource
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestModuleRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import org.junit.Rule
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val accessRepository = TestAccessRepository(
        apps = listOf(
            reader(10100, "com.example.notes", lastSeen = 200),
            reader(10200, "com.android.gallery", lastSeen = 300),
            reader(10300, OWN_PACKAGE, lastSeen = 400),
        ),
    )
    private val appInfoRepository = TestAppInfoRepository(
        apps = mapOf(
            "com.example.notes" to AppInfo("Notes", AppIconSource("com.example.notes", 10100, 0), isSystem = false),
            "com.android.gallery" to AppInfo("Gallery", AppIconSource("com.android.gallery", 10200, 0), isSystem = true),
        ),
    )
    private val moduleRepository = TestModuleRepository()
    private val userDataRepository = TestUserDataRepository()

    // One per process, shared by every overview opened in a test.
    private val recordingAutoStart = RecordingAutoStart(moduleRepository)

    private fun viewModel() = OverviewViewModel(
        accessRepository = accessRepository,
        moduleRepository = moduleRepository,
        moduleUpdater = ModuleUpdater(moduleRepository, accessRepository),
        recordingAutoStart = recordingAutoStart,
        getAppRecords = GetAppRecordsUseCase(accessRepository, appInfoRepository),
        getAppVisibility = GetAppVisibilityUseCase(userDataRepository, OWN_PACKAGE),
        userDataRepository = userDataRepository,
        defaultDispatcher = mainDispatcherRule.testDispatcher,
    )

    private fun TestScope.collectedViewModel(): OverviewViewModel {
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.onScreenResumed()
        return viewModel
    }

    @Test
    fun givenOverviewHidden_whenRangeChanges_thenReadsOnlyOnReturn() = runTest {
        val viewModel = viewModel()
        viewModel.onScreenResumed()
        runCurrent()
        assertEquals(0, accessRepository.appReads)

        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(1, accessRepository.appReads)

        viewModel.onScreenPaused()
        collector.cancel()
        userDataRepository.setTimeRange(TimeRange.WEEK)
        runCurrent()
        assertEquals(1, accessRepository.appReads)

        advanceTimeBy(5_001)
        userDataRepository.setTimeRange(TimeRange.ALL)
        runCurrent()
        assertEquals(1, accessRepository.appReads)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.onScreenResumed()
        runCurrent()

        assertEquals(2, accessRepository.appReads)
        assertEquals(TimeRange.ALL, viewModel.uiState.value.range)
    }

    @Test
    fun givenUserSystemAndOwnApps_whenLoaded_thenOnlyUserAppsCount() = runTest {
        val viewModel = collectedViewModel()
        advanceUntilIdle()

        val files = viewModel.uiState.value.files
        assertEquals(listOf("com.example.notes"), files?.readers?.map { it.packageName })
        assertEquals(true, viewModel.uiState.value.recording)
    }

    @Test
    fun givenLoadedOverview_whenSystemAppsShown_thenRecountedWithoutReadingAgain() = runTest {
        val viewModel = collectedViewModel()
        advanceUntilIdle()
        val reads = accessRepository.appReads

        userDataRepository.setShowSystemApps(true)
        advanceUntilIdle()

        assertEquals(
            listOf("com.android.gallery", "com.example.notes"),
            viewModel.uiState.value.files?.readers?.map { it.packageName },
        )
        assertEquals(reads, accessRepository.appReads)
    }

    @Test
    fun givenOutdatedModule_whenLoaded_thenUpdatedAfterTheRecordsAreShown() = runTest {
        moduleRepository.installed = InstalledModule.OUTDATED
        val viewModel = collectedViewModel()
        advanceUntilIdle()

        assertEquals(1, moduleRepository.updates)
        assertEquals(ModuleUpdate.Idle, viewModel.uiState.value.moduleUpdate)
        assertEquals(listOf("com.example.notes"), viewModel.uiState.value.files?.readers?.map { it.packageName })
    }

    @Test
    fun givenComparisonFailure_whenLoaded_thenStateShowsFailureBeforeOfferingUpdate() = runTest {
        moduleRepository.comparisonFailure = RecordsException.CommandFailed(LoadFailure.Operation.MODULE_ARCHIVE_INVALID)
        val viewModel = collectedViewModel()
        advanceUntilIdle()

        assertEquals(LoadFailure.Operation.MODULE_ARCHIVE_INVALID, viewModel.uiState.value.failure)
        assertEquals(ModuleUpdate.Idle, viewModel.uiState.value.moduleUpdate)
        assertEquals(0, moduleRepository.updates)

        moduleRepository.comparisonFailure = null
        moduleRepository.installed = InstalledModule.NEWER
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.failure)
        assertEquals(0, moduleRepository.updates)
    }

    @Test
    fun givenCurrentModule_whenRefreshed_thenComparedOnlyOnce() = runTest {
        val viewModel = collectedViewModel()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1, moduleRepository.comparisons)
        assertEquals(0, moduleRepository.updates)
    }

    @Test
    fun givenDaemonTooOldForTheQuery_whenLoaded_thenModuleUpdated() = runTest {
        accessRepository.failure = RecordsException.ModuleOutdated()
        moduleRepository.installed = InstalledModule.UNKNOWN
        val viewModel = collectedViewModel()
        advanceUntilIdle()

        assertEquals(1, moduleRepository.updates)
        assertEquals(LoadFailure.ModuleOutdated, viewModel.uiState.value.failure)
    }

    @Test
    fun givenInstallerFailure_whenInstallingModule_thenStateShowsFailure() = runTest {
        accessRepository.failure = RecordsException.ModuleMissing()
        moduleRepository.installFailure = RecordsException.CommandFailed(LoadFailure.Operation.KERNEL_BTF_MISSING)
        val viewModel = collectedViewModel()

        viewModel.installModule()
        advanceUntilIdle()

        assertEquals(ModuleSetup.Failed(LoadFailure.Operation.KERNEL_BTF_MISSING), viewModel.uiState.value.moduleSetup)
    }

    @Test
    fun givenUpdateFailure_whenOutdatedModuleIsLoaded_thenStateShowsFailure() = runTest {
        accessRepository.failure = RecordsException.ModuleOutdated()
        moduleRepository.updateFailure = RecordsException.CommandFailed(LoadFailure.Operation.MODULE_DISABLED)
        val viewModel = collectedViewModel()
        advanceUntilIdle()

        assertEquals(ModuleUpdate.Failed(LoadFailure.Operation.MODULE_DISABLED), viewModel.uiState.value.moduleUpdate)
    }

    @Test
    fun givenStartFailure_whenStartingDaemon_thenStateShowsFailure() = runTest {
        accessRepository.status = accessRepository.status.copy(recording = false)
        moduleRepository.startFailure = RecordsException.CommandFailed(LoadFailure.Operation.DAEMON_START_FAILED)
        val viewModel = collectedViewModel()

        viewModel.startDaemon()
        advanceUntilIdle()

        assertEquals(LoadFailure.Operation.DAEMON_START_FAILED, viewModel.uiState.value.daemonStartError)
        assertEquals(false, viewModel.uiState.value.daemonStarting)
    }

    @Test
    fun givenAppMadeInstallationStopped_whenOverviewLoads_thenStartsItOnce() = runTest {
        accessRepository.status = accessRepository.status.copy(recording = false)
        moduleRepository.installedByApp = true
        val viewModel = collectedViewModel()
        runCurrent()

        assertEquals(1, moduleRepository.starts)
        assertEquals(true, viewModel.uiState.value.daemonStarting)

        accessRepository.status = accessRepository.status.copy(recording = true)
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.daemonStarting)
        assertNull(viewModel.uiState.value.daemonStartError)
        assertEquals(true, viewModel.uiState.value.recording)

        accessRepository.status = accessRepository.status.copy(recording = false)
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1, moduleRepository.starts)
        assertEquals(false, viewModel.uiState.value.recording)
    }

    @Test
    fun givenAutomaticStartDone_whenOverviewOpenedAgainInTheSameProcess_thenNotStartedAgain() = runTest {
        accessRepository.status = accessRepository.status.copy(recording = false)
        moduleRepository.installedByApp = true
        collectedViewModel()
        advanceUntilIdle()

        val reopened = collectedViewModel()
        advanceUntilIdle()

        assertEquals(1, moduleRepository.starts)
        assertEquals(false, reopened.uiState.value.daemonStarting)
        assertNull(reopened.uiState.value.daemonStartError)
    }

    @Test
    fun givenInstallationTurnedOffByItsBootGuard_whenOverviewLoads_thenLeftOffWithoutFailure() = runTest {
        accessRepository.status = accessRepository.status.copy(recording = false)
        moduleRepository.installedByApp = true
        moduleRepository.turnedOff = true
        val viewModel = collectedViewModel()
        advanceUntilIdle()

        assertEquals(0, moduleRepository.starts)
        assertTrue(moduleRepository.turnedOff)
        assertEquals(false, viewModel.uiState.value.daemonStarting)
        assertNull(viewModel.uiState.value.daemonStartError)

        viewModel.startDaemon()
        advanceUntilIdle()

        assertEquals(1, moduleRepository.starts)
        assertEquals(false, moduleRepository.turnedOff)
    }

    @Test
    fun givenFlashedModuleStopped_whenOverviewLoads_thenNotStarted() = runTest {
        accessRepository.status = accessRepository.status.copy(recording = false)
        val viewModel = collectedViewModel()
        advanceUntilIdle()

        assertEquals(0, moduleRepository.starts)
        assertEquals(false, viewModel.uiState.value.daemonStarting)
        assertEquals(false, viewModel.uiState.value.recording)
    }

    @Test
    fun givenAutomaticStartFails_whenOverviewLoads_thenFailureShownAsForAManualStart() = runTest {
        accessRepository.status = accessRepository.status.copy(recording = false)
        moduleRepository.installedByApp = true
        moduleRepository.startFailure = RecordsException.CommandFailed(LoadFailure.Operation.DAEMON_START_FAILED)
        val viewModel = collectedViewModel()
        advanceUntilIdle()

        assertEquals(LoadFailure.Operation.DAEMON_START_FAILED, viewModel.uiState.value.daemonStartError)
        assertEquals(false, viewModel.uiState.value.daemonStarting)
    }

    @Test
    fun givenNoRoot_whenLoaded_thenFailureShownAndModuleLeftAlone() = runTest {
        accessRepository.failure = RecordsException.NoRoot()
        val viewModel = collectedViewModel()
        advanceUntilIdle()

        assertEquals(LoadFailure.NoRoot, viewModel.uiState.value.failure)
        assertNull(viewModel.uiState.value.files)
        assertEquals(0, moduleRepository.updates)
    }

    @Test
    fun givenUnsavedRange_whenRetried_thenUpdatesAfterSaving() = runTest {
        val viewModel = collectedViewModel()
        userDataRepository.writeFailure = IOException("Storage unavailable")
        viewModel.setRange(TimeRange.WEEK)
        advanceUntilIdle()

        assertEquals(TimeRange.DAY, viewModel.uiState.value.range)
        val failure = assertNotNull(viewModel.uiState.value.message)

        userDataRepository.writeFailure = null
        viewModel.onMessageShown(failure, retry = true)
        advanceUntilIdle()

        assertEquals(TimeRange.WEEK, viewModel.uiState.value.range)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun givenUnsavedRange_whenNewerRangeSaved_thenOldRetryCannotOverrideIt() = runTest {
        val viewModel = collectedViewModel()
        userDataRepository.writeFailure = IOException("Storage unavailable")
        viewModel.setRange(TimeRange.WEEK)
        runCurrent()
        val failure = assertNotNull(viewModel.uiState.value.message)

        userDataRepository.writeFailure = null
        viewModel.setRange(TimeRange.ALL)
        viewModel.onMessageShown(failure, retry = true)
        advanceUntilIdle()

        assertEquals(TimeRange.ALL, viewModel.uiState.value.range)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun givenCancelledWrite_whenRangeChanged_thenNoFailureIsShown() = runTest {
        val viewModel = collectedViewModel()
        userDataRepository.writeFailure = CancellationException()

        viewModel.setRange(TimeRange.WEEK)
        advanceUntilIdle()

        assertEquals(TimeRange.DAY, viewModel.uiState.value.range)
        assertNull(viewModel.uiState.value.message)
    }

    private companion object {
        const val OWN_PACKAGE = "li.mercury.bigbrother"

        fun reader(uid: Int, packageName: String, lastSeen: Long) = AppSummary(
            uid = uid,
            packageName = packageName,
            counts = mapOf(Category.STORAGE to 3),
            lastSeen = lastSeen,
            filesRead = 3,
            folders = listOf("DCIM"),
        )
    }
}
