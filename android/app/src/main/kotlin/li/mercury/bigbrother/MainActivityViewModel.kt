package li.mercury.bigbrother

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.model.UserData

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    userDataRepository: UserDataRepository,
    accessRepository: AccessRepository,
) : ViewModel() {
    /** The settings; null until read, so that the first frame already has the right colors. */
    val userData: StateFlow<UserData?> = userDataRepository.userData
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Whether the most recent status check found the daemon not recording. */
    val recordingStopped: StateFlow<Boolean> = accessRepository.recorderStatus
        .map { it?.recording == false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
