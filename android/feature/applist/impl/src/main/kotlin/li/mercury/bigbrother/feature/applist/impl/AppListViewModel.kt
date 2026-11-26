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

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
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
import li.mercury.bigbrother.core.ui.UserSettingsWriter
import timber.log.Timber
import javax.inject.Inject

data class AppListUiState(
    val range: TimeRange = TimeRange.DAY,
    /** Null until the first load finishes. */
    val apps: List<AppSummary>? = null,
    /** Names and icons by package name. */
    val appInfo: Map<String, AppInfo> = emptyMap(),
    val refreshing: Boolean = false,
    val failure: LoadFailure? = null,
    /** Whether apps that are part of the system image are listed. */
    val showSystemApps: Boolean = false,
    val hiddenApps: Int = 0,
    val message: UserSettingsWriter.Failure? = null,
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val getAppRecords: GetAppRecordsUseCase,
    getAppVisibility: GetAppVisibilityUseCase,
    private val userDataRepository: UserDataRepository,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val settingsWriter = UserSettingsWriter(viewModelScope) { Timber.w(it) }
    private val refreshes = MutableStateFlow(0L)
    private val load = MutableStateFlow(AppListLoad())
    private var completed: Pair<TimeRange, Long>? = null

    /** When the last successful load finished, on the elapsed realtime clock. */
    private var loadedAt = 0L

    private val ranges = userDataRepository.userData.map { it.timeRange }.distinctUntilChanged()

    val uiState: StateFlow<AppListUiState> = channelFlow {
        launch {
            combine(ranges, refreshes, ::Pair).collectLatest { request ->
                if (request != completed) loadApps(request)
            }
        }
        combine(load, getAppVisibility()) { state, visibility ->
            state.ui.withVisibility(state.records, visibility)
        }.combine(settingsWriter.message) { state, message ->
            state.copy(message = message)
        }.collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppListUiState())

    internal fun present(filters: Flow<AppListFilter>, displayName: (AppSummary, AppInfo?) -> String): Flow<AppListPresentation> = combine(uiState, filters) { state, filter -> state.present(filter, displayName) }.flowOn(defaultDispatcher)

    /** Reload when the screen is shown again and the data is stale. */
    fun refreshIfStale() {
        if (completed?.second != refreshes.value || load.value.ui.refreshing) return
        if (SystemClock.elapsedRealtime() - loadedAt >= STALE_AFTER_MS) refresh()
    }

    fun refresh() {
        refreshes.update { it + 1 }
    }

    private suspend fun loadApps(request: Pair<TimeRange, Long>) {
        val range = request.first
        load.update { it.copy(ui = it.ui.copy(range = range, refreshing = true)) }
        try {
            val loaded = getAppRecords(range, forceRefresh = request.second != (completed?.second ?: 0L))
            currentCoroutineContext().ensureActive()
            loadedAt = SystemClock.elapsedRealtime()
            completed = request
            load.update { AppListLoad(it.ui.copy(refreshing = false, failure = null), loaded) }
        } catch (e: RecordsException) {
            currentCoroutineContext().ensureActive()
            completed = request
            load.update { it.copy(ui = it.ui.copy(refreshing = false, failure = e.toLoadFailure())) }
        } finally {
            load.update { it.copy(ui = it.ui.copy(refreshing = false)) }
        }
    }

    private fun AppListUiState.withVisibility(records: AppRecords?, visibility: AppVisibility): AppListUiState {
        val shown = records?.let(visibility::visibleApps)
        return copy(
            apps = shown?.apps,
            appInfo = records?.appInfo.orEmpty(),
            showSystemApps = visibility.showSystemApps,
            hiddenApps = shown?.hiddenCount ?: 0,
        )
    }

    fun setRange(range: TimeRange) = settingsWriter.save(UserSettingsWriter.Setting.TIME_RANGE) {
        userDataRepository.setTimeRange(range)
    }

    fun setShowSystemApps(show: Boolean) = settingsWriter.save(UserSettingsWriter.Setting.SHOW_SYSTEM_APPS) {
        userDataRepository.setShowSystemApps(show)
    }

    fun onMessageShown(failure: UserSettingsWriter.Failure, retry: Boolean) = settingsWriter.resolve(failure, retry)
}

private data class AppListLoad(val ui: AppListUiState = AppListUiState(), val records: AppRecords? = null)

/** How soon after a load the screen being shown again reloads it. */
private const val STALE_AFTER_MS = 10_000L
