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

import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.DestinationActivity
import li.mercury.bigbrother.core.model.HostActivity
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkRecording
import li.mercury.bigbrother.core.model.NetworkRule
import li.mercury.bigbrother.core.model.NetworkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkActivityPresentationTest {
    @Test
    fun givenPeriodAndSearch_whenShowingDestinations_thenTotalsCoverAllDestinationsInThePeriod() {
        val domain = domain(2, 20, 30)
        val address = address(3, 1)
        val state = NetworkActivityUiState(
            bucket = 0,
            activity = activity(listOf(domain.copy(connections = 8)), listOf(address)),
            period = activity(listOf(domain), listOf(address)),
            query = " API.Example ",
        )

        val shown = state.withShown()

        assertEquals(listOf("api.example.com"), shown.shown.domains.map { it.title })
        assertEquals(0, shown.shown.addressCount)
        assertEquals(ActivityTotals(destinations = 2, connections = 5, refused = 1, sent = 20, received = 30), shown.totals)
    }

    @Test
    fun givenPendingChanges_whenShowingBlocked_thenKeepsUnblockedRowsAndIncludesRulesWithoutAttempts() {
        val seen = domain(2, 20, 30)
        val unblocked = NetworkTarget.Domain("api.example.com", exact = true)
        val pending = NetworkTarget.Address("192.0.2.10", port = 443)
        val manual = NetworkTarget.Domain("unused.example.org", exact = true)
        val state = NetworkActivityUiState(
            activity = activity(listOf(seen), emptyList()).copy(
                rules = listOf(NetworkRule(unblocked, 1, manual = false), NetworkRule(manual, 2, manual = true)),
            ),
            blockedOnly = true,
            blockedShown = setOf(unblocked),
            pending = mapOf(unblocked to false, pending to true),
        )

        val shown = state.withShown()

        assertEquals(2, shown.blockedCount)
        val previous = shown.shown.domains.single { it.toggle == unblocked }
        assertFalse(previous.blocked)
        assertEquals(seen.target, previous.opens)
        assertEquals(2, previous.connections)
        val unused = shown.shown.domains.single { it.toggle == manual }
        assertTrue(unused.manual)
        assertTrue(unused.blocked && unused.noAttempts)
        assertNull(unused.opens)
        val added = shown.shown.addresses.single()
        assertEquals(pending, added.toggle)
        assertTrue(added.blocked && added.noAttempts)
    }

    @Test
    fun givenMoreThanSixDestinations_whenSortedByData_thenKeepsFullCountAndLimitsVisibleRows() {
        val addresses = (1..8).map { n -> address(n, 0).copy(target = NetworkTarget.Address("192.0.2.$n"), sent = n.toLong()) }
        val state = NetworkActivityUiState(activity = activity(emptyList(), addresses))

        val shown = state.withShown().shown

        assertEquals(8, shown.addressCount)
        assertEquals(addresses.reversed().take(6).map { it.target }, shown.addresses.map { it.opens })
        assertEquals(8, state.copy(showAllAddresses = true).withShown().shown.addresses.size)
    }

    private fun activity(domains: List<DestinationActivity>, addresses: List<DestinationActivity>) = NetworkActivity(
        since = 0,
        until = 3_600,
        bucketSeconds = BucketSize.HOUR.seconds,
        recording = NetworkRecording(on = true, since = 0),
        networkAllowed = true,
        traffic = true,
        names = true,
        buckets = emptyList(),
        domains = domains,
        addresses = addresses,
        rules = emptyList(),
    )

    private fun domain(connections: Int, sent: Long, received: Long) = DestinationActivity(
        target = NetworkTarget.Domain("example.com"),
        hosts = listOf(HostActivity("api.example.com", connections, refused = 0, sent, received)),
        connections = connections,
        refused = 0,
        sent = sent,
        received = received,
        first = 1,
        last = 2,
    )

    private fun address(connections: Int, refused: Int) = DestinationActivity(
        target = NetworkTarget.Address("192.0.2.1"),
        connections = connections,
        refused = refused,
        sent = null,
        received = null,
        first = 1,
        last = 2,
    )
}
