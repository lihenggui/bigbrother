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

package li.mercury.bigbrother.feature.appdetail.impl

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.InstalledApp
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestInstalledAppRepository
import li.mercury.bigbrother.core.testing.repository.TestSandboxRepository
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val info = AppInfo(
        label = "Notes",
        packageInfo = PackageInfo().apply {
            applicationInfo = ApplicationInfo().apply { uid = APP_UID }
        },
        isSystem = false,
    )
    private val installed = TestInstalledAppRepository(apps = listOf(InstalledApp(PACKAGE, APP_UID, info)))
    private val sandbox = TestSandboxRepository()

    @Test
    fun givenUnisolatedApp_whenConfirmed_thenIsolatesAndOpensItsSettings() = runTest {
        val model = viewModel()
        model.requestJail()
        runCurrent()
        assertEquals(JailAction.Confirm, model.uiState.value.jailAction)
        assertTrue(sandbox.isolated.isEmpty())

        model.dismissJailAction()
        model.confirmIsolation()
        assertTrue(sandbox.isolated.isEmpty())

        model.requestJail()
        model.confirmIsolation()
        model.confirmIsolation()
        runCurrent()

        assertEquals(listOf(PACKAGE), sandbox.isolated)
        assertEquals(JailAction.Open(PACKAGE), model.uiState.value.jailAction)
    }

    @Test
    fun givenAppAlreadyIsolated_whenOpened_thenGoesStraightToItsSettings() = runTest {
        val entry = SandboxedApp(APP_UID, PACKAGE, true, 0, emptyMap(), emptyMap())
        val repository = TestSandboxRepository(apps = listOf(entry))
        val model = viewModel(repository)

        model.requestJail()
        runCurrent()

        assertTrue(repository.isolated.isEmpty())
        assertEquals(JailAction.Open(PACKAGE), model.uiState.value.jailAction)
    }

    @Test
    fun givenPackageChangedSinceRecording_whenConfirmed_thenLeavesItAlone() = runTest {
        val model = viewModel()
        model.requestJail()
        installed.apps = listOf(InstalledApp(PACKAGE, APP_UID + 1, info))

        model.confirmIsolation()
        runCurrent()

        assertTrue(sandbox.isolated.isEmpty())
        assertFalse(model.uiState.value.canOpenJail)
        assertEquals(JailAction.Failed(LoadFailure.Operation.APP_MISSING), model.uiState.value.jailAction)
    }

    @Test
    fun givenIsolationFails_whenConfirmed_thenReportsFailureWithoutOpeningSettings() = runTest {
        sandbox.failsToIsolate = mapOf(PACKAGE to RecordsException.NoRoot())
        val model = viewModel()
        model.requestJail()

        model.confirmIsolation()
        runCurrent()

        assertEquals(JailAction.Failed(LoadFailure.NoRoot), model.uiState.value.jailAction)
    }

    private fun TestScope.viewModel(repository: TestSandboxRepository = sandbox): AppDetailViewModel {
        val model = AppDetailViewModel(
            accessRepository = TestAccessRepository(),
            appInfoRepository = TestAppInfoRepository(mapOf(PACKAGE to info)),
            installedAppRepository = installed,
            sandboxRepository = repository,
            ownPackage = "li.mercury.bigbrother",
            currentUserId = 0,
            defaultDispatcher = mainDispatcherRule.testDispatcher,
            uid = APP_UID,
            packageName = PACKAGE,
            range = TimeRange.DAY,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        runCurrent()
        return model
    }

    private companion object {
        const val PACKAGE = "com.example.notes"
        const val APP_UID = 10_000
    }
}
