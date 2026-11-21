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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.DestinationDetail
import li.mercury.bigbrother.core.model.HostActivity
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkRule
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.PortActivity
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Robolectric for org.json, which the answers are read with.
@RunWith(RobolectricTestRunner::class)
class BbdNetworkTest {
    @Test
    fun givenActivity_whenRead_thenDomainsAddressesAndRulesParsed() = runTest {
        val shell = TestBbdRootShell(bbdFixture("net-activity"))
        val dataSource = RootShellBbdDataSource(shell, StandardTestDispatcher(testScheduler))
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            dataSource.getActivity("com.example.shopping", userId = 0, since = 1_763_393_520)
        }

        assertFalse(read.isCompleted)
        val activity = read.await()

        assertTrue(activity.recording.on)
        assertTrue(activity.networkAllowed)
        assertEquals(listOf(NetworkTarget.Domain("example-shop.com")), activity.domains.map { it.target })
        val site = activity.domains.single()
        assertEquals(
            HostActivity("img.example-shop.com", 212, 0, 153_434, 19_081_878, NetworkTarget.Domain("img.example-shop.com", exact = true)),
            site.hosts[1],
        )
        assertEquals(listOf("203.0.113.10", "2001:db8::10"), site.addresses)
        val address = activity.addresses.single()
        assertEquals(NetworkTarget.Address("2001:db8::53"), address.target)
        // Not counted on this destination: no amounts, not zero.
        assertNull(address.sent)
        assertEquals(listOf(PortActivity(8443, 41, 0, null, null, NetworkTarget.Address("2001:db8::53", 8443))), address.ports)
        // The rule of a kind this build does not know is left out.
        assertEquals(
            listOf(
                NetworkRule(NetworkTarget.Domain("adnet.example"), 1_763_400_000, manual = false),
                NetworkRule(NetworkTarget.Address("203.0.113.81", 8080), 1_763_420_001, manual = true),
            ),
            activity.rules,
        )
        assertEquals(listOf(28 to 9), activity.buckets.map { it.connections to it.refused })
    }

    @Test
    fun givenBlockedApp_whenRead_thenNetworkNotAllowed() = runTest {
        val shell = TestBbdRootShell(bbdFixture("net-activity").replace("\"allowed\"", "\"blocked\""))

        assertFalse(RootShellBbdDataSource(shell, Dispatchers.Unconfined).getActivity("com.example.shopping", 0, 0).networkAllowed)
    }

    @Test
    fun givenPackageAndTarget_whenBlocked_thenBothQuotedAndLabelOmitsThem() = runTest {
        val shell = TestBbdRootShell(bbdFixture("net-ok"))
        val dataSource = RootShellBbdDataSource(shell, Dispatchers.Unconfined)

        dataSource.blockNetwork("com.example.it's", userId = 10, NetworkTarget.Address("2001:db8::5", 443), manual = true)

        val line = shell.lines.single()
        assertTrue("net block 'com.example.it'\\''s' 'ip:[2001:db8::5]:443' --manual --user 10 --json" in line, line)
        assertEquals("bbd net block", shell.labels.single())
    }

    @Test
    fun givenDaemonWithoutNetCommands_whenRead_thenModuleOutdated() = runTest {
        val shell = TestBbdRootShell("", error = "error: unrecognized subcommand 'net'")

        val dataSource = RootShellBbdDataSource(shell, Dispatchers.Unconfined)
        assertFailsWith<RecordsException.ModuleOutdated> { dataSource.getActivity("com.example.shopping", 0, 0) }
    }

    @Test
    fun givenDaemonRefusing_whenUnblocked_thenFailsWithItsReason() = runTest {
        val shell = TestBbdRootShell(bbdFixture("net-refused"))
        val dataSource = RootShellBbdDataSource(shell, Dispatchers.Unconfined)

        val failure = assertFailsWith<RecordsException.CommandFailed> {
            dataSource.unblockNetwork("com.example.shopping", 0, NetworkTarget.Domain("adnet.example"))
        }
        assertEquals(LoadFailure.Operation.COMMAND_FAILED, failure.toLoadFailure())
    }

    @Test
    fun givenRecords_whenCleared_thenCountOfDeleted() = runTest {
        val shell = TestBbdRootShell(bbdFixture("net-clear"))

        assertEquals(BbdNetworkClearResult(5_120), RootShellBbdDataSource(shell, Dispatchers.Unconfined).clearNetwork("com.example.shopping", 0))
    }

    @Test
    fun givenDestination_whenRead_thenWithItsHours() = runTest {
        val shell = TestBbdRootShell(
            bbdFixture("net-destination"),
        )

        val dataSource = RootShellBbdDataSource(shell, Dispatchers.Unconfined)
        val detail = dataSource.getDestination("com.example.shopping", 0, NetworkTarget.Address("198.51.100.53"), since = 0)

        assertEquals(NetworkTarget.Address("198.51.100.53"), detail.destination.target)
        assertEquals(3_600L, detail.buckets.single().start)
        assertEquals(3_600, detail.bucketSeconds)
        assertTrue("'ip:198.51.100.53' --since 0 --bucket hour --user 0" in shell.lines.single())
    }

    @Test
    fun givenClockChanges_whenReadingDays_thenUsesMidnightsOnBothSidesOfEachChange() = runTest {
        val zone = ZoneId.of("America/Los_Angeles")
        val shell = TestBbdRootShell(bbdFixture("net-activity"))
        val dataSource = RootShellBbdDataSource(shell, Dispatchers.Unconfined)
        val transitions = listOf(
            listOf("2026-03-07T08:00:00Z", "2026-03-08T08:00:00Z", "2026-03-09T07:00:00Z"),
            listOf("2026-10-31T07:00:00Z", "2026-11-01T07:00:00Z", "2026-11-02T08:00:00Z"),
        )
        for (transition in transitions) {
            val starts = transition.map { Instant.parse(it).epochSecond }
            val until = starts.last() + 5400
            for (days in listOf(7, 30)) {
                val since = until - days * 86_400L

                val activity = dataSource.getActivity("com.example.shopping", 0, since, until, BucketSize.DAY, zone)

                val args = "--since $since --until $until --bucket day --day-starts="
                val line = shell.lines.last()
                assertContains(line, args)
                val actual = line.substringAfter(args).substringBefore(' ').split(',').map(String::toLong)
                assertEquals(days + 1, actual.size)
                assertEquals(starts, actual.takeLast(starts.size))
                assertEquals(zone, activity.zone)
            }
        }
    }

    @Test
    fun givenSkippedDate_whenReadingDays_thenNoBarForTheMissingDateOrExclusiveEnd() = runTest {
        val shell = TestBbdRootShell(bbdFixture("net-activity"))
        val start = Instant.parse("2011-12-29T10:00:00Z").epochSecond
        val afterSkip = Instant.parse("2011-12-30T10:00:00Z").epochSecond
        val end = Instant.parse("2011-12-31T10:00:00Z").epochSecond

        RootShellBbdDataSource(shell, Dispatchers.Unconfined).getActivity(
            "com.example.shopping",
            0,
            start + 1,
            end,
            BucketSize.DAY,
            ZoneId.of("Pacific/Apia"),
        )

        assertContains(shell.lines.single(), "--day-starts=$start,$afterSkip ")
    }

    @Test
    fun givenDaysUpToNow_whenReadingActivityAndDestination_thenBothSendBoundsInTheRequestedZone() = runTest {
        val shell = TestBbdRootShell(bbdFixture("net-daily"))
        val dataSource = RootShellBbdDataSource(shell, Dispatchers.Unconfined)
        val zone = ZoneId.of("Asia/Kathmandu")
        val first = LocalDate.now(zone).minusDays(2).atStartOfDay(zone).toEpochSecond()
        val since = first + 3600
        val before = Instant.now().epochSecond

        dataSource.getActivity("com.example.shopping", 0, since, bucket = BucketSize.DAY, zone = zone)
        val detail = dataSource.getDestination("com.example.shopping", 0, NetworkTarget.Address("198.51.100.53"), since, BucketSize.DAY, zone)

        val after = Instant.now().epochSecond
        val dayArgs = Regex("--since $since --until (\\d+) --bucket day --day-starts=([0-9,]+) ")
        for (line in shell.lines) {
            val (_, end, starts) = checkNotNull(dayArgs.find(line)).groupValues
            val until = end.toLong()
            assertTrue(until in before..after)
            assertEquals(first, starts.substringBefore(',').toLong())
            assertEquals(
                Instant.ofEpochSecond(until - 1).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond(),
                starts.substringAfterLast(',').toLong(),
            )
        }
        assertEquals(zone, detail.zone)
    }

    @Test
    fun givenDaemonWithoutDayStarts_whenReadingDays_thenModuleOutdated() = runTest {
        val shell = TestBbdRootShell("", error = "error: unexpected argument '--day-starts=0' found")

        assertFailsWith<RecordsException.ModuleOutdated> {
            RootShellBbdDataSource(shell, Dispatchers.Unconfined).getActivity("com.example.shopping", 0, 0, 1, BucketSize.DAY, ZoneId.of("UTC"))
        }
    }

    @Test
    fun givenSiteWithManyAddresses_whenRead_thenCountsThoseNotListed() = runTest {
        val shell = TestBbdRootShell(
            bbdFixture("net-address-count"),
        )

        val site = RootShellBbdDataSource(shell, Dispatchers.Unconfined).getActivity("com.example.shopping", 0, 0).domains.single()

        assertEquals(40, site.addressCount)
        assertEquals(listOf("203.0.113.1"), site.addresses)
    }

    @Test
    fun givenRuleOnNameWithUnderscore_whenRead_thenListed() = runTest {
        val shell = TestBbdRootShell(
            bbdFixture("net-host-rule"),
        )

        val rule = RootShellBbdDataSource(shell, Dispatchers.Unconfined).getActivity("com.example.shopping", 0, 0).rules.single()

        assertEquals("host:a_b.example.com", rule.target.id)
    }

    @Test
    fun givenActivityWithMalformedDestination_whenRead_thenReportsBadResponse() = runTest {
        val shell = TestBbdRootShell(bbdFixture("net-malformed-destination"))
        val dataSource = RootShellBbdDataSource(shell, StandardTestDispatcher(testScheduler))

        assertFailsWith<RecordsException.BadResponse> { dataSource.getActivity("com.example.shopping", 0, 0) }
    }

    private suspend fun RootShellBbdDataSource.getActivity(
        packageName: String,
        userId: Int,
        since: Long,
        until: Long? = null,
        bucket: BucketSize = BucketSize.HOUR,
        zone: ZoneId = ZoneId.systemDefault(),
    ): NetworkActivity = getNetworkActivity(packageName, userId, since, until, bucket, zone).asExternalModel(zone)

    private suspend fun RootShellBbdDataSource.getDestination(
        packageName: String,
        userId: Int,
        target: NetworkTarget,
        since: Long,
        bucket: BucketSize = BucketSize.HOUR,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DestinationDetail = getNetworkDestination(packageName, userId, target, since, bucket, zone).asExternalModel(zone)
}
