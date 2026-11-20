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

import android.os.Process
import android.os.UserHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.PackageDetails
import li.mercury.bigbrother.core.model.TimeRange

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
)

@HiltViewModel(assistedFactory = AppDetailViewModel.Factory::class)
class AppDetailViewModel @AssistedInject constructor(
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
    @Assisted private val uid: Int,
    @Assisted packageName: String,
    @Assisted private val range: TimeRange,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        AppDetailUiState(
            app = AppSummary(uid, packageName, counts = emptyMap(), lastSeen = 0),
            range = range,
        ),
    )
    internal val uiState: StateFlow<AppDetailUiState> = mutableUiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        loadSummary()
        loadAccesses()
    }

    /** The app's totals, name, icon and package facts, for the header and the info section. */
    private fun loadSummary() {
        // Name and package facts come from the package manager, so they show
        // even if the records fail to load; the two are fetched side by side.
        val packageName = mutableUiState.value.app.packageName.takeIf { it.isNotEmpty() }
        if (packageName != null) {
            viewModelScope.launch {
                val info = appInfoRepository.getAppInfo(packageName)
                // The package manager answers for this app's own Android user.
                // For a clone or a work profile app it would describe, and
                // its actions would open, another user's copy, so it gives no
                // package facts there.
                val details = if (UserHandle.getUserHandleForUid(uid) == Process.myUserHandle()) {
                    appInfoRepository.getPackageDetails(packageName)
                } else {
                    null
                }
                mutableUiState.update { it.copy(appInfo = info, packageDetails = details) }
            }
        }
        viewModelScope.launch {
            try {
                val apps = accessRepository.getApps(range)
                val app = withContext(defaultDispatcher) { apps.firstOrNull { it.uid == uid } } ?: return@launch
                mutableUiState.update { it.copy(app = app) }
            } catch (_: RecordsException) {
                // The access list reports the failure; the header keeps its placeholder.
            }
        }
    }

    private fun loadAccesses() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val accesses = accessRepository.getAccesses(
                    uid = uid,
                    category = null,
                    range = range,
                    limit = MAX_ACCESSES,
                    offset = 0,
                )
                val records = withContext(defaultDispatcher) { summarizeAccesses(accesses) }
                ensureActive()
                mutableUiState.update {
                    it.copy(
                        records = records,
                        truncated = accesses.size == MAX_ACCESSES,
                        failure = null,
                    )
                }
            } catch (e: RecordsException) {
                ensureActive()
                mutableUiState.update { it.copy(records = null, failure = e.toLoadFailure()) }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(uid: Int, packageName: String, range: TimeRange): AppDetailViewModel
    }
}

/** Most recent paths loaded for one app. */
internal const val MAX_ACCESSES = 5000
