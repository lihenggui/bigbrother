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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelineEntry
import java.time.ZoneId
import javax.inject.Inject

data class TimelineUiState(
    /** Visible entries loaded so far, most recent first; null until the first page is in. */
    val entries: List<TimelineEntry>? = null,
    /** Where the next, older, page starts; null when everything is loaded. */
    val nextBefore: Long? = null,
    val loadingMore: Boolean = false,
    /** Loading the next page failed; it is tried again on request, not on its own. */
    val loadMoreFailure: LoadFailure? = null,
    val refreshing: Boolean = false,
    val appInfo: Map<String, AppInfo> = emptyMap(),
    val failure: LoadFailure? = null,
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    getAppVisibility: GetAppVisibilityUseCase,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val load = MutableStateFlow(TimelineUiState())
    private val requests = MutableStateFlow(PageRequest())
    private var completed: PageRequest? = null

    /** When the first page was loaded, in milliseconds since boot. */
    private var loadedAt = 0L

    val uiState: StateFlow<TimelineUiState> = channelFlow {
        launch {
            requests.collectLatest { request ->
                if (request != completed) readPage(request)
            }
        }
        combine(load, getAppVisibility()) { state, visibility ->
            state.copy(entries = state.entries?.let { visibility.visibleEntries(it, state.appInfo) })
        }.collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())

    internal fun present(filters: Flow<TimelineFilter>, zone: ZoneId): Flow<TimelinePresentation> = combine(uiState, filters) { state, filter -> state.present(filter, zone) }
        .flowOn(defaultDispatcher)

    /**
     * Reloading drops the older pages, and with them the user's place in a
     * long timeline. Keep recent pages when returning to the screen.
     */
    fun refreshIfStale() {
        if (completed != requests.value) return
        if (load.value.entries == null || SystemClock.elapsedRealtime() - loadedAt > STALE_AFTER_MS) refresh()
    }

    /** Load the most recent page again, dropping older pages. */
    fun refresh() {
        requests.update { PageRequest(it.version + 1) }
    }

    /** Load the next, older, page, if there is one. */
    fun loadMore() {
        val before = load.value.nextBefore ?: return
        requests.update { if (it == completed) PageRequest(it.version + 1, before) else it }
    }

    private suspend fun readPage(request: PageRequest) {
        val first = request.before == null
        load.update { it.copy(refreshing = first, loadingMore = !first, loadMoreFailure = it.loadMoreFailure.takeIf { first }) }
        try {
            val page = accessRepository.getTimeline(TimeRange.ALL, before = request.before, limit = PAGE_SIZE)
            val info = appInfoFor(page.entries)
            currentCoroutineContext().ensureActive()
            if (first) loadedAt = SystemClock.elapsedRealtime()
            completed = request
            load.update {
                it.copy(
                    entries = if (first) page.entries else it.entries.orEmpty() + page.entries,
                    nextBefore = page.nextBefore,
                    appInfo = if (first) info else it.appInfo + info,
                    failure = if (first) null else it.failure,
                    loadMoreFailure = null,
                )
            }
        } catch (e: RecordsException) {
            currentCoroutineContext().ensureActive()
            completed = request
            // Keep the existing pages and offer a retry where loading failed.
            load.update {
                if (first) it.copy(failure = e.toLoadFailure()) else it.copy(loadMoreFailure = e.toLoadFailure())
            }
        } finally {
            load.update { it.copy(refreshing = false, loadingMore = false) }
        }
    }

    private data class PageRequest(val version: Long = 0, val before: Long? = null)

    private suspend fun appInfoFor(entries: List<TimelineEntry>): Map<String, AppInfo> = appInfoRepository.getAppInfo(entries.map { it.packageName }.filter { it.isNotEmpty() }.toSet())

    private companion object {
        /** Entries after which a page ends; the daemon finishes the day it is in. */
        const val PAGE_SIZE = 300

        /** How old the loaded pages may get before returning to the screen reloads them. */
        const val STALE_AFTER_MS = 60_000L
    }
}
