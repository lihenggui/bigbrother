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
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoApp
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoLoadFailure
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.screenshotName
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.LocalDate

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

    @Test
    fun givenRecordOpening_whenShown_thenShowsLoadingIndicator() {
        capturePhone("RecordLoading") { Record(DemoApp.STEP_COUNTER, SENSORS) { RecordUiState(appInfo = it.appInfo) } }
    }

    @Test
    fun givenLoadFailure_whenShown_thenSaysWhy() {
        val failure = DemoLoadFailure
        capturePhone("RecordFailure${failure.screenshotName}") {
            Record(DemoApp.STEP_COUNTER, SENSORS) { RecordUiState(appInfo = it.appInfo, loaded = true, failure = failure) }
        }
    }

    @Test
    fun givenRecordsAgedOut_whenShown_thenSaysNothingIsRecorded() {
        capturePhone("RecordGone") { Record(DemoApp.STEP_COUNTER, SENSORS) { RecordUiState(appInfo = it.appInfo, loaded = true) } }
    }

    @Test
    fun givenHistoryLoading_whenShown_thenShowsRecordAndLoadingIndicator() {
        capturePhone("RecordHistoryLoading") { Record(DemoApp.KEYBOARD, CLIPBOARD) { it.copy(periods = null) } }
    }

    @Test
    fun givenHistoryFailed_whenShown_thenOffersToRetry() {
        capturePhone("RecordHistoryFailure") {
            Record(DemoApp.KEYBOARD, CLIPBOARD) { it.copy(periods = null, historyFailure = LoadFailure.Other("database is locked")) }
        }
    }

    @Test
    fun givenHistoryOfSomePaths_whenShown_thenSaysHistoryIsPartial() {
        capturePhone("RecordHistoryPartial") { Record(DemoApp.KEYBOARD, CLIPBOARD) { it.copy(partialHistory = true) } }
    }

    @Test
    fun givenManyCommandsRun_whenShown_thenListsFirstAndOffersTheRest() {
        capturePhone("RecordManyDetails") {
            val records = remember { DemoRecords(clock) }
            val accesses = PROPERTIES.mapIndexed { index, property ->
                Access(
                    category = Category.COMMAND,
                    path = "exec:getprop $property",
                    action = Action.EXEC,
                    viaProvider = false,
                    items = 0,
                    allowed = 1,
                    denied = 0,
                    lastSeen = clock.todayAt(12, 20 + index),
                    topic = "system_properties",
                )
            }
            val group = groupByCategory(accesses).values.flatten().single()
            RecordScreen(
                state = RecordUiState(
                    appInfo = records.appInfo.getValue(DemoApp.NEWS.packageName),
                    group = group,
                    periods = accesses.map { AccessPeriod(it.action, it.viaProvider, it.allowed, it.denied, it.lastSeen, it.path) },
                    loaded = true,
                ),
                uid = DemoApp.NEWS.uid,
                packageName = DemoApp.NEWS.packageName,
                onClose = {},
                onRetry = {},
            )
        }
    }

    private fun capture(deviceName: String, deviceSpec: String) {
        composeTestRule.captureForDevice(deviceName = deviceName, deviceSpec = deviceSpec, screenshotName = "SensorsRecord") {
            Record(DemoApp.STEP_COUNTER, SENSORS)
        }
    }

    private fun capturePhone(name: String, body: @Composable () -> Unit) {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, name, body = body)
    }

    /** The record sheet of one of [app]'s kinds of data, from its own records, in the state [state] makes of it. */
    @Composable
    private fun Record(app: DemoApp, topic: String, state: (RecordUiState) -> RecordUiState = { it }) {
        val records = remember { DemoRecords(clock) }
        val group = remember {
            groupByCategory(records.accesses(app)).values.flatten()
                .first { it.topic == topic && it.source == RecordSource.APP }
        }
        RecordScreen(
            state = state(
                RecordUiState(
                    appInfo = records.appInfo.getValue(app.packageName),
                    group = group,
                    periods = records.history(app, topic, HISTORY_DAY),
                    sensorSessions = records.sensorSessions(app).takeIf { topic == SENSORS },
                    loaded = true,
                ),
            ),
            uid = app.uid,
            packageName = app.packageName,
            onClose = {},
            onRetry = {},
        )
    }

    private companion object {
        /** The day the list of each access is on: the list shows the date. */
        val HISTORY_DAY: LocalDate = LocalDate.of(2026, 11, 16)

        const val SENSORS = "sensors"
        const val CLIPBOARD = "clipboard"

        /** System properties an app read one by one: more than the sheet lists before asking. */
        val PROPERTIES = listOf(
            "ro.product.model",
            "ro.product.brand",
            "ro.product.manufacturer",
            "ro.build.fingerprint",
            "ro.build.version.release",
            "ro.build.version.sdk",
            "ro.build.version.security_patch",
            "ro.hardware",
            "ro.boot.verifiedbootstate",
            "ro.debuggable",
            "ro.secure",
            "persist.sys.timezone",
        )
    }
}
