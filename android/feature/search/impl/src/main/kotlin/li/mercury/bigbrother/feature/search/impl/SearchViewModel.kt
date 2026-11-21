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

package li.mercury.bigbrother.feature.search.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.domain.SearchRecordsUseCase
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.dataCategories
import li.mercury.bigbrother.core.ui.topicCategory
import li.mercury.bigbrother.core.ui.topicTitle
import javax.inject.Inject

/** A kind of data whose name matched, and how many apps accessed it. */
internal data class DataMatch(val category: DataCategory, val topic: String?, val apps: Int)

internal data class SearchUiState(
    val query: String = "",
    val apps: List<AppSummary> = emptyList(),
    val files: List<PathMatch> = emptyList(),
    val data: List<DataMatch> = emptyList(),
    val appInfo: Map<String, AppInfo> = emptyMap(),
    val complete: Boolean = true,
    val failure: LoadFailure? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRecords: SearchRecordsUseCase,
    @Dispatcher(DEFAULT) defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val request = MutableStateFlow(SearchRequest())

    private val completed = request
        .mapLatest { current ->
            val state = if (current.text.isEmpty()) {
                SearchUiState()
            } else {
                // The delay belongs to this request so even a change during it cancels the old search.
                delay(DEBOUNCE_MS)
                search(current)
            }
            current.version to state
        }
        .flowOn(defaultDispatcher)
        .onStart { emit(0L to SearchUiState()) }

    internal val uiState: StateFlow<SearchUiState> = combine(request, completed) { current, (version, state) ->
        when {
            current.text.isEmpty() -> SearchUiState()

            current.version == version -> state

            // A running shell command may take time to finish after cancellation.
            // Keep earlier results under the searching label while the next search waits.
            else -> if (state.failure == null) {
                state.copy(query = current.text, complete = false)
            } else {
                SearchUiState(query = current.text, complete = false)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    internal fun setQuery(text: String, titleIds: Set<Int>) {
        val trimmed = text.trim()
        request.update { current ->
            if (current.text == trimmed && current.titleIds == titleIds) current else SearchRequest(trimmed, titleIds, current.version + 1)
        }
    }

    internal fun retry() {
        request.update { it.copy(version = it.version + 1) }
    }

    private suspend fun search(current: SearchRequest): SearchUiState = try {
        val results = searchRecords(current.text)
        SearchUiState(
            query = current.text,
            apps = results.apps,
            files = results.files,
            data = dataMatches(current.titleIds, results.visibleApps),
            appInfo = results.appInfo,
        )
    } catch (e: RecordsException) {
        SearchUiState(query = current.text, failure = e.toLoadFailure())
    }

    private fun dataMatches(titleIds: Set<Int>, apps: List<AppSummary>): List<DataMatch> {
        if (titleIds.isEmpty()) return emptyList()
        val categoryCounts = apps.flatMap { it.dataCategories() }.groupingBy { it }.eachCount()
        val topicCounts = apps.flatMap { it.topics.distinct() }.groupingBy { it }.eachCount()
        return buildList {
            for (category in DataCategory.entries) {
                if (category.title in titleIds) {
                    categoryCounts[category]?.let { add(DataMatch(category, topic = null, apps = it)) }
                }
            }
            for ((topic, count) in topicCounts) {
                val title = topicTitle(topic) ?: continue
                if (title !in titleIds) continue
                val category = topicCategory(topic) ?: continue
                add(DataMatch(category, topic, count))
            }
        }
    }

    private data class SearchRequest(val text: String = "", val titleIds: Set<Int> = emptySet(), val version: Long = 0)

    private companion object {
        const val DEBOUNCE_MS = 250L
    }
}
