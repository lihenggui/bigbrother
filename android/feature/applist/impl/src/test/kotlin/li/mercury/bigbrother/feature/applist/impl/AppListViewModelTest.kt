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

package li.mercury.bigbrother.feature.applist.impl

import android.content.pm.PackageInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.domain.GetAppRecordsUseCase
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import org.junit.Rule
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AppListViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun givenUnsavedFilters_whenRangeRetriedAndOtherDismissed_thenOnlyRangeChanges() = runTest {
        val settings = TestUserDataRepository()
        val viewModel = AppListViewModel(GetAppRecordsUseCase(TestAccessRepository(), TestAppInfoRepository()), GetAppVisibilityUseCase(settings, OWN_PACKAGE), settings, mainDispatcherRule.testDispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        settings.writeFailure = IOException("Storage unavailable")

        viewModel.setRange(TimeRange.WEEK)
        viewModel.setShowSystemApps(true)
        advanceUntilIdle()

        val rangeFailure = assertNotNull(viewModel.uiState.value.message)
        assertEquals(TimeRange.DAY, viewModel.uiState.value.range)
        assertFalse(viewModel.uiState.value.showSystemApps)

        settings.writeFailure = null
        viewModel.onMessageShown(rangeFailure, retry = true)
        advanceUntilIdle()
        val systemAppsFailure = assertNotNull(viewModel.uiState.value.message)
        viewModel.onMessageShown(systemAppsFailure, retry = false)
        advanceUntilIdle()

        assertEquals(TimeRange.WEEK, viewModel.uiState.value.range)
        assertFalse(viewModel.uiState.value.showSystemApps)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun givenHiddenAndOwnRecords_whenShowingSystemApps_thenReusesTheLoadedRecords() = runTest {
        val system = AppSummary(10001, "com.example.system", emptyMap(), lastSeen = 2)
        val own = AppSummary(10002, OWN_PACKAGE, emptyMap(), lastSeen = 1)
        val access = TestAccessRepository(apps = listOf(system, own))
        val info = TestAppInfoRepository(mapOf(system.packageName to AppInfo("System", PackageInfo(), isSystem = true)))
        val settings = TestUserDataRepository()
        val viewModel = AppListViewModel(GetAppRecordsUseCase(access, info), GetAppVisibilityUseCase(settings, OWN_PACKAGE), settings, mainDispatcherRule.testDispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(emptyList(), viewModel.uiState.value.apps)
        assertEquals(1, viewModel.uiState.value.hiddenApps)

        viewModel.setShowSystemApps(true)
        advanceUntilIdle()

        assertEquals(listOf(system), viewModel.uiState.value.apps)
        assertEquals(0, viewModel.uiState.value.hiddenApps)
        assertEquals(1, access.appReads)
    }

    private companion object {
        const val OWN_PACKAGE = "com.example.recorder"
    }
}
