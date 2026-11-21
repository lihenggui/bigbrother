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

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.bbd.BbdNetworkClearResult
import li.mercury.bigbrother.core.data.bbd.TestBbdDataSource
import li.mercury.bigbrother.core.model.NetworkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RootNetworkRepositoryTest {
    @Test
    fun givenSuccessfulWrites_whenRecordsAndRulesChange_thenObserversAreInvalidated() = runTest {
        val dataSource = object : TestBbdDataSource() {
            override suspend fun blockNetwork(packageName: String, userId: Int, target: NetworkTarget, manual: Boolean) = Unit

            override suspend fun unblockNetwork(packageName: String, userId: Int, target: NetworkTarget) = Unit

            override suspend fun clearNetwork(packageName: String, userId: Int): BbdNetworkClearResult = BbdNetworkClearResult(5_120)
        }
        val repository = RootNetworkRepository(dataSource, StandardTestDispatcher(testScheduler))
        val target = NetworkTarget.Domain("adnet.example")

        repository.block("com.example.shopping", 0, target)
        assertEquals(1, repository.changes.value)
        repository.unblock("com.example.shopping", 0, target)
        assertEquals(2, repository.changes.value)
        assertEquals(5_120, repository.clear("com.example.shopping", 0))
        assertEquals(3, repository.changes.value)
    }

    @Test
    fun givenWriteRefused_whenUnblocked_thenChangeIsNotCounted() = runTest {
        val dataSource = object : TestBbdDataSource() {
            override suspend fun unblockNetwork(packageName: String, userId: Int, target: NetworkTarget): Unit = throw RecordsException.CommandFailed("no such rule")
        }
        val repository = RootNetworkRepository(dataSource, StandardTestDispatcher(testScheduler))

        assertFailsWith<RecordsException.CommandFailed> {
            repository.unblock("com.example.shopping", 0, NetworkTarget.Domain("adnet.example"))
        }
        assertEquals(0, repository.changes.value)
    }
}
