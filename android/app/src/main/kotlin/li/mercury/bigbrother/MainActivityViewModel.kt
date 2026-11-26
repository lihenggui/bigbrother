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

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.FirstLaunchRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.model.ThemeMode
import javax.inject.Inject

@HiltViewModel
internal class MainActivityViewModel @Inject constructor(
    userDataRepository: UserDataRepository,
    accessRepository: AccessRepository,
    firstLaunch: FirstLaunchRepository,
) : ViewModel() {
    private val showGuide = MutableStateFlow<Boolean?>(null)

    /** Whether this session may still show the guide, read before the claim below settles it. */
    val mayShowGuide: Boolean = firstLaunch.isUnclaimed()

    val uiState: StateFlow<MainActivityUiState> = combine(
        userDataRepository.userData,
        showGuide.filterNotNull(),
        accessRepository.recorderStatus,
    ) { settings, guideVisible, recorder ->
        MainActivityUiState.Ready(
            useDynamicColor = settings.useDynamicColor,
            themeMode = settings.themeMode,
            showGuide = guideVisible,
            recordingStopped = recorder?.recording == false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainActivityUiState.Loading)

    init {
        viewModelScope.launch {
            // Leaving during the splash screen may already have dismissed the guide.
            showGuide.compareAndSet(null, firstLaunch.claim())
        }
    }

    fun dismissGuide() {
        showGuide.value = false
    }
}

@Immutable
internal sealed interface MainActivityUiState {
    data object Loading : MainActivityUiState

    data class Ready(
        val useDynamicColor: Boolean,
        val themeMode: ThemeMode,
        val showGuide: Boolean,
        val recordingStopped: Boolean,
    ) : MainActivityUiState
}
