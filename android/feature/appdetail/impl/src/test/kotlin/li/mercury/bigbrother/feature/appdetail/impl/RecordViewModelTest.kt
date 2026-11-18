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

package li.mercury.bigbrother.feature.appdetail.impl

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.SensorSession
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val session = SensorSession(
        sensor = "android.sensor.accelerometer",
        highRate = false,
        periodNs = 20_000_000,
        rateHz = 50.0,
        start = 100,
        end = 200,
    )
    private val period = AccessPeriod(Action.CALL, viaProvider = false, allowed = 1, denied = 0, time = 100)
    private val accessRepository = TestAccessRepository(
        accesses = listOf(
            call("binder:android.gui.SensorEventConnection/enableDisable@android.sensor.accelerometer", "sensors"),
            call("binder:android.content.IClipboard/getPrimaryClip", "clipboard"),
        ),
        history = listOf(period),
        sensorSessions = listOf(session),
    )

    private fun call(path: String, topic: String) = Access(
        category = Category.SERVICE,
        path = path,
        action = Action.CALL,
        viaProvider = false,
        items = 0,
        allowed = 1,
        denied = 0,
        lastSeen = 100,
        topic = topic,
    )

    private fun viewModel(topic: String) = RecordViewModel(
        accessRepository = accessRepository,
        appInfoRepository = TestAppInfoRepository(),
        uid = 10100,
        packageName = "com.example.steps",
        range = TimeRange.DAY,
        topic = topic,
        source = RecordSource.APP.name,
    )

    @Test
    fun givenSensorRecords_whenLoaded_thenSessionsAndHistoryShown() = runTest {
        val viewModel = viewModel("sensors")
        advanceUntilIdle()

        assertEquals(listOf(session), viewModel.uiState.value.sensorSessions)
        assertEquals(listOf(period), viewModel.uiState.value.periods)
    }

    @Test
    fun givenSessionsNotRead_whenSensorRecordLoaded_thenSessionsLeftOutAndHistoryShown() = runTest {
        accessRepository.sensorSessionsFailure = RecordsException.CommandFailed("failed")
        val viewModel = viewModel("sensors")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.sensorSessions)
        assertNull(viewModel.uiState.value.failure)
        assertEquals(listOf(period), viewModel.uiState.value.periods)
    }

    @Test
    fun givenClipboardRecords_whenLoaded_thenHistoryShownWithoutSessions() = runTest {
        val viewModel = viewModel("clipboard")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.sensorSessions)
        assertEquals(listOf(period), viewModel.uiState.value.periods)
        assertEquals(0, accessRepository.sensorSessionReads)
    }
}
