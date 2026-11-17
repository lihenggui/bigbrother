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
import li.mercury.bigbrother.core.model.TimeRange
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
class AppDetailScreenScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenAppThatReadPhotos_whenShownInEnglish_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "2_app_detail") { AppPage() }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenAppThatReadPhotos_whenShownInChinese_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "2_app_detail") { AppPage() }
    }

    @Test
    fun givenAppThatReadPhotos_whenShownInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice(
            deviceName = "phone_dark",
            deviceSpec = DefaultTestDevices.PHONE.spec,
            screenshotName = "AppDetail",
            darkMode = true,
        ) {
            AppPage()
        }
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenAppThatReadTheClipboard_whenShown_thenMatchesGolden() {
        composeTestRule.captureForDevice(
            deviceName = "phone",
            deviceSpec = DefaultTestDevices.PHONE.spec,
            screenshotName = "AppDetailClipboard",
        ) {
            AppPage(DemoApp.SHOPPING)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenAppThatTurnedSensorsOn_whenShown_thenMatchesGolden() {
        composeTestRule.captureForDevice(
            deviceName = "phone",
            deviceSpec = DefaultTestDevices.PHONE.spec,
            screenshotName = "AppDetailSensors",
        ) {
            AppPage(DemoApp.STEP_COUNTER)
        }
    }

    /** The weather app's page, the demo app that did the most, or another [app]'s. */
    @Composable
    private fun AppPage(app: DemoApp = DemoApp.WEATHER) {
        val records = remember { DemoRecords(clock) }
        AppDetailScreen(
            state = AppDetailUiState(
                app = records.summary(app),
                appInfo = records.appInfo.getValue(app.packageName),
                accesses = records.accesses(app),
                range = TimeRange.DAY,
                packageDetails = records.packageDetails,
            ),
            focusCategory = null,
            openRecord = null,
            onBack = {},
            onRetry = {},
            onOpenFiles = {},
            onOpenRecord = { _, _ -> },
        )
    }
}
