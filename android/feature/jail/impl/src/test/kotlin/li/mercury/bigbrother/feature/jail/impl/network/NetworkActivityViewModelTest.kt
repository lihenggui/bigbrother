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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.model.ActivityBucket
import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestNetworkRepository
import li.mercury.bigbrother.core.testing.repository.TestSandboxRepository
import org.junit.Rule
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkActivityViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val networkRepository = TestNetworkRepository(shopActivity())
    private val sandboxRepository = TestSandboxRepository(
        apps = listOf(SandboxedApp(UID, PACKAGE, enabled = true, created = 0, settings = emptyMap(), items = emptyMap())),
    )

    private fun TestScope.viewModel(): NetworkActivityViewModel {
        val viewModel = NetworkActivityViewModel(
            UID,
            PACKAGE,
            networkRepository,
            sandboxRepository,
            TestAppInfoRepository(),
            mainDispatcherRule.testDispatcher,
            currentUserId = 0,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private fun NetworkActivityViewModel.row(title: String) = with(uiState.value.shown) { (domains + addresses).single { it.title == title } }

    @Test
    fun givenManyDomains_whenLoaded_thenFirstSixShown() = runTest {
        val viewModel = viewModel()

        val shown = viewModel.uiState.value.shown
        assertEquals(24, shown.domainCount)
        assertEquals(6, shown.domains.size)
        assertEquals(14, shown.addressCount)
        assertEquals(6, shown.addresses.size)
        viewModel.showAllDomains()
        assertEquals(24, viewModel.uiState.value.shown.domains.size)
    }

    @Test
    fun givenDomainWithOneName_whenLoaded_thenRowIsTheNameAndBlocksItExactly() = runTest {
        val viewModel = viewModel()

        val row = viewModel.row("cdn.shopcdn.example")
        assertEquals(NetworkTarget.Domain("cdn.shopcdn.example", exact = true), row.toggle)
        // Several names: blocked one by one in the detail.
        assertNull(viewModel.row("example-shop.com").toggle)
        assertEquals(4, viewModel.row("example-shop.com").subdomains)
    }

    @Test
    fun givenBlocked_whenUndo_thenRuleRemoved() = runTest {
        val viewModel = viewModel()
        val target: NetworkTarget = NetworkTarget.Domain("cdn.shopcdn.example", exact = true)

        viewModel.block(target)
        assertTrue(viewModel.row("cdn.shopcdn.example").blocked)
        assertEquals(NetworkMessage.Blocked(target), viewModel.uiState.value.message)

        viewModel.undo()

        assertEquals(listOf(target to true, target to false), networkRepository.changed)
        assertFalse(networkRepository.activity.rules.any { it.target == target })
        assertFalse(viewModel.row("cdn.shopcdn.example").blocked)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun givenBlockFails_whenBlocked_thenRowRestoredAndErrorShown() = runTest {
        val viewModel = viewModel()
        networkRepository.changeFailure = RecordsException.ModuleOutdated()

        viewModel.block(NetworkTarget.Address("203.0.113.24"))

        assertFalse(viewModel.row("203.0.113.24").blocked)
        assertEquals(LoadFailure.ModuleOutdated, viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.pending.isEmpty())
    }

    @Test
    fun givenSortedByData_whenBlocked_thenOrderKept() = runTest {
        val viewModel = viewModel()
        val before = viewModel.uiState.value.shown.domains.map { it.title }

        viewModel.block(NetworkTarget.Domain("cdn.shopcdn.example", exact = true))

        assertEquals(before, viewModel.uiState.value.shown.domains.map { it.title })
        assertTrue(viewModel.row("cdn.shopcdn.example").blocked)
    }

    @Test
    fun givenBucketSelected_whenRangeChanged_thenSelectionCleared() = runTest {
        networkRepository.period = shopNight()
        val viewModel = viewModel()
        val night = shopActivity().buckets[12].start

        viewModel.selectBucket(night)
        assertEquals(night, viewModel.uiState.value.bucket)
        assertEquals(3, viewModel.uiState.value.totals.destinations)
        assertEquals(night to (night + 3600), networkRepository.asked.last())

        viewModel.setRange(TimeRange.WEEK)

        assertNull(viewModel.uiState.value.bucket)
        assertEquals(38, viewModel.uiState.value.totals.destinations)
    }

    @Test
    fun givenActivityShown_whenRangeChanged_thenPreviousActivityShownUntilNewOneLoads() = runTest {
        val viewModel = viewModel()
        val day = viewModel.uiState.value.activity
        val week = shopActivity().copy(since = shopActivity().since - 6 * 86_400, bucketSeconds = BucketSize.DAY.seconds)
        val gate = CompletableDeferred<Unit>()
        networkRepository.gate = gate
        networkRepository.activity = week

        viewModel.setRange(TimeRange.WEEK)

        assertEquals(TimeRange.WEEK, viewModel.uiState.value.range)
        assertEquals(day, viewModel.uiState.value.activity)
        gate.complete(Unit)
        assertEquals(week, viewModel.uiState.value.activity)
    }

    @Test
    fun givenRangeLoading_whenOldBarSelected_thenNewRangeRead() = runTest {
        val viewModel = viewModel()
        val week = shopActivity().copy(since = shopActivity().since - 6 * 86_400, bucketSeconds = BucketSize.DAY.seconds)
        val gate = CompletableDeferred<Unit>()
        networkRepository.gate = gate
        networkRepository.activity = week

        viewModel.setRange(TimeRange.WEEK)
        viewModel.selectBucket(shopActivity().buckets[12].start)
        gate.complete(Unit)

        assertEquals(week, viewModel.uiState.value.activity)
    }

    @Test
    fun givenDailyChartAcrossClockChange_whenBarSelected_thenQueriesOnlyThatBarsShownInterval() = runTest {
        val viewModel = viewModel()
        viewModel.setRange(TimeRange.WEEK)
        val transitions = listOf(
            "2026-03-08T08:00:00Z" to "2026-03-09T07:00:00Z",
            "2026-11-01T07:00:00Z" to "2026-11-02T08:00:00Z",
        )
        for ((dayStart, nextStart) in transitions) {
            val day = Instant.parse(dayStart).epochSecond
            val next = Instant.parse(nextStart).epochSecond
            val previous = day - 86_400
            val since = day - 5400
            val until = next + 1800
            networkRepository.activity = shopActivity().copy(
                since = since,
                until = until,
                bucketSeconds = BucketSize.DAY.seconds,
                buckets = listOf(previous, day, next).map { ActivityBucket(it, connections = 1, refused = 0) },
                zone = ZoneId.of("America/Los_Angeles"),
            )
            viewModel.refresh()

            for ((bar, start, end) in listOf(Triple(previous, since, day), Triple(day, day, next), Triple(next, next, until))) {
                networkRepository.asked.clear()
                viewModel.selectBucket(bar)

                assertEquals(start to end, networkRepository.asked.single())
                assertEquals(bar, viewModel.uiState.value.bucket)
            }
        }
    }

    @Test
    fun givenQuery_whenFiltered_thenHostsAndAddressesMatch() = runTest {
        val viewModel = viewModel()

        // A name under a domain finds the domain; an address of a domain finds it too.
        viewModel.setQuery("log.metrics")
        assertEquals(listOf("metrics.example"), viewModel.uiState.value.shown.domains.map { it.title })
        viewModel.setQuery("2001:db8::10")
        assertEquals(listOf("example-shop.com"), viewModel.uiState.value.shown.domains.map { it.title })
        viewModel.setQuery("198.51.100.53")
        assertEquals(listOf("198.51.100.53"), viewModel.uiState.value.shown.addresses.map { it.title })
        assertEquals(0, viewModel.uiState.value.shown.domainCount)
    }

    @Test
    fun givenBlockedOnly_whenUnblocked_thenRowStaysUntilFilterLeft() = runTest {
        val viewModel = viewModel()
        viewModel.setBlockedOnly(true)
        assertEquals(listOf("adnet.example", "track.example.com"), viewModel.uiState.value.shown.domains.map { it.title }.sorted())
        assertTrue(viewModel.row("track.example.com").noAttempts)

        viewModel.unblock(NetworkTarget.Domain("adnet.example"))

        assertFalse(viewModel.row("adnet.example").blocked)
        viewModel.setBlockedOnly(false)
        viewModel.setBlockedOnly(true)
        assertEquals(listOf("track.example.com"), viewModel.uiState.value.shown.domains.map { it.title })
    }

    @Test
    fun givenRecording_whenStopped_thenRecordingItemTurnedOff() = runTest {
        val viewModel = viewModel()

        viewModel.setRecording(false)

        assertEquals(SandboxMode.OFF, sandboxRepository.apps.value?.single()?.settings?.get(SandboxItem.NETWORK_RECORD))
    }

    @Test
    fun givenRecords_whenCleared_thenReadAgainWithRulesKept() = runTest {
        val viewModel = viewModel()

        viewModel.clearRecords()

        assertEquals(listOf(PACKAGE), networkRepository.cleared)
        assertEquals(0, viewModel.uiState.value.totals.destinations)
        assertEquals(3, viewModel.uiState.value.blockedCount)
    }

    @Test
    fun givenModuleWithoutNetCommands_whenLoaded_thenModuleUpdateNeeded() = runTest {
        networkRepository.failure = RecordsException.ModuleOutdated()

        val viewModel = viewModel()

        assertEquals(LoadFailure.ModuleOutdated, viewModel.uiState.value.failure)
    }

    private companion object {
        const val UID = 10204
        const val PACKAGE = "com.example.shopping"
    }
}
