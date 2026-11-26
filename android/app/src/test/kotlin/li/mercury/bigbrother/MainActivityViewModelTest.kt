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

package li.mercury.bigbrother

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.model.ThemeMode
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestFirstLaunchRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun givenPendingFirstLaunch_whenUserLeaves_thenGuideStaysDismissed() = runTest {
        val decision = CompletableDeferred<Boolean>()
        val viewModel = MainActivityViewModel(TestUserDataRepository(), TestAccessRepository(), TestFirstLaunchRepository { decision.await() })
        try {
            runCurrent()
            assertEquals(MainActivityUiState.Loading, viewModel.uiState.value)
            viewModel.dismissGuide()
            decision.complete(true)
            runCurrent()
            assertEquals(false, (viewModel.uiState.value as MainActivityUiState.Ready).showGuide)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun givenFreshInstall_whenGuideIsDismissed_thenSettingsChangesKeepItDismissed() = runTest {
        val settings = TestUserDataRepository()
        val viewModel = MainActivityViewModel(settings, TestAccessRepository(), TestFirstLaunchRepository { true })
        try {
            runCurrent()
            assertEquals(true, (viewModel.uiState.value as MainActivityUiState.Ready).showGuide)
            viewModel.dismissGuide()
            settings.setUseDynamicColor(false)
            settings.setThemeMode(ThemeMode.DARK)
            runCurrent()
            assertEquals(
                MainActivityUiState.Ready(useDynamicColor = false, themeMode = ThemeMode.DARK, showGuide = false, recordingStopped = false),
                viewModel.uiState.value,
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
