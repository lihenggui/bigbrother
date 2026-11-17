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

package li.mercury.bigbrother.feature.timeline.impl

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils.matchesCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.SpeakableTextPresentCheck
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.StoreScreenshotType
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureStoreScreenshot
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import li.mercury.bigbrother.feature.search.impl.GlobalSearchBar
import li.mercury.bigbrother.feature.search.impl.SearchViewModel
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
class TimelineScreenScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenDayOfRecords_whenShownInEnglish_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "3_timeline", searchBarFinding) { Timeline() }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenDayOfRecords_whenShownInChinese_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "3_timeline", searchBarFinding) { Timeline() }
    }

    @Test
    fun givenDayOfRecords_whenShownInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice(
            deviceName = "phone_dark",
            deviceSpec = DefaultTestDevices.PHONE.spec,
            screenshotName = "Timeline",
            accessibilitySuppressions = searchBarFinding,
            darkMode = true,
        ) {
            Timeline()
        }
    }

    /** Today's demo records by the hour, with the search bar the timeline has in the app. */
    @Composable
    private fun Timeline() {
        val records = remember { DemoRecords(clock) }
        TimelineScreen(
            state = TimelineUiState(entries = records.timeline, appInfo = records.appInfo),
            topBar = { SearchBar() },
            selectedUid = null,
            onRefresh = {},
            onLoadMore = {},
            onOpenEntry = { _, _ -> },
        )
    }
}

/**
 * The search bar's collapsed field is tapped through a target laid over it,
 * which has no label of its own. Screens showing it skip that one check.
 */
private val searchBarFinding = matchesCheck(SpeakableTextPresentCheck::class.java)

/** The search bar the top-level screens have as their top bar, idle. */
@Composable
private fun SearchBar() {
    val viewModel = remember {
        SearchViewModel(
            context = ApplicationProvider.getApplicationContext(),
            accessRepository = TestAccessRepository(),
            appInfoRepository = TestAppInfoRepository(),
            userDataRepository = TestUserDataRepository(),
        )
    }
    GlobalSearchBar(
        onOpenApp = { _, _, _ -> },
        onOpenCategory = {},
        onOpenSettings = {},
        settingsBadge = false,
        viewModel = viewModel,
    )
}
