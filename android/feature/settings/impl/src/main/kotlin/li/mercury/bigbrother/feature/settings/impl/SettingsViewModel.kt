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
import li.mercury.bigbrother.core.data.repository.UserSettingsWriter
import li.mercury.bigbrother.core.model.RecorderStatus
import li.mercury.bigbrother.core.model.UserData
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Collecting the logs for a bug report. */
sealed interface LogExport {
    data object Idle : LogExport
    data object Collecting : LogExport

    /** Collected, to be offered for sharing once. */
    data class Ready(val file: File) : LogExport
    data object Failed : LogExport
}

data class SettingsUiState(
    val userData: UserData = UserData(),
    /** Null until known, or when it can't be read. */
    val status: RecorderStatus? = null,
    /** The installed module's version, if known. */
    val moduleVersion: String? = null,
    val logExport: LogExport = LogExport.Idle,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val moduleRepository: ModuleRepository,
    userDataRepository: UserDataRepository,
    private val diagnosticsRepository: DiagnosticsRepository,
) : ViewModel() {
    private val moduleVersion = MutableStateFlow<String?>(null)
    private val logExport = MutableStateFlow<LogExport>(LogExport.Idle)

    private val settingsWriter = UserSettingsWriter(viewModelScope, userDataRepository)
    val settingsWriteFailures = settingsWriter.failures

    val uiState: StateFlow<SettingsUiState> = combine(
        userDataRepository.userData,
        accessRepository.recorderStatus,
        moduleVersion,
        logExport,
    ) { userData, status, version, export -> SettingsUiState(userData, status, version, export) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun refresh() {
        viewModelScope.launch {
            moduleVersion.value = moduleRepository.installedVersion()
            try {
                accessRepository.getRecorderStatus()
            } catch (_: RecordsException) {
                // The status row says it is unknown.
            }
        }
    }

    fun exportLogs() {
        if (logExport.value == LogExport.Collecting) return
        logExport.value = LogExport.Collecting
        viewModelScope.launch {
            logExport.value = try {
                LogExport.Ready(diagnosticsRepository.exportLogs())
            } catch (e: IOException) {
                Timber.w(e, "Logs not exported")
                LogExport.Failed
            }
        }
    }

    /** The collected logs were offered for sharing: they are not offered again. */
    fun dismissLogExport() {
        logExport.value = LogExport.Idle
    }

    fun setShowSystemApps(show: Boolean) = settingsWriter.setShowSystemApps(show)

    fun setUseDynamicColor(use: Boolean) = settingsWriter.setUseDynamicColor(use)

    fun resolveSettingsWrite(failure: UserSettingsWriter.Failure, retry: Boolean) = settingsWriter.resolve(failure, retry)
}
