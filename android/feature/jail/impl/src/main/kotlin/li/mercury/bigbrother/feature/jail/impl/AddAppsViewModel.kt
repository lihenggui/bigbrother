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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.InstalledAppRepository
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.domain.AppVisibility
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.InstalledApp
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.model.isNonCoreUid
import javax.inject.Inject

data class AddAppsUiState(
    /** The apps that can be put in the sandbox and match the search, by name; null until read. */
    val apps: List<InstalledApp>? = null,
    /** Chosen, by package name. */
    val selected: Set<String> = emptySet(),
    val failure: LoadFailure? = null,
)

/**
 * The apps the user can put in the sandbox: installed, not in it already,
 * not this app, and, unless the settings show them, not the system's. They
 * are listed by name; nothing here suggests one over another.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddAppsViewModel @Inject constructor(
    private val installedAppRepository: InstalledAppRepository,
    private val sandboxRepository: SandboxRepository,
    getAppVisibility: GetAppVisibilityUseCase,
    savedStateHandle: SavedStateHandle,
    @Dispatcher(DEFAULT) defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val refreshes = MutableStateFlow(0L)
    private val installed = refreshes.mapLatest {
        available = null
        try {
            InstalledChoices.Apps(installedAppRepository.getInstalledApps())
        } catch (e: RecordsException) {
            InstalledChoices.Failed(e.toLoadFailure())
        }
    }
    private val query = MutableStateFlow("")
    private val selected = savedStateHandle.getMutableStateFlow(SELECTED_PACKAGES, arrayListOf<String>())

    private var available: Pair<List<InstalledApp>, AppVisibility>? = null
    private val appChoices = combine(installed, getAppVisibility(), ::Pair).onEach { (choices, visibility) ->
        available = (choices as? InstalledChoices.Apps)?.let { it.apps to visibility }
    }

    val uiState: StateFlow<AddAppsUiState> = combine(appChoices, sandboxRepository.apps, query, selected) { (choices, visibility), sandboxed, query, selected ->
        val installed = when (choices) {
            is InstalledChoices.Apps -> choices.apps
            is InstalledChoices.Failed -> return@combine AddAppsUiState(failure = choices.failure)
        }
        withContext(defaultDispatcher) {
            // A restored draft must wait for the sandbox list so existing isolation is left alone.
            val eligible = sandboxed?.let { installed.eligibleApps(it, visibility) }
            val apps = eligible
                ?.filter { app ->
                    query.isBlank() || app.info.label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true)
                }
                ?.sortedByName { it.info.label }
            val packages = selected.toSet()
            AddAppsUiState(apps = apps, selected = eligible.orEmpty().mapNotNullTo(mutableSetOf()) { it.packageName.takeIf(packages::contains) })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddAppsUiState())

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
        retry()
    }

    fun retry() {
        refreshes.update { it + 1 }
    }

    /** Resolves the draft against current apps, including choices hidden by the search. */
    fun chosen(): List<InstalledApp> {
        val sandboxed = sandboxRepository.apps.value ?: return emptyList()
        val (installed, visibility) = available ?: return emptyList()
        val packages = selected.value.toSet()
        return installed.filter { it.packageName in packages }.eligibleApps(sandboxed, visibility)
    }

    private fun List<InstalledApp>.eligibleApps(sandboxed: List<SandboxedApp>, visibility: AppVisibility): List<InstalledApp> {
        val isolated = sandboxed.filter { it.enabled }.map { it.uid }.toSet()
        return filter { app ->
            isNonCoreUid(app.uid) && app.uid !in isolated && visibility.isShown(app)
        }
    }

    private sealed interface InstalledChoices {
        data class Apps(val apps: List<InstalledApp>) : InstalledChoices
        data class Failed(val failure: LoadFailure) : InstalledChoices
    }

    private companion object {
        const val SELECTED_PACKAGES = "selected_packages"
    }
}
