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

internal open class TestBbdDataSource : BbdDataSource {
    override suspend fun getApps(range: TimeRange): List<BbdAppSummary> = error("Unexpected getApps call")

    override suspend fun getAccesses(uid: Int, category: Category?, range: TimeRange, limit: Int, offset: Int): List<BbdAccess> = error("Unexpected getAccesses call")

    override suspend fun searchPaths(
        text: String,
        categories: Collection<Category>,
        uids: Collection<Int>,
        range: TimeRange,
        limit: Int,
    ): List<BbdPathMatch> = error("Unexpected searchPaths call")

    override suspend fun getHistory(uid: Int, paths: List<String>, range: TimeRange): List<BbdAccessPeriod> = error("Unexpected getHistory call")

    override suspend fun getSensorSessions(uid: Int, range: TimeRange): List<BbdSensorSession> = error("Unexpected getSensorSessions call")

    override suspend fun getTimeline(range: TimeRange, before: Long?, limit: Int): BbdTimelinePage = error("Unexpected getTimeline call")

    override suspend fun getRecorderStatus(): BbdRecorderStatus = error("Unexpected getRecorderStatus call")

    override suspend fun getDiagnostics(): BbdDiagnostics = error("Unexpected getDiagnostics call")

    override suspend fun getNetworkActivity(packageName: String, userId: Int, since: Long, until: Long?, bucket: BucketSize, zone: ZoneId): BbdNetworkActivity = error("Unexpected getNetworkActivity call")

    override suspend fun getNetworkDestination(packageName: String, userId: Int, target: NetworkTarget, since: Long, bucket: BucketSize, zone: ZoneId): BbdDestinationDetail = error("Unexpected getNetworkDestination call")

    override suspend fun blockNetwork(packageName: String, userId: Int, target: NetworkTarget, manual: Boolean): Unit = error("Unexpected blockNetwork call")

    override suspend fun unblockNetwork(packageName: String, userId: Int, target: NetworkTarget): Unit = error("Unexpected unblockNetwork call")

    override suspend fun clearNetwork(packageName: String, userId: Int): BbdNetworkClearResult = error("Unexpected clearNetwork call")

    override suspend fun listSandboxApps(): List<BbdSandboxedApp> = error("Unexpected listSandboxApps call")

    override suspend fun getSandboxGlobal(): BbdSandboxGlobal = error("Unexpected getSandboxGlobal call")

    override suspend fun getSandboxStatus(): BbdSandboxStatus = error("Unexpected getSandboxStatus call")

    override suspend fun enableSandbox(packageName: String, userId: Int): Unit = error("Unexpected enableSandbox call")

    override suspend fun disableSandbox(packageName: String, userId: Int): Unit = error("Unexpected disableSandbox call")

    override suspend fun disableAllSandboxes(): BbdSandboxReleaseResult = error("Unexpected disableAllSandboxes call")

    override suspend fun setSandboxItems(packageName: String, userId: Int, changes: Map<SandboxItem, SandboxMode?>): Unit = error("Unexpected setSandboxItems call")

    override suspend fun setSandboxGlobal(changes: Map<SandboxItem, SandboxMode>): BbdSandboxGlobal? = error("Unexpected setSandboxGlobal call")

    override suspend fun setSandboxAuto(on: Boolean): Boolean? = error("Unexpected setSandboxAuto call")
}
