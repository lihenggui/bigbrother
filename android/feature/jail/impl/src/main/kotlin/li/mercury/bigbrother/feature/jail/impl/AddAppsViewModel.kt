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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.common.di.AppPackageName
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.repository.InstalledAppRepository
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.model.InstalledApp
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.model.isNonCoreUid
import javax.inject.Inject

data class AddAppsUiState(
    /** The apps that can be put in the sandbox and match the search, by name; null until read. */
    val apps: List<InstalledApp>? = null,
    /** Chosen, by package name. */
    val selected: Set<String> = emptySet(),
)

/**
 * The apps the user can put in the sandbox: installed, not in it already,
 * not this app, and, unless the settings show them, not the system's. They
 * are listed by name; nothing here suggests one over another.
 */
@HiltViewModel
class AddAppsViewModel @Inject constructor(
    private val installedAppRepository: InstalledAppRepository,
    private val sandboxRepository: SandboxRepository,
    userDataRepository: UserDataRepository,
    savedStateHandle: SavedStateHandle,
    @AppPackageName private val ownPackage: String,
    @Dispatcher(DEFAULT) defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private val query = MutableStateFlow("")
    private val selected = savedStateHandle.getMutableStateFlow(SELECTED_PACKAGES, arrayListOf<String>())

    private val showSystemApps = userDataRepository.userData
        .map { it.showSystemApps }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val uiState: StateFlow<AddAppsUiState> = combine(
        installed,
        sandboxRepository.apps,
        showSystemApps,
        query,
        selected,
    ) { installed, sandboxed, showSystem, query, selected ->
        // A restored draft must wait for the sandbox list so existing isolation is left alone.
        val eligible = if (sandboxed == null) null else installed?.eligibleApps(sandboxed, showSystem)
        val apps = eligible
            ?.filter { app ->
                query.isBlank() || app.info.label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true)
            }
            ?.sortedByName { it.info.label }
        val packages = selected.toSet()
        AddAppsUiState(apps = apps, selected = eligible.orEmpty().mapNotNullTo(mutableSetOf()) { it.packageName.takeIf(packages::contains) })
    }.flowOn(defaultDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddAppsUiState())

    init {
        viewModelScope.launch { installed.value = installedAppRepository.getInstalledApps() }
    }

    /**
     * Lists the apps that match [text]. The search field keeps the text
     * itself: the state that comes back is worked out on another thread.
     */
    fun setQuery(text: String) {
        query.value = text
    }

    fun toggle(packageName: String) {
        selected.update { ArrayList(if (packageName in it) it - packageName else it + packageName) }
    }

    /** Starts again, with nothing chosen or searched for, and the installed apps read anew. */
    fun clear() {
        query.value = ""
        selected.value = arrayListOf()
        viewModelScope.launch { installed.value = installedAppRepository.getInstalledApps() }
    }

    /** Resolves the draft against current apps, including choices hidden by the search. */
    fun chosen(): List<InstalledApp> {
        val sandboxed = sandboxRepository.apps.value ?: return emptyList()
        val packages = selected.value.toSet()
        return installed.value.orEmpty()
            .filter { it.packageName in packages }
            .eligibleApps(sandboxed, showSystemApps.value)
    }

    private fun List<InstalledApp>.eligibleApps(sandboxed: List<SandboxedApp>, showSystem: Boolean): List<InstalledApp> {
        val isolated = sandboxed.filter { it.enabled }.map { it.uid }.toSet()
        return filter { app ->
            app.packageName != ownPackage &&
                isNonCoreUid(app.uid) &&
                app.uid !in isolated &&
                (showSystem || !app.info.isSystem)
        }
    }

    private companion object {
        const val SELECTED_PACKAGES = "selected_packages"
    }
}
