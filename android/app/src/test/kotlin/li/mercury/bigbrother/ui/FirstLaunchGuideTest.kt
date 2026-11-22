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
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
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
    fun givenSecondPage_whenAnimatingToLastPage_thenStartWaitsForTheLastPage() {
        compose.setContent {
            BigBrotherTheme {
                FirstLaunchGuide(
                    onDismiss = {},
                    pager = rememberPagerState(initialPage = GuidePage.Timeline.ordinal) { GuidePage.entries.size },
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
    fun givenGuideInProgress_whenRestored_thenContinuesAndFinishesOnThirdPage() {
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
    fun givenFirstPage_whenShownInDarkTheme_thenTheSearchlightMatchesItsDesign() {
        val device = DefaultTestDevices.PHONE
        compose.captureForDevice(device.description, device.spec, "FirstLaunchAccessDark", darkMode = true) {
            FirstLaunchGuide(onDismiss = {})
        }
    }

    @Test
    fun givenJailPage_whenShownOnTablet_thenArtAndCopyAreSideBySide() {
        val device = DefaultTestDevices.TABLET
        compose.captureForDevice(device.description, device.spec, "FirstLaunchJail") {
            FirstLaunchGuide(
                onDismiss = {},
                pager = rememberPagerState(initialPage = GuidePage.Jail.ordinal) { GuidePage.entries.size },
            )
        }
    }

    @Test
    fun givenSecondPage_whenPredictiveBackCancelsThenCommits_thenPagerSettlesOnTheExpectedPage() {
        lateinit var pager: PagerState
        compose.setContent {
            BigBrotherTheme {
                pager = rememberPagerState(initialPage = 1) { GuidePage.entries.size }
                FirstLaunchGuide(onDismiss = {}, pager = pager)
            }
        }
        swipeBack()
        compose.runOnIdle {
            assertEquals(GuidePage.Timeline.ordinal, pager.settledPage)
            check(pager.currentPageOffsetFraction < 0f)
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        visible(R.string.guide_timeline_title)
        compose.runOnIdle { assertEquals(0f, pager.currentPageOffsetFraction, 0.001f) }

        swipeBack()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        visible(R.string.guide_access_title)
        compose.runOnIdle { assertEquals(GuidePage.Access.ordinal, pager.settledPage) }
    }

    @Test
    fun givenFirstPage_whenPredictiveBackCancelsThenCommits_thenOverviewIsPreviewedAndShown() {
        val showGuide = mutableStateOf(true)
        compose.setContent {
            BigBrotherTheme {
                FirstLaunchContent(showGuide.value, onDismiss = { showGuide.value = false }) { Text("Overview content") }
            }
        }
        swipeBack()
        compose.onNodeWithText("Overview content").assertExists()
        compose.runOnIdle { check(showGuide.value) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        visible(R.string.guide_access_title)
        compose.onNodeWithText("Overview content").assertDoesNotExist()

        swipeBack()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Overview content").assertIsDisplayed()
        compose.runOnIdle { check(!showGuide.value) }
    }

    private fun swipeBack() {
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(1f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(140f, 400f, 0.75f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
    }

    private fun click(id: Int) = compose.onNodeWithText(compose.activity.getString(id)).performClick()

    private fun visible(id: Int) = compose.onNodeWithText(compose.activity.getString(id)).assertIsDisplayed()
}
