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

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelineEntry
import javax.inject.Inject

data class TimelineUiState(
    /** Visible entries loaded so far, most recent first; null until the first page is in. */
    val entries: List<TimelineEntry>? = null,
    /** Where the next, older, page starts; null when everything is loaded. */
    val nextBefore: Long? = null,
    val loadingMore: Boolean = false,
    /** Loading the next page failed; it is tried again on request, not on its own. */
    val loadMoreFailed: Boolean = false,
    val refreshing: Boolean = false,
    val appInfo: Map<String, AppInfo> = emptyMap(),
    val failure: LoadFailure? = null,
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    getAppVisibility: GetAppVisibilityUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var loadedEntries: List<TimelineEntry>? = null
    private var visibility = getAppVisibility.default

    /** When the most recent page was loaded, in milliseconds since boot. */
    private var loadedAt = 0L

    init {
        viewModelScope.launch {
            getAppVisibility().collect { updated ->
                visibility = updated
                _uiState.update { state ->
                    state.copy(entries = loadedEntries?.let { updated.visibleEntries(it, state.appInfo) })
                }
            }
        }
    }

    /**
     * Load the most recent page again, unless it was loaded moments ago:
     * reloading drops the older pages, and with them the user's place in a
     * long timeline, each time the screen comes back.
     */
    fun refreshIfStale() {
        if (_uiState.value.entries == null || SystemClock.elapsedRealtime() - loadedAt > STALE_AFTER_MS) refresh()
    }

    /** Load the most recent page again, dropping older pages. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                val page = accessRepository.getTimeline(TimeRange.ALL, before = null, limit = PAGE_SIZE)
                val appInfo = appInfoFor(page.entries)
                loadedEntries = page.entries
                _uiState.update {
                    it.copy(
                        entries = visibility.visibleEntries(page.entries, appInfo),
                        nextBefore = page.nextBefore,
                        appInfo = appInfo,
                        refreshing = false,
                        loadingMore = false,
                        failure = null,
                    )
                }
                loadedAt = SystemClock.elapsedRealtime()
            } catch (e: RecordsException) {
                // A page that was loading when the refresh began was cancelled with it.
                _uiState.update { it.copy(refreshing = false, loadingMore = false, failure = e.toLoadFailure()) }
            }
        }
    }

    /** Load the next, older, page, if there is one. */
    fun loadMore() {
        val state = _uiState.value
        val before = state.nextBefore ?: return
        if (state.loadingMore || loadJob?.isActive == true) return
        _uiState.update { it.copy(loadingMore = true, loadMoreFailed = false) }
        loadJob = viewModelScope.launch {
            try {
                val page = accessRepository.getTimeline(TimeRange.ALL, before = before, limit = PAGE_SIZE)
                val appInfo = appInfoFor(page.entries)
                val entries = loadedEntries.orEmpty() + page.entries
                loadedEntries = entries
                _uiState.update {
                    val allAppInfo = it.appInfo + appInfo
                    it.copy(
                        entries = visibility.visibleEntries(entries, allAppInfo),
                        nextBefore = page.nextBefore,
                        appInfo = allAppInfo,
                        loadingMore = false,
                    )
                }
            } catch (_: RecordsException) {
                // What is already shown stays, with a way to try again below it.
                _uiState.update { it.copy(loadingMore = false, loadMoreFailed = true) }
            }
        }
    }

    private suspend fun appInfoFor(entries: List<TimelineEntry>): Map<String, AppInfo> = appInfoRepository.getAppInfo(entries.map { it.packageName }.filter { it.isNotEmpty() }.toSet())

    private companion object {
        /** Entries after which a page ends; the daemon finishes the day it is in. */
        const val PAGE_SIZE = 300

        /** How old the loaded pages may get before returning to the screen reloads them. */
        const val STALE_AFTER_MS = 60_000L
    }
}
