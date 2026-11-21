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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestNetworkRepository
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DestinationViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val target: NetworkTarget = NetworkTarget.Domain("example-shop.com")
    private val repository = TestNetworkRepository(shopActivity())

    @Test
    fun givenDomainWithSharedAddresses_whenLoaded_thenShowsOtherDomainsAndOwnResolver() = runTest {
        val viewModel = collectState()
        runCurrent()

        assertEquals(target, viewModel.uiState.value.destination?.target)
        assertEquals(listOf("metrics.example"), viewModel.uiState.value.sharedWith)
        assertTrue(viewModel.uiState.value.ownResolver)
    }

    @Test
    fun givenUnknownTarget_whenOpened_thenShowsMissingDestination() = runTest {
        val viewModel = collectState("unknown:example-shop.com")
        runCurrent()

        assertEquals(LoadFailure.Operation.DESTINATION_MISSING, viewModel.uiState.value.failure)
        assertNull(viewModel.uiState.value.target)
        assertNull(viewModel.uiState.value.detail)
    }

    @Test
    fun givenDomain_whenBlockedThenUndone_thenClearsRuleAndMessage() = runTest {
        val viewModel = collectState()
        runCurrent()

        viewModel.block(target)
        runCurrent()

        assertEquals(target, viewModel.uiState.value.destination?.rule)
        assertEquals(NetworkMessage.Blocked(target), viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.pending.isEmpty())

        viewModel.undo()
        runCurrent()

        assertEquals(listOf(target to true, target to false), repository.changed)
        assertNull(viewModel.uiState.value.destination?.rule)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun givenRuleWriteFailure_whenBlocked_thenRevertsThePendingChange() = runTest {
        val viewModel = collectState()
        runCurrent()
        repository.changeFailure = RecordsException.ModuleOutdated()

        viewModel.block(target)
        runCurrent()

        assertEquals(target, viewModel.uiState.value.destination?.target)
        assertNull(viewModel.uiState.value.destination?.rule)
        assertTrue(viewModel.uiState.value.pending.isEmpty())
        assertEquals(LoadFailure.ModuleOutdated, viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.message)
    }

    private fun TestScope.collectState(targetId: String = target.id): DestinationViewModel {
        val viewModel = DestinationViewModel(10204, "com.example.shopping", targetId, TimeRange.DAY, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }
}
