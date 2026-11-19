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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.CommandResult
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.model.HostActivity
import li.mercury.bigbrother.core.model.NetworkRule
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.PortActivity
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Robolectric for org.json, which the answers are read with.
@RunWith(RobolectricTestRunner::class)
class RootNetworkRepositoryTest {
    @Test
    fun givenActivity_whenRead_thenDomainsAddressesAndRulesParsed() = runTest {
        val shell = TestRootShell(ACTIVITY)

        val activity = RootNetworkRepository(shell).getActivity("com.example.shopping", userId = 0, since = 1_763_393_520)

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
        val shell = TestRootShell(ACTIVITY.replace("\"allowed\"", "\"blocked\""))

        assertFalse(RootNetworkRepository(shell).getActivity("com.example.shopping", 0, 0).networkAllowed)
    }

    @Test
    fun givenPackageAndTarget_whenBlocked_thenBothQuotedAndChangeCounted() = runTest {
        val shell = TestRootShell("""{"ok":true}""")
        val repository = RootNetworkRepository(shell)

        repository.block("com.example.it's", userId = 10, NetworkTarget.Address("2001:db8::5", 443), manual = true)

        val line = shell.lines.single()
        assertTrue("net block 'com.example.it'\\''s' 'ip:[2001:db8::5]:443' --manual --user 10 --json" in line, line)
        assertEquals("bbd net block", shell.labels.single())
        assertEquals(1, repository.changes.value)
    }

    @Test
    fun givenDaemonWithoutNetCommands_whenRead_thenModuleOutdated() = runTest {
        val shell = TestRootShell("", error = "error: unrecognized subcommand 'net'")

        assertFailsWith<RecordsException.ModuleOutdated> { RootNetworkRepository(shell).getActivity("com.example.shopping", 0, 0) }
    }

    @Test
    fun givenDaemonRefusing_whenUnblocked_thenFailsWithItsReasonAndNothingCounted() = runTest {
        val shell = TestRootShell("""{"ok":false,"error":"no such rule"}""")
        val repository = RootNetworkRepository(shell)

        val failure = assertFailsWith<RecordsException.CommandFailed> {
            repository.unblock("com.example.shopping", 0, NetworkTarget.Domain("adnet.example"))
        }
        assertEquals("no such rule", failure.message)
        assertEquals(0, repository.changes.value)
    }

    @Test
    fun givenRecords_whenCleared_thenCountOfDeleted() = runTest {
        val shell = TestRootShell("""{"ok":true,"deleted":5120}""")

        assertEquals(5_120, RootNetworkRepository(shell).clear("com.example.shopping", 0))
    }

    @Test
    fun givenDestination_whenRead_thenWithItsHours() = runTest {
        val shell = TestRootShell(
            """{"ok":true,"addr":"198.51.100.53","ports":[{"port":8443,"conn":41,"refused":0}],"conn":41,"refused":0,
              |"first":1,"last":2,"rule":null,"hours":[{"t":3600,"conn":4,"refused":1}]}
            """.trimMargin(),
        )

        val detail = RootNetworkRepository(shell).getDestination("com.example.shopping", 0, NetworkTarget.Address("198.51.100.53"), since = 0)

        assertEquals(NetworkTarget.Address("198.51.100.53"), detail.destination.target)
        assertEquals(3_600L, detail.hours.single().start)
        assertTrue("'ip:198.51.100.53' --since 0" in shell.lines.single())
    }

    /** Answers every command with [output], or fails it with [error] if set. */
    private class TestRootShell(private val output: String, private val error: String? = null) : RootShell(Dispatchers.Unconfined) {
        val lines = mutableListOf<String>()
        val labels = mutableListOf<String>()

        override suspend fun run(command: String, label: String): CommandResult {
            lines += command
            labels += label
            return if (error != null) {
                CommandResult(isSuccess = false, code = 2, output = "", error = error)
            } else {
                CommandResult(isSuccess = true, code = 0, output = output, error = "")
            }
        }
    }

    private companion object {
        val ACTIVITY = """
            {"ok":true,"uid":10234,"since":1763393520,"until":1763479920,"bucket":3600,
             "recording":{"on":true,"since":1763277120},"network":"allowed","traffic":true,"names":true,
             "buckets":[{"t":1763393520,"conn":28,"refused":9}],
             "destinations":[
              {"site":"example-shop.com","hosts":[{"name":"api.example-shop.com","conn":300,"refused":0,"sent":1000,"recv":2000,"rule":null},
                {"name":"img.example-shop.com","conn":212,"refused":0,"sent":153434,"recv":19081878,"rule":"host:img.example-shop.com"}],
               "addrs":["203.0.113.10","2001:db8::10"],
               "ports":[{"port":443,"conn":512,"refused":0,"sent":1153434,"recv":19083878}],
               "conn":512,"refused":0,"sent":1153434,"recv":19083878,"first":1763277300,"last":1763479860,"rule":null},
              {"addr":"2001:DB8::53","ports":[{"port":8443,"conn":41,"refused":0,"rule":"ip:[2001:db8::53]:8443"}],
               "conn":41,"refused":0,"first":1763393600,"last":1763479800,"rule":null}],
             "rules":[{"target":"domain:adnet.example","created":1763400000,"manual":false},
                      {"target":"cidr:203.0.113.0/24","created":1763460000,"manual":true},
                      {"target":"ip:203.0.113.81:8080","created":1763420001,"manual":true}]}
        """.trimIndent()
    }
}
