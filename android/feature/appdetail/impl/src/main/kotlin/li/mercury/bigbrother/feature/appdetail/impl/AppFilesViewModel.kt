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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

internal sealed interface FileAccessResult {
    data class Granted(val path: String) : FileAccessResult
    data object Denied : FileAccessResult
}

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
    val fileAccessResult: FileAccessResult? = null,
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
    private val refreshes = MutableStateFlow(0L)
    private val files = MutableStateFlow<LoadedFiles?>(null)
    private val filter = MutableStateFlow(BrowserFilter(query = browserQuery(), readOnly = savedState[READ_ONLY]))
    private val detailsPath = MutableStateFlow<String?>(null)
    private var completed: Long? = null
    private var builtFor: Pair<LoadedFiles, BrowserFilter>? = null
    private var fileAccessJob: Job? = null

    internal val uiState: StateFlow<AppFilesUiState> = channelFlow {
        launch {
            refreshes.collectLatest { request ->
                if (request != completed) {
                    load()
                    completed = request
                }
            }
        }
        launch {
            combine(files, filter, ::Pair).collectLatest { request ->
                val (files, filter) = request
                if (files != null && request != builtFor) buildBrowser(files, filter)
            }
        }
        launch {
            detailsPath.collectLatest { path ->
                val details = mutableUiState.value.fileDetails
                if (path != null && details?.periods == null && details?.failure == null) loadFileDetails(path)
            }
        }
        mutableUiState.collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), mutableUiState.value)

    fun retry() {
        refreshes.update { it + 1 }
    }

    private suspend fun load(): Unit = coroutineScope {
        files.value = null
        builtFor = null
        mutableUiState.update { it.copy(browser = null, truncated = false, failure = null) }
        val packageName = mutableUiState.value.packageName
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
            val loaded = withContext(defaultDispatcher) {
                accesses.filter { access ->
                    ensureActive()
                    access.isFile()
                }
            }
            mutableUiState.update { it.copy(truncated = accesses.size == MAX_ACCESSES) }
            files.value = LoadedFiles(loaded)
        } catch (e: RecordsException) {
            ensureActive()
            mutableUiState.update { it.copy(failure = e.toLoadFailure()) }
        }
    }

    fun openSearch() {
        savedState[SEARCHING] = true
        mutableUiState.update { it.copy(searching = true) }
        updateBrowserQuery()
    }

    fun closeSearch() {
        savedState[SEARCHING] = false
        savedState[QUERY] = ""
        mutableUiState.update { it.copy(searching = false, query = "") }
        updateBrowserQuery()
    }

    fun search(query: String) {
        savedState[QUERY] = query
        mutableUiState.update { it.copy(query = query) }
        updateBrowserQuery()
    }

    fun showReadOnly(readOnly: Boolean) {
        if (readOnly == savedState.get<Boolean>(READ_ONLY)) return
        savedState[READ_ONLY] = readOnly
        filter.update { it.copy(readOnly = readOnly) }
    }

    private fun browserQuery(): String = with(mutableUiState.value) { if (searching) query.trim() else "" }

    private fun updateBrowserQuery() {
        filter.update { it.copy(query = browserQuery()) }
    }

    private suspend fun buildBrowser(files: LoadedFiles, filter: BrowserFilter) {
        val readOnly = filter.readOnly ?: savedState.get<Boolean>(READ_ONLY)
        val browser = withContext(defaultDispatcher) {
            fileBrowserOf(files.accesses, filter.query, readOnly) { ensureActive() }
        }
        // A new load or filter can be queued while the worker finishes.
        if (this.files.value !== files || this.filter.value != filter) return
        if (readOnly == null) savedState[READ_ONLY] = browser.readOnly
        mutableUiState.update { it.copy(browser = browser) }
        builtFor = files to filter
    }

    /** Open the details of the file at [path] and load when it was accessed. */
    fun showFileDetails(path: String) {
        if (detailsPath.value == path) return
        mutableUiState.update { it.copy(fileDetails = FileDetails(path)) }
        detailsPath.value = path
    }

    fun dismissFileDetails() {
        detailsPath.value = null
        mutableUiState.update { it.copy(fileDetails = null) }
    }

    private suspend fun loadFileDetails(path: String) {
        val details = try {
            FileDetails(path, periods = accessRepository.getHistory(uid, listOf(path), range))
        } catch (e: RecordsException) {
            FileDetails(path, failure = e.toLoadFailure())
        }
        currentCoroutineContext().ensureActive()
        if (detailsPath.value == path) mutableUiState.update { it.copy(fileDetails = details) }
    }

    /** Obtain access before the screen offers this file to another app. */
    fun ensureFileAccess(path: String) {
        fileAccessJob?.cancel()
        mutableUiState.update { it.copy(fileAccessResult = null) }
        fileAccessJob = viewModelScope.launch {
            val result = if (fileAccessRepository.ensureAllFilesAccess()) FileAccessResult.Granted(path) else FileAccessResult.Denied
            ensureActive()
            mutableUiState.update { it.copy(fileAccessResult = result) }
        }
    }

    internal fun onFileAccessHandled(result: FileAccessResult) {
        mutableUiState.update { if (it.fileAccessResult === result) it.copy(fileAccessResult = null) else it }
    }

    private class LoadedFiles(val accesses: List<Access>)

    private data class BrowserFilter(val query: String, val readOnly: Boolean?)

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
