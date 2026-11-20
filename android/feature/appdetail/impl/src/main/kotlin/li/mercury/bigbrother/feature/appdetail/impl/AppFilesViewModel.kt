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

import androidx.lifecycle.SavedStateHandle
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
import li.mercury.bigbrother.core.data.repository.FileAccessRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange

/** The file whose access history is shown. */
data class FileDetails(
    val path: String,
    /** Null while loading. */
    val periods: List<AccessPeriod>? = null,
    val failure: LoadFailure? = null,
)

internal data class AppFilesUiState(
    val packageName: String,
    val appInfo: AppInfo? = null,
    val browser: FileBrowser? = null,
    val searching: Boolean = false,
    val query: String = "",
    val truncated: Boolean = false,
    val failure: LoadFailure? = null,
    /** Set while the details of one file are open. */
    val fileDetails: FileDetails? = null,
)

@HiltViewModel(assistedFactory = AppFilesViewModel.Factory::class)
class AppFilesViewModel @AssistedInject constructor(
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    private val fileAccessRepository: FileAccessRepository,
    private val savedState: SavedStateHandle,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
    @Assisted private val uid: Int,
    @Assisted packageName: String,
    @Assisted private val range: TimeRange,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        AppFilesUiState(
            packageName = packageName,
            searching = savedState[SEARCHING] ?: false,
            query = savedState[QUERY] ?: "",
        ),
    )
    internal val uiState: StateFlow<AppFilesUiState> = mutableUiState.asStateFlow()

    private var loadJob: Job? = null
    private var browserJob: Job? = null
    private var detailsJob: Job? = null
    private var files: List<Access>? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        browserJob?.cancel()
        files = null
        mutableUiState.update { it.copy(browser = null, truncated = false, failure = null) }
        val packageName = mutableUiState.value.packageName
        loadJob = viewModelScope.launch {
            if (packageName.isNotEmpty()) {
                launch {
                    val info = appInfoRepository.getAppInfo(packageName)
                    ensureActive()
                    mutableUiState.update { it.copy(appInfo = info) }
                }
            }
            try {
                // One request for everything so a folder can be assembled from all its paths.
                val accesses = accessRepository.getAccesses(uid, null, range, MAX_ACCESSES, 0)
                files = withContext(defaultDispatcher) {
                    accesses.filter { access ->
                        ensureActive()
                        access.isFile()
                    }
                }
                ensureActive()
                mutableUiState.update { it.copy(truncated = accesses.size == MAX_ACCESSES) }
                buildBrowser()
            } catch (e: RecordsException) {
                ensureActive()
                mutableUiState.update { it.copy(failure = e.toLoadFailure()) }
            }
        }
    }

    fun openSearch() {
        savedState[SEARCHING] = true
        mutableUiState.update { it.copy(searching = true) }
    }

    fun closeSearch() {
        val wasFiltering = mutableUiState.value.query.isNotBlank()
        savedState[SEARCHING] = false
        savedState[QUERY] = ""
        mutableUiState.update { it.copy(searching = false, query = "") }
        if (wasFiltering) buildBrowser()
    }

    fun search(query: String) {
        val changed = query.trim() != mutableUiState.value.query.trim()
        savedState[QUERY] = query
        mutableUiState.update { it.copy(query = query) }
        if (changed) buildBrowser()
    }

    fun showReadOnly(readOnly: Boolean) {
        if (readOnly == savedState.get<Boolean>(READ_ONLY)) return
        savedState[READ_ONLY] = readOnly
        buildBrowser()
    }

    private fun buildBrowser() {
        val files = files ?: return
        val state = mutableUiState.value
        val query = if (state.searching) state.query.trim() else ""
        val readOnly = savedState.get<Boolean>(READ_ONLY)
        browserJob?.cancel()
        browserJob = viewModelScope.launch {
            val browser = withContext(defaultDispatcher) {
                fileBrowserOf(files, query, readOnly) { ensureActive() }
            }
            ensureActive()
            if (readOnly == null) savedState[READ_ONLY] = browser.readOnly
            mutableUiState.update { it.copy(browser = browser) }
        }
    }

    /** Open the details of the file at [path] and load when it was accessed. */
    fun showFileDetails(path: String) {
        detailsJob?.cancel()
        mutableUiState.update { it.copy(fileDetails = FileDetails(path)) }
        detailsJob = viewModelScope.launch {
            val details = try {
                FileDetails(path, periods = accessRepository.getHistory(uid, listOf(path), range))
            } catch (e: RecordsException) {
                FileDetails(path, failure = e.toLoadFailure())
            }
            ensureActive()
            mutableUiState.update { it.copy(fileDetails = details) }
        }
    }

    fun dismissFileDetails() {
        detailsJob?.cancel()
        mutableUiState.update { it.copy(fileDetails = null) }
    }

    /** Whether this app can reach files on shared storage, obtaining access if it can. */
    suspend fun ensureFileAccess(): Boolean = fileAccessRepository.ensureAllFilesAccess()

    @AssistedFactory
    interface Factory {
        fun create(uid: Int, packageName: String, range: TimeRange): AppFilesViewModel
    }

    private companion object {
        const val SEARCHING = "searching"
        const val QUERY = "query"
        const val READ_ONLY = "readOnly"
    }
}
