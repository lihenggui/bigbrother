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
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.bbd.BbdDataSource
import li.mercury.bigbrother.core.data.bbd.asExternalModel
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.model.RecorderStatus
import li.mercury.bigbrother.core.model.SensorSession
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelinePage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RootAccessRepository @Inject constructor(
    private val bbd: BbdDataSource,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) : AccessRepository {
    private val _recorderStatus = MutableStateFlow<RecorderStatus?>(null)
    override val recorderStatus: StateFlow<RecorderStatus?> = _recorderStatus.asStateFlow()

    /**
     * The overview, the app list, a category and an app's page each start
     * from the list of apps; one read serves them all for 5 s.
     */
    private val recentApps = RecentRead<TimeRange, List<AppSummary>>(maxAgeMs = 5_000)

    override suspend fun getApps(range: TimeRange): List<AppSummary> = recentApps.get(range) {
        withContext(defaultDispatcher) { bbd.getApps(range).map { it.asExternalModel() } }
    }

    /** An app's page, its files and its records each read all of its records within moments; they share one read. */
    private val recentAccesses = RecentRead<List<Any?>, List<Access>>(maxAgeMs = 10_000)

    override suspend fun getAccesses(
        uid: Int,
        category: Category?,
        range: TimeRange,
        limit: Int,
        offset: Int,
    ): List<Access> = recentAccesses.get(listOf(uid, category, range, limit, offset)) {
        withContext(defaultDispatcher) { bbd.getAccesses(uid, category, range, limit, offset).map { it.asExternalModel() } }
    }

    override suspend fun searchPaths(
        text: String,
        categories: Collection<Category>,
        uids: Collection<Int>,
        range: TimeRange,
        limit: Int,
    ): List<PathMatch> = withContext(defaultDispatcher) {
        bbd.searchPaths(text, categories, uids, range, limit).map { it.asExternalModel() }
    }

    override suspend fun getTimeline(range: TimeRange, before: Long?, limit: Int): TimelinePage = withContext(defaultDispatcher) {
        bbd.getTimeline(range, before, limit).asExternalModel()
    }

    override suspend fun getHistory(uid: Int, paths: List<String>, range: TimeRange): List<AccessPeriod> = withContext(defaultDispatcher) {
        // For a single path, the daemon can leave the subject implicit.
        val onlyPath = paths.singleOrNull()
        bbd.getHistory(uid, paths, range).map { it.asExternalModel(onlyPath) }
    }

    override suspend fun getSensorSessions(uid: Int, range: TimeRange): List<SensorSession> = withContext(defaultDispatcher) {
        bbd.getSensorSessions(uid, range).map { it.asExternalModel() }
    }

    override suspend fun getRecorderStatus(): RecorderStatus = try {
        withContext(defaultDispatcher) { bbd.getRecorderStatus().asExternalModel() }.also { _recorderStatus.value = it }
    } catch (e: RecordsException) {
        _recorderStatus.value = null
        throw e
    }
}
