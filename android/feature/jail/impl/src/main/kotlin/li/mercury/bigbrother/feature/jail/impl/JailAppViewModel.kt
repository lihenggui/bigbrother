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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.common.di.CurrentUserId
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.InstalledAppRepository
import li.mercury.bigbrother.core.data.repository.NetworkRepository
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxPart
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.model.labelOrPackageName
import li.mercury.bigbrother.core.model.userIdOf
import li.mercury.bigbrother.feature.jail.impl.network.networkMode

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
    /** How many destinations it reached in the last 24 hours; null if not read. */
    val destinations: Int? = null,
    val destinationsFailure: LoadFailure? = null,
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
    val name: String get() = info.labelOrPackageName(packageName)
}

/** What the sandbox keeps from one app, item by item. */
@HiltViewModel(assistedFactory = JailAppViewModel.Factory::class)
class JailAppViewModel @AssistedInject constructor(
    @Assisted private val uid: Int,
    @Assisted private val packageName: String,
    private val sandboxRepository: SandboxRepository,
    private val appInfoRepository: AppInfoRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val networkRepository: NetworkRepository,
    @CurrentUserId currentUserId: Int,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(uid: Int, packageName: String): JailAppViewModel
    }

    private val initial = JailAppUiState(packageName = packageName, otherUser = userIdOf(uid).takeIf { it != currentUserId })
    private val load = MutableStateFlow(initial)

    /** Taken out from here: the page leaves even if the list is not read again. */
    private val releasedHere = MutableStateFlow(false)

    val uiState: StateFlow<JailAppUiState> = combine(sandboxRepository.apps, sandboxRepository.global, load, releasedHere) { apps, global, loaded, released ->
        val app = apps?.firstOrNull { it.uid == uid && it.enabled }
        loaded.copy(
            app = app,
            global = global.orEmpty(),
            left = released || (!loaded.loading && loaded.failure == null && apps != null && app == null),
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
                readDestinations()
            } catch (e: RecordsException) {
                load.update { it.copy(loading = false, failure = e.toLoadFailure()) }
            }
        }
    }

    /** While the page is visible, read back restrictions that the daemon may recover. */
    fun watchAppOps(): Job = viewModelScope.launch {
        while (true) {
            val app = sandboxRepository.apps.map(::appWithUnheldOps).filterNotNull().first()
            delay(if (app.appOpsChecking) CHECKING_REFRESH_MS else RECOVERY_REFRESH_MS)
            if (appWithUnheldOps(sandboxRepository.apps.value) != null) {
                try {
                    sandboxRepository.refresh()
                    load.update { it.copy(failure = null) }
                } catch (e: RecordsException) {
                    load.update { it.copy(failure = e.toLoadFailure()) }
                }
            }
        }
    }

    private fun appWithUnheldOps(apps: List<SandboxedApp>?): SandboxedApp? = apps?.firstOrNull { it.uid == uid && it.enabled && it.unmet.values.any { parts -> SandboxPart.APP_OPS in parts } }

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

    /** Has every isolation item follow the global settings again; the network has a section of its own. */
    fun reset() {
        change(ITEM_ORDER.associateWith { null })
    }

    /**
     * Allows the app to connect, or keeps it from the network: like any
     * item, following the global settings again where they say the same.
     */
    fun setNetworkAllowed(allowed: Boolean) {
        val global = uiState.value.global[SandboxItem.NETWORK] ?: SandboxItem.NETWORK.default
        choose(SandboxItem.NETWORK, networkMode(allowed).takeIf { it != global })
    }

    /** Records the app's connections, or stops; there is no global setting to follow. */
    fun setRecording(on: Boolean) {
        change(mapOf(SandboxItem.NETWORK_RECORD to if (on) SandboxMode.ON else SandboxMode.OFF))
    }

    /**
     * Counts the destinations of the last 24 hours, for an app whose
     * connections are recorded or that has something blocked.
     */
    private suspend fun readDestinations() {
        val app = sandboxRepository.apps.value?.firstOrNull { it.uid == uid && it.enabled }
        if (app == null || (!app.modeOf(SandboxItem.NETWORK_RECORD).isOn && app.networkRules == 0 && app.recordingSince == null)) {
            load.update { it.copy(destinations = null, destinationsFailure = null) }
            return
        }
        try {
            val now = System.currentTimeMillis() / 1000
            val activity = networkRepository.getActivity(packageName, app.userId, now - DAY)
            load.update { it.copy(destinations = activity.domains.size + activity.addresses.size, destinationsFailure = null) }
        } catch (e: RecordsException) {
            load.update { it.copy(destinations = null, destinationsFailure = e.toLoadFailure()) }
        }
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
        const val DAY = 86_400L
        const val CHECKING_REFRESH_MS = 1_000L
        const val RECOVERY_REFRESH_MS = 30_000L
    }
}
