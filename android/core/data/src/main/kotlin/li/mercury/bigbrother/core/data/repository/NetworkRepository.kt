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
import kotlinx.coroutines.flow.update
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.data.root.bbd
import li.mercury.bigbrother.core.data.root.parseBbdOutput
import li.mercury.bigbrother.core.data.root.shellQuoted
import li.mercury.bigbrother.core.model.ActivityBucket
import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.DestinationActivity
import li.mercury.bigbrother.core.model.DestinationDetail
import li.mercury.bigbrother.core.model.HostActivity
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkRecording
import li.mercury.bigbrother.core.model.NetworkRule
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.PortActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the apps in the jail connect to, and the destinations blocked for
 * each. Whether an app's network is cut off or recorded is one of its
 * sandbox items ([SandboxRepository]); the daemon keeps the rest.
 */
interface NetworkRepository {
    /**
     * Counts the changes made to rules and records: screens that show them
     * read them again when it moves.
     */
    val changes: StateFlow<Int>

    /** An app's connections from [since] to [until] (now if null), in periods of [bucket]. */
    suspend fun getActivity(packageName: String, userId: Int, since: Long, until: Long? = null, bucket: BucketSize = BucketSize.HOUR): NetworkActivity

    /** One destination of an app, a domain or an address, from [since], in periods of [bucket]. */
    suspend fun getDestination(packageName: String, userId: Int, target: NetworkTarget, since: Long, bucket: BucketSize = BucketSize.HOUR): DestinationDetail

    /** Blocks [target] for an app at once; [manual] if it was typed in rather than chosen from the records. */
    suspend fun block(packageName: String, userId: Int, target: NetworkTarget, manual: Boolean = false)

    /** Removes the rule blocking [target] for an app. */
    suspend fun unblock(packageName: String, userId: Int, target: NetworkTarget)

    /** Deletes an app's records, keeping its rules; returns how many were deleted. */
    suspend fun clear(packageName: String, userId: Int): Int
}

/** Runs `bbd net ... --json` as root. */
@Singleton
internal class RootNetworkRepository @Inject constructor(
    private val rootShell: RootShell,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) : NetworkRepository {
    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()

    // Package names come from the package manager and targets from the
    // daemon or the user: all are quoted. Everything else is a number or a
    // constant.
    override suspend fun getActivity(packageName: String, userId: Int, since: Long, until: Long?, bucket: BucketSize): NetworkActivity {
        val range = "--since $since" + (until?.let { " --until $it" } ?: "") + bars(bucket)
        return net("activity ${packageName.shellQuoted()} $range --user $userId") { toActivity() }
    }

    override suspend fun getDestination(packageName: String, userId: Int, target: NetworkTarget, since: Long, bucket: BucketSize): DestinationDetail {
        val args = "destination ${packageName.shellQuoted()} ${target.id.shellQuoted()} --since $since${bars(bucket)} --user $userId"
        return net(args) {
            DestinationDetail(toDestination(), optJSONArray("buckets").toBuckets(), optInt("bucket", BucketSize.HOUR.seconds))
        }
    }

    /**
     * The bars of a chart. The daemon counts by the hour of UTC; days begin
     * where the device has them, which only this side knows.
     */
    private fun bars(bucket: BucketSize): String = " --bucket ${bucket.id}" +
        if (bucket == BucketSize.DAY) " --offset ${TimeZone.getDefault().getOffset(System.currentTimeMillis()) / MILLIS_PER_SECOND}" else ""

    override suspend fun block(packageName: String, userId: Int, target: NetworkTarget, manual: Boolean) {
        net("block ${packageName.shellQuoted()} ${target.id.shellQuoted()}" + (if (manual) " --manual" else "") + " --user $userId") {
            _changes.update { it + 1 }
        }
    }

    override suspend fun unblock(packageName: String, userId: Int, target: NetworkTarget) {
        net("unblock ${packageName.shellQuoted()} ${target.id.shellQuoted()} --user $userId") {
            _changes.update { it + 1 }
        }
    }

    override suspend fun clear(packageName: String, userId: Int): Int = net("clear ${packageName.shellQuoted()} --user $userId") {
        _changes.update { it + 1 }
        optInt("deleted")
    }

    /** Checks and maps one decoded answer; the daemon can refuse a command even when it exits successfully. */
    private suspend fun <T> net(args: String, map: JSONObject.() -> T): T = defaultDispatcher.parseBbdOutput {
        val answer = JSONObject(rootShell.bbd("net", args))
        if (!answer.optBoolean("ok", true)) {
            throw RecordsException.CommandFailed(answer.optString("error").ifBlank { "bbd refused the command" })
        }
        answer.map()
    }

    private fun JSONObject.toActivity(): NetworkActivity {
        val destinations = optJSONArray("destinations").objects().map { it.toDestination() }
        val recording = optJSONObject("recording")
        return NetworkActivity(
            since = getLong("since"),
            until = getLong("until"),
            bucketSeconds = optInt("bucket", BucketSize.HOUR.seconds),
            recording = NetworkRecording(
                on = recording?.optBoolean("on") == true,
                since = recording?.optLongOrNull("since"),
            ),
            networkAllowed = optString("network") != "blocked",
            traffic = optBoolean("traffic"),
            names = optBoolean("names"),
            buckets = optJSONArray("buckets").toBuckets(),
            domains = destinations.filter { it.target is NetworkTarget.Domain },
            addresses = destinations.filter { it.target is NetworkTarget.Address },
            // A rule of a kind this build does not know is left out.
            rules = optJSONArray("rules").objects().mapNotNull { rule ->
                NetworkTarget.fromId(rule.getString("target"))?.let { NetworkRule(it, rule.optLong("created"), rule.optBoolean("manual")) }
            },
        )
    }

    private fun JSONObject.toDestination(): DestinationActivity {
        val site = optString("site")
        val target = if (site.isNotEmpty()) {
            NetworkTarget.Domain(site)
        } else {
            val addr = getString("addr")
            NetworkTarget.Address(if (addr.contains(':')) addr.lowercase() else addr)
        }
        val hosts = optJSONArray("hosts")
        return DestinationActivity(
            target = target,
            hosts = List(hosts?.length() ?: 0) { index ->
                // A name without counts of its own is still a name seen.
                val host = hosts!!.optJSONObject(index)
                    ?: return@List HostActivity(hosts.getString(index), connections = 0, refused = 0, sent = null, received = null)
                HostActivity(
                    name = host.getString("name"),
                    connections = host.optInt("conn"),
                    refused = host.optInt("refused"),
                    sent = host.optLongOrNull("sent"),
                    received = host.optLongOrNull("recv"),
                    rule = host.rule(),
                )
            },
            addresses = optJSONArray("addrs").strings(),
            addressCount = optInt("addr_count", optJSONArray("addrs")?.length() ?: 0),
            ports = optJSONArray("ports").objects().map { port ->
                PortActivity(
                    port = port.getInt("port"),
                    connections = port.optInt("conn"),
                    refused = port.optInt("refused"),
                    sent = port.optLongOrNull("sent"),
                    received = port.optLongOrNull("recv"),
                    rule = port.rule(),
                )
            },
            connections = optInt("conn"),
            refused = optInt("refused"),
            sent = optLongOrNull("sent"),
            received = optLongOrNull("recv"),
            first = optLong("first"),
            last = optLong("last"),
            rule = rule(),
        )
    }

    private fun JSONObject.rule(): NetworkTarget? = if (isNull("rule")) null else NetworkTarget.fromId(getString("rule"))

    private fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)

    private fun JSONArray?.toBuckets(): List<ActivityBucket> = objects().map { ActivityBucket(it.getLong("t"), it.optInt("conn"), it.optInt("refused")) }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

    private companion object {
        const val MILLIS_PER_SECOND = 1000
    }
}
