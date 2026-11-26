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
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import li.mercury.bigbrother.core.data.repository.ModuleInstallation
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.RecorderStatus
import li.mercury.bigbrother.core.model.ThemeMode
import li.mercury.bigbrother.core.model.UserData
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.assertSamplesCover
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureMultiDevice
import li.mercury.bigbrother.core.screenshottesting.screenshotName
import org.junit.Assert.assertEquals
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
        composeTestRule.captureMultiDevice("Settings") { Settings(SettingsUiState(status = RECORDING)) }
    }

    @Test
    fun givenDisplaySettings_whenFontScaleIsDoubled_thenMatchesGolden() {
        composeTestRule.captureForDevice("phone_large_text", DefaultTestDevices.PHONE.spec, "SettingsDisplay", fontScale = 2f, beforeCapture = {
            val theme = composeTestRule.activity.getString(R.string.theme)
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(theme))
        }) {
            Settings(SettingsUiState(status = RECORDING))
        }
    }

    @Test
    fun givenSystemTheme_whenPickerOpensWithLargeText_thenDarkCanBeSelected() {
        var selected: ThemeMode? = null
        val inDialog = hasAnyAncestor(isDialog())
        composeTestRule.captureForDevice("phone_large_text", DefaultTestDevices.PHONE.spec, "SettingsTheme", fontScale = 2f, beforeCapture = {
            val activity = composeTestRule.activity
            val theme = activity.getString(R.string.theme)
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(theme))
            composeTestRule.onNodeWithText(theme).performClick()
            composeTestRule.onNode(hasText(activity.getString(R.string.theme_system)) and inDialog).assertIsSelected()
        }) {
            Settings(SettingsUiState(status = RECORDING), onThemeMode = { selected = it })
        }
        composeTestRule.onNode(hasText(composeTestRule.activity.getString(R.string.theme_dark)) and inDialog).performClick()
        composeTestRule.runOnIdle { assertEquals(ThemeMode.DARK, selected) }
    }

    @Test
    fun givenRecording_whenShownInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice("phone_dark", DefaultTestDevices.PHONE.spec, "Settings", darkMode = true) {
            Settings(SettingsUiState(status = RECORDING))
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenRecording_whenShownInChinese_thenMatchesGolden() {
        composeTestRule.captureForDevice("phone_zh", DefaultTestDevices.PHONE.spec, "Settings") {
            Settings(SettingsUiState(status = RECORDING))
        }
    }

    @Test
    fun givenStatusNotYetRead_whenShown_thenSaysItIsUnknown() {
        capturePhone("SettingsStatusUnknown") { Settings(SettingsUiState()) }
    }

    @Test
    fun givenRecordingStopped_whenShown_thenSaysSo() {
        capturePhone("SettingsStopped") {
            Settings(SettingsUiState(status = RecorderStatus(recording = false)))
        }
    }

    @Test
    fun givenKindsNotRecordedOnDevice_whenShown_thenNamesThem() {
        capturePhone("SettingsCoverage") {
            // An id this build has no name for is shown as it is.
            val status = RecorderStatus(recording = true, unavailable = listOf("network", "binder", "kprobe"))
            Settings(SettingsUiState(status = status))
        }
    }

    @Test
    fun givenSwitchesOn_whenShown_thenSwitchesShowIt() {
        capturePhone("SettingsSwitchesOn") {
            val userData = UserData(showSystemApps = true, useDynamicColor = true)
            Settings(SettingsUiState(userData = userData, status = RECORDING))
        }
    }

    @Test
    fun givenNothingInstalled_whenShown_thenSaysRecordingIsNotSetUp() {
        capturePhone("SettingsNotSetUp") {
            Settings(SettingsUiState(moduleVersionFailure = LoadFailure.ModuleMissing, recorderStatusFailure = LoadFailure.ModuleMissing))
        }
    }

    @Test
    fun givenModuleFromRootManager_whenShown_thenSaysItIsRemovedThere() {
        capturePhone("SettingsModuleInManager") {
            Settings(SettingsUiState(status = RECORDING, installation = ModuleInstallation.MANAGER))
        }
    }

    @Test
    fun givenModuleStagedInRootManager_whenShown_thenSaysItTakesEffectAfterRestart() {
        capturePhone("SettingsModuleInManagerPending") {
            Settings(SettingsUiState(status = RECORDING, installation = ModuleInstallation.MANAGER_PENDING))
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
                Settings(SettingsUiState(status = RECORDING, logExport = export))
            }
        }
    }

    @Test
    fun givenEachModuleRemovalStep_whenShown_thenSaysHowItGoes() {
        for (removal in MODULE_REMOVALS) {
            capturePhone("SettingsModuleRemoval${removal.screenshotName}") {
                Settings(SettingsUiState(status = RECORDING, installation = ModuleInstallation.APP, moduleRemoval = removal))
            }
        }
    }

    @Test
    fun givenModuleRemovalCases_whenChecked_thenEveryStepHasOne() {
        assertSamplesCover(ModuleRemoval::class.java, MODULE_REMOVALS)
    }

    @Test
    fun givenRemovalAsked_whenShown_thenDialogSaysWhatIsLost() {
        capturePhone("SettingsRemoveModuleDialog") {
            Settings(SettingsUiState(status = RECORDING, installation = ModuleInstallation.APP), confirmingRemoval = true)
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
    private fun Settings(state: SettingsUiState, confirmingRemoval: Boolean = false, onThemeMode: (ThemeMode) -> Unit = {}) {
        SettingsScreen(
            state = state,
            appVersion = APP_VERSION,
            onBack = {},
            onShowSystemApps = {},
            onUseDynamicColor = {},
            onThemeMode = onThemeMode,
            onExportLogs = {},
            onDismissLogExport = {},
            onRemoveModule = {},
            initiallyConfirmingRemoval = confirmingRemoval,
        )
    }

    private companion object {
        const val APP_VERSION = "1.2.3"
        val RECORDING = RecorderStatus(recording = true)
        val LOG_EXPORTS = listOf(LogExport.Idle, LogExport.Collecting, LogExport.Failed(LoadFailure.Operation.LOG_EXPORT_FAILED))
        val MODULE_REMOVALS = listOf(ModuleRemoval.Idle, ModuleRemoval.Removing, ModuleRemoval.Failed(LoadFailure.Operation.MODULE_REMOVE_FAILED))
    }
}
