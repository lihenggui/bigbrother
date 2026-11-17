package li.mercury.bigbrother.core.data.repository

import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import kotlinx.coroutines.flow.StateFlow
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.model.RecorderStatus
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

    /** Each time [uid] touched [path], most recent first. */
    suspend fun getHistory(uid: Int, path: String, range: TimeRange): List<AccessPeriod>

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
     * Paths of [category] any app touched that contain [text], ignoring
     * case, most recent first.
     */
    suspend fun searchPaths(text: String, category: Category, range: TimeRange, limit: Int): List<PathMatch>
}
