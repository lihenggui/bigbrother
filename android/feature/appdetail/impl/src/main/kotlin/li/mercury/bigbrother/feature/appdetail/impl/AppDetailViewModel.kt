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

package li.mercury.bigbrother.feature.appdetail.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.di.AppPackageName
import li.mercury.bigbrother.core.common.di.CurrentUserId
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.InstalledAppRepository
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.PackageDetails
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.isNonCoreUid
import li.mercury.bigbrother.core.model.userIdOf

internal data class AppDetailUiState(
    /** The app's totals. Until loaded, a placeholder that only carries the uid. */
    val app: AppSummary,
    val appInfo: AppInfo? = null,
    /** Everything the app touched; null until loaded. */
    val records: AppDetailRecords? = null,
    /** There were more records than are shown. */
    val truncated: Boolean = false,
    val failure: LoadFailure? = null,
    /** The time range the records cover. */
    val range: TimeRange = TimeRange.DAY,
    /** Version and install facts; null until loaded or when not installed. */
    val packageDetails: PackageDetails? = null,
    val canOpenJail: Boolean = false,
    val jailAction: JailAction? = null,
)

internal sealed interface JailAction {
    data object Checking : JailAction
    data object Confirm : JailAction
    data object Isolating : JailAction
    data class Open(val packageName: String) : JailAction
    data class Failed(val failure: LoadFailure) : JailAction
}

@HiltViewModel(assistedFactory = AppDetailViewModel.Factory::class)
class AppDetailViewModel @AssistedInject constructor(
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val sandboxRepository: SandboxRepository,
    @AppPackageName private val ownPackage: String,
    @CurrentUserId private val currentUserId: Int,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
    @Assisted private val uid: Int,
    @Assisted private val packageName: String,
    @Assisted private val range: TimeRange,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        AppDetailUiState(
            app = AppSummary(uid, packageName, counts = emptyMap(), lastSeen = 0),
            range = range,
        ),
    )
    private val refreshes = MutableStateFlow(0L)
    private var completed: Long? = null

    internal val uiState: StateFlow<AppDetailUiState> = channelFlow {
        launch {
            refreshes.collectLatest { request ->
                if (request != completed) {
                    load()
                    completed = request
                }
            }
        }
        mutableUiState.collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), mutableUiState.value)

    fun retry() {
        refreshes.update { it + 1 }
    }

    fun requestJail() {
        val state = mutableUiState.value
        if (!state.canOpenJail || state.jailAction != null) return
        showJailAction(JailAction.Checking)
        viewModelScope.launch {
            try {
                sandboxRepository.refresh()
                val isolated = sandboxRepository.apps.value?.firstOrNull { it.uid == uid && it.enabled }
                showJailAction(if (isolated == null) JailAction.Confirm else JailAction.Open(isolated.packageName))
            } catch (e: RecordsException) {
                showJailAction(JailAction.Failed(e.toLoadFailure()))
            }
        }
    }

    fun confirmIsolation() {
        if (mutableUiState.value.jailAction != JailAction.Confirm) return
        showJailAction(JailAction.Isolating)
        viewModelScope.launch {
            try {
                // The records can outlive an installation. Check the live package again
                // before applying restrictions to its Android user.
                if (installedAppRepository.getInstalledApps().none { it.uid == uid && it.packageName == packageName }) {
                    mutableUiState.update { it.copy(canOpenJail = false, jailAction = JailAction.Failed(LoadFailure.Operation.APP_MISSING)) }
                    return@launch
                }
                sandboxRepository.isolate(packageName, mutableUiState.value.app.userId)
                val entry = sandboxRepository.apps.value?.firstOrNull { it.uid == uid && it.enabled }
                showJailAction(JailAction.Open(entry?.packageName ?: packageName))
            } catch (e: RecordsException) {
                showJailAction(JailAction.Failed(e.toLoadFailure()))
            }
        }
    }

    fun dismissJailAction() {
        if (mutableUiState.value.jailAction != JailAction.Isolating) showJailAction(null)
    }

    private fun showJailAction(action: JailAction?) {
        mutableUiState.update { it.copy(jailAction = action) }
    }

    private suspend fun load(): Unit = coroutineScope {
        // Package facts and totals can still be shown if reading the records fails.
        if (packageName.isNotEmpty()) launch { loadPackageInfo() }
        launch { loadSummary() }
        loadAccesses()
    }

    private suspend fun loadPackageInfo() {
        val info = appInfoRepository.getAppInfo(packageName)
        // The package manager describes this user's copy, which may be a different app.
        val details = if (userIdOf(uid) == currentUserId) appInfoRepository.getPackageDetails(packageName) else null
        val canOpenJail = packageName != ownPackage && isNonCoreUid(uid) && info?.uid == uid
        currentCoroutineContext().ensureActive()
        mutableUiState.update {
            it.copy(appInfo = info, packageDetails = details, canOpenJail = canOpenJail && it.app.packageName == packageName)
        }
    }

    private suspend fun loadSummary() {
        try {
            val apps = accessRepository.getApps(range)
            val app = withContext(defaultDispatcher) { apps.firstOrNull { it.uid == uid } } ?: return
            mutableUiState.update { it.copy(app = app, canOpenJail = it.canOpenJail && app.packageName == packageName) }
        } catch (_: RecordsException) {
            // The summary only enriches the header. The access list has its own result.
        }
    }

    private suspend fun loadAccesses() {
        try {
            val accesses = accessRepository.getAccesses(uid, null, range, MAX_ACCESSES, 0)
            val records = withContext(defaultDispatcher) { summarizeAccesses(accesses) }
            mutableUiState.update {
                it.copy(records = records, truncated = accesses.size == MAX_ACCESSES, failure = null)
            }
        } catch (e: RecordsException) {
            currentCoroutineContext().ensureActive()
            mutableUiState.update { it.copy(records = null, failure = e.toLoadFailure()) }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(uid: Int, packageName: String, range: TimeRange): AppDetailViewModel
    }
}

/** Most recent paths loaded for one app. */
internal const val MAX_ACCESSES = 5000
