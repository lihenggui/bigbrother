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
import li.mercury.bigbrother.core.screenshottesting.StoreScreenshotType
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureStoreScreenshot
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
class AppFilesScreenScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenPhotosRead_whenFolderShownInEnglish_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "4_files") { CameraFolder() }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenPhotosRead_whenFolderShownInChinese_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "4_files") { CameraFolder() }
    }

    @Test
    fun givenPhotosRead_whenFolderShownInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice(
            deviceName = "phone_dark",
            deviceSpec = DefaultTestDevices.PHONE.spec,
            screenshotName = "AppFiles",
            darkMode = true,
        ) {
            CameraFolder()
        }
    }

    /** The weather app's files, open at the camera folder, as from its page. */
    @Composable
    private fun CameraFolder() {
        val records = remember { DemoRecords(clock) }
        AppFilesScreen(
            state = AppFilesUiState(
                packageName = DemoApp.WEATHER.packageName,
                appInfo = records.appInfo.getValue(DemoApp.WEATHER.packageName),
                accesses = records.accesses(DemoApp.WEATHER).filter { it.isFile() },
            ),
            uid = DemoApp.WEATHER.uid,
            initialLocation = "/storage/emulated/0/DCIM/Camera",
            onBack = {},
            onRetry = {},
            onFileDetails = {},
            onDismissFileDetails = {},
            onOpenFile = {},
        )
    }
}
