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
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.common.di.CurrentUserId
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.InstalledAppRepository
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxedApp

data class JailAppUiState(
    val packageName: String = "",
    /**
     * Null until read. Once the app has left the sandbox, the one read last:
     * the page shows the app as it was while it leaves.
     */
    val app: SandboxedApp? = null,
    val info: AppInfo? = null,
    /** The Android user the app belongs to, if not the one this app runs as. */
    val otherUser: Int? = null,
    val global: Map<SandboxItem, SandboxMode> = emptyMap(),
    /**
     * The permissions the app asks for: which items concern it. Null if not
     * known, for an app not installed for this app's user.
     */
    val requested: Set<String>? = null,
    val loading: Boolean = true,
    val failure: LoadFailure? = null,
    /** Why the last change was not made, to be told once. */
    val error: LoadFailure? = null,
    /**
     * The app is no longer in the sandbox, taken out from here or elsewhere,
     * or uninstalled: the page leaves.
     */
    val left: Boolean = false,
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
    @CurrentUserId currentUserId: Int,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(uid: Int, packageName: String): JailAppViewModel
    }

    private val initial = JailAppUiState(packageName = packageName, otherUser = (uid / PER_USER_RANGE).takeIf { it != currentUserId })
    private val load = MutableStateFlow(initial)

    /** Taken out from here: the page leaves even if the list is not read again. */
    private val releasedHere = MutableStateFlow(false)

    val uiState: StateFlow<JailAppUiState> = combine(sandboxRepository.apps, sandboxRepository.global, load, releasedHere) { apps, global, loaded, released ->
        val app = apps?.firstOrNull { it.uid == uid && it.enabled }
        loaded.copy(
            app = app,
            global = global.orEmpty(),
            left = released || (apps != null && app == null),
        )
    }.runningReduce { shown, next ->
        // Going straight from the app to leaving: not by a page without it.
        if (next.left && next.app == null) next.copy(app = shown.app) else next
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    fun refresh() {
        viewModelScope.launch {
            try {
                // Read again each time: what is in effect for the app is
                // found when the list is read.
                sandboxRepository.refresh()
                val info = appInfoRepository.getAppInfo(packageName)
                val requested = installedAppRepository.getRequestedPermissions(packageName)
                load.update { it.copy(info = info, requested = requested, loading = false, failure = null) }
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
        val app = shownApp() ?: return
        viewModelScope.launch {
            try {
                sandboxRepository.release(app.packageName, app.userId)
                releasedHere.value = true
            } catch (e: RecordsException) {
                load.update { it.copy(error = e.toLoadFailure()) }
            }
        }
    }

    fun dismissError() {
        load.update { it.copy(error = null) }
    }

    /** The app, while it is in the sandbox: once it has left, nothing is changed. */
    private fun shownApp(): SandboxedApp? = uiState.value.takeUnless { it.left }?.app

    private fun change(changes: Map<SandboxItem, SandboxMode?>) {
        val app = shownApp() ?: return
        viewModelScope.launch {
            try {
                sandboxRepository.setItems(app.packageName, app.userId, changes)
            } catch (e: RecordsException) {
                load.update { it.copy(error = e.toLoadFailure()) }
            }
        }
    }

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}
