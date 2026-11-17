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
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.model.RecorderStatus
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelinePage

/**
 * Records a test sets, in place of the daemon. [failure], when set, is
 * thrown by every read, as the daemon's errors are. Reads are counted.
 */
class TestAccessRepository(
    var apps: List<AppSummary> = emptyList(),
    var accesses: List<Access> = emptyList(),
    var history: List<AccessPeriod> = emptyList(),
    var timeline: TimelinePage = TimelinePage(emptyList(), nextBefore = null),
    var status: RecorderStatus = RecorderStatus(recording = true),
) : AccessRepository {
    var failure: RecordsException? = null
    var appReads = 0
        private set

    private val _recorderStatus = MutableStateFlow<RecorderStatus?>(null)
    override val recorderStatus: StateFlow<RecorderStatus?> = _recorderStatus

    private fun check() {
        failure?.let { throw it }
    }

    override suspend fun getApps(range: TimeRange): List<AppSummary> {
        check()
        appReads++
        return apps
    }

    override suspend fun getAccesses(uid: Int, category: Category?, range: TimeRange, limit: Int, offset: Int): List<Access> {
        check()
        return accesses.drop(offset).take(limit)
    }

    override suspend fun getHistory(uid: Int, paths: List<String>, range: TimeRange): List<AccessPeriod> {
        check()
        return history
    }

    override suspend fun getRecorderStatus(): RecorderStatus {
        check()
        return status.also { _recorderStatus.value = it }
    }

    override suspend fun getTimeline(range: TimeRange, before: Long?, limit: Int): TimelinePage {
        check()
        return timeline
    }

    override suspend fun searchPaths(
        text: String,
        categories: Collection<Category>,
        uids: Collection<Int>,
        range: TimeRange,
        limit: Int,
    ): List<PathMatch> {
        check()
        return emptyList()
    }
}
