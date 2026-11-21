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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.data.repository.UserSettingsWriter
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.domain.AppRecords
import li.mercury.bigbrother.core.domain.AppVisibility
import li.mercury.bigbrother.core.domain.GetAppRecordsUseCase
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
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
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val getAppRecords: GetAppRecordsUseCase,
    getAppVisibility: GetAppVisibilityUseCase,
    private val userDataRepository: UserDataRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    private val settingsWriter = UserSettingsWriter(viewModelScope, userDataRepository)
    val settingsWriteFailures = settingsWriter.failures

    private var loadJob: Job? = null
    private var records: AppRecords? = null
    private var visibility = getAppVisibility.default

    /** When the last load finished, on the elapsed realtime clock. */
    private var loadedAt = 0L

    init {
        viewModelScope.launch {
            getAppVisibility().collect { updated ->
                visibility = updated
                _uiState.update { it.withVisibility(records, updated) }
            }
        }
        // A range chosen here or on the overview reloads the list.
        viewModelScope.launch {
            userDataRepository.userData.map { it.timeRange }.distinctUntilChanged().collect { range ->
                _uiState.update { it.copy(range = range) }
                refresh()
            }
        }
    }

    /**
     * Reload when the screen is shown again, unless a load is running or has
     * just finished: at launch, the first load is still on its way.
     */
    fun refreshIfStale() {
        if (loadJob?.isActive == true) return
        if (SystemClock.elapsedRealtime() - loadedAt < STALE_AFTER_MS) return
        refresh()
    }

    /** Load, or reload, the list. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                val range = userDataRepository.userData.first().timeRange
                val loaded = getAppRecords(range)
                records = loaded
                loadedAt = SystemClock.elapsedRealtime()
                _uiState.update {
                    it.withVisibility(loaded, visibility).copy(range = range, refreshing = false, failure = null)
                }
            } catch (e: RecordsException) {
                _uiState.update { it.copy(refreshing = false, failure = e.toLoadFailure()) }
            }
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

    fun setRange(range: TimeRange) = settingsWriter.setTimeRange(range)

    fun setShowSystemApps(show: Boolean) = settingsWriter.setShowSystemApps(show)

    fun resolveSettingsWrite(failure: UserSettingsWriter.Failure, retry: Boolean) = settingsWriter.resolve(failure, retry)
}

/** How soon after a load the screen being shown again reloads it. */
private const val STALE_AFTER_MS = 10_000L
