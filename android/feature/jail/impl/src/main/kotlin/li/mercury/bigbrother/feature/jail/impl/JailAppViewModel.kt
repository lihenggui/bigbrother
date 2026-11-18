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
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.InstalledAppRepository
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.model.SandboxedApp

data class JailAppUiState(
    val packageName: String = "",
    /** Null until read, or once the app is not in the sandbox. */
    val app: SandboxedApp? = null,
    val info: AppInfo? = null,
    val global: Map<SandboxItem, SandboxMode> = emptyMap(),
    val status: SandboxStatus? = null,
    /** The permissions the app asks for: which items concern it. */
    val requested: Set<String> = emptySet(),
    val loading: Boolean = true,
    val failure: LoadFailure? = null,
    /** Why the last change was not made, to be told once. */
    val error: LoadFailure? = null,
    /** The app was taken out of the sandbox from here. */
    val released: Boolean = false,
) {
    val name: String get() = info?.label ?: packageName
}

/** What the sandbox keeps from one app, item by item. */
@HiltViewModel(assistedFactory = JailAppViewModel.Factory::class)
class JailAppViewModel @AssistedInject constructor(
    @Assisted private val uid: Int,
    @Assisted private val packageName: String,
    private val sandboxRepository: SandboxRepository,
    private val appInfoRepository: AppInfoRepository,
    private val installedAppRepository: InstalledAppRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(uid: Int, packageName: String): JailAppViewModel
    }

    private val load = MutableStateFlow(JailAppUiState(packageName = packageName))

    val uiState: StateFlow<JailAppUiState> = combine(sandboxRepository.apps, sandboxRepository.global, load) { apps, global, loaded ->
        loaded.copy(
            app = apps?.firstOrNull { it.uid == uid && it.enabled },
            global = global.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JailAppUiState(packageName = packageName))

    fun refresh() {
        viewModelScope.launch {
            try {
                if (sandboxRepository.apps.value == null) sandboxRepository.refresh()
                val status = sandboxRepository.getStatus()
                val info = appInfoRepository.getAppInfo(packageName)
                val requested = installedAppRepository.getRequestedPermissions(packageName)
                load.update { it.copy(info = info, status = status, requested = requested, loading = false, failure = null) }
            } catch (e: RecordsException) {
                load.update { it.copy(loading = false, failure = e.toLoadFailure()) }
            }
        }
    }

    /**
     * Turns [item] on or off. On, it takes the global mode if that is on
     * (and so follows the global settings again), and is on otherwise.
     */
    fun setOn(item: SandboxItem, on: Boolean) {
        val global = uiState.value.global[item] ?: SandboxMode.ON
        val wanted = if (on) global.takeIf { it.isOn } ?: SandboxMode.ON else SandboxMode.OFF
        choose(item, wanted.takeIf { it != global })
    }

    /** Sets [item] to [mode], or with null to follow the global settings. */
    fun choose(item: SandboxItem, mode: SandboxMode?) {
        change(mapOf(item to mode))
    }

    /** Has every item follow the global settings again. */
    fun reset() {
        change(SandboxItem.entries.associateWith { null })
    }

    fun release() {
        val app = uiState.value.app ?: return
        viewModelScope.launch {
            try {
                sandboxRepository.release(app.packageName, app.userId)
                load.update { it.copy(released = true) }
            } catch (e: RecordsException) {
                load.update { it.copy(error = e.toLoadFailure()) }
            }
        }
    }

    fun dismissError() {
        load.update { it.copy(error = null) }
    }

    private fun change(changes: Map<SandboxItem, SandboxMode?>) {
        val app = uiState.value.app ?: return
        viewModelScope.launch {
            try {
                sandboxRepository.setItems(app.packageName, app.userId, changes)
            } catch (e: RecordsException) {
                load.update { it.copy(error = e.toLoadFailure()) }
            }
        }
    }
}
