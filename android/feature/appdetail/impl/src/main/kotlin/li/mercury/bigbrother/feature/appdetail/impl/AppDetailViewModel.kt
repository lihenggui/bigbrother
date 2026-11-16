package li.mercury.bigbrother.feature.appdetail.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.FileAccessRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange

/** The file whose access history is shown. */
data class FileDetails(
    val path: String,
    /** Null while loading. */
    val periods: List<AccessPeriod>? = null,
    val failure: LoadFailure? = null,
)

data class AppDetailUiState(
    /** The app's totals. Until loaded, a placeholder that only carries the uid. */
    val app: AppSummary,
    val appInfo: AppInfo? = null,
    /** Everything the app touched; null until loaded. */
    val accesses: List<Access>? = null,
    /** There were more records than are shown. */
    val truncated: Boolean = false,
    val failure: LoadFailure? = null,
    /** Set while the details of one file are open. */
    val fileDetails: FileDetails? = null,
)

@HiltViewModel(assistedFactory = AppDetailViewModel.Factory::class)
class AppDetailViewModel @AssistedInject constructor(
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    private val fileAccessRepository: FileAccessRepository,
    @Assisted private val uid: Int,
    @Assisted packageName: String,
    @Assisted private val range: TimeRange,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AppDetailUiState(AppSummary(uid, packageName, counts = emptyMap(), lastSeen = 0)),
    )
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() = load()

    /** Open the details of the file at [path] and load when it was accessed. */
    fun showFileDetails(path: String) {
        _uiState.update { it.copy(fileDetails = FileDetails(path)) }
        viewModelScope.launch {
            val details = try {
                FileDetails(path, periods = accessRepository.getHistory(uid, path, range))
            } catch (e: RecordsException) {
                FileDetails(path, failure = e.toLoadFailure())
            }
            // Unless the user has closed it or opened another file meanwhile.
            _uiState.update { if (it.fileDetails?.path == path) it.copy(fileDetails = details) else it }
        }
    }

    fun dismissFileDetails() {
        _uiState.update { it.copy(fileDetails = null) }
    }

    /** Whether this app can reach files on shared storage, obtaining access if it can. */
    suspend fun ensureFileAccess(): Boolean = fileAccessRepository.ensureAllFilesAccess()

    private fun load() {
        loadSummary()
        loadAccesses()
    }

    /** The app's name and icon, for the header. */
    private fun loadSummary() {
        viewModelScope.launch {
            try {
                val app = accessRepository.getApps(range).firstOrNull { it.uid == uid } ?: return@launch
                val info = app.packageName.takeIf { it.isNotEmpty() }
                    ?.let { appInfoRepository.getAppInfo(it) }
                _uiState.update { it.copy(app = app, appInfo = info) }
            } catch (_: RecordsException) {
                // The access list reports the failure; the header keeps its placeholder.
            }
        }
    }

    private fun loadAccesses() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                // One request for everything: the screen arranges the paths
                // into a tree, which needs all of them at once.
                val accesses = accessRepository.getAccesses(
                    uid = uid,
                    category = null,
                    range = range,
                    limit = MAX_ACCESSES,
                    offset = 0,
                )
                _uiState.update {
                    it.copy(
                        accesses = accesses,
                        truncated = accesses.size == MAX_ACCESSES,
                        failure = null,
                    )
                }
            } catch (e: RecordsException) {
                _uiState.update { it.copy(accesses = emptyList(), failure = e.toLoadFailure()) }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(uid: Int, packageName: String, range: TimeRange): AppDetailViewModel
    }

    private companion object {
        /** Most recent paths loaded for one app. */
        const val MAX_ACCESSES = 5000
    }
}
