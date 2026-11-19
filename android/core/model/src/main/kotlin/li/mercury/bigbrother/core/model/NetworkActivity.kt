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

package li.mercury.bigbrother.core.model

import java.net.IDN
import java.time.ZoneId

/**
 * What a block rule or a destination names. The daemon writes it as
 * `domain:example.com` (the name and its subdomains), `host:t.example.com`
 * (that name only) or `ip:203.0.113.7`, `ip:203.0.113.7:8080`,
 * `ip:[2001:db8::5]:443`.
 */
sealed interface NetworkTarget {
    /** As the daemon writes it. */
    val id: String

    /** As it is shown. */
    val name: String

    /** A domain name: with [exact], that name only; otherwise its subdomains too. */
    data class Domain(override val name: String, val exact: Boolean = false) : NetworkTarget {
        override val id: String get() = (if (exact) HOST else DOMAIN) + name

        /** Whether [host] is this name or, unless [exact], one of its subdomains. */
        fun covers(host: String): Boolean = host == name || (!exact && host.endsWith(".$name"))
    }

    /** An IP address, on [port] only or on every port. */
    data class Address(val ip: String, val port: Int? = null) : NetworkTarget {
        override val name: String
            get() = when {
                port == null -> ip
                ':' in ip -> "[$ip]:$port"
                else -> "$ip:$port"
            }

        override val id: String get() = IP + name
    }

    companion object {
        private const val DOMAIN = "domain:"
        private const val HOST = "host:"
        private const val IP = "ip:"

        /**
         * A target as the daemon writes it; null for a form this build does
         * not know. A name is taken as written: the daemon decides what one
         * is, and a rule it has must be shown to be removed.
         */
        fun fromId(id: String): NetworkTarget? = when {
            id.startsWith(DOMAIN) -> id.removePrefix(DOMAIN).takeIf { it.isNotBlank() }?.let { Domain(it) }
            id.startsWith(HOST) -> id.removePrefix(HOST).takeIf { it.isNotBlank() }?.let { Domain(it, exact = true) }
            id.startsWith(IP) -> address(id.removePrefix(IP))
            else -> null
        }

        /**
         * A target typed in: a domain, which includes its subdomains, an
         * IPv4 or IPv6 address, or either with a port (`203.0.113.7:8080`,
         * `[2001:db8::5]:443`). Null if it is none of these.
         */
        fun parse(text: String): NetworkTarget? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            return address(trimmed) ?: domain(trimmed)?.let { Domain(it) }
        }

        private fun address(text: String): Address? {
            if (text.startsWith("[")) {
                val end = text.indexOf("]:")
                if (end < 0) return null
                val ip = ipv6(text.substring(1, end)) ?: return null
                return port(text.substring(end + 2))?.let { Address(ip, it) }
            }
            ipv4(text)?.let { return Address(it) }
            ipv6(text)?.let { return Address(it) }
            val colon = text.lastIndexOf(':')
            if (colon < 0) return null
            val ip = ipv4(text.substring(0, colon)) ?: return null
            return port(text.substring(colon + 1))?.let { Address(ip, it) }
        }

        private fun port(text: String): Int? = text.takeIf { it.length in 1..5 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1..65_535 }

        private fun ipv4(text: String): String? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val ok = parts.all { part -> part.length in 1..3 && part.all(Char::isDigit) && part.toInt() <= 255 }
            return if (ok) parts.joinToString(".") { it.toInt().toString() } else null
        }

        /**
         * An IPv6 address as the daemon writes it, whichever way it was
         * typed: a rule is found again by its text.
         */
        private fun ipv6(text: String): String? {
            if (':' !in text) return null
            val halves = text.lowercase().split("::")
            if (halves.size > 2) return null
            val parsed = halves.mapIndexed { index, half -> groups(half, mayEndInIpv4 = index == halves.lastIndex) ?: return null }
            val count = parsed.sumOf { it.size }
            val groups = when {
                halves.size == 2 && count <= IPV6_GROUPS - 1 -> parsed[0] + List(IPV6_GROUPS - count) { 0 } + parsed[1]
                halves.size == 1 && count == IPV6_GROUPS -> parsed[0]
                else -> return null
            }
            return written(groups)
        }

        /** The 16-bit groups of the part of an IPv6 address on one side of `::`. */
        private fun groups(half: String, mayEndInIpv4: Boolean): List<Int>? {
            if (half.isEmpty()) return emptyList()
            val parts = half.split(':')
            return parts.flatMapIndexed { at, part ->
                if (mayEndInIpv4 && at == parts.lastIndex && '.' in part) {
                    // An IPv4 address may end it, as two groups.
                    val bytes = ipv4(part)?.split('.')?.map(String::toInt) ?: return null
                    listOf(bytes[0] shl 8 or bytes[1], bytes[2] shl 8 or bytes[3])
                } else if (part.length in 1..4 && part.all { it.isDigit() || it in 'a'..'f' }) {
                    listOf(part.toInt(16))
                } else {
                    return null
                }
            }
        }

        /** The groups in the shortest form: the first of the longest runs of zeros left out. */
        private fun written(groups: List<Int>): String {
            if (groups.take(5).all { it == 0 } && groups[5] == 0xffff) {
                return "::ffff:${groups[6] shr 8}.${groups[6] and 0xff}.${groups[7] shr 8}.${groups[7] and 0xff}"
            }
            var best = 0
            var length = 0
            var at = 0
            while (at < groups.size) {
                var end = at
                while (end < groups.size && groups[end] == 0) end += 1
                if (end - at > length) {
                    best = at
                    length = end - at
                }
                at = end + 1
            }
            val hex = groups.map { it.toString(16) }
            if (length < 2) return hex.joinToString(":")
            return hex.take(best).joinToString(":") + "::" + hex.drop(best + length).joinToString(":")
        }

        private const val IPV6_GROUPS = 8

        /** [text] as a domain name in ASCII, lower case, without a final dot; null if it is not one. */
        private fun domain(text: String): String? {
            val ascii = try {
                IDN.toASCII(text.removeSuffix("."), IDN.ALLOW_UNASSIGNED).lowercase()
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (ascii.length > 253) return null
            val labels = ascii.split('.')
            if (labels.size < 2) return null
            // Names in use have underscores, and the daemon takes them.
            val ok = labels.all { label ->
                label.length in 1..63 &&
                    label.all { it in 'a'..'z' || it.isDigit() || it == '-' || it == '_' } &&
                    !label.startsWith('-') &&
                    !label.endsWith('-')
            }
            // A last label of digits only would be an address, not a name.
            return if (ok && !labels.last().all(Char::isDigit)) ascii else null
        }
    }
}

/** A destination blocked for an app, from [created] (seconds since the epoch); [manual] if typed in rather than chosen from the records. */
data class NetworkRule(val target: NetworkTarget, val created: Long, val manual: Boolean)

/**
 * Connections to one name, port or destination: [refused] of them refused
 * by a rule or because the app has no network. [sent] and [received] are
 * bytes, null where they were not counted.
 */
interface ConnectionCounts {
    val connections: Int
    val refused: Int
    val sent: Long?
    val received: Long?
}

/** One name seen under a domain, with [rule] the target of the rule blocking it, if one does. */
data class HostActivity(
    val name: String,
    override val connections: Int,
    override val refused: Int,
    override val sent: Long?,
    override val received: Long?,
    val rule: NetworkTarget? = null,
) : ConnectionCounts

/** Connections to one port of a destination. */
data class PortActivity(
    val port: Int,
    override val connections: Int,
    override val refused: Int,
    override val sent: Long?,
    override val received: Long?,
    val rule: NetworkTarget? = null,
) : ConnectionCounts

/**
 * Where an app connected to: a domain ([NetworkTarget.Domain], a registrable
 * one, with the [hosts] seen under it) or, with no name known, an address.
 * [first] and [last] are seconds since the epoch; [rule] is the target of
 * the rule blocking it as a whole, if one does.
 */
data class DestinationActivity(
    val target: NetworkTarget,
    val hosts: List<HostActivity> = emptyList(),
    val addresses: List<String> = emptyList(),
    val ports: List<PortActivity> = emptyList(),
    override val connections: Int,
    override val refused: Int,
    override val sent: Long?,
    override val received: Long?,
    val first: Long,
    val last: Long,
    val rule: NetworkTarget? = null,
    /** How many addresses it has; [addresses] lists the first of them. */
    val addressCount: Int = addresses.size,
) : ConnectionCounts

/** Connections in the period from [start] (seconds since the epoch). */
data class ActivityBucket(val start: Long, val connections: Int, val refused: Int)

/** How the chart is grouped. [seconds] identifies the grouping in the daemon's answer. */
enum class BucketSize(val id: String, val seconds: Int) {
    HOUR("hour", 3_600),
    DAY("day", 86_400),
}

/** Whether an app's connections are recorded, and since when. */
data class NetworkRecording(val on: Boolean, val since: Long?)

/** Where an app connected to from [since] until [until], with its rules. */
data class NetworkActivity(
    val since: Long,
    val until: Long,
    val bucketSeconds: Int,
    val recording: NetworkRecording,
    /** False while the app is kept from the network. */
    val networkAllowed: Boolean,
    /** Whether data amounts are counted on this device. */
    val traffic: Boolean,
    /** Whether domain names can be read on this device. */
    val names: Boolean,
    val buckets: List<ActivityBucket>,
    val domains: List<DestinationActivity>,
    val addresses: List<DestinationActivity>,
    /** Every rule of the app, also those nothing tried to reach in the period. */
    val rules: List<NetworkRule>,
    /** The device time zone used for this chart. */
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    /**
     * This activity with [target] blocked from [now], or not blocked by a
     * rule of its own, as the daemon will report it once it has made the
     * change.
     */
    fun withRule(target: NetworkTarget, blocked: Boolean, now: Long, manual: Boolean = false): NetworkActivity = copy(
        domains = domains.map { it.withRule(target, blocked) },
        addresses = addresses.map { it.withRule(target, blocked) },
        rules = when {
            !blocked -> rules.filter { it.target != target }
            rules.any { it.target == target } -> rules
            else -> rules + NetworkRule(target, now, manual)
        },
    )
}

/** One destination of an app, with its connections grouped by hour or by local day. */
data class DestinationDetail(
    val destination: DestinationActivity,
    val buckets: List<ActivityBucket>,
    val bucketSeconds: Int = BucketSize.HOUR.seconds,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** This destination with [target] blocked, or the rule [target] gone if not [blocked]. */
fun DestinationActivity.withRule(target: NetworkTarget, blocked: Boolean): DestinationActivity {
    if (!blocked) {
        return copy(
            rule = rule.takeIf { it != target },
            hosts = hosts.map { host -> host.copy(rule = host.rule.takeIf { it != target }) },
            ports = ports.map { port -> port.copy(rule = port.rule.takeIf { it != target }) },
        )
    }
    val own = this.target
    return when (target) {
        is NetworkTarget.Domain -> if (own !is NetworkTarget.Domain) {
            this
        } else {
            copy(
                rule = rule ?: target.takeIf { !it.exact && it.covers(own.name) },
                hosts = hosts.map { host -> host.copy(rule = host.rule ?: target.takeIf { it.covers(host.name) }) },
            )
        }

        is NetworkTarget.Address -> when {
            own !is NetworkTarget.Address || own.ip != target.ip -> this
            target.port == null -> copy(rule = rule ?: target)
            else -> copy(ports = ports.map { port -> port.copy(rule = port.rule ?: target.takeIf { port.port == it.port }) })
        }
    }
}
