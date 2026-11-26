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

package li.mercury.bigbrother.feature.jail.impl.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.di.CurrentUserId
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.NetworkRepository
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkRecording
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.userIdOf

/** Where one app in the jail connected to, and blocking destinations of it. */
@HiltViewModel(assistedFactory = NetworkActivityViewModel.Factory::class)
class NetworkActivityViewModel @AssistedInject constructor(
    @Assisted uid: Int,
    @Assisted private val packageName: String,
    private val networkRepository: NetworkRepository,
    private val sandboxRepository: SandboxRepository,
    private val appInfoRepository: AppInfoRepository,
    @Dispatcher(DEFAULT) defaultDispatcher: CoroutineDispatcher,
    @CurrentUserId currentUserId: Int,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(uid: Int, packageName: String): NetworkActivityViewModel
    }

    private val userId = userIdOf(uid)
    private val load = MutableStateFlow(
        NetworkActivityUiState(packageName = packageName, otherUser = userId.takeIf { it != currentUserId }),
    )

    private val refreshes = MutableStateFlow(ReadRequest())
    private var completed: Pair<ReadRequest, Int>? = null
    private var infoLoaded = false

    val uiState: StateFlow<NetworkActivityUiState> = channelFlow {
        launch {
            if (!infoLoaded) {
                val info = appInfoRepository.getAppInfo(packageName)
                ensureActive()
                load.update { it.copy(info = info) }
                infoLoaded = true
            }
        }
        launch {
            // A change made here or in a destination's detail also invalidates the read.
            combine(refreshes, networkRepository.changes, ::Pair).collectLatest { request ->
                if (request != completed) {
                    val shown = load.value.activity.takeIf { request.first.reuseActivityAt == request.second }
                    read(shown, request)
                    completed = request
                }
            }
        }
        load.collect { send(it) }
    }.map { withContext(defaultDispatcher) { it.withShown() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), load.value)

    fun refresh() {
        refreshes.update { ReadRequest(it.version + 1) }
    }

    fun setRange(range: TimeRange) {
        if (range == load.value.range) return
        // The activity shown stays until the new range's replaces it, so the
        // page does not fall back to the loader; but it is no longer reused
        // for a bar chosen in the meantime.
        load.update { it.copy(range = range, bucket = null, period = null) }
        completed = null
        refresh()
    }

    /** Lists the destinations of the bar from [start], or with null of the whole range again. */
    fun selectBucket(start: Long?) {
        val shown = load.value
        val chosen = start.takeIf { it != shown.bucket }
        load.update { it.copy(bucket = chosen, period = null) }
        if (chosen != null) {
            refreshes.update { ReadRequest(it.version + 1, completed?.second) }
        }
    }

    fun setBlockedOnly(on: Boolean) {
        load.update { state ->
            state.copy(blockedOnly = on, blockedShown = if (on) state.blockedTargets() else emptySet())
        }
    }

    fun setSort(sort: DestinationSort) {
        load.update { it.copy(sort = sort) }
    }

    fun setQuery(text: String) {
        load.update { it.copy(query = text) }
    }

    fun showAllDomains() {
        load.update { it.copy(showAllDomains = true) }
    }

    fun showAllAddresses() {
        load.update { it.copy(showAllAddresses = true) }
    }

    fun block(target: NetworkTarget, manual: Boolean = false) {
        change(target, blocked = true, manual = manual)
    }

    fun unblock(target: NetworkTarget) {
        change(target, blocked = false)
    }

    /** Takes back the block or unblock just told of. */
    fun undo() {
        val message = load.value.message ?: return
        load.update { it.copy(message = null) }
        when (message) {
            is NetworkMessage.Blocked -> change(message.target, blocked = false, tell = false)
            is NetworkMessage.Unblocked -> change(message.target, blocked = true, tell = false)
        }
    }

    fun setRecording(on: Boolean) {
        viewModelScope.launch {
            try {
                val app = sandboxRepository.setItems(
                    packageName,
                    userId,
                    mapOf(SandboxItem.NETWORK_RECORD to if (on) SandboxMode.ON else SandboxMode.OFF),
                )
                val currentRequest = refreshes.value to networkRepository.changes.value
                if (app == null || load.value.activity == null || completed != currentRequest) {
                    refresh()
                } else {
                    val recording = NetworkRecording(on = app.recordingSince != null, since = app.recordingSince)
                    load.update {
                        it.copy(activity = it.activity?.copy(recording = recording), period = it.period?.copy(recording = recording))
                    }
                }
            } catch (e: RecordsException) {
                load.update { it.copy(error = e.toLoadFailure()) }
            }
        }
    }

    fun clearRecords() {
        viewModelScope.launch {
            try {
                networkRepository.clear(packageName, userId)
            } catch (e: RecordsException) {
                load.update { it.copy(error = e.toLoadFailure()) }
            }
        }
    }

    fun dismissMessage() {
        load.update { it.copy(message = null) }
    }

    fun dismissError() {
        load.update { it.copy(error = null) }
    }

    private suspend fun read(shown: NetworkActivity?, request: Pair<ReadRequest, Int>) {
        val state = load.value
        try {
            val activity = shown ?: networkRepository.getActivity(packageName, userId, state.range.since(now()), bucket = state.range.bucketSize)
            val index = activity.buckets.indexOfFirst { it.start == state.bucket }
            val period = if (index == -1) {
                null
            } else {
                val since = maxOf(activity.since, activity.buckets[index].start)
                val until = activity.buckets.getOrNull(index + 1)?.start ?: activity.until
                networkRepository.getActivity(packageName, userId, since, until, state.range.bucketSize, activity.zone)
            }
            currentCoroutineContext().ensureActive()
            if (refreshes.value != request.first || networkRepository.changes.value != request.second) return
            load.update { current ->
                val bucket = current.bucket.takeIf { it == state.bucket && period != null }
                current.copy(
                    activity = activity,
                    bucket = bucket,
                    period = period.takeIf { bucket != null },
                    failure = null,
                    // Rules added elsewhere join those the filter lists.
                    blockedShown = if (current.blockedOnly) current.blockedShown + activity.rules.map { it.target } else emptySet(),
                )
            }
        } catch (e: RecordsException) {
            currentCoroutineContext().ensureActive()
            if (refreshes.value == request.first && networkRepository.changes.value == request.second) {
                load.update { it.copy(failure = e.toLoadFailure()) }
            }
        }
    }

    private data class ReadRequest(val version: Long = 0, val reuseActivityAt: Int? = null)

    /**
     * Blocks or unblocks [target], shown as done at once. If the daemon
     * does not make the change, the row is as it was and the reason told.
     */
    private fun change(target: NetworkTarget, blocked: Boolean, manual: Boolean = false, tell: Boolean = true) {
        load.update { state ->
            state.copy(
                pending = state.pending + (target to blocked),
                blockedShown = if (state.blockedOnly) state.blockedShown + target else state.blockedShown,
            )
        }
        viewModelScope.launch {
            try {
                if (blocked) {
                    networkRepository.block(packageName, userId, target, manual)
                } else {
                    networkRepository.unblock(packageName, userId, target)
                }
                val message = if (blocked) NetworkMessage.Blocked(target, manual) else NetworkMessage.Unblocked(target)
                load.update { state ->
                    state.copy(
                        // The rules as now made are shown until read again.
                        activity = state.activity?.withRule(target, blocked, now(), manual),
                        period = state.period?.withRule(target, blocked, now(), manual),
                        pending = state.pending - target,
                        message = if (tell) message else state.message,
                    )
                }
            } catch (e: RecordsException) {
                load.update { it.copy(pending = it.pending - target, error = e.toLoadFailure()) }
            }
        }
    }

    private fun now() = System.currentTimeMillis() / 1000
}
