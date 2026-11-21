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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.domain.GetAppRecordsUseCase
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.dataCategories
import li.mercury.bigbrother.core.ui.readFiles

data class CategoryUiState(
    val category: DataCategory,
    val range: TimeRange = TimeRange.DAY,
    /** Apps that accessed the category, in the order listed; null until loaded. */
    val apps: List<AppSummary>? = null,
    val appInfo: Map<String, AppInfo> = emptyMap(),
    val failure: LoadFailure? = null,
)

@HiltViewModel(assistedFactory = CategoryViewModel.Factory::class)
class CategoryViewModel @AssistedInject constructor(
    private val getAppRecords: GetAppRecordsUseCase,
    private val getAppVisibility: GetAppVisibilityUseCase,
    private val userDataRepository: UserDataRepository,
    @Assisted category: DataCategory,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CategoryUiState(category))
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val userData = userDataRepository.userData.first()
                val category = _uiState.value.category
                val visibility = getAppVisibility.forSettings(userData)
                val records = getAppRecords(userData.timeRange)
                val shown = visibility.visibleApps(records).apps
                    .filter { category in it.dataCategories() }
                    // For files, apps that read some come before those that only looked.
                    .sortedWith(
                        compareByDescending<AppSummary> { category == DataCategory.FILES && it.readFiles }
                            .thenByDescending { it.lastSeen },
                    )
                _uiState.update {
                    it.copy(range = userData.timeRange, apps = shown, appInfo = records.appInfo, failure = null)
                }
            } catch (e: RecordsException) {
                _uiState.update { it.copy(apps = emptyList(), failure = e.toLoadFailure()) }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(category: DataCategory): CategoryViewModel
    }
}
