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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SensorSession
import li.mercury.bigbrother.core.model.TimeRange

data class RecordUiState(
    val appInfo: AppInfo? = null,
    /** The records of the kind shown; null until loaded, or if there are none any more. */
    val group: TopicGroup? = null,
    /** Each access, most recent first; null until loaded. */
    val periods: List<AccessPeriod>? = null,
    /** Only the history of some of the paths was loaded. */
    val partialHistory: Boolean = false,
    /** Why the history could not be loaded, when the records could. */
    val historyFailure: LoadFailure? = null,
    /**
     * For sensors: when each was on, most recent first. Null when not
     * loaded, when the read failed, or for records that are not the app's
     * own.
     */
    val sensorSessions: List<SensorSession>? = null,
    val loaded: Boolean = false,
    val failure: LoadFailure? = null,
)

@HiltViewModel(assistedFactory = RecordViewModel.Factory::class)
class RecordViewModel @AssistedInject constructor(
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    @Assisted private val uid: Int,
    @Assisted("packageName") private val packageName: String,
    @Assisted private val range: TimeRange,
    @Assisted("topic") private val topic: String,
    @Assisted("source") private val source: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        _uiState.update { it.copy(failure = null, historyFailure = null) }
        if (packageName.isNotEmpty()) {
            viewModelScope.launch {
                _uiState.update { it.copy(appInfo = appInfoRepository.getAppInfo(packageName)) }
            }
        }
        viewModelScope.launch {
            try {
                val accesses = accessRepository.getAccesses(uid, null, range, MAX_ACCESSES, 0)
                val group = groupByCategory(accesses).values.flatten()
                    .firstOrNull { it.topic == topic && it.source.name == source }
                _uiState.update { it.copy(group = group, loaded = true, failure = null) }
                if (group != null) {
                    if (group.topic == SENSORS && group.source == RecordSource.APP) loadSensorSessions()
                    loadHistory(group)
                }
            } catch (e: RecordsException) {
                _uiState.update { it.copy(loaded = true, failure = e.toLoadFailure()) }
            }
        }
    }

    /**
     * The history of the paths in the group, merged. A kind of data can span
     * many paths (an app looking up hundreds of other apps); the most recent
     * ones are enough to show when it happened.
     */
    private suspend fun loadHistory(group: TopicGroup) {
        val paths = group.accesses.sortedByDescending { it.lastSeen }.map { it.path }.distinct()
        try {
            val periods = accessRepository.getHistory(uid, paths.take(MAX_HISTORY_PATHS), range).take(MAX_PERIODS)
            _uiState.update { it.copy(periods = periods, partialHistory = paths.size > MAX_HISTORY_PATHS) }
        } catch (e: RecordsException) {
            _uiState.update { it.copy(historyFailure = e.toLoadFailure()) }
        }
    }

    /**
     * When each sensor was on. If the read fails, the section is left out
     * and the rest of the record still shows.
     */
    private suspend fun loadSensorSessions() {
        val sessions = try {
            accessRepository.getSensorSessions(uid, range)
        } catch (_: RecordsException) {
            null
        }
        _uiState.update { it.copy(sensorSessions = sessions) }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            uid: Int,
            @Assisted("packageName") packageName: String,
            range: TimeRange,
            @Assisted("topic") topic: String,
            @Assisted("source") source: String,
        ): RecordViewModel
    }

    private companion object {
        const val SENSORS = "sensors"
        const val MAX_HISTORY_PATHS = 20
        const val MAX_PERIODS = 300
    }
}
