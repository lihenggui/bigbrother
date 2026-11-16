package li.mercury.bigbrother.core.data.repository

import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.TimeRange

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

    /** When [uid] touched [path], hour by hour, most recent first. */
    suspend fun getHistory(uid: Int, path: String, range: TimeRange): List<AccessPeriod>

    /** Whether the daemon is currently recording. */
    suspend fun isRecording(): Boolean
}
