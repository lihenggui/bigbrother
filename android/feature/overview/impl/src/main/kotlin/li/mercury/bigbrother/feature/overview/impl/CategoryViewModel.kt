package li.mercury.bigbrother.feature.overview.impl

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.dataCategories
import li.mercury.bigbrother.core.ui.isHiddenByDefault
import li.mercury.bigbrother.core.ui.readFiles

data class CategoryUiState(
    val category: DataCategory,
    val range: TimeRange = TimeRange.DAY,
    /** Apps that accessed the category, in the order listed; null until loaded. */
    val apps: List<AppSummary>? = null,
    val appInfo: Map<String, AppInfo> = emptyMap(),
    val failure: LoadFailure? = null,
)

@HiltViewModel(assistedFactory = CategoryViewModel.Factory::class)
class CategoryViewModel @AssistedInject constructor(
    @ApplicationContext private val context: Context,
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    private val userDataRepository: UserDataRepository,
    @Assisted category: DataCategory,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CategoryUiState(category))
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val userData = userDataRepository.userData.first()
                val category = _uiState.value.category
                val apps = accessRepository.getApps(userData.timeRange)
                val appInfo = appInfoRepository.getAppInfo(
                    apps.map { it.packageName }.filter { it.isNotEmpty() }.toSet(),
                )
                val shown = apps
                    .filter { it.packageName != context.packageName }
                    .filter { userData.showSystemApps || !isHiddenByDefault(it, appInfo) }
                    .filter { category in it.dataCategories() }
                    // For files, apps that read some come before those that only looked.
                    .sortedWith(
                        compareByDescending<AppSummary> { category == DataCategory.FILES && it.readFiles }
                            .thenByDescending { it.lastSeen },
                    )
                _uiState.update {
                    it.copy(range = userData.timeRange, apps = shown, appInfo = appInfo, failure = null)
                }
            } catch (e: RecordsException) {
                _uiState.update { it.copy(apps = emptyList(), failure = e.toLoadFailure()) }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(category: DataCategory): CategoryViewModel
    }
}
