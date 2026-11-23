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

package li.mercury.bigbrother.feature.settings.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.DiagnosticsRepository
import li.mercury.bigbrother.core.data.repository.ModuleRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.RecorderStatus
import li.mercury.bigbrother.core.model.UserData
import li.mercury.bigbrother.core.ui.UserSettingsWriter
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/** Collecting the logs for a bug report. */
sealed interface LogExport {
    data object Idle : LogExport
    data object Collecting : LogExport

    /** Collected, to be offered for sharing once. */
    data class Ready(val file: File) : LogExport
    data class Failed(val failure: LoadFailure) : LogExport
}

/** Removing a module this app installed. */
sealed interface ModuleRemoval {
    data object Idle : ModuleRemoval
    data object Removing : ModuleRemoval

    /** The module is still installed. */
    data class Failed(val failure: LoadFailure) : ModuleRemoval
}

data class SettingsUiState(
    val userData: UserData = UserData(),
    /** Null until known, or when it can't be read. */
    val status: RecorderStatus? = null,
    /** The installed module's version, if known. */
    val moduleVersion: String? = null,
    val moduleVersionFailure: LoadFailure? = null,
    /** Only a module this app installed: one from a root manager is removed there. */
    val moduleRemovable: Boolean = false,
    val moduleRemoval: ModuleRemoval = ModuleRemoval.Idle,
    val recorderStatusFailure: LoadFailure? = null,
    val logExport: LogExport = LogExport.Idle,
    val message: UserSettingsWriter.Failure? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val moduleRepository: ModuleRepository,
    private val userDataRepository: UserDataRepository,
    private val diagnosticsRepository: DiagnosticsRepository,
) : ViewModel() {
    private val recordingDetails = MutableStateFlow(RecordingDetails())
    private val logExport = MutableStateFlow<LogExport>(LogExport.Idle)
    private val moduleRemoval = MutableStateFlow<ModuleRemoval>(ModuleRemoval.Idle)

    private val settingsWriter = UserSettingsWriter(viewModelScope) { Timber.w(it) }

    val uiState: StateFlow<SettingsUiState> = combine(
        userDataRepository.userData,
        accessRepository.recorderStatus,
        recordingDetails,
        logExport,
        settingsWriter.message,
    ) { userData, status, details, export, message ->
        SettingsUiState(
            userData = userData,
            status = status,
            moduleVersion = details.version,
            moduleVersionFailure = details.versionFailure,
            moduleRemovable = details.removable,
            recorderStatusFailure = details.statusFailure.takeIf { status == null },
            logExport = export,
            message = message,
        )
    }
        .combine(moduleRemoval) { state, removal -> state.copy(moduleRemoval = removal) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun refresh() {
        viewModelScope.launch { loadRecordingDetails() }
    }

    fun removeModule() {
        if (moduleRemoval.value == ModuleRemoval.Removing) return
        moduleRemoval.value = ModuleRemoval.Removing
        viewModelScope.launch {
            val result = try {
                moduleRepository.remove()
                ModuleRemoval.Idle
            } catch (e: RecordsException) {
                Timber.w("Module not removed: %s", e.toLoadFailure())
                ModuleRemoval.Failed(e.toLoadFailure())
            }
            // Read back before the row leaves the busy state, so that after
            // a removal it disappears rather than offering itself again.
            loadRecordingDetails()
            moduleRemoval.value = result
        }
    }

    private suspend fun loadRecordingDetails() {
        val details = try {
            RecordingDetails(version = moduleRepository.installedVersion())
        } catch (e: RecordsException) {
            RecordingDetails(versionFailure = e.toLoadFailure())
        }
        val removable = try {
            moduleRepository.isInstalledByApp()
        } catch (e: RecordsException) {
            // Removing needs the same root access that just failed: not offered.
            Timber.w("Module origin unknown: %s", e.toLoadFailure())
            false
        }
        recordingDetails.value = try {
            accessRepository.getRecorderStatus()
            details.copy(removable = removable)
        } catch (e: RecordsException) {
            details.copy(removable = removable, statusFailure = e.toLoadFailure())
        }
    }

    fun exportLogs() {
        if (logExport.value == LogExport.Collecting) return
        logExport.value = LogExport.Collecting
        viewModelScope.launch {
            logExport.value = try {
                LogExport.Ready(diagnosticsRepository.exportLogs())
            } catch (e: RecordsException) {
                Timber.w("Logs not exported: %s", e.toLoadFailure())
                LogExport.Failed(e.toLoadFailure())
            }
        }
    }

    /** The collected logs were offered for sharing: they are not offered again. */
    fun dismissLogExport() {
        logExport.value = LogExport.Idle
    }

    fun setShowSystemApps(show: Boolean) = settingsWriter.save(UserSettingsWriter.Setting.SHOW_SYSTEM_APPS) {
        userDataRepository.setShowSystemApps(show)
    }

    fun setUseDynamicColor(use: Boolean) = settingsWriter.save(UserSettingsWriter.Setting.USE_DYNAMIC_COLOR) {
        userDataRepository.setUseDynamicColor(use)
    }

    fun onMessageShown(failure: UserSettingsWriter.Failure, retry: Boolean) = settingsWriter.resolve(failure, retry)

    private data class RecordingDetails(
        val version: String? = null,
        val versionFailure: LoadFailure? = null,
        val removable: Boolean = false,
        val statusFailure: LoadFailure? = null,
    )
}
