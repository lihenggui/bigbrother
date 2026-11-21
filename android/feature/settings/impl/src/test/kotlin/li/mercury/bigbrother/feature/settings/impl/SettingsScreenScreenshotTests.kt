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

package li.mercury.bigbrother.feature.settings.impl

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.RecorderStatus
import li.mercury.bigbrother.core.model.UserData
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.assertSamplesCover
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureMultiDevice
import li.mercury.bigbrother.core.screenshottesting.screenshotName
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
class SettingsScreenScreenshotTests {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun givenRecording_whenShownOnEachDevice_thenMatchesGolden() {
        composeTestRule.captureMultiDevice("Settings") { Settings(SettingsUiState(status = RECORDING, moduleVersion = VERSION)) }
    }

    @Test
    fun givenRecording_whenShownInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice("phone_dark", DefaultTestDevices.PHONE.spec, "Settings", darkMode = true) {
            Settings(SettingsUiState(status = RECORDING, moduleVersion = VERSION))
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenRecording_whenShownInChinese_thenMatchesGolden() {
        composeTestRule.captureForDevice("phone_zh", DefaultTestDevices.PHONE.spec, "Settings") {
            Settings(SettingsUiState(status = RECORDING, moduleVersion = VERSION))
        }
    }

    @Test
    fun givenStatusNotYetRead_whenShown_thenSaysItIsUnknown() {
        capturePhone("SettingsStatusUnknown") { Settings(SettingsUiState()) }
    }

    @Test
    fun givenRecordingStopped_whenShown_thenSaysSo() {
        capturePhone("SettingsStopped") {
            Settings(SettingsUiState(status = RecorderStatus(recording = false), moduleVersion = VERSION))
        }
    }

    @Test
    fun givenKindsNotRecordedOnDevice_whenShown_thenNamesThem() {
        capturePhone("SettingsCoverage") {
            // An id this build has no name for is shown as it is.
            val status = RecorderStatus(recording = true, unavailable = listOf("network", "binder", "kprobe"))
            Settings(SettingsUiState(status = status, moduleVersion = VERSION))
        }
    }

    @Test
    fun givenSwitchesOn_whenShown_thenSwitchesShowIt() {
        capturePhone("SettingsSwitchesOn") {
            val userData = UserData(showSystemApps = true, useDynamicColor = true)
            Settings(SettingsUiState(userData = userData, status = RECORDING, moduleVersion = VERSION))
        }
    }

    @Test
    fun givenRecordingDetailsReadFailure_whenShown_thenShowsReason() {
        capturePhone("SettingsRecordingDetailsFailure") {
            Settings(
                SettingsUiState(
                    moduleVersionFailure = LoadFailure.Operation.MODULE_VERSION_UNREADABLE,
                    recorderStatusFailure = LoadFailure.Operation.DAEMON_UNAVAILABLE,
                ),
            )
        }
    }

    @Test
    fun givenEachLogExportStep_whenShown_thenSaysHowItGoes() {
        for (export in LOG_EXPORTS) {
            capturePhone("SettingsLogExport${export.screenshotName}") {
                Settings(SettingsUiState(status = RECORDING, moduleVersion = VERSION, logExport = export))
            }
        }
    }

    @Test
    fun givenLogExportCases_whenChecked_thenEveryStepHasOne() {
        // Ready opens the share sheet and goes back to Idle at once: it has no look of its own.
        assertSamplesCover(LogExport::class.java, LOG_EXPORTS, excluded = setOf(LogExport.Ready::class.java))
    }

    private fun capturePhone(name: String, body: @Composable () -> Unit) {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, name, body = body)
    }

    @Composable
    private fun Settings(state: SettingsUiState) {
        SettingsScreen(
            state = state,
            appVersion = APP_VERSION,
            onBack = {},
            onShowSystemApps = {},
            onUseDynamicColor = {},
            onExportLogs = {},
            onDismissLogExport = {},
        )
    }

    private companion object {
        const val APP_VERSION = "1.2.3"
        const val VERSION = "v$APP_VERSION"
        val RECORDING = RecorderStatus(recording = true)
        val LOG_EXPORTS = listOf(LogExport.Idle, LogExport.Collecting, LogExport.Failed(LoadFailure.Operation.LOG_EXPORT_FAILED))
    }
}
