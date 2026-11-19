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

package li.mercury.bigbrother.core.testing.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.NetworkRepository
import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.DestinationDetail
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkTarget

/**
 * One app's network activity kept in memory, [activity], whatever range is
 * asked for, except that a period asked for with an end gives [period] if
 * set. Rules are applied to it as the daemon would.
 */
class TestNetworkRepository(var activity: NetworkActivity) : NetworkRepository {
    /** Thrown by every call while set. */
    var failure: RecordsException? = null

    /** Thrown by [block] and [unblock] while set. */
    var changeFailure: RecordsException? = null

    /** What a period with an end, such as one bar of the chart, holds. */
    var period: NetworkActivity? = null

    /** The ranges asked for, as (since, until). */
    val asked = mutableListOf<Pair<Long, Long?>>()

    /** The targets blocked and unblocked, in order, with true for blocked. */
    val changed = mutableListOf<Pair<NetworkTarget, Boolean>>()

    /** The apps whose records were cleared. */
    val cleared = mutableListOf<String>()

    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes

    override suspend fun getActivity(packageName: String, userId: Int, since: Long, until: Long?, bucket: BucketSize): NetworkActivity {
        check()
        asked += since to until
        return if (until != null) period ?: activity else activity
    }

    override suspend fun getDestination(packageName: String, userId: Int, target: NetworkTarget, since: Long, bucket: BucketSize): DestinationDetail {
        check()
        val destination = (activity.domains + activity.addresses).firstOrNull { it.target == target }
            ?: throw RecordsException.CommandFailed("no such destination")
        return DestinationDetail(destination, activity.buckets)
    }

    override suspend fun block(packageName: String, userId: Int, target: NetworkTarget, manual: Boolean) {
        check()
        changeFailure?.let { throw it }
        changed += target to true
        activity = activity.withRule(target, blocked = true, now = activity.until, manual = manual)
        _changes.update { it + 1 }
    }

    override suspend fun unblock(packageName: String, userId: Int, target: NetworkTarget) {
        check()
        changeFailure?.let { throw it }
        changed += target to false
        activity = activity.withRule(target, blocked = false, now = activity.until)
        _changes.update { it + 1 }
    }

    override suspend fun clear(packageName: String, userId: Int): Int {
        check()
        cleared += packageName
        val deleted = activity.domains.size + activity.addresses.size
        activity = activity.copy(buckets = emptyList(), domains = emptyList(), addresses = emptyList())
        _changes.update { it + 1 }
        return deleted
    }

    private fun check() {
        failure?.let { throw it }
    }
}
