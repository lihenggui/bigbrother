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

package li.mercury.bigbrother.feature.jail.impl.network

import li.mercury.bigbrother.core.model.ActivityBucket
import li.mercury.bigbrother.core.model.DestinationActivity
import li.mercury.bigbrother.core.model.HostActivity
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkRecording
import li.mercury.bigbrother.core.model.NetworkRule
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.PortActivity

/*
 * A made-up shopping app's network activity over a day, with example
 * domains and documentation addresses only.
 */

private const val HOUR = 3_600L
private const val KB = 1_024L
private const val MB = 1_024L * 1_024L

/** Connections made and refused, hour by hour, over the 24 hours up to now. */
private val HOURLY = listOf(
    28 to 9, 22 to 8, 18 to 8, 31 to 9, 52 to 12, 64 to 12, 47 to 10, 20 to 8, 9 to 7, 4 to 7, 3 to 7, 2 to 7,
    3 to 7, 2 to 7, 2 to 7, 5 to 7, 21 to 8, 48 to 10, 39 to 9, 18 to 8, 26 to 8, 57 to 11, 44 to 10, 36 to 6,
)

/** Now, in the samples that do not take a clock: Wednesday 18 November 2026, 15:00 UTC. */
internal const val SAMPLE_NOW = 1_763_478_000L

internal fun domain(
    site: String,
    hosts: List<String> = listOf(site),
    connections: Int,
    sent: Long? = null,
    received: Long? = null,
    refused: Int = 0,
    port: Int = 443,
    addresses: List<String> = emptyList(),
    rule: NetworkTarget? = null,
    last: Long = SAMPLE_NOW - 600,
    ports: List<PortActivity>? = null,
): DestinationActivity = DestinationActivity(
    target = NetworkTarget.Domain(site),
    hosts = hosts.mapIndexed { index, name ->
        // The first name has most of it.
        val share = if (index == 0) 1.0 - 0.1 * (hosts.size - 1) else 0.1
        HostActivity(
            name = name,
            connections = (connections * share).toInt(),
            refused = (refused * share).toInt(),
            sent = sent?.let { (it * share).toLong() },
            received = received?.let { (it * share).toLong() },
            rule = rule,
        )
    },
    addresses = addresses,
    ports = ports ?: listOf(PortActivity(port, connections, refused, sent, received)),
    connections = connections,
    refused = refused,
    sent = sent,
    received = received,
    first = last - 5 * HOUR,
    last = last,
    rule = rule,
)

internal fun address(
    ip: String,
    connections: Int,
    sent: Long? = null,
    received: Long? = null,
    refused: Int = 0,
    port: Int = 443,
    rule: NetworkTarget? = null,
    last: Long = SAMPLE_NOW - 900,
): DestinationActivity = DestinationActivity(
    target = NetworkTarget.Address(ip),
    addresses = listOf(ip),
    ports = listOf(PortActivity(port, connections, refused, sent, received)),
    connections = connections,
    refused = refused,
    sent = sent,
    received = received,
    first = last - 3 * HOUR,
    last = last,
    rule = rule,
)

private val ADNET = NetworkTarget.Domain("adnet.example")
private val TRACKER = NetworkTarget.Domain("track.example.com", exact = true)
private val BLOCKED_ADDRESS = NetworkTarget.Address("203.0.113.80")

/** The domains of the shopping app: 24, the first six as the design shows them. */
internal fun shopDomains(now: Long = SAMPLE_NOW): List<DestinationActivity> = listOf(
    domain(
        "example-shop.com",
        hosts = listOf("api.example-shop.com", "img.example-shop.com", "cdn.example-shop.com", "m.example-shop.com"),
        connections = 512,
        sent = 1_153_434,
        received = 19_083_878,
        addresses = listOf("203.0.113.10", "203.0.113.40", "2001:db8::10"),
        last = now - 120,
    ),
    domain("shopcdn.example", hosts = listOf("cdn.shopcdn.example"), connections = 88, sent = 96 * KB, received = 9_600 * KB, last = now - 400),
    domain(
        "metrics.example",
        hosts = listOf("log.metrics.example", "cfg.metrics.example"),
        connections = 96,
        sent = 1_468_006,
        received = 96 * KB,
        addresses = listOf("203.0.113.40", "203.0.113.41", "2001:db8::40", "203.0.113.42", "203.0.113.43"),
        last = now - 1_680,
        ports = listOf(
            PortActivity(443, 92, 0, 1_300 * KB, 90 * KB),
            PortActivity(8080, 4, 0, 120 * KB, 6 * KB),
        ),
    ),
    domain("adnet.example", hosts = listOf("t.adnet.example"), connections = 0, refused = 202, rule = ADNET, last = now - 300),
    domain("push.example.net", connections = 24, sent = 8 * KB, received = 120 * KB, port = 5223, last = now - 60),
    domain("example.org", connections = 30, sent = 34 * KB, received = 860 * KB, last = now - 5_000),
) + (1..18).map { index ->
    domain("static$index.example", connections = 20 - index, sent = (20L - index) * 200, received = (20L - index) * 2 * KB, last = now - index * HOUR)
}

/** The addresses the shopping app reached without a name: 14. */
internal fun shopAddresses(now: Long = SAMPLE_NOW): List<DestinationActivity> = listOf(
    address("198.51.100.53", connections = 41, sent = 6 * KB, received = 46 * KB, port = 8443, last = now - 200),
    address("203.0.113.24", connections = 37, sent = 210 * KB, received = 2_355 * KB, last = now - 3_000),
    address("203.0.113.80", connections = 0, refused = 31, rule = BLOCKED_ADDRESS, last = now - 700),
) + (1..11).map { index ->
    address("198.51.100.${100 + index}", connections = 12 - index, sent = (12L - index) * 300, received = (12L - index) * KB, last = now - index * HOUR)
}

/** The shopping app's day, recorded since two days ago, with three destinations blocked. */
internal fun shopActivity(now: Long = SAMPLE_NOW, traffic: Boolean = true): NetworkActivity {
    val start = now - HOURLY.size * HOUR
    val domains = shopDomains(now)
    val addresses = shopAddresses(now)
    return NetworkActivity(
        since = now - 24 * HOUR,
        until = now,
        bucketSeconds = HOUR.toInt(),
        recording = NetworkRecording(on = true, since = now - 54 * HOUR - 1_680),
        networkAllowed = true,
        traffic = traffic,
        names = true,
        buckets = HOURLY.mapIndexed { index, (made, refused) -> ActivityBucket(start + index * HOUR, made, refused) },
        domains = if (traffic) domains else domains.map { it.withoutTraffic() },
        addresses = if (traffic) addresses else addresses.map { it.withoutTraffic() },
        rules = listOf(
            NetworkRule(ADNET, now - 20 * HOUR, manual = false),
            NetworkRule(TRACKER, now - 2 * HOUR, manual = true),
            NetworkRule(BLOCKED_ADDRESS, now - 16 * HOUR, manual = false),
        ),
    )
}

private fun DestinationActivity.withoutTraffic() = copy(
    sent = null,
    received = null,
    hosts = hosts.map { it.copy(sent = null, received = null) },
    ports = ports.map { it.copy(sent = null, received = null) },
)

/** Recording just turned on: nothing yet. */
internal fun emptyActivity(now: Long = SAMPLE_NOW): NetworkActivity = NetworkActivity(
    since = now - 24 * HOUR,
    until = now,
    bucketSeconds = HOUR.toInt(),
    recording = NetworkRecording(on = true, since = now - 1_680),
    networkAllowed = true,
    traffic = true,
    names = true,
    buckets = emptyList(),
    domains = emptyList(),
    addresses = emptyList(),
    rules = emptyList(),
)

/** The 03:00 hour of the shopping app's day: what still connected in the background. */
internal fun shopNight(now: Long = SAMPLE_NOW): NetworkActivity {
    val day = shopActivity(now)
    return day.copy(
        since = day.buckets[12].start,
        until = day.buckets[12].start + HOUR,
        domains = listOf(
            domain("adnet.example", hosts = listOf("t.adnet.example"), connections = 0, refused = 7, rule = ADNET),
            domain("push.example.net", connections = 2, sent = 2 * KB, received = 8 * KB, port = 5223),
        ),
        addresses = listOf(address("198.51.100.53", connections = 1, sent = 1 * KB, received = 6 * KB, port = 8443)),
    )
}

/** A flashlight kept from the network: every attempt refused, no names. */
internal fun flashlightActivity(now: Long = SAMPLE_NOW): NetworkActivity {
    val start = now - HOURLY.size * HOUR
    val refused = listOf(4, 4, 5, 4, 6, 5, 4, 4, 2, 2, 2, 2, 2, 2, 2, 2, 4, 5, 6, 4, 5, 6, 5, 3)
    return NetworkActivity(
        since = now - 24 * HOUR,
        until = now,
        bucketSeconds = HOUR.toInt(),
        recording = NetworkRecording(on = true, since = now - 30 * HOUR),
        networkAllowed = false,
        traffic = true,
        names = true,
        buckets = refused.mapIndexed { index, count -> ActivityBucket(start + index * HOUR, 0, count) },
        domains = emptyList(),
        addresses = listOf(
            address("203.0.113.7", connections = 0, refused = 48),
            address("198.51.100.9", connections = 0, refused = 30, port = 8080),
            address("203.0.113.61", connections = 0, refused = 12),
            address("2001:db8::61", connections = 0, refused = 6),
        ),
        rules = emptyList(),
    )
}
