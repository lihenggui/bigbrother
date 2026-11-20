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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppListViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun givenUnsavedFilters_whenRangeRetriedAndOtherDismissed_thenOnlyRangeChanges() = runTest {
        val settings = TestUserDataRepository()
        val viewModel = AppListViewModel(TestAccessRepository(), TestAppInfoRepository(), settings)
        settings.writeFailure = IOException("Storage unavailable")

        viewModel.setRange(TimeRange.WEEK)
        viewModel.setShowSystemApps(true)
        advanceUntilIdle()

        val failures = viewModel.settingsWriteFailures.value
        assertEquals(2, failures.size)
        assertEquals(TimeRange.DAY, viewModel.uiState.value.range)
        assertFalse(viewModel.uiState.value.showSystemApps)

        settings.writeFailure = null
        viewModel.resolveSettingsWrite(failures[0], retry = true)
        viewModel.resolveSettingsWrite(failures[1], retry = false)
        advanceUntilIdle()

        assertEquals(TimeRange.WEEK, viewModel.uiState.value.range)
        assertFalse(viewModel.uiState.value.showSystemApps)
        assertTrue(viewModel.settingsWriteFailures.value.isEmpty())
    }
}
