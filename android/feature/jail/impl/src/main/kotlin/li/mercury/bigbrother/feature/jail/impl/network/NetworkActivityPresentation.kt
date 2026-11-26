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

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.map
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.ConnectionCounts
import li.mercury.bigbrother.core.model.DestinationActivity
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.labelOrPackageName

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

@Immutable
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
    val name: String get() = info.labelOrPackageName(packageName)

    /** What the list and the summary show: the chosen period's, or the whole range's. */
    val listed: NetworkActivity? get() = if (bucket != null) period else activity
}

/** Rows shown of each group before "Show all". */
internal const val ROWS_SHOWN = 6

/** The activity with the changes not made yet as if they were. */
private fun NetworkActivityUiState.applied(activity: NetworkActivity?): NetworkActivity? = activity?.let { start ->
    pending.entries.fold(start) { shown, (target, blocked) -> shown.withRule(target, blocked, now = shown.until) }
}

/** The targets blocked, pending changes included. */
internal fun NetworkActivityUiState.blockedTargets(): Set<NetworkTarget> = applied(activity)?.rules.orEmpty().map { it.target }.toSet()

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
