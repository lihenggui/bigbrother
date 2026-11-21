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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.domain.GetAppRecordsUseCase
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.AppIconSource
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.UserData
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.data.testAppSummary
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import li.mercury.bigbrother.core.ui.DataCategory
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val olderRead = testAppSummary(10001, "com.example.notes", mapOf(Category.STORAGE to 1), lastSeen = 100, filesRead = 1)
    private val newerRead = testAppSummary(10002, "com.example.photos", mapOf(Category.STORAGE to 1), lastSeen = 200, filesRead = 1)
    private val lookup = testAppSummary(10003, "com.example.browser", mapOf(Category.STORAGE to 1), lastSeen = 300)
    private val system = testAppSummary(10004, "com.example.system", mapOf(Category.STORAGE to 1), lastSeen = 400, filesRead = 1)
    private val camera = testAppSummary(10005, "com.example.camera", mapOf(Category.PERMISSION to 1), lastSeen = 500, topics = listOf("camera"))
    private val access = TestAccessRepository(apps = listOf(olderRead, lookup, system, camera, newerRead))
    private val systemInfo = AppInfo("System", AppIconSource(system.packageName, system.uid, lastUpdateTime = 0), isSystem = true)
    private val appInfo = TestAppInfoRepository(mapOf(system.packageName to systemInfo))
    private val settings = TestUserDataRepository(UserData(timeRange = TimeRange.WEEK))

    @Test
    fun givenMixedRecords_whenFilesAreLoaded_thenVisibleReadersPrecedeNewerLookups() = runTest {
        val viewModel = collectState()
        runCurrent()

        assertEquals(DataCategory.FILES, viewModel.uiState.value.category)
        assertEquals(TimeRange.WEEK, viewModel.uiState.value.range)
        assertEquals(listOf(newerRead, olderRead, lookup), viewModel.uiState.value.apps)

        settings.setShowSystemApps(true)
        viewModel.refresh()
        runCurrent()

        assertEquals(listOf(system, newerRead, olderRead, lookup), viewModel.uiState.value.apps)
    }

    @Test
    fun givenNoRoot_whenRefreshedAfterGrant_thenFailureClearsAndRecordsAppear() = runTest {
        access.failure = RecordsException.NoRoot()
        val viewModel = collectState()
        runCurrent()

        assertEquals(LoadFailure.NoRoot, viewModel.uiState.value.failure)
        assertEquals(emptyList(), viewModel.uiState.value.apps)

        access.failure = null
        viewModel.refresh()
        runCurrent()

        assertEquals(listOf(newerRead, olderRead, lookup), viewModel.uiState.value.apps)
        assertNull(viewModel.uiState.value.failure)
    }

    private fun TestScope.collectState(): CategoryViewModel {
        val viewModel = CategoryViewModel(
            GetAppRecordsUseCase(access, appInfo),
            GetAppVisibilityUseCase(settings, "com.example.recorder"),
            settings,
            DataCategory.FILES,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }
}
