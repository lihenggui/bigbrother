package li.mercury.bigbrother.feature.appdetail.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
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
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange

/** The file whose access history is shown. */
data class FileDetails(
    val path: String,
    /** Null while loading. */
    val periods: List<AccessPeriod>? = null,
    val failure: LoadFailure? = null,
)

data class AppFilesUiState(
    val packageName: String,
    val appInfo: AppInfo? = null,
    /** The app's file records; null until loaded. */
    val accesses: List<Access>? = null,
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
    @Assisted private val uid: Int,
    @Assisted packageName: String,
    @Assisted private val range: TimeRange,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppFilesUiState(packageName))
    val uiState: StateFlow<AppFilesUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        val packageName = _uiState.value.packageName.takeIf { it.isNotEmpty() }
        if (packageName != null) {
            viewModelScope.launch {
                _uiState.update { it.copy(appInfo = appInfoRepository.getAppInfo(packageName)) }
            }
        }
        viewModelScope.launch {
            try {
                // One request for everything: the screen arranges the paths
                // into a tree, which needs all of them at once.
                val accesses = accessRepository.getAccesses(uid, null, range, MAX_ACCESSES, 0)
                _uiState.update {
                    it.copy(
                        accesses = accesses.filter { access -> access.isFile() },
                        truncated = accesses.size == MAX_ACCESSES,
                        failure = null,
                    )
                }
            } catch (e: RecordsException) {
                _uiState.update { it.copy(accesses = emptyList(), failure = e.toLoadFailure()) }
            }
        }
    }

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

    @AssistedFactory
    interface Factory {
        fun create(uid: Int, packageName: String, range: TimeRange): AppFilesViewModel
    }
}
