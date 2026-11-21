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

import li.mercury.bigbrother.core.model.ActivityBucket
import li.mercury.bigbrother.core.model.DestinationActivity
import li.mercury.bigbrother.core.model.DestinationDetail
import li.mercury.bigbrother.core.model.HostActivity
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkRecording
import li.mercury.bigbrother.core.model.NetworkRule
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.PortActivity
import java.time.ZoneId

internal data class BbdNetworkClearResult(val deleted: Int)

internal data class BbdNetworkActivity(
    val since: Long,
    val until: Long,
    val bucketSeconds: Int,
    val recording: BbdNetworkRecording,
    val network: String,
    val traffic: Boolean,
    val names: Boolean,
    val buckets: List<BbdActivityBucket>,
    val destinations: List<BbdDestinationActivity>,
    val rules: List<BbdNetworkRule>,
)

internal fun BbdNetworkActivity.asExternalModel(zone: ZoneId): NetworkActivity {
    val destinations = destinations.map(BbdDestinationActivity::asExternalModel)
    return NetworkActivity(
        since = since,
        until = until,
        bucketSeconds = bucketSeconds,
        recording = recording.asExternalModel(),
        networkAllowed = network != "blocked",
        traffic = traffic,
        names = names,
        buckets = buckets.map(BbdActivityBucket::asExternalModel),
        domains = destinations.filter { it.target is NetworkTarget.Domain },
        addresses = destinations.filter { it.target is NetworkTarget.Address },
        rules = rules.mapNotNull(BbdNetworkRule::asExternalModel),
        zone = zone,
    )
}

internal data class BbdNetworkRecording(
    val on: Boolean,
    val since: Long?,
)

internal fun BbdNetworkRecording.asExternalModel(): NetworkRecording = NetworkRecording(
    on = on,
    since = since,
)

internal data class BbdNetworkRule(
    val target: String,
    val created: Long,
    val manual: Boolean,
)

/** Ignore rules of a kind this build does not know. */
private fun BbdNetworkRule.asExternalModel(): NetworkRule? = NetworkTarget.fromId(target)?.let { NetworkRule(it, created, manual) }

internal data class BbdDestinationDetail(
    val destination: BbdDestinationActivity,
    val buckets: List<BbdActivityBucket>,
    val bucketSeconds: Int,
)

internal fun BbdDestinationDetail.asExternalModel(zone: ZoneId): DestinationDetail = DestinationDetail(
    destination = destination.asExternalModel(),
    buckets = buckets.map(BbdActivityBucket::asExternalModel),
    bucketSeconds = bucketSeconds,
    zone = zone,
)

internal data class BbdDestinationActivity(
    val site: String?,
    val address: String?,
    val hosts: List<BbdHostActivity>,
    val addresses: List<String>,
    val addressCount: Int,
    val ports: List<BbdPortActivity>,
    val connections: Int,
    val refused: Int,
    val sent: Long?,
    val received: Long?,
    val first: Long,
    val last: Long,
    val rule: String?,
)

internal fun BbdDestinationActivity.asExternalModel(): DestinationActivity = DestinationActivity(
    hosts = hosts.map(BbdHostActivity::asExternalModel),
    addresses = addresses,
    addressCount = addressCount,
    ports = ports.map(BbdPortActivity::asExternalModel),
    connections = connections,
    refused = refused,
    sent = sent,
    received = received,
    first = first,
    last = last,
    rule = rule?.let(NetworkTarget::fromId),
    target = destinationTarget(),
)

private fun BbdDestinationActivity.destinationTarget(): NetworkTarget {
    if (site != null) return NetworkTarget.Domain(site)
    val addr = checkNotNull(address)
    return NetworkTarget.Address(if (addr.contains(':')) addr.lowercase() else addr)
}

internal data class BbdHostActivity(
    val name: String,
    val connections: Int,
    val refused: Int,
    val sent: Long?,
    val received: Long?,
    val rule: String?,
)

internal fun BbdHostActivity.asExternalModel(): HostActivity = HostActivity(
    name = name,
    connections = connections,
    refused = refused,
    sent = sent,
    received = received,
    rule = rule?.let(NetworkTarget::fromId),
)

internal data class BbdPortActivity(
    val port: Int,
    val connections: Int,
    val refused: Int,
    val sent: Long?,
    val received: Long?,
    val rule: String?,
)

internal fun BbdPortActivity.asExternalModel(): PortActivity = PortActivity(
    port = port,
    connections = connections,
    refused = refused,
    sent = sent,
    received = received,
    rule = rule?.let(NetworkTarget::fromId),
)

internal data class BbdActivityBucket(
    val start: Long,
    val connections: Int,
    val refused: Int,
)

internal fun BbdActivityBucket.asExternalModel(): ActivityBucket = ActivityBucket(
    start = start,
    connections = connections,
    refused = refused,
)
