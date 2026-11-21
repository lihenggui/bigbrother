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

package li.mercury.bigbrother.feature.timeline.impl

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.AppIconSource
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelineEntry
import li.mercury.bigbrother.core.model.TimelinePage
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun givenHiddenFirstPage_whenLoadingMoreAndShowingSystemApps_thenKeepsCursorAndReusesBothPages() = runTest {
        val system = entry(10001, "com.example.system", 7200L)
        val own = entry(10002, OWN_PACKAGE, 7200L)
        val isolated = entry(99001, "", 3600L)
        val user = entry(10003, "com.example.user", 3600L)
        val cursors = mutableListOf<Long?>()
        val access = object : AccessRepository by TestAccessRepository() {
            override suspend fun getTimeline(range: TimeRange, before: Long?, limit: Int): TimelinePage {
                cursors += before
                return if (before == null) TimelinePage(listOf(system, own), 7200L) else TimelinePage(listOf(isolated, user), null)
            }
        }
        val systemInfo = AppInfo("System", AppIconSource(system.packageName, system.uid, lastUpdateTime = 0), isSystem = true)
        val info = TestAppInfoRepository(mapOf(system.packageName to systemInfo))
        val settings = TestUserDataRepository()
        val viewModel = TimelineViewModel(access, info, GetAppVisibilityUseCase(settings, OWN_PACKAGE), mainDispatcherRule.testDispatcher)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(emptyList(), viewModel.uiState.value.entries)
        assertEquals(7200L, viewModel.uiState.value.nextBefore)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(user), viewModel.uiState.value.entries)

        settings.setShowSystemApps(true)
        advanceUntilIdle()
        assertEquals(listOf(system, isolated, user), viewModel.uiState.value.entries)
        assertEquals(listOf(null, 7200L), cursors)
    }

    private fun entry(uid: Int, packageName: String, hour: Long) = TimelineEntry(
        hourStart = hour,
        uid = uid,
        packageName = packageName,
        category = Category.STORAGE,
        topic = null,
        paths = 1,
        filesRead = 1,
        allowed = 1,
        denied = 0,
        modes = 1,
        items = 0,
        folder = null,
        firstSeen = hour,
        lastSeen = hour,
    )

    private companion object {
        const val OWN_PACKAGE = "com.example.recorder"
    }
}
