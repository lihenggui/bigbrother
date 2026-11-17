package li.mercury.bigbrother.feature.applist.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange

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
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    private val userDataRepository: UserDataRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            userDataRepository.userData.map { it.showSystemApps }.distinctUntilChanged().collect { show ->
                _uiState.update { it.copy(showSystemApps = show) }
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

    /** Load, or reload, the list. The screen also calls this each time it is resumed. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                val range = userDataRepository.userData.first().timeRange
                val apps = accessRepository.getApps(range)
                val appInfo = appInfoRepository.getAppInfo(
                    apps.map { it.packageName }.filter { it.isNotEmpty() }.toSet(),
                )
                _uiState.update {
                    it.copy(range = range, apps = apps, appInfo = appInfo, refreshing = false, failure = null)
                }
            } catch (e: RecordsException) {
                _uiState.update { it.copy(refreshing = false, failure = e.toLoadFailure()) }
            }
        }
    }

    fun setRange(range: TimeRange) {
        viewModelScope.launch { userDataRepository.setTimeRange(range) }
    }

    fun setShowSystemApps(show: Boolean) {
        viewModelScope.launch { userDataRepository.setShowSystemApps(show) }
    }
}
