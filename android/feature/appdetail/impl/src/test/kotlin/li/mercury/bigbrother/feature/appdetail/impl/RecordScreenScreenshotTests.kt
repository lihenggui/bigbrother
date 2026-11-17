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

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoApp
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RecordScreenScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenSensorsTurnedOn_whenShownOnPhone_thenMatchesGolden() {
        capture("phone", DefaultTestDevices.PHONE.spec)
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenSensorsTurnedOn_whenShownOnFoldable_thenMatchesGolden() {
        capture("foldable", DefaultTestDevices.FOLDABLE.spec)
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenSensorsTurnedOn_whenShownInChinese_thenMatchesGolden() {
        capture("phone_zh", DefaultTestDevices.PHONE.spec)
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenClipboardReadAndWritten_whenShownOnPhone_thenMatchesGolden() {
        composeTestRule.captureForDevice(
            deviceName = "phone",
            deviceSpec = DefaultTestDevices.PHONE.spec,
            screenshotName = "ClipboardRecord",
        ) {
            Record(DemoApp.KEYBOARD, CLIPBOARD)
        }
    }

    private fun capture(deviceName: String, deviceSpec: String) {
        composeTestRule.captureForDevice(deviceName = deviceName, deviceSpec = deviceSpec, screenshotName = "SensorsRecord") {
            Record(DemoApp.STEP_COUNTER, SENSORS)
        }
    }

    /** The record sheet of one of [app]'s kinds of data, from its own records. */
    @Composable
    private fun Record(app: DemoApp, topic: String) {
        val records = remember { DemoRecords(clock) }
        val group = remember {
            groupByCategory(records.accesses(app)).values.flatten()
                .first { it.topic == topic && it.source == RecordSource.APP }
        }
        RecordScreen(
            state = RecordUiState(
                appInfo = records.appInfo.getValue(app.packageName),
                group = group,
                periods = records.history(app, topic),
                sensorSessions = records.sensorSessions(app).takeIf { topic == SENSORS },
                loaded = true,
            ),
            uid = app.uid,
            packageName = app.packageName,
            onClose = {},
            onRetry = {},
        )
    }

    private companion object {
        const val SENSORS = "sensors"
        const val CLIPBOARD = "clipboard"
    }
}
