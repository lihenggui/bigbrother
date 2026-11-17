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

import kotlinx.coroutines.flow.StateFlow
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.model.RecorderStatus
import li.mercury.bigbrother.core.model.SensorSession
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelinePage

/**
 * The records of what apps touched. All functions throw
 * [li.mercury.bigbrother.core.data.RecordsException] when the records can't
 * be read.
 */
interface AccessRepository {
    /** Every app with records in [range], most recently active first. */
    suspend fun getApps(range: TimeRange): List<AppSummary>

    /** Paths one app touched, most recent first. A null [category] means all. */
    suspend fun getAccesses(
        uid: Int,
        category: Category?,
        range: TimeRange,
        limit: Int,
        offset: Int,
    ): List<Access>

    /** Each time [uid] touched any of [paths], most recent first. */
    suspend fun getHistory(uid: Int, paths: List<String>, range: TimeRange): List<AccessPeriod>

    /**
     * The periods in which [uid] had a sensor turned on that overlap
     * [range], most recent first. A module older than this app does not
     * keep them and throws [li.mercury.bigbrother.core.data.RecordsException.ModuleOutdated].
     */
    suspend fun getSensorSessions(uid: Int, range: TimeRange): List<SensorSession>

    /** Whether the daemon is currently recording, and what it can't record. */
    suspend fun getRecorderStatus(): RecorderStatus

    /**
     * The status from the most recent [getRecorderStatus] by any caller;
     * null before the first, or after one that failed.
     */
    val recorderStatus: StateFlow<RecorderStatus?>

    /**
     * Each app's activity per hour and kind, most recent first, for hours
     * that start before [before] (Unix time; null for now). Pass the page's
     * [TimelinePage.nextBefore] to read on.
     */
    suspend fun getTimeline(range: TimeRange, before: Long?, limit: Int): TimelinePage

    /**
     * Paths in any of [categories] that any of [uids] touched and that
     * contain [text], ignoring case, most recent first.
     */
    suspend fun searchPaths(
        text: String,
        categories: Collection<Category>,
        uids: Collection<Int>,
        range: TimeRange,
        limit: Int,
    ): List<PathMatch>
}
