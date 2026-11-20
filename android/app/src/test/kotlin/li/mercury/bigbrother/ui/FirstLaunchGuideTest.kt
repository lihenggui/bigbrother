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

package li.mercury.bigbrother.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import li.mercury.bigbrother.R
import li.mercury.bigbrother.core.designsystem.theme.BigBrotherTheme
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class FirstLaunchGuideTest {
    @get:Rule(order = 0)
    val registration = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun givenFirstPage_whenSkipped_thenDismissesImmediately() {
        var dismissed = 0
        compose.setContent {
            BigBrotherTheme { FirstLaunchGuide(onDismiss = { dismissed++ }) }
        }
        click(R.string.guide_skip)
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun givenFirstPage_whenIllustrationIsSwiped_thenTimelineIsShown() {
        compose.setContent { BigBrotherTheme { FirstLaunchGuide(onDismiss = {}) } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.guide_guard_description))
            .performTouchInput { swipeLeft() }
        visible(R.string.guide_timeline_title)
    }

    @Test
    fun givenThirdPage_whenAnimatingToLastPage_thenStartWaitsForThePageToSettle() {
        compose.setContent {
            BigBrotherTheme {
                FirstLaunchGuide(
                    onDismiss = {},
                    pager = rememberPagerState(initialPage = GuidePage.Jail.ordinal) { GuidePage.entries.size },
                )
            }
        }
        compose.mainClock.autoAdvance = false
        click(R.string.guide_next)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(compose.activity.getString(R.string.guide_start)).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        visible(R.string.guide_start)
    }

    @Test
    fun givenGuideInProgress_whenRestored_thenContinuesAndFinishesOnFourthPage() {
        var dismissed = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            BigBrotherTheme { FirstLaunchGuide(onDismiss = { dismissed++ }) }
        }
        click(R.string.guide_next)
        restoration.emulateSavedInstanceStateRestore()
        visible(R.string.guide_timeline_title)
        click(R.string.guide_next)
        visible(R.string.guide_jail_title)
        click(R.string.guide_next)
        visible(R.string.guide_framework_title)
        click(R.string.guide_start)
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenChineseLocale_whenShownOnPhone_thenEachPageMatchesItsDesign() {
        val device = DefaultTestDevices.PHONE
        for (page in GuidePage.entries) {
            compose.captureForDevice(device.description, device.spec, "FirstLaunch${page.name}Chinese") {
                FirstLaunchGuide(
                    onDismiss = {},
                    pager = rememberPagerState(initialPage = page.ordinal) { GuidePage.entries.size },
                )
            }
        }
    }

    @Test
    fun givenFrameworkPage_whenWindowSizeChanges_thenProtectionsAndSetupAreVisible() {
        for (device in listOf(DefaultTestDevices.PHONE, DefaultTestDevices.TABLET)) {
            compose.captureForDevice(device.description, device.spec, "FirstLaunchFramework") {
                FirstLaunchGuide(
                    onDismiss = {},
                    pager = rememberPagerState(initialPage = GuidePage.Framework.ordinal) { GuidePage.entries.size },
                )
            }
        }
    }

    private fun click(id: Int) = compose.onNodeWithText(compose.activity.getString(id)).performClick()

    private fun visible(id: Int) = compose.onNodeWithText(compose.activity.getString(id)).assertIsDisplayed()
}
