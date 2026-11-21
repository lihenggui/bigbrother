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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.common.di.CurrentUserId
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
import li.mercury.bigbrother.core.model.labelOrPackageName
import javax.inject.Inject

/**
 * An app in the jail, with its name and icon if it is installed, and
 * [otherUser], the Android user it belongs to, if not the one this app runs as.
 */
data class IsolatedApp(val app: SandboxedApp, val info: AppInfo?, val otherUser: Int? = null) {
    val name: String get() = info.labelOrPackageName(app.packageName)
}

data class JailUiState(
    /** The apps in the sandbox, by name; null until read. */
    val apps: List<IsolatedApp>? = null,
    val global: Map<SandboxItem, SandboxMode> = emptyMap(),
    /** Null until known; as it bears on the apps in the sandbox. */
    val status: SandboxStatus? = null,
    val refreshing: Boolean = false,
    val failure: LoadFailure? = null,
    val message: JailMessage? = null,
)

/**
 * The daemon's status as it bore on the apps in the sandbox when it was
 * read. App ops reported not held while no app needed them say nothing
 * about an app put in afterwards, which the daemon holds them for first.
 */
internal suspend fun SandboxRepository.relevantStatus(): SandboxStatus = getStatus().relevantTo(apps.value.orEmpty())

/** What a change did, to be told once. */
sealed interface JailMessage {
    /**
     * [apps] were put in the sandbox, and can be taken out again.
     * [notIsolated] names those chosen with them that could not be.
     */
    data class Isolated(
        val names: List<String>,
        val apps: List<InstalledApp>,
        val notIsolated: List<String> = emptyList(),
    ) : JailMessage

    /** None of the apps chosen, [names], could be put in the sandbox, first for [failure]. */
    data class NotIsolated(val names: List<String>, val failure: LoadFailure) : JailMessage

    /** Undoing left [names] in the sandbox, first for [failure]. */
    data class NotReleased(val names: List<String>, val failure: LoadFailure) : JailMessage
}

@HiltViewModel
class JailViewModel @Inject constructor(
    private val sandboxRepository: SandboxRepository,
    private val appInfoRepository: AppInfoRepository,
    @CurrentUserId private val currentUserId: Int,
) : ViewModel() {
    private val load = MutableStateFlow(JailUiState())

    private val isolated = sandboxRepository.apps.map { apps ->
        val enabled = apps?.filter { it.enabled } ?: return@map null
        // The package manager answers for this app's user. Another user's
        // copy of a package is the same app, so it gives the name and icon.
        val infos = appInfoRepository.getAppInfo(enabled.map { it.packageName })
        enabled.map { IsolatedApp(it, infos[it.packageName], it.userId.takeIf { user -> user != currentUserId }) }.sortedByName { it.name }
    }

    val uiState: StateFlow<JailUiState> = combine(isolated, sandboxRepository.global, load) { apps, global, loaded ->
        loaded.copy(apps = apps, global = global.orEmpty(), status = loaded.status?.relevantTo(apps.orEmpty().map { it.app }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JailUiState())

    fun refresh() {
        viewModelScope.launch {
            load.update { it.copy(refreshing = true) }
            try {
                sandboxRepository.refresh()
                val status = sandboxRepository.relevantStatus()
                load.update { it.copy(status = status, refreshing = false, failure = null) }
            } catch (e: RecordsException) {
                load.update { it.copy(refreshing = false, failure = e.toLoadFailure()) }
            }
        }
    }

    /**
     * Puts [apps] in the sandbox, each whatever became of the others. Those
     * that were can be taken out again, also when others could not be put in.
     */
    fun isolate(apps: List<InstalledApp>) {
        viewModelScope.launch {
            val (done, failed, failure) = eachApp(apps) { sandboxRepository.isolate(it.packageName, it.userId) }
            val message = when {
                done.isEmpty() -> failure?.let { JailMessage.NotIsolated(failed.names(), it) }
                else -> JailMessage.Isolated(done.names(), done, notIsolated = failed.names())
            }
            load.update { it.copy(message = message) }
        }
    }

    /** Takes out again what [isolate] put in, each whatever became of the others. */
    fun undoIsolate(apps: List<InstalledApp>) {
        viewModelScope.launch {
            val (_, failed, failure) = eachApp(apps) { sandboxRepository.release(it.packageName, it.userId) }
            if (failure != null) load.update { it.copy(message = JailMessage.NotReleased(failed.names(), failure)) }
        }
    }

    /** The message was shown: it is not shown again. */
    fun onMessageShown(message: JailMessage) {
        load.update { if (it.message === message) it.copy(message = null) else it }
    }

    /**
     * Runs [change] for each of [apps], the others' failures notwithstanding,
     * except one that would fail for every app: then those left count as
     * failed too, without a root command each.
     */
    private suspend fun eachApp(apps: List<InstalledApp>, change: suspend (InstalledApp) -> Unit): Outcome {
        val done = mutableListOf<InstalledApp>()
        val failed = mutableListOf<InstalledApp>()
        var failure: LoadFailure? = null
        for ((index, app) in apps.withIndex()) {
            try {
                change(app)
                done += app
            } catch (e: RecordsException) {
                failure = failure ?: e.toLoadFailure()
                if (e.failsEveryApp) {
                    failed += apps.subList(index, apps.size)
                    break
                }
                failed += app
            }
        }
        return Outcome(done, failed, failure)
    }

    /** The apps a change was made for and those it failed for, and why the first one failed. */
    private data class Outcome(val done: List<InstalledApp>, val failed: List<InstalledApp>, val failure: LoadFailure?)

    private fun List<InstalledApp>.names() = map { it.info.label }

    /** Whether no other app could fare better: root, or the daemon itself, is missing. */
    private val RecordsException.failsEveryApp: Boolean
        get() = when (this) {
            is RecordsException.NoRoot, is RecordsException.ModuleMissing, is RecordsException.ModuleOutdated -> true
            is RecordsException.CommandFailed -> false
        }
}
