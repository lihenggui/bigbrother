package li.mercury.bigbrother.feature.overview.impl

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import li.mercury.bigbrother.core.data.repository.ModuleRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.isHiddenByDefault

/** Why the daemon could not be started from the app. */
sealed interface StartFailure {
    /** The module is disabled or marked for removal in the root manager. */
    data object ModuleDisabled : StartFailure

    /** Started, but it never reported in; its log says why. */
    data object NotReporting : StartFailure

    /** The service script failed, with its output. */
    data class Failed(val output: String) : StartFailure
}

/** Progress of flashing the module from inside the app. */
sealed interface ModuleSetup {
    data object Idle : ModuleSetup
    data object Installing : ModuleSetup

    /** Installed, but the manager could not apply it without a reboot. */
    data object NeedsReboot : ModuleSetup
    data class Failed(val output: String) : ModuleSetup
}

data class OverviewUiState(
    val range: TimeRange = TimeRange.DAY,
    /** The overview of the apps shown; null until the first load finishes. */
    val files: FilesOverview? = null,
    val categories: List<CategoryOverview> = emptyList(),
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
    /** A request to start the daemon is in progress. */
    val daemonStarting: Boolean = false,
    /** Why the last request to start the daemon failed, if it did. */
    val daemonStartError: StartFailure? = null,
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessRepository: AccessRepository,
    private val moduleRepository: ModuleRepository,
    private val appInfoRepository: AppInfoRepository,
    private val userDataRepository: UserDataRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OverviewUiState())
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        // A change of range or of the system apps setting, here or on
        // another screen, reloads the overview.
        viewModelScope.launch {
            userDataRepository.userData
                .map { it.timeRange to it.showSystemApps }
                .distinctUntilChanged()
                .collect { (range, _) ->
                    _uiState.update { it.copy(range = range) }
                    refresh()
                }
        }
    }

    /** Load, or reload, the overview. The screen also calls this each time it is resumed. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                val userData = userDataRepository.userData.first()
                val apps = accessRepository.getApps(userData.timeRange)
                val status = accessRepository.getRecorderStatus()
                val pending = moduleRepository.isUpdatePending()
                // An update that is already staged makes the installed copy
                // look current; it isn't running until the restart.
                val outdated = !pending && moduleRepository.isOutdated()
                val appInfo = appInfoRepository.getAppInfo(
                    apps.map { it.packageName }.filter { it.isNotEmpty() }.toSet(),
                )
                val shown = visibleApps(apps, appInfo, userData.showSystemApps)
                val (files, categories) = overviewOf(shown)
                _uiState.update {
                    it.copy(
                        range = userData.timeRange,
                        files = files,
                        categories = categories,
                        appInfo = appInfo,
                        recording = status.recording,
                        refreshing = false,
                        failure = null,
                        moduleSetup = ModuleSetup.Idle,
                        // Recording again means an earlier start problem is over.
                        daemonStartError = it.daemonStartError.takeUnless { status.recording },
                        moduleOutdated = outdated,
                        modulePendingReboot = pending,
                    )
                }
            } catch (e: RecordsException) {
                _uiState.update { it.copy(refreshing = false, failure = e.toLoadFailure()) }
            }
        }
    }

    /** This app's own lookups, and the apps hidden by default, are left out. */
    private fun visibleApps(
        apps: List<AppSummary>,
        appInfo: Map<String, AppInfo>,
        showSystemApps: Boolean,
    ): List<AppSummary> = apps
        .filter { it.packageName != context.packageName }
        .filter { showSystemApps || !isHiddenByDefault(it, appInfo) }

    fun setRange(range: TimeRange) {
        viewModelScope.launch { userDataRepository.setTimeRange(range) }
    }

    /**
     * Start the daemon when the module is installed but not running, then
     * wait for it to report in: it counts as recording only once it has
     * written its first batch, a few seconds after starting.
     */
    fun startDaemon() {
        if (_uiState.value.daemonStarting) return
        loadJob?.cancel()
        _uiState.update { it.copy(daemonStarting = true, daemonStartError = null) }
        viewModelScope.launch {
            val error = try {
                when {
                    !moduleRepository.startDaemon() -> StartFailure.ModuleDisabled
                    awaitRecording() -> null
                    else -> StartFailure.NotReporting
                }
            } catch (e: RecordsException) {
                StartFailure.Failed(e.message.orEmpty())
            }
            _uiState.update { it.copy(daemonStarting = false, daemonStartError = error) }
            refresh()
        }
    }

    /** Polls the daemon's status until it is recording, or gives up. */
    private suspend fun awaitRecording(): Boolean {
        repeat(START_POLLS) {
            delay(START_POLL_INTERVAL_MS)
            val recording = try {
                accessRepository.getRecorderStatus().recording
            } catch (_: RecordsException) {
                false
            }
            if (recording) return true
        }
        return false
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

/** How long to wait for a started daemon: its first write comes within seconds. */
private const val START_POLLS = 20
private const val START_POLL_INTERVAL_MS = 1_000L
