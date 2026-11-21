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
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.NetworkRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.DestinationActivity
import li.mercury.bigbrother.core.model.DestinationDetail
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.userIdOf
import li.mercury.bigbrother.core.model.withRule

data class DestinationUiState(
    /** The destination asked for; null if the daemon names it in a way this build does not know. */
    val target: NetworkTarget? = null,
    /** Null while it is read. */
    val detail: DestinationDetail? = null,
    /**
     * For a domain: the other domains whose addresses it shares, which
     * blocking it blocks too.
     */
    val sharedWith: List<String> = emptyList(),
    /** For a domain: whether the app also connected to addresses it resolved on its own. */
    val ownResolver: Boolean = false,
    /** Blocks and unblocks not made yet, shown as made: true to block. */
    val pending: Map<NetworkTarget, Boolean> = emptyMap(),
    val failure: LoadFailure? = null,
    /** Why the last change was not made, to be told once. */
    val error: LoadFailure? = null,
    val message: NetworkMessage? = null,
) {
    /** The destination with the changes not made yet as if they were. */
    val destination: DestinationActivity?
        get() = detail?.destination?.let { start -> pending.entries.fold(start) { shown, (target, blocked) -> shown.withRule(target, blocked) } }
}

/** One destination of an app: its names, ports and addresses, each of which can be blocked. */
@HiltViewModel(assistedFactory = DestinationViewModel.Factory::class)
class DestinationViewModel @AssistedInject constructor(
    @Assisted uid: Int,
    @Assisted("package") private val packageName: String,
    @Assisted("target") target: String,
    @Assisted private val range: TimeRange,
    private val networkRepository: NetworkRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(uid: Int, @Assisted("package") packageName: String, @Assisted("target") target: String, range: TimeRange): DestinationViewModel
    }

    private val userId = userIdOf(uid)
    private val target = NetworkTarget.fromId(target)
    private val load = MutableStateFlow(DestinationUiState(target = this.target))
    private val refreshes = MutableStateFlow(0L)
    private var completed: Pair<Long, Int>? = null

    val uiState: StateFlow<DestinationUiState> = channelFlow {
        launch {
            combine(refreshes, networkRepository.changes, ::Pair).collectLatest { request ->
                if (request != completed) {
                    read()
                    completed = request
                }
            }
        }
        load.collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), load.value)

    fun refresh() {
        refreshes.update { it + 1 }
    }

    private suspend fun read() {
        val target = target
        if (target == null) {
            load.update { it.copy(failure = RecordsException.CommandFailed("unknown destination").toLoadFailure()) }
            return
        }
        try {
            val since = range.since(System.currentTimeMillis() / 1000)
            val detail = networkRepository.getDestination(packageName, userId, target, since, range.bucketSize)
            // What blocking a domain does besides is found among the app's other destinations.
            val activity = if (target is NetworkTarget.Domain) {
                networkRepository.getActivity(packageName, userId, since, bucket = range.bucketSize)
            } else {
                null
            }
            currentCoroutineContext().ensureActive()
            load.update {
                it.copy(
                    detail = detail,
                    sharedWith = activity?.sharingAddressesWith(detail.destination).orEmpty(),
                    ownResolver = activity?.addresses?.isNotEmpty() == true,
                    failure = null,
                )
            }
        } catch (e: RecordsException) {
            currentCoroutineContext().ensureActive()
            load.update { it.copy(failure = e.toLoadFailure()) }
        }
    }

    fun block(target: NetworkTarget) {
        change(target, blocked = true)
    }

    fun unblock(target: NetworkTarget) {
        change(target, blocked = false)
    }

    fun undo() {
        val message = load.value.message ?: return
        load.update { it.copy(message = null) }
        change(message.target, blocked = message is NetworkMessage.Unblocked, tell = false)
    }

    fun dismissMessage() {
        load.update { it.copy(message = null) }
    }

    fun dismissError() {
        load.update { it.copy(error = null) }
    }

    private fun change(target: NetworkTarget, blocked: Boolean, tell: Boolean = true) {
        load.update { it.copy(pending = it.pending + (target to blocked)) }
        viewModelScope.launch {
            try {
                if (blocked) networkRepository.block(packageName, userId, target) else networkRepository.unblock(packageName, userId, target)
                load.update { state ->
                    state.copy(
                        detail = state.detail?.let { it.copy(destination = it.destination.withRule(target, blocked)) },
                        pending = state.pending - target,
                        message = if (!tell) {
                            state.message
                        } else if (blocked) {
                            NetworkMessage.Blocked(target)
                        } else {
                            NetworkMessage.Unblocked(target)
                        },
                    )
                }
            } catch (e: RecordsException) {
                load.update { it.copy(pending = it.pending - target, error = e.toLoadFailure()) }
            }
        }
    }
}

/** The other domains of the app that connected to an address of [destination]. */
internal fun NetworkActivity.sharingAddressesWith(destination: DestinationActivity): List<String> {
    val addresses = destination.addresses.toSet()
    if (addresses.isEmpty()) return emptyList()
    return domains
        .filter { it.target != destination.target && it.addresses.any { address -> address in addresses } }
        .map { it.target.name }
}
