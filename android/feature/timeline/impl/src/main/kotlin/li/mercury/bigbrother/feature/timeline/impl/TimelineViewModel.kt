package li.mercury.bigbrother.feature.timeline.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelineEntry

data class TimelineUiState(
    /** Everything loaded so far, most recent first; null until the first page is in. */
    val entries: List<TimelineEntry>? = null,
    /** Where the next, older, page starts; null when everything is loaded. */
    val nextBefore: Long? = null,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val appInfo: Map<String, AppInfo> = emptyMap(),
    val showSystemApps: Boolean = false,
    val failure: LoadFailure? = null,
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    userDataRepository: UserDataRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            userDataRepository.userData.map { it.showSystemApps }.distinctUntilChanged().collect { show ->
                _uiState.update { it.copy(showSystemApps = show) }
            }
        }
    }

    /** Load the most recent page again, dropping older pages. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                val page = accessRepository.getTimeline(TimeRange.ALL, before = null, limit = PAGE_SIZE)
                val appInfo = appInfoFor(page.entries)
                _uiState.update {
                    it.copy(
                        entries = page.entries,
                        nextBefore = page.nextBefore,
                        appInfo = appInfo,
                        refreshing = false,
                        loadingMore = false,
                        failure = null,
                    )
                }
            } catch (e: RecordsException) {
                _uiState.update { it.copy(refreshing = false, failure = e.toLoadFailure()) }
            }
        }
    }

    /** Load the next, older, page, if there is one. */
    fun loadMore() {
        val state = _uiState.value
        val before = state.nextBefore ?: return
        if (state.loadingMore || loadJob?.isActive == true) return
        _uiState.update { it.copy(loadingMore = true) }
        loadJob = viewModelScope.launch {
            try {
                val page = accessRepository.getTimeline(TimeRange.ALL, before = before, limit = PAGE_SIZE)
                val appInfo = appInfoFor(page.entries)
                _uiState.update {
                    it.copy(
                        entries = it.entries.orEmpty() + page.entries,
                        nextBefore = page.nextBefore,
                        appInfo = it.appInfo + appInfo,
                        loadingMore = false,
                    )
                }
            } catch (_: RecordsException) {
                // What is already shown stays; reaching the end again retries.
                _uiState.update { it.copy(loadingMore = false) }
            }
        }
    }

    private suspend fun appInfoFor(entries: List<TimelineEntry>): Map<String, AppInfo> =
        appInfoRepository.getAppInfo(entries.map { it.packageName }.filter { it.isNotEmpty() }.toSet())

    private companion object {
        /** Entries after which a page ends; the daemon finishes the day it is in. */
        const val PAGE_SIZE = 300
    }
}
