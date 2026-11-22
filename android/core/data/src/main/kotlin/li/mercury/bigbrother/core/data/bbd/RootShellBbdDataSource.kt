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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.data.root.bbd
import li.mercury.bigbrother.core.data.root.bbdStatsForDiagnostics
import li.mercury.bigbrother.core.data.root.daemonCommandFailure
import li.mercury.bigbrother.core.data.root.shellQuoted
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.TimeRange
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

internal class RootShellBbdDataSource @Inject constructor(
    private val rootShell: RootShell,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : BbdDataSource {
    override suspend fun getApps(range: TimeRange): List<BbdAppSummary> = decode {
        JSONArray(rootShell.bbd("apps", range.argument())).mapObjects(JSONObject::toBbdAppSummary)
    }

    override suspend fun getAccesses(uid: Int, category: Category?, range: TimeRange, limit: Int, offset: Int): List<BbdAccess> = decode {
        val args = buildString {
            append(" --uid $uid --limit $limit --offset $offset")
            if (category != null) append(" --cat ${category.id}")
            append(range.argument())
        }
        JSONArray(rootShell.bbd("files", args)).mapObjectsNotNull(JSONObject::toBbdAccess)
    }

    override suspend fun searchPaths(
        text: String,
        categories: Collection<Category>,
        uids: Collection<Int>,
        range: TimeRange,
        limit: Int,
    ): List<BbdPathMatch> = decode {
        // An empty UID filter would search every app.
        if (uids.isEmpty()) return@decode emptyList()
        // Attach the quoted value so text starting with "-" stays an option value.
        val args = buildString {
            append(" --contains=${text.shellQuoted()}")
            categories.forEach { append(" --cat ${it.id}") }
            uids.forEach { append(" --uid $it") }
            append(" --limit $limit")
            append(range.argument())
        }
        JSONArray(rootShell.bbd("files", args)).mapObjectsNotNull { row ->
            val access = row.toBbdAccess() ?: return@mapObjectsNotNull null
            BbdPathMatch(uid = row.getInt("uid"), packageName = row.getString("pkg"), access = access)
        }
    }

    override suspend fun getHistory(uid: Int, paths: List<String>, range: TimeRange): List<BbdAccessPeriod> = decode {
        val args = buildString {
            append(" --uid $uid")
            paths.forEach { append(" --path=${it.shellQuoted()}") }
            append(range.argument())
        }
        JSONArray(rootShell.bbd("history", args)).mapObjects(JSONObject::toBbdAccessPeriod)
    }

    override suspend fun getSensorSessions(uid: Int, range: TimeRange): List<BbdSensorSession> = decode {
        val args = " --uid $uid" + range.argument()
        JSONArray(rootShell.bbd("sensors", args)).mapObjects(JSONObject::toBbdSensorSession)
    }

    override suspend fun getTimeline(range: TimeRange, before: Long?, limit: Int): BbdTimelinePage = decode {
        val args = buildString {
            append(" --limit $limit")
            if (before != null) append(" --before $before")
            append(range.argument())
        }
        JSONObject(rootShell.bbd("timeline", args)).toBbdTimelinePage()
    }

    override suspend fun getRecorderStatus(): BbdRecorderStatus = decode {
        JSONObject(rootShell.bbd("stats")).toBbdRecorderStatus()
    }

    override suspend fun getDiagnostics(): BbdDiagnostics = withContext(ioDispatcher) {
        BbdDiagnostics(
            log = diagnosticOutput("daemon log") {
                rootShell.run("tail -n 5000 /data/adb/big_brother/bbd.log 2>&1", label = "daemon log").output
            },
            stats = diagnosticOutput("bbd stats") { rootShell.bbdStatsForDiagnostics() },
        )
    }

    private suspend fun diagnosticOutput(label: String, read: suspend () -> String): String? = try {
        read()
    } catch (e: RecordsException) {
        Timber.w("Not exported, %s: %s", label, e.toLoadFailure())
        null
    }

    override suspend fun getNetworkActivity(
        packageName: String,
        userId: Int,
        since: Long,
        until: Long?,
        bucket: BucketSize,
        zone: ZoneId,
    ): BbdNetworkActivity = decode {
        val range = chartRange(since, until, bucket, zone)
        net("activity ${packageName.shellQuoted()} $range --user $userId").toBbdNetworkActivity()
    }

    override suspend fun getNetworkDestination(
        packageName: String,
        userId: Int,
        target: NetworkTarget,
        since: Long,
        bucket: BucketSize,
        zone: ZoneId,
    ): BbdDestinationDetail = decode {
        val range = chartRange(since, null, bucket, zone)
        net("destination ${packageName.shellQuoted()} ${target.id.shellQuoted()} $range --user $userId").toBbdDestinationDetail()
    }

    override suspend fun blockNetwork(packageName: String, userId: Int, target: NetworkTarget, manual: Boolean) {
        decode {
            val manualFlag = if (manual) " --manual" else ""
            net("block ${packageName.shellQuoted()} ${target.id.shellQuoted()}$manualFlag --user $userId")
        }
    }

    override suspend fun unblockNetwork(packageName: String, userId: Int, target: NetworkTarget) {
        decode { net("unblock ${packageName.shellQuoted()} ${target.id.shellQuoted()} --user $userId") }
    }

    override suspend fun clearNetwork(packageName: String, userId: Int): BbdNetworkClearResult = decode {
        val answer = net("clear ${packageName.shellQuoted()} --user $userId")
        BbdNetworkClearResult(deleted = answer.optInt("deleted"))
    }

    override suspend fun listSandboxApps(): List<BbdSandboxedApp> = decode {
        val listed = JSONObject(sandbox("list"))
        val hookHealth = listed.optJSONObject("hook_health").toHookHealth()
        listed.getJSONArray("apps").mapObjects { it.toBbdSandboxedApp(hookHealth) }
    }

    override suspend fun getSandboxGlobal(): BbdSandboxGlobal = decode {
        JSONObject(sandbox("global")).toBbdSandboxGlobal()
    }

    override suspend fun getSandboxStatus(): BbdSandboxStatus = decode {
        JSONObject(sandbox("status")).toBbdSandboxStatus()
    }

    override suspend fun enableSandbox(packageName: String, userId: Int) = sandboxChange("enable ${packageName.shellQuoted()} --user $userId")

    override suspend fun disableSandbox(packageName: String, userId: Int) = sandboxChange("disable ${packageName.shellQuoted()} --user $userId")

    override suspend fun disableAllSandboxes(): BbdSandboxReleaseResult = decode {
        val answer = JSONObject(sandbox("disable --all"))
        // The daemon names apps it could not release in time.
        BbdSandboxReleaseResult(failedCount = answer.getJSONArray("failed").length())
    }

    override suspend fun setSandboxItems(packageName: String, userId: Int, changes: Map<SandboxItem, SandboxMode?>) {
        val settings = changes.entries.joinToString(" ") { (item, mode) -> "${item.id}=${mode?.id ?: "global"}" }
        sandboxChange("set ${packageName.shellQuoted()} $settings --user $userId")
    }

    override suspend fun setSandboxGlobal(changes: Map<SandboxItem, SandboxMode>) {
        sandboxChange("global " + changes.entries.joinToString(" ") { (item, mode) -> "${item.id}=${mode.id}" })
    }

    override suspend fun setSandboxAuto(on: Boolean) = sandboxChange("auto " + if (on) "on" else "off")

    private fun TimeRange.argument(): String = if (hours > 0) " --hours $hours" else ""

    private suspend fun sandbox(args: String): String = rootShell.bbd("sandbox", " $args", json = true)

    private suspend fun sandboxChange(args: String) {
        withContext(ioDispatcher) { sandbox(args) }
    }

    private suspend fun net(args: String): JSONObject {
        val answer = JSONObject(rootShell.bbd("net", " $args", json = true))
        // The daemon can refuse a command even when it exits successfully.
        if (!answer.optBoolean("ok", true)) {
            throw daemonCommandFailure(answer.optString("error"))
        }
        return answer
    }

    private suspend fun <T> decode(block: suspend () -> T): T = withContext(ioDispatcher) {
        try {
            block()
        } catch (_: JSONException) {
            throw RecordsException.BadResponse()
        }
    }

    /**
     * The app and daemon need the same end of the range to agree on its
     * last day. Only the app has the device's time zone rules.
     */
    private fun chartRange(since: Long, until: Long?, bucket: BucketSize, zone: ZoneId): String {
        if (bucket == BucketSize.HOUR) {
            return "--since $since" + (until?.let { " --until $it" } ?: "") + " --bucket ${bucket.id}"
        }
        val end = until ?: Instant.now().epochSecond
        if (since >= end) throw RecordsException.CommandFailed(LoadFailure.Operation.INVALID_PERIOD)
        val firstDay = Instant.ofEpochSecond(since).atZone(zone).toLocalDate()
        val starts = generateSequence(firstDay) { it.plusDays(1) }
            .map { it.atStartOfDay(zone).toEpochSecond() }
            .takeWhile { it < end }
            .distinct() // A skipped date has the same start as the following date.
            .take(MOST_BUCKETS + 1)
            .toList()
        if (starts.size > MOST_BUCKETS) throw RecordsException.CommandFailed(LoadFailure.Operation.INVALID_PERIOD)
        return "--since $since --until $end --bucket ${bucket.id} --day-starts=${starts.joinToString(",")}"
    }

    private companion object {
        const val MOST_BUCKETS = 1000
    }
}
