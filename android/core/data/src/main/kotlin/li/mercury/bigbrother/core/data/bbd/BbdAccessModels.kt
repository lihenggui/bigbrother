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

import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.model.RecorderStatus
import li.mercury.bigbrother.core.model.SensorSession
import li.mercury.bigbrother.core.model.SubjectCount
import li.mercury.bigbrother.core.model.TimelineEntry
import li.mercury.bigbrother.core.model.TimelinePage

internal data class BbdAppSummary(
    val uid: Int,
    val packageName: String,
    val counts: Map<Category, Int>,
    val lastSeen: Long,
    val filesRead: Int,
    val folders: List<String>,
    val folderCount: Int,
    val topics: List<String>,
)

internal fun BbdAppSummary.asExternalModel(): AppSummary = AppSummary(
    uid = uid,
    packageName = packageName,
    counts = counts,
    lastSeen = lastSeen,
    filesRead = filesRead,
    folders = folders,
    folderCount = folderCount,
    topics = topics,
)

internal data class BbdAccess(
    val category: Category,
    val path: String,
    val mode: Int,
    val items: Int,
    val allowed: Int,
    val denied: Int,
    val lastSeen: Long,
    val topic: String?,
)

internal fun BbdAccess.asExternalModel(): Access = Access(
    category = category,
    path = path,
    items = items,
    allowed = allowed,
    denied = denied,
    lastSeen = lastSeen,
    topic = topic,
    action = Action.fromMode(mode),
    viaProvider = Action.isViaProvider(mode),
    fromIsolated = Action.isFromIsolated(mode),
)

internal data class BbdPathMatch(
    val uid: Int,
    val packageName: String,
    val access: BbdAccess,
)

internal fun BbdPathMatch.asExternalModel(): PathMatch = PathMatch(
    uid = uid,
    packageName = packageName,
    access = access.asExternalModel(),
)

internal data class BbdAccessPeriod(
    val mode: Int,
    val allowed: Int,
    val denied: Int,
    val time: Long,
    val subject: String?,
)

internal fun BbdAccessPeriod.asExternalModel(onlyPath: String?): AccessPeriod = AccessPeriod(
    allowed = allowed,
    denied = denied,
    time = time,
    subject = subject ?: onlyPath,
    action = Action.fromMode(mode),
    viaProvider = Action.isViaProvider(mode),
)

internal data class BbdSensorSession(
    val sensor: String,
    val highRate: Boolean,
    val periodNs: Long?,
    val rateHz: Double?,
    val start: Long,
    val end: Long?,
)

internal fun BbdSensorSession.asExternalModel(): SensorSession = SensorSession(
    sensor = sensor,
    highRate = highRate,
    periodNs = periodNs,
    rateHz = rateHz,
    start = start,
    end = end,
)

internal data class BbdTimelinePage(
    val entries: List<BbdTimelineEntry>,
    val nextBefore: Long?,
)

internal fun BbdTimelinePage.asExternalModel(): TimelinePage = TimelinePage(
    entries = entries.map(BbdTimelineEntry::asExternalModel),
    nextBefore = nextBefore,
)

internal data class BbdTimelineEntry(
    val hourStart: Long,
    val uid: Int,
    val packageName: String,
    val category: Category,
    val topic: String?,
    val paths: Int,
    val filesRead: Int,
    val allowed: Int,
    val denied: Int,
    val modes: Int,
    val items: Int,
    val folder: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val subjects: List<BbdSubjectCount>,
)

internal fun BbdTimelineEntry.asExternalModel(): TimelineEntry = TimelineEntry(
    hourStart = hourStart,
    uid = uid,
    packageName = packageName,
    category = category,
    topic = topic,
    paths = paths,
    filesRead = filesRead,
    allowed = allowed,
    denied = denied,
    modes = modes,
    items = items,
    folder = folder,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    subjects = subjects.map(BbdSubjectCount::asExternalModel),
)

internal data class BbdSubjectCount(
    val path: String,
    val allowed: Int,
    val denied: Int,
)

internal fun BbdSubjectCount.asExternalModel(): SubjectCount = SubjectCount(
    path = path,
    allowed = allowed,
    denied = denied,
)

internal data class BbdRecorderStatus(
    val daemonRunning: Boolean,
    val coverage: Map<String, Long>,
)

internal fun BbdRecorderStatus.asExternalModel(): RecorderStatus = RecorderStatus(
    recording = daemonRunning,
    // Only a definite zero indicates a kind the daemon cannot record.
    unavailable = coverage.filterValues { it == 0L }.keys.sorted(),
)

internal data class BbdDiagnostics(
    val log: String?,
    val stats: String?,
)
