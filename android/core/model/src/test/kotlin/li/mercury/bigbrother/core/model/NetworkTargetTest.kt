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

import li.mercury.bigbrother.core.model.NetworkTarget.Address
import li.mercury.bigbrother.core.model.NetworkTarget.Domain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkTargetTest {
    @Test
    fun givenDomainTyped_whenParsed_thenDomainWithSubdomainsInLowerCase() {
        assertEquals(Domain("track.example.com"), NetworkTarget.parse(" Track.Example.COM. "))
        assertEquals("domain:track.example.com", NetworkTarget.parse("track.example.com")?.id)
    }

    @Test
    fun givenAddressesTyped_whenParsed_thenAddressWithPortIfGiven() {
        assertEquals(Address("203.0.113.7"), NetworkTarget.parse("203.0.113.7"))
        assertEquals(Address("203.0.113.7", 8080), NetworkTarget.parse("203.0.113.7:8080"))
        assertEquals(Address("2001:db8::5"), NetworkTarget.parse("2001:DB8::5"))
        assertEquals(Address("2001:db8::5", 443), NetworkTarget.parse("[2001:db8::5]:443"))
        assertEquals(Address("::ffff:203.0.113.7"), NetworkTarget.parse("::ffff:203.0.113.7"))
    }

    @Test
    fun givenAddressWithPort_whenWrittenForTheDaemon_thenBracketsAroundIpv6() {
        assertEquals("ip:[2001:db8::5]:443", Address("2001:db8::5", 443).id)
        assertEquals("ip:203.0.113.7:8080", Address("203.0.113.7", 8080).id)
        assertEquals("ip:2001:db8::5", Address("2001:db8::5").id)
    }

    @Test
    fun givenInvalidInput_whenParsed_thenNull() {
        listOf(
            "",
            "localhost",
            "203.0.113",
            "203.0.113.256",
            "203.0.113.7:0",
            "203.0.113.7:65536",
            "2001:db8::5:443x",
            "1::2::3",
            "[2001:db8::5]",
            "example..com",
            "-example.com",
            "exa mple.com",
            "https://example.com/",
        ).forEach { assertNull(NetworkTarget.parse(it), it) }
    }

    @Test
    fun givenDaemonTargets_whenRead_thenEachKindAndUnknownLeftOut() {
        assertEquals(Domain("adnet.example"), NetworkTarget.fromId("domain:adnet.example"))
        assertEquals(Domain("t.example.com", exact = true), NetworkTarget.fromId("host:t.example.com"))
        assertEquals(Address("203.0.113.81", 8080), NetworkTarget.fromId("ip:203.0.113.81:8080"))
        assertEquals(Address("2001:db8::5", 443), NetworkTarget.fromId("ip:[2001:db8::5]:443"))
        assertNull(NetworkTarget.fromId("cidr:203.0.113.0/24"))
    }

    @Test
    fun givenDomainRule_whenApplied_thenSiteAndItsNamesBlockedAndUnblockedAgain() {
        val destination = DestinationActivity(
            target = Domain("example.com"),
            hosts = listOf(HostActivity("a.example.com", 1, 0, null, null), HostActivity("b.example.com", 1, 0, null, null)),
            connections = 2,
            refused = 0,
            sent = null,
            received = null,
            first = 0,
            last = 0,
        )
        val rule = Domain("example.com")

        val blocked = destination.withRule(rule, blocked = true)
        assertEquals(rule, blocked.rule)
        assertEquals(listOf(rule, rule), blocked.hosts.map { it.rule })

        val one = destination.withRule(Domain("a.example.com", exact = true), blocked = true)
        assertNull(one.rule)
        assertEquals(listOf(Domain("a.example.com", exact = true), null), one.hosts.map { it.rule })

        assertEquals(destination, blocked.withRule(rule, blocked = false))
    }
}
