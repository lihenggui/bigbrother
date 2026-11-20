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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.common.di.CurrentUserId
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.NetworkRepository
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.ConnectionCounts
import li.mercury.bigbrother.core.model.DestinationActivity
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.TimeRange

/** How the destinations are listed. */
enum class DestinationSort { DATA, CONNECTIONS, RECENT, NAME }

/** A block or unblock just made, to be told once and undone if wished. */
sealed interface NetworkMessage {
    val target: NetworkTarget

    data class Blocked(override val target: NetworkTarget, val manual: Boolean = false) : NetworkMessage

    data class Unblocked(override val target: NetworkTarget) : NetworkMessage
}

/**
 * One row of the activity's lists: a destination, one name of a domain, or
 * a rule nothing tried to reach. [opens] is the destination its detail
 * shows; [toggle] what its button blocks, or unblocks while [blocked], and
 * null where the row has no button: a domain with several names, blocked
 * name by name in its detail. [port] is named when it is the only one and
 * not the web's.
 */
data class DestinationRow(
    val key: String,
    val title: String,
    val isAddress: Boolean,
    val opens: NetworkTarget?,
    val toggle: NetworkTarget?,
    val blocked: Boolean,
    val manual: Boolean = false,
    override val connections: Int = 0,
    override val refused: Int = 0,
    override val sent: Long? = null,
    override val received: Long? = null,
    val last: Long = 0,
    val port: Int? = null,
    val subdomains: Int = 0,
    /** A rule with no attempt in the period. */
    val noAttempts: Boolean = false,
) : ConnectionCounts

/** The rows listed, the first few of each group unless all are asked for, and how many match. */
data class ShownDestinations(
    val domains: List<DestinationRow> = emptyList(),
    val domainCount: Int = 0,
    val addresses: List<DestinationRow> = emptyList(),
    val addressCount: Int = 0,
)

/** The totals of a period, for the summary above the chart. */
data class ActivityTotals(
    val destinations: Int = 0,
    val connections: Int = 0,
    val refused: Int = 0,
    val sent: Long = 0,
    val received: Long = 0,
)

data class NetworkActivityUiState(
    val packageName: String = "",
    val info: AppInfo? = null,
    /** The Android user the app belongs to, if not the one this app runs as. */
    val otherUser: Int? = null,
    val range: TimeRange = TimeRange.DAY,
    /** The start of the chart's bar chosen, whose destinations the list shows. */
    val bucket: Long? = null,
    val blockedOnly: Boolean = false,
    val sort: DestinationSort = DestinationSort.DATA,
    val query: String = "",
    /** The whole range; null while it is read. */
    val activity: NetworkActivity? = null,
    /** The chosen bar's period; null while it is read or none is chosen. */
    val period: NetworkActivity? = null,
    /** Blocks and unblocks not made yet, shown as made: true to block. */
    val pending: Map<NetworkTarget, Boolean> = emptyMap(),
    /** What the blocked filter lists besides the rules: those unblocked since it was chosen, which stay in place. */
    val blockedShown: Set<NetworkTarget> = emptySet(),
    val showAllDomains: Boolean = false,
    val showAllAddresses: Boolean = false,
    /** Worked out from the rest, off the main thread. */
    val shown: ShownDestinations = ShownDestinations(),
    val totals: ActivityTotals = ActivityTotals(),
    /** How many destinations are blocked, pending changes included. */
    val blockedCount: Int = 0,
    val failure: LoadFailure? = null,
    /** Why the last change was not made, to be told once. */
    val error: LoadFailure? = null,
    val message: NetworkMessage? = null,
) {
    val name: String get() = info?.label ?: packageName

    /** What the list and the summary show: the chosen period's, or the whole range's. */
    val listed: NetworkActivity? get() = if (bucket != null) period else activity
}

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

    private val userId = uid / PER_USER_RANGE
    private val load = MutableStateFlow(
        NetworkActivityUiState(packageName = packageName, otherUser = userId.takeIf { it != currentUserId }),
    )

    val uiState: StateFlow<NetworkActivityUiState> = load
        .map { it.withShown() }
        .flowOn(defaultDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), load.value)

    private var reading: Job? = null

    init {
        viewModelScope.launch {
            val info = appInfoRepository.getAppInfo(packageName)
            load.update { it.copy(info = info) }
        }
        // A change made here or in a destination's detail: read again.
        viewModelScope.launch { networkRepository.changes.drop(1).collect { read() } }
    }

    fun refresh() {
        read()
    }

    fun setRange(range: TimeRange) {
        if (range == load.value.range) return
        load.update { it.copy(range = range, bucket = null, period = null, activity = null) }
        read()
    }

    /** Lists the destinations of the bar from [start], or with null of the whole range again. */
    fun selectBucket(start: Long?) {
        val shown = load.value
        val chosen = start.takeIf { it != shown.bucket }
        load.update { it.copy(bucket = chosen, period = null) }
        if (chosen != null) read(shown.activity)
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
                sandboxRepository.setItems(packageName, userId, mapOf(SandboxItem.NETWORK_RECORD to if (on) SandboxMode.ON else SandboxMode.OFF))
                read()
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

    private fun read(shown: NetworkActivity? = null) {
        reading?.cancel()
        reading = viewModelScope.launch {
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
                load.update { it.copy(failure = e.toLoadFailure()) }
            }
        }
    }

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

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}

/** Rows shown of each group before "Show all". */
internal const val ROWS_SHOWN = 6

/** The activity with the changes not made yet as if they were. */
private fun NetworkActivityUiState.applied(activity: NetworkActivity?): NetworkActivity? = activity?.let { start ->
    pending.entries.fold(start) { shown, (target, blocked) -> shown.withRule(target, blocked, now = shown.until) }
}

/** The targets blocked, pending changes included. */
private fun NetworkActivityUiState.blockedTargets(): Set<NetworkTarget> = applied(activity)?.rules.orEmpty().map { it.target }.toSet()

/** The state with what it shows worked out. */
internal fun NetworkActivityUiState.withShown(): NetworkActivityUiState {
    val whole = applied(activity) ?: return copy(shown = ShownDestinations(), totals = ActivityTotals(), blockedCount = 0)
    val listed = applied(listed)
    val rows = when {
        listed == null -> Pair(emptyList(), emptyList())

        blockedOnly -> ruleRows(whole, listed, blockedShown + whole.rules.map { it.target })
            .let { (d, a) -> d.matching(query) to a.matching(query) }

        else -> listed.domains.filter { it.matches(query) }.map { it.toRow() } to
            listed.addresses.filter { it.matches(query) }.map { it.toRow() }
    }
    val (domains, addresses) = rows.let { (d, a) -> d.sortedWith(sort.comparator) to a.sortedWith(sort.comparator) }
    return copy(
        shown = ShownDestinations(
            domains = if (showAllDomains) domains else domains.take(ROWS_SHOWN),
            domainCount = domains.size,
            addresses = if (showAllAddresses) addresses else addresses.take(ROWS_SHOWN),
            addressCount = addresses.size,
        ),
        totals = listed?.totals() ?: ActivityTotals(),
        blockedCount = whole.rules.size,
    )
}

private fun NetworkActivity.totals(): ActivityTotals {
    val all = domains + addresses
    return ActivityTotals(
        destinations = all.size,
        connections = all.sumOf { it.connections },
        refused = all.sumOf { it.refused },
        sent = all.sumOf { it.sent ?: 0 },
        received = all.sumOf { it.received ?: 0 },
    )
}

/**
 * A destination as a row. A domain seen under one name is listed by that
 * name, which its button blocks exactly; seen under several, by the domain,
 * whose names are blocked one by one in its detail.
 */
internal fun DestinationActivity.toRow(): DestinationRow {
    val onlyPort = ports.singleOrNull()?.port?.takeUnless { isWebPort(it) }
    return when (val own = target) {
        is NetworkTarget.Domain -> {
            val host = hosts.singleOrNull()
            val name = host?.name ?: own.name
            val rule = host?.rule ?: rule
            val several = hosts.size > 1
            DestinationRow(
                key = target.id,
                title = name,
                isAddress = false,
                opens = target,
                toggle = if (several) null else rule ?: NetworkTarget.Domain(name, exact = true),
                blocked = rule != null || (several && hosts.all { it.rule != null }),
                connections = connections,
                refused = refused,
                sent = sent,
                received = received,
                last = last,
                port = onlyPort,
                subdomains = if (several) hosts.size else 0,
            )
        }

        is NetworkTarget.Address -> DestinationRow(
            key = target.id,
            title = own.ip,
            isAddress = true,
            opens = target,
            toggle = rule ?: target,
            blocked = rule != null,
            connections = connections,
            refused = refused,
            sent = sent,
            received = received,
            last = last,
            port = onlyPort,
        )
    }
}

/**
 * The rows of the blocked filter: one per target, a rule or one unblocked
 * since, with what tried to reach it in [listed]. The detail it opens is
 * the destination it belongs to in the whole range, if there is one.
 */
private fun ruleRows(whole: NetworkActivity, listed: NetworkActivity, targets: Set<NetworkTarget>): Pair<List<DestinationRow>, List<DestinationRow>> {
    val rules = whole.rules.associateBy { it.target }
    val rows = targets.map { target ->
        val rule = rules[target]
        val counts: ConnectionCounts? = when (target) {
            is NetworkTarget.Domain -> listed.domains.firstOrNull { !target.exact && it.target == target }
                ?: listed.domains.flatMap { it.hosts }.firstOrNull { target.exact && it.name == target.name }

            is NetworkTarget.Address -> listed.addresses.firstOrNull { (it.target as NetworkTarget.Address).ip == target.ip }?.let { destination ->
                if (target.port == null) destination else destination.ports.firstOrNull { it.port == target.port }
            }
        }
        val opens = when (target) {
            is NetworkTarget.Domain -> whole.domains.firstOrNull { destination ->
                destination.target == target || destination.hosts.any { it.name == target.name }
            }?.target

            is NetworkTarget.Address -> whole.addresses.firstOrNull { (it.target as NetworkTarget.Address).ip == target.ip }?.target
        }
        DestinationRow(
            key = target.id,
            title = target.name,
            isAddress = target is NetworkTarget.Address,
            opens = opens,
            toggle = target,
            blocked = rule != null,
            manual = rule?.manual == true,
            connections = counts?.connections ?: 0,
            refused = counts?.refused ?: 0,
            sent = counts?.sent,
            received = counts?.received,
            last = (counts as? DestinationActivity)?.last ?: rule?.created ?: 0,
            noAttempts = counts == null || counts.connections + counts.refused == 0,
        )
    }
    return rows.filterNot { it.isAddress } to rows.filter { it.isAddress }
}

private fun List<DestinationRow>.matching(query: String): List<DestinationRow> {
    val text = query.trim()
    if (text.isEmpty()) return this
    return filter { row ->
        row.title.contains(text, ignoreCase = true) || row.opens?.name?.contains(text, ignoreCase = true) == true
    }
}

/** Whether a search for [query] finds this destination: by its name, the names seen under it or its addresses. */
internal fun DestinationActivity.matches(query: String): Boolean {
    val text = query.trim()
    return text.isEmpty() ||
        target.name.contains(text, ignoreCase = true) ||
        hosts.any { it.name.contains(text, ignoreCase = true) } ||
        addresses.any { it.contains(text, ignoreCase = true) }
}

private val DestinationSort.comparator: Comparator<DestinationRow>
    get() = when (this) {
        DestinationSort.DATA -> compareByDescending<DestinationRow> { row ->
            if (row.sent == null && row.received == null) -1L else (row.sent ?: 0) + (row.received ?: 0)
        }.thenByDescending { it.connections + it.refused }

        DestinationSort.CONNECTIONS -> compareByDescending<DestinationRow> { it.connections }.thenByDescending { it.refused }

        DestinationSort.RECENT -> compareByDescending { it.last }

        DestinationSort.NAME -> compareBy { it.title }
    }.thenBy { it.title }
