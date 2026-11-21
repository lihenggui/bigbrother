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

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
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
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.InstalledModule
import li.mercury.bigbrother.core.data.repository.ModuleRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.data.repository.UserSettingsWriter
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.domain.AppRecords
import li.mercury.bigbrother.core.domain.GetAppRecordsUseCase
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import timber.log.Timber
import javax.inject.Inject

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

/** Bringing the installed module up to date with the one this app carries. */
sealed interface ModuleUpdate {
    data object Idle : ModuleUpdate
    data object Updating : ModuleUpdate

    /** Disabled or marked for removal in the root manager, and left as it is. */
    data object Disabled : ModuleUpdate

    /** The files were replaced, but the new daemon did not report in. */
    data object NotReporting : ModuleUpdate

    /** Replacing the files failed, with the output saying why. */
    data class Failed(val output: String) : ModuleUpdate
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
    /** Bringing the installed module up to date, when it is older than this app's. */
    val moduleUpdate: ModuleUpdate = ModuleUpdate.Idle,
    /** A module update is installed and waits for a restart. */
    val modulePendingReboot: Boolean = false,
    /** A request to start the daemon is in progress. */
    val daemonStarting: Boolean = false,
    /** Why the last request to start the daemon failed, if it did. */
    val daemonStartError: StartFailure? = null,
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val moduleRepository: ModuleRepository,
    private val getAppRecords: GetAppRecordsUseCase,
    private val getAppVisibility: GetAppVisibilityUseCase,
    private val userDataRepository: UserDataRepository,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OverviewUiState())
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    private val settingsWriter = UserSettingsWriter(viewModelScope, userDataRepository)
    val settingsWriteFailures = settingsWriter.failures

    private var loadJob: Job? = null

    private var loaded: AppRecords? = null

    /** When the last load finished, on the elapsed realtime clock. */
    private var loadedAt = 0L

    /**
     * Whether an outdated module has been updated automatically already.
     * Once is enough: a failure is shown with a way to try again rather
     * than retried on every load.
     */
    private var autoUpdated = false

    /** The installed module was found to be the one this app carries. */
    private var moduleCurrent = false

    init {
        // A range chosen here or on another screen reloads the overview.
        viewModelScope.launch {
            userDataRepository.userData.map { it.timeRange }.distinctUntilChanged().collect { range ->
                _uiState.update { it.copy(range = range) }
                refresh()
            }
        }
        // The system apps setting only changes which of the loaded apps count.
        viewModelScope.launch {
            getAppVisibility().collect { visibility ->
                val loaded = loaded ?: return@collect
                val (files, categories) = overviewOf(visibility.visibleApps(loaded).apps)
                _uiState.update { it.copy(files = files, categories = categories) }
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

    /** Stop a load in progress, and with it the refresh indicator. */
    private fun cancelLoad() {
        loadJob?.cancel()
        _uiState.update { it.copy(refreshing = false) }
    }

    /** Load, or reload, the overview. */
    fun refresh() {
        loadJob?.cancel()
        // Off the main thread: between its steps, the load would otherwise
        // wait for the main thread, which is busy drawing the first screen.
        loadJob = viewModelScope.launch(defaultDispatcher) {
            val start = SystemClock.elapsedRealtime()
            _uiState.update { it.copy(refreshing = true) }
            try {
                val userData = userDataRepository.userData.first()
                val records = getAppRecords(userData.timeRange)
                val status = accessRepository.getRecorderStatus()
                val pending = moduleRepository.isUpdatePending()
                loaded = records
                loadedAt = SystemClock.elapsedRealtime()
                // The setting as it is now: it may have changed during the load.
                val visibility = getAppVisibility().first()
                val (files, categories) = overviewOf(visibility.visibleApps(records).apps)
                _uiState.update {
                    it.copy(
                        range = userData.timeRange,
                        files = files,
                        categories = categories,
                        appInfo = records.appInfo,
                        recording = status.recording,
                        refreshing = false,
                        failure = null,
                        // An installation in progress is not over because the
                        // records loaded meanwhile.
                        moduleSetup = it.moduleSetup.takeIf { setup -> setup == ModuleSetup.Installing }
                            ?: ModuleSetup.Idle,
                        // Recording again means an earlier start problem is over.
                        daemonStartError = it.daemonStartError.takeUnless { status.recording },
                        modulePendingReboot = pending,
                    )
                }
                Timber.i("Overview loaded in %d ms: %d apps", SystemClock.elapsedRealtime() - start, records.apps.size)
            } catch (e: RecordsException) {
                val failure = e.toLoadFailure()
                Timber.w("Overview not loaded: %s", failure)
                // A daemon too old for even these commands is updated too.
                if (failure == LoadFailure.ModuleOutdated && !autoUpdated) {
                    autoUpdated = true
                    updateModule()
                    return@launch
                }
                _uiState.update { it.copy(refreshing = false, failure = failure) }
                return@launch
            }
            checkModule()
        }
    }

    /**
     * Once the records are on screen, compares the installed module with the
     * one this app carries: the module follows the app, so one older than it,
     * or built differently, is replaced. A current module stays current for
     * as long as this app runs, and is not compared again.
     */
    private suspend fun checkModule() {
        if (autoUpdated || moduleCurrent) return
        val installed = moduleRepository.compareWithBundled()
        Timber.i("Installed module: %s", installed)
        when (installed) {
            InstalledModule.OUTDATED -> {
                autoUpdated = true
                updateModule()
            }

            InstalledModule.CURRENT -> moduleCurrent = true

            InstalledModule.NEWER, InstalledModule.UNKNOWN -> Unit
        }
    }

    /**
     * Replace the installed module's files with this app's and restart the
     * daemon, without a reboot, then wait for the new daemon to report in.
     */
    fun updateModule() {
        if (_uiState.value.moduleUpdate == ModuleUpdate.Updating) return
        cancelLoad()
        _uiState.update { it.copy(moduleUpdate = ModuleUpdate.Updating) }
        viewModelScope.launch {
            val result = try {
                when {
                    !moduleRepository.updateInPlace() -> ModuleUpdate.Disabled
                    awaitRecording() -> ModuleUpdate.Idle
                    else -> ModuleUpdate.NotReporting
                }
            } catch (e: RecordsException) {
                ModuleUpdate.Failed(e.message.orEmpty())
            }
            Timber.i("Module update: %s", result)
            _uiState.update { it.copy(moduleUpdate = result) }
            refresh()
        }
    }

    fun setRange(range: TimeRange) = settingsWriter.setTimeRange(range)

    fun resolveSettingsWrite(failure: UserSettingsWriter.Failure, retry: Boolean) = settingsWriter.resolve(failure, retry)

    /**
     * Start the daemon when the module is installed but not running, then
     * wait for it to report in: it counts as recording only once it has
     * written its first batch, a few seconds after starting.
     */
    fun startDaemon() {
        if (_uiState.value.daemonStarting) return
        cancelLoad()
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
                    _uiState.update { it.copy(moduleSetup = ModuleSetup.Idle) }
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

/** How soon after a load the screen being shown again reloads it. */
private const val STALE_AFTER_MS = 10_000L

/** How long to wait for a started daemon: its first write comes within seconds. */
private const val START_POLLS = 20
private const val START_POLL_INTERVAL_MS = 1_000L
