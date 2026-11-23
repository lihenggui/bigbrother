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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.ModuleRepository
import li.mercury.bigbrother.core.data.repository.ModuleUpdate
import li.mercury.bigbrother.core.data.repository.ModuleUpdater
import li.mercury.bigbrother.core.data.repository.RecordingAutoStart
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.domain.AppRecords
import li.mercury.bigbrother.core.domain.GetAppRecordsUseCase
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.UserSettingsWriter
import timber.log.Timber
import javax.inject.Inject

/** Progress of installing the module from inside the app. */
sealed interface ModuleSetup {
    data object Idle : ModuleSetup
    data object Installing : ModuleSetup
    data class Failed(val failure: LoadFailure) : ModuleSetup
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
    val daemonStartError: LoadFailure? = null,
    val message: UserSettingsWriter.Failure? = null,
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val moduleRepository: ModuleRepository,
    private val moduleUpdater: ModuleUpdater,
    private val recordingAutoStart: RecordingAutoStart,
    private val getAppRecords: GetAppRecordsUseCase,
    private val getAppVisibility: GetAppVisibilityUseCase,
    private val userDataRepository: UserDataRepository,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val settingsWriter = UserSettingsWriter(viewModelScope) { Timber.w(it) }
    private val refreshes = MutableStateFlow(0L)
    private val screenResumed = MutableStateFlow(false)
    private val recorderBusy = MutableStateFlow(false)
    private val load = MutableStateFlow(OverviewLoad())
    private var completed: OverviewRequest? = null

    /** When the last successful load finished, on the elapsed realtime clock. */
    private var loadedAt = 0L

    private val ranges = userDataRepository.userData.map { it.timeRange }.distinctUntilChanged()
    private val requests = combine(ranges, refreshes, screenResumed, recorderBusy) { range, refresh, resumed, busy ->
        OverviewRequest(range, refresh).takeIf { resumed && !busy }
    }.distinctUntilChanged()

    val uiState: StateFlow<OverviewUiState> = channelFlow {
        launch {
            requests.collectLatest { request ->
                if (request != null && request != completed && screenResumed.value && !recorderBusy.value) {
                    loadOverview(request)
                }
            }
        }
        val content = combine(load, getAppVisibility()) { loaded, visibility ->
            val records = loaded.records ?: return@combine loaded.ui
            val (files, categories) = withContext(defaultDispatcher) {
                overviewOf(visibility.visibleApps(records).apps)
            }
            loaded.ui.copy(files = files, categories = categories, appInfo = records.appInfo)
        }
        combine(content, moduleUpdater.state, settingsWriter.message) { state, update, message ->
            state.copy(moduleUpdate = update, message = message)
        }.collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewUiState())

    /** Recheck stale records on return, without reading them in the background. */
    fun onScreenResumed() {
        if (completed?.refresh == refreshes.value && SystemClock.elapsedRealtime() - loadedAt >= STALE_AFTER_MS) refresh()
        screenResumed.value = true
    }

    fun onScreenPaused() {
        screenResumed.value = false
    }

    fun refresh() {
        refreshes.update { it + 1 }
    }

    private suspend fun loadOverview(request: OverviewRequest) {
        val start = SystemClock.elapsedRealtime()
        load.update { it.copy(ui = it.ui.copy(range = request.range, refreshing = true)) }
        try {
            val failure = readOverview(request, start)
            currentCoroutineContext().ensureActive()
            val shouldUpdate = moduleUpdater.shouldUpdate(failure)
            completed = request
            if (shouldUpdate) {
                updateModule()
            } else if (failure != null) {
                updateState { it.copy(failure = failure) }
            } else if (shouldStartAutomatically()) {
                startRecording(automatic = true)
            }
        } catch (e: RecordsException) {
            currentCoroutineContext().ensureActive()
            // A failed comparison must be retried before an update can be offered.
            completed = request
            updateState { it.copy(failure = e.toLoadFailure()) }
        } finally {
            updateState { it.copy(refreshing = false) }
        }
    }

    private suspend fun readOverview(request: OverviewRequest, start: Long): LoadFailure? = try {
        val (records, status, pending) = withContext(defaultDispatcher) {
            Triple(getAppRecords(request.range), accessRepository.getRecorderStatus(), moduleRepository.isUpdatePending())
        }
        loadedAt = SystemClock.elapsedRealtime()
        load.update {
            OverviewLoad(
                records = records,
                ui = it.ui.copy(
                    recording = status.recording,
                    refreshing = false,
                    failure = null,
                    // An installation may still be in progress when the records arrive.
                    moduleSetup = it.ui.moduleSetup.takeIf { setup -> setup == ModuleSetup.Installing } ?: ModuleSetup.Idle,
                    daemonStartError = it.ui.daemonStartError.takeUnless { status.recording },
                    modulePendingReboot = pending,
                ),
            )
        }
        Timber.i("Overview loaded in %d ms: %d apps", loadedAt - start, records.apps.size)
        null
    } catch (e: RecordsException) {
        e.toLoadFailure().also { Timber.w("Overview not loaded: %s", it) }
    }

    /** A staged module comes first: starting now would run the one it replaces. */
    private suspend fun shouldStartAutomatically(): Boolean {
        val ui = load.value.ui
        return ui.recording == false && !ui.modulePendingReboot && recordingAutoStart.claim()
    }

    fun updateModule() {
        runRecorderAction { moduleUpdater.updateInPlace() }
    }

    fun setRange(range: TimeRange) = settingsWriter.save(UserSettingsWriter.Setting.TIME_RANGE) {
        userDataRepository.setTimeRange(range)
    }

    fun onMessageShown(failure: UserSettingsWriter.Failure, retry: Boolean) = settingsWriter.resolve(failure, retry)

    /**
     * Start the daemon when the module is installed but not running, then
     * wait for it to report in: it counts as recording only once it has
     * written its first batch, a few seconds after starting.
     */
    fun startDaemon() = startRecording(automatic = false)

    /**
     * An automatic start never turns on an installation that its boot guard
     * turned off: that is left to the user, who is offered the start as before.
     */
    private fun startRecording(automatic: Boolean) {
        runRecorderAction {
            updateState { it.copy(daemonStarting = true, daemonStartError = null) }
            val error = try {
                moduleRepository.startDaemon(turnOn = !automatic)
                moduleUpdater.awaitRecording()
                null
            } catch (e: RecordsException) {
                e.toLoadFailure().takeUnless { automatic && it == LoadFailure.Operation.MODULE_DISABLED }
            }
            if (automatic) Timber.i("Automatic start: %s", error ?: "recording")
            updateState { it.copy(daemonStarting = false, daemonStartError = error) }
        }
    }

    private fun runRecorderAction(action: suspend () -> Unit) {
        if (!recorderBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                action()
            } finally {
                refresh()
                recorderBusy.value = false
            }
        }
    }

    /** Install the module bundled in this app's APK; it starts at once. */
    fun installModule() {
        if (load.value.ui.moduleSetup == ModuleSetup.Installing) return
        updateState { it.copy(moduleSetup = ModuleSetup.Installing) }
        viewModelScope.launch {
            try {
                moduleRepository.installBundled()
                updateState { it.copy(moduleSetup = ModuleSetup.Idle) }
                refresh()
            } catch (e: RecordsException) {
                updateState { it.copy(moduleSetup = ModuleSetup.Failed(e.toLoadFailure())) }
            }
        }
    }
    private fun updateState(transform: (OverviewUiState) -> OverviewUiState) {
        load.update { it.copy(ui = transform(it.ui)) }
    }

    private data class OverviewLoad(val records: AppRecords? = null, val ui: OverviewUiState = OverviewUiState())

    private data class OverviewRequest(val range: TimeRange, val refresh: Long)
}

/** How soon after a load the screen being shown again reloads it. */
private const val STALE_AFTER_MS = 10_000L
