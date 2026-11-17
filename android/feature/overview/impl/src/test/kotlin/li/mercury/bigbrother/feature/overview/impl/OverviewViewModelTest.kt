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

import android.content.pm.PackageInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.InstalledModule
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestModuleRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
            "com.example.notes" to AppInfo("Notes", PackageInfo(), isSystem = false),
            "com.android.gallery" to AppInfo("Gallery", PackageInfo(), isSystem = true),
        ),
    )
    private val moduleRepository = TestModuleRepository()
    private val userDataRepository = TestUserDataRepository()

    private fun viewModel() = OverviewViewModel(
        ownPackage = OWN_PACKAGE,
        accessRepository = accessRepository,
        moduleRepository = moduleRepository,
        appInfoRepository = appInfoRepository,
        userDataRepository = userDataRepository,
        defaultDispatcher = mainDispatcherRule.testDispatcher,
    )

    @Test
    fun givenUserSystemAndOwnApps_whenLoaded_thenOnlyUserAppsCount() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        val files = viewModel.uiState.value.files
        assertEquals(listOf("com.example.notes"), files?.readers?.map { it.packageName })
        assertEquals(true, viewModel.uiState.value.recording)
    }

    @Test
    fun givenLoadedOverview_whenSystemAppsShown_thenRecountedWithoutReadingAgain() = runTest {
        val viewModel = viewModel()
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
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(1, moduleRepository.updates)
        assertEquals(ModuleUpdate.Idle, viewModel.uiState.value.moduleUpdate)
        assertEquals(listOf("com.example.notes"), viewModel.uiState.value.files?.readers?.map { it.packageName })
    }

    @Test
    fun givenCurrentModule_whenRefreshed_thenComparedOnlyOnce() = runTest {
        val viewModel = viewModel()
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
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(1, moduleRepository.updates)
        assertEquals(LoadFailure.ModuleOutdated, viewModel.uiState.value.failure)
    }

    @Test
    fun givenNoRoot_whenLoaded_thenFailureShownAndModuleLeftAlone() = runTest {
        accessRepository.failure = RecordsException.NoRoot()
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(LoadFailure.NoRoot, viewModel.uiState.value.failure)
        assertNull(viewModel.uiState.value.files)
        assertEquals(0, moduleRepository.updates)
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
