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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
import javax.inject.Inject

data class AddAppsUiState(
    /** The apps that can be put in the sandbox and match the search, by name; null until read. */
    val apps: List<InstalledApp>? = null,
    val query: String = "",
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
    sandboxRepository: SandboxRepository,
    userDataRepository: UserDataRepository,
    @AppPackageName private val ownPackage: String,
    @Dispatcher(DEFAULT) defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow(emptySet<String>())

    private val showSystemApps = userDataRepository.userData.map { it.showSystemApps }.distinctUntilChanged()

    val uiState: StateFlow<AddAppsUiState> = combine(
        installed,
        sandboxRepository.apps,
        showSystemApps,
        query,
        selected,
    ) { installed, sandboxed, showSystem, query, selected ->
        val isolated = sandboxed.orEmpty().filter { it.enabled }.map { it.uid }.toSet()
        val apps = installed
            ?.filter { app ->
                app.packageName != ownPackage &&
                    app.uid % PER_USER_RANGE >= FIRST_APPLICATION_UID &&
                    app.uid !in isolated &&
                    (showSystem || !app.info.isSystem) &&
                    (query.isBlank() || app.info.label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
            }
            ?.sortedByName { it.info.label }
        AddAppsUiState(apps = apps, query = query, selected = selected)
    }.flowOn(defaultDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddAppsUiState())

    init {
        viewModelScope.launch { installed.value = installedAppRepository.getInstalledApps() }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun toggle(packageName: String) {
        selected.update { if (packageName in it) it - packageName else it + packageName }
    }

    /** Starts again, with nothing chosen or searched for, and the installed apps read anew. */
    fun clear() {
        query.value = ""
        selected.value = emptySet()
        viewModelScope.launch { installed.value = installedAppRepository.getInstalledApps() }
    }

    /** The apps chosen, among those listed. */
    fun chosen(): List<InstalledApp> = installed.value.orEmpty().filter { it.packageName in selected.value }

    private companion object {
        const val PER_USER_RANGE = 100_000

        /** App ids below are the system's own, shared by many components. */
        const val FIRST_APPLICATION_UID = 10_000
    }
}
