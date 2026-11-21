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

package li.mercury.bigbrother.core.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.bbd.BbdDataSource
import li.mercury.bigbrother.core.data.bbd.asExternalModel
import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.DestinationDetail
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkTarget
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the apps in the jail connect to, and the destinations blocked for
 * each. Whether an app's network is cut off or recorded is one of its
 * sandbox items ([SandboxRepository]); the daemon keeps the rest.
 */
interface NetworkRepository {
    /**
     * Counts the changes made to rules and records: screens that show them
     * read them again when it moves.
     */
    val changes: StateFlow<Int>

    /** An app's connections from [since] to [until] (now if null), in periods of [bucket]. */
    suspend fun getActivity(
        packageName: String,
        userId: Int,
        since: Long,
        until: Long? = null,
        bucket: BucketSize = BucketSize.HOUR,
        zone: ZoneId = ZoneId.systemDefault(),
    ): NetworkActivity

    /** One destination of an app, a domain or an address, from [since], in periods of [bucket]. */
    suspend fun getDestination(
        packageName: String,
        userId: Int,
        target: NetworkTarget,
        since: Long,
        bucket: BucketSize = BucketSize.HOUR,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DestinationDetail

    /** Blocks [target] for an app at once; [manual] if it was typed in rather than chosen from the records. */
    suspend fun block(packageName: String, userId: Int, target: NetworkTarget, manual: Boolean = false)

    /** Removes the rule blocking [target] for an app. */
    suspend fun unblock(packageName: String, userId: Int, target: NetworkTarget)

    /** Deletes an app's records, keeping its rules; returns how many were deleted. */
    suspend fun clear(packageName: String, userId: Int): Int
}

@Singleton
internal class RootNetworkRepository @Inject constructor(
    private val bbd: BbdDataSource,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) : NetworkRepository {
    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()

    override suspend fun getActivity(packageName: String, userId: Int, since: Long, until: Long?, bucket: BucketSize, zone: ZoneId): NetworkActivity = withContext(defaultDispatcher) {
        bbd.getNetworkActivity(packageName, userId, since, until, bucket, zone).asExternalModel(zone)
    }

    override suspend fun getDestination(packageName: String, userId: Int, target: NetworkTarget, since: Long, bucket: BucketSize, zone: ZoneId): DestinationDetail = withContext(defaultDispatcher) {
        bbd.getNetworkDestination(packageName, userId, target, since, bucket, zone).asExternalModel(zone)
    }

    override suspend fun block(packageName: String, userId: Int, target: NetworkTarget, manual: Boolean) = withContext(defaultDispatcher) {
        bbd.blockNetwork(packageName, userId, target, manual)
        _changes.update { it + 1 }
    }

    override suspend fun unblock(packageName: String, userId: Int, target: NetworkTarget) = withContext(defaultDispatcher) {
        bbd.unblockNetwork(packageName, userId, target)
        _changes.update { it + 1 }
    }

    override suspend fun clear(packageName: String, userId: Int): Int = withContext(defaultDispatcher) {
        bbd.clearNetwork(packageName, userId).deleted.also { _changes.update { it + 1 } }
    }
}
