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

import li.mercury.bigbrother.core.model.Category
import org.json.JSONObject

internal fun JSONObject.toBbdAppSummary(): BbdAppSummary = BbdAppSummary(
    uid = getInt("uid"),
    packageName = getString("pkg"),
    counts = Category.entries.associateWith { optInt(it.id) },
    lastSeen = getLong("last_ts"),
    filesRead = getInt("files_read"),
    folders = optJSONArray("folders").strings(),
    folderCount = optInt("folder_count"),
    topics = optJSONArray("topics").strings(),
)

/** Skip a category this build does not know before reading the rest of the row. */
internal fun JSONObject.toBbdAccess(): BbdAccess? {
    val category = Category.fromId(getString("cat")) ?: return null
    return BbdAccess(
        category = category,
        mode = getInt("mode"),
        path = getString("path"),
        items = optInt("items"),
        allowed = getInt("ok"),
        denied = getInt("fail"),
        lastSeen = getLong("last_ts"),
        topic = optString("topic").ifEmpty { null },
    )
}

internal fun JSONObject.toBbdAccessPeriod(): BbdAccessPeriod = BbdAccessPeriod(
    mode = getInt("mode"),
    allowed = getInt("ok"),
    denied = getInt("fail"),
    time = getLong("ts"),
    subject = optString("path").ifEmpty { null },
)

internal fun JSONObject.toBbdSensorSession(): BbdSensorSession = BbdSensorSession(
    sensor = optString("sensor"),
    highRate = optBoolean("high_rate"),
    periodNs = optLongOrNull("period_ns"),
    rateHz = if (isNull("rate_hz")) null else getDouble("rate_hz"),
    start = getLong("start_ts"),
    end = optLongOrNull("end_ts"),
)

internal fun JSONObject.toBbdTimelinePage(): BbdTimelinePage = BbdTimelinePage(
    entries = getJSONArray("entries").mapObjectsNotNull(JSONObject::toBbdTimelineEntry),
    nextBefore = if (has("next_before")) getLong("next_before") else null,
)

private fun JSONObject.toBbdTimelineEntry(): BbdTimelineEntry? {
    val category = Category.fromId(getString("cat")) ?: return null
    return BbdTimelineEntry(
        hourStart = getLong("hour_ts"),
        uid = getInt("uid"),
        packageName = getString("pkg"),
        category = category,
        topic = optString("topic").ifEmpty { null },
        paths = getInt("paths"),
        filesRead = getInt("files_read"),
        allowed = getInt("ok"),
        denied = getInt("fail"),
        modes = getInt("modes"),
        items = optInt("items"),
        folder = optString("folder").ifEmpty { null },
        firstSeen = getLong("first_ts"),
        lastSeen = getLong("last_ts"),
        subjects = optJSONArray("subjects")?.mapObjects {
            BbdSubjectCount(path = it.getString("path"), allowed = it.getInt("ok"), denied = it.getInt("fail"))
        }.orEmpty(),
    )
}

internal fun JSONObject.toBbdRecorderStatus(): BbdRecorderStatus {
    val meta = optJSONObject("meta") ?: JSONObject()
    return BbdRecorderStatus(
        daemonRunning = getBoolean("daemon_running"),
        coverage = meta.keys().asSequence()
            .filter { it.startsWith(COVERAGE_PREFIX) }
            .associate { it.removePrefix(COVERAGE_PREFIX) to meta.optLong(it) },
    )
}

private const val COVERAGE_PREFIX = "coverage_"
