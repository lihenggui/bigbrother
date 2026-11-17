package li.mercury.bigbrother.feature.settings.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.ModuleRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.model.RecorderStatus
import li.mercury.bigbrother.core.model.UserData

data class SettingsUiState(
    val userData: UserData = UserData(),
    /** Null until known, or when it can't be read. */
    val status: RecorderStatus? = null,
    /** The installed module's version, if known. */
    val moduleVersion: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val moduleRepository: ModuleRepository,
    private val userDataRepository: UserDataRepository,
) : ViewModel() {
    private val moduleVersion = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        userDataRepository.userData,
        accessRepository.recorderStatus,
        moduleVersion,
    ) { userData, status, version -> SettingsUiState(userData, status, version) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun refresh() {
        viewModelScope.launch {
            moduleVersion.value = moduleRepository.installedVersion()
            try {
                accessRepository.getRecorderStatus()
            } catch (_: RecordsException) {
                // The status row says it is unknown.
            }
        }
    }

    fun setShowSystemApps(show: Boolean) {
        viewModelScope.launch { userDataRepository.setShowSystemApps(show) }
    }

    fun setUseDynamicColor(use: Boolean) {
        viewModelScope.launch { userDataRepository.setUseDynamicColor(use) }
    }
}
