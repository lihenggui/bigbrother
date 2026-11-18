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

package li.mercury.bigbrother.feature.jail.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.InstalledApp
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.model.SandboxedApp
import javax.inject.Inject

/** An app in the jail, with its name and icon if it is installed. */
data class IsolatedApp(val app: SandboxedApp, val info: AppInfo?) {
    val name: String get() = info?.label ?: app.packageName
}

data class JailUiState(
    /** The apps in the sandbox, by name; null until read. */
    val apps: List<IsolatedApp>? = null,
    val global: Map<SandboxItem, SandboxMode> = emptyMap(),
    /** Null until known. */
    val status: SandboxStatus? = null,
    val refreshing: Boolean = false,
    val failure: LoadFailure? = null,
)

/** What a change did, to be told once. */
sealed interface JailMessage {
    /** [apps] were put in the sandbox, and can be taken out again. */
    data class Isolated(val names: List<String>, val apps: List<InstalledApp>) : JailMessage

    data class Failed(val failure: LoadFailure) : JailMessage
}

@HiltViewModel
class JailViewModel @Inject constructor(
    private val sandboxRepository: SandboxRepository,
    private val appInfoRepository: AppInfoRepository,
) : ViewModel() {
    private val load = MutableStateFlow(JailUiState())
    private val _message = MutableStateFlow<JailMessage?>(null)
    val message: StateFlow<JailMessage?> = _message.asStateFlow()

    private val isolated = sandboxRepository.apps.map { apps ->
        val enabled = apps?.filter { it.enabled } ?: return@map null
        val infos = appInfoRepository.getAppInfo(enabled.map { it.packageName })
        enabled.map { IsolatedApp(it, infos[it.packageName]) }.sortedByName { it.name }
    }

    val uiState: StateFlow<JailUiState> = combine(isolated, sandboxRepository.global, load) { apps, global, loaded ->
        loaded.copy(apps = apps, global = global.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JailUiState())

    fun refresh() {
        viewModelScope.launch {
            load.update { it.copy(refreshing = true) }
            try {
                sandboxRepository.refresh()
                val status = sandboxRepository.getStatus()
                load.update { it.copy(status = status, refreshing = false, failure = null) }
            } catch (e: RecordsException) {
                load.update { it.copy(refreshing = false, failure = e.toLoadFailure()) }
            }
        }
    }

    /**
     * Puts [apps] in the sandbox, each whatever became of the others. If one
     * could not be put in, that is told, and the list shows those that were.
     */
    fun isolate(apps: List<InstalledApp>) {
        viewModelScope.launch {
            val done = mutableListOf<InstalledApp>()
            var failure: LoadFailure? = null
            for (app in apps) {
                try {
                    sandboxRepository.isolate(app.packageName, app.uid / PER_USER_RANGE)
                    done += app
                } catch (e: RecordsException) {
                    failure = failure ?: e.toLoadFailure()
                }
            }
            _message.value = when {
                failure != null -> JailMessage.Failed(failure)
                done.isNotEmpty() -> JailMessage.Isolated(done.map { it.info.label }, done)
                else -> null
            }
        }
    }

    /** Takes out again what [isolate] put in. */
    fun undoIsolate(apps: List<InstalledApp>) {
        viewModelScope.launch {
            try {
                apps.forEach { sandboxRepository.release(it.packageName, it.uid / PER_USER_RANGE) }
            } catch (e: RecordsException) {
                _message.value = JailMessage.Failed(e.toLoadFailure())
            }
        }
    }

    /** The message was shown: it is not shown again. */
    fun dismissMessage() {
        _message.value = null
    }

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}
