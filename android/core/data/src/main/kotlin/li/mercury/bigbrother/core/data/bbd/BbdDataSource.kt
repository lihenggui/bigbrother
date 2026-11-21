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

package li.mercury.bigbrother.core.data.bbd

import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.TimeRange
import java.time.ZoneId

internal interface BbdDataSource {
    suspend fun getApps(range: TimeRange): List<BbdAppSummary>

    suspend fun getAccesses(uid: Int, category: Category?, range: TimeRange, limit: Int, offset: Int): List<BbdAccess>

    suspend fun searchPaths(
        text: String,
        categories: Collection<Category>,
        uids: Collection<Int>,
        range: TimeRange,
        limit: Int,
    ): List<BbdPathMatch>

    suspend fun getHistory(uid: Int, paths: List<String>, range: TimeRange): List<BbdAccessPeriod>

    suspend fun getSensorSessions(uid: Int, range: TimeRange): List<BbdSensorSession>

    suspend fun getTimeline(range: TimeRange, before: Long?, limit: Int): BbdTimelinePage

    suspend fun getRecorderStatus(): BbdRecorderStatus

    suspend fun getDiagnostics(): BbdDiagnostics

    suspend fun getNetworkActivity(packageName: String, userId: Int, since: Long, until: Long?, bucket: BucketSize, zone: ZoneId): BbdNetworkActivity

    suspend fun getNetworkDestination(packageName: String, userId: Int, target: NetworkTarget, since: Long, bucket: BucketSize, zone: ZoneId): BbdDestinationDetail

    suspend fun blockNetwork(packageName: String, userId: Int, target: NetworkTarget, manual: Boolean)

    suspend fun unblockNetwork(packageName: String, userId: Int, target: NetworkTarget)

    suspend fun clearNetwork(packageName: String, userId: Int): BbdNetworkClearResult

    suspend fun listSandboxApps(): List<BbdSandboxedApp>

    suspend fun getSandboxGlobal(): BbdSandboxGlobal

    suspend fun getSandboxStatus(): BbdSandboxStatus

    suspend fun enableSandbox(packageName: String, userId: Int)

    suspend fun disableSandbox(packageName: String, userId: Int)

    /** A malformed reply throws [li.mercury.bigbrother.core.data.RecordsException.BadResponse] after the command ran. */
    suspend fun disableAllSandboxes(): BbdSandboxReleaseResult

    suspend fun setSandboxItems(packageName: String, userId: Int, changes: Map<SandboxItem, SandboxMode?>)

    suspend fun setSandboxGlobal(changes: Map<SandboxItem, SandboxMode>)

    suspend fun setSandboxAuto(on: Boolean)
}
