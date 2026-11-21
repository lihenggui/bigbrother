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
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.toBbdNetworkActivity(): BbdNetworkActivity {
    val destinations = optJSONArray("destinations").objects().map(JSONObject::toBbdDestination)
    val recording = optJSONObject("recording")
    return BbdNetworkActivity(
        since = getLong("since"),
        until = getLong("until"),
        bucketSeconds = optInt("bucket", BucketSize.HOUR.seconds),
        recording = BbdNetworkRecording(
            on = recording?.optBoolean("on") == true,
            since = recording?.optLongOrNull("since"),
        ),
        network = optString("network"),
        traffic = optBoolean("traffic"),
        names = optBoolean("names"),
        buckets = optJSONArray("buckets").toBuckets(),
        destinations = destinations,
        rules = optJSONArray("rules").objects().map { rule ->
            BbdNetworkRule(rule.getString("target"), rule.optLong("created"), rule.optBoolean("manual"))
        },
    )
}

internal fun JSONObject.toBbdDestinationDetail(): BbdDestinationDetail = BbdDestinationDetail(
    destination = toBbdDestination(),
    buckets = optJSONArray("buckets").toBuckets(),
    bucketSeconds = optInt("bucket", BucketSize.HOUR.seconds),
)

private fun JSONObject.toBbdDestination(): BbdDestinationActivity {
    val site = optString("site").ifEmpty { null }
    return BbdDestinationActivity(
        site = site,
        address = if (site == null) getString("addr") else null,
        hosts = optJSONArray("hosts").objects().map { host ->
            BbdHostActivity(
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
            BbdPortActivity(
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

private fun JSONObject.rule(): String? = if (isNull("rule")) null else getString("rule")

private fun JSONArray?.toBuckets(): List<BbdActivityBucket> = objects().map { BbdActivityBucket(it.getLong("t"), it.optInt("conn"), it.optInt("refused")) }
