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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.domain.AppRecords
import li.mercury.bigbrother.core.domain.AppVisibility
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CategoryViewModel.Factory::class)
class CategoryViewModel @AssistedInject constructor(
    private val getAppRecords: GetAppRecordsUseCase,
    getAppVisibility: GetAppVisibilityUseCase,
    userDataRepository: UserDataRepository,
    @Assisted private val category: DataCategory,
) : ViewModel() {
    private val refreshes = MutableStateFlow(0L)

    private var lastRefresh = 0L
    private val ranges = userDataRepository.userData.map { it.timeRange }.distinctUntilChanged()
    private val records = combine(ranges, refreshes, ::Pair).mapLatest { (range, refresh) ->
        val loaded = read(range, forceRefresh = refresh != lastRefresh)
        lastRefresh = refresh
        loaded
    }

    val uiState: StateFlow<CategoryUiState> = combine(records, getAppVisibility(), ::present)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryUiState(category))

    fun refresh() {
        refreshes.update { it + 1 }
    }

    private suspend fun read(range: TimeRange, forceRefresh: Boolean): CategoryLoad = try {
        CategoryLoad(range, records = getAppRecords(range, forceRefresh))
    } catch (e: RecordsException) {
        CategoryLoad(range, failure = e.toLoadFailure())
    }

    private fun present(load: CategoryLoad, visibility: AppVisibility): CategoryUiState {
        val records = load.records ?: return CategoryUiState(category, load.range, emptyList(), failure = load.failure)
        val shown = visibility.visibleApps(records).apps
            .filter { category in it.dataCategories() }
            // For files, apps that read some come before those that only looked.
            .sortedWith(
                compareByDescending<AppSummary> { category == DataCategory.FILES && it.readFiles }
                    .thenByDescending { it.lastSeen },
            )
        return CategoryUiState(category, load.range, shown, records.appInfo)
    }

    private data class CategoryLoad(val range: TimeRange, val records: AppRecords? = null, val failure: LoadFailure? = null)

    @AssistedFactory
    interface Factory {
        fun create(category: DataCategory): CategoryViewModel
    }
}
