package li.mercury.bigbrother.feature.appdetail.impl

import android.os.Process
import android.os.UserHandle
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
import li.mercury.bigbrother.core.model.PackageDetails
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
    /** The time range the records cover. */
    val range: TimeRange = TimeRange.DAY,
    /** Version and install facts; null until loaded or when not installed. */
    val packageDetails: PackageDetails? = null,
    /**
     * Id of the selected tab, or null for the first tab with content. Kept
     * here rather than in the screen so that it survives the tabs being
     * rebuilt when records reload.
     */
    val selectedTab: String? = null,
)

@HiltViewModel(assistedFactory = AppDetailViewModel.Factory::class)
class AppDetailViewModel @AssistedInject constructor(
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    private val fileAccessRepository: FileAccessRepository,
    @Assisted private val uid: Int,
    @Assisted("packageName") packageName: String,
    @Assisted private val range: TimeRange,
    @Assisted("tab") initialTab: String?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AppDetailUiState(
            app = AppSummary(uid, packageName, counts = emptyMap(), lastSeen = 0),
            range = range,
            selectedTab = initialTab,
        ),
    )
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() = load()

    fun selectTab(id: String) {
        _uiState.update { it.copy(selectedTab = id) }
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

    private fun load() {
        loadSummary()
        loadAccesses()
    }

    /** The app's totals, name, icon and package facts, for the header and the info tab. */
    private fun loadSummary() {
        // Name and package facts come from the package manager, so they show
        // even if the records fail to load; the two are fetched side by side.
        val packageName = _uiState.value.app.packageName.takeIf { it.isNotEmpty() }
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
                _uiState.update { it.copy(appInfo = info, packageDetails = details) }
            }
        }
        viewModelScope.launch {
            try {
                val app = accessRepository.getApps(range).firstOrNull { it.uid == uid } ?: return@launch
                _uiState.update { it.copy(app = app) }
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
        fun create(
            uid: Int,
            @Assisted("packageName") packageName: String,
            range: TimeRange,
            @Assisted("tab") initialTab: String?,
        ): AppDetailViewModel
    }

    private companion object {
        /** Most recent paths loaded for one app. */
        const val MAX_ACCESSES = 5000
    }
}
