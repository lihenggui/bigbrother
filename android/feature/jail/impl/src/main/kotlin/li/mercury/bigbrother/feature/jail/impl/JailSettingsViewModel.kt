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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.feature.jail.impl.network.networkMode
import javax.inject.Inject

data class JailSettingsUiState(
    /** Every item's global mode; null until read. */
    val global: Map<SandboxItem, SandboxMode>? = null,
    /** Whether apps installed from now on are isolated with these settings. */
    val autoIsolate: Boolean = false,
    /** Null until known; as it bears on the apps in the sandbox. */
    val status: SandboxStatus? = null,
    /** How many apps are in the sandbox. */
    val isolated: Int = 0,
    val failure: LoadFailure? = null,
    /** Why the last change was not made, to be told once. */
    val error: LoadFailure? = null,
    /** How many apps releasing them all left in the sandbox, to be told once. */
    val notReleased: Int = 0,
)

/** The global settings of the jail, which apps follow unless they have their own. */
@HiltViewModel
class JailSettingsViewModel @Inject constructor(
    private val sandboxRepository: SandboxRepository,
) : ViewModel() {
    private val load = MutableStateFlow(JailSettingsUiState())

    val uiState: StateFlow<JailSettingsUiState> = combine(
        sandboxRepository.apps,
        sandboxRepository.global,
        sandboxRepository.autoIsolate,
        load,
    ) { apps, global, autoIsolate, loaded ->
        loaded.copy(
            global = global,
            autoIsolate = autoIsolate == true,
            status = loaded.status?.relevantTo(apps.orEmpty()),
            isolated = apps.orEmpty().count { it.enabled },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JailSettingsUiState())

    fun refresh() {
        viewModelScope.launch {
            try {
                if (sandboxRepository.global.value == null) sandboxRepository.refresh()
                val status = sandboxRepository.relevantStatus()
                load.update { it.copy(status = status, failure = null) }
            } catch (e: RecordsException) {
                load.update { it.copy(failure = e.toLoadFailure()) }
            }
        }
    }

    fun setOn(item: SandboxItem, on: Boolean) {
        choose(item, if (on) SandboxMode.ON else SandboxMode.OFF)
    }

    fun choose(item: SandboxItem, mode: SandboxMode) {
        change { sandboxRepository.setGlobal(mapOf(item to mode)) }
    }

    fun setNetworkAllowed(allowed: Boolean) {
        choose(SandboxItem.NETWORK, networkMode(allowed))
    }

    fun setAutoIsolate(on: Boolean) {
        change { sandboxRepository.setAutoIsolate(on) }
    }

    fun releaseAll() {
        change {
            val left = sandboxRepository.releaseAll()
            load.update { it.copy(notReleased = left) }
        }
    }

    fun dismissError() {
        load.update { it.copy(error = null, notReleased = 0) }
    }

    private fun change(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: RecordsException) {
                load.update { it.copy(error = e.toLoadFailure()) }
            }
        }
    }
}
