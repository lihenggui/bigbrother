package li.mercury.bigbrother.feature.applist.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.ModuleRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange

/** Progress of flashing the module from inside the app. */
sealed interface ModuleSetup {
    data object Idle : ModuleSetup
    data object Installing : ModuleSetup

    /** Installed, but the manager could not apply it without a reboot. */
    data object NeedsReboot : ModuleSetup
    data class Failed(val output: String) : ModuleSetup
}

data class AppListUiState(
    val range: TimeRange = TimeRange.DAY,
    /** Null until the first load finishes. */
    val apps: List<AppSummary>? = null,
    /** Names and icons by package name. */
    val appInfo: Map<String, AppInfo> = emptyMap(),
    /** Whether the daemon is recording; null until known. */
    val recording: Boolean? = null,
    val refreshing: Boolean = false,
    val failure: LoadFailure? = null,
    val moduleSetup: ModuleSetup = ModuleSetup.Idle,
    /** The installed module is older than the one bundled in this app. */
    val moduleOutdated: Boolean = false,
    /** A module update is installed and waits for a restart. */
    val modulePendingReboot: Boolean = false,
    /**
     * Whether apps that are part of the system image are listed. Off by
     * default: system components read shared storage routinely and would
     * dominate the list.
     */
    val showSystemApps: Boolean = false,
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val moduleRepository: ModuleRepository,
    private val appInfoRepository: AppInfoRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** Load, or reload, the list. The screen calls this each time it is resumed. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                val apps = accessRepository.getApps(_uiState.value.range)
                val recording = accessRepository.isRecording()
                val pending = moduleRepository.isUpdatePending()
                // An update that is already staged makes the installed copy
                // look current; it isn't running until the restart.
                val outdated = !pending && moduleRepository.isOutdated()
                val appInfo = appInfoRepository.getAppInfo(
                    apps.map { it.packageName }.filter { it.isNotEmpty() }.toSet(),
                )
                _uiState.update {
                    it.copy(
                        apps = apps,
                        appInfo = appInfo,
                        recording = recording,
                        refreshing = false,
                        failure = null,
                        moduleSetup = ModuleSetup.Idle,
                        moduleOutdated = outdated,
                        modulePendingReboot = pending,
                    )
                }
            } catch (e: RecordsException) {
                _uiState.update { it.copy(refreshing = false, failure = e.toLoadFailure()) }
            }
        }
    }

    fun setRange(range: TimeRange) {
        if (range == _uiState.value.range) return
        _uiState.update { it.copy(range = range) }
        refresh()
    }

    fun setShowSystemApps(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }

    /** Flash the module bundled in this app's APK. */
    fun installModule() {
        if (_uiState.value.moduleSetup == ModuleSetup.Installing) return
        _uiState.update { it.copy(moduleSetup = ModuleSetup.Installing) }
        viewModelScope.launch {
            try {
                moduleRepository.installBundled()
                if (moduleRepository.isActive()) {
                    // Already installed: either applied on the spot, or
                    // staged until the next restart. refresh() tells which.
                    refresh()
                } else {
                    _uiState.update { it.copy(moduleSetup = ModuleSetup.NeedsReboot) }
                }
            } catch (e: RecordsException) {
                _uiState.update { it.copy(moduleSetup = ModuleSetup.Failed(e.message.orEmpty())) }
            }
        }
    }
}
