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

package li.mercury.bigbrother.feature.overview.impl

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils.matchesCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.SpeakableTextPresentCheck
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.StoreScreenshotType
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureMultiDevice
import li.mercury.bigbrother.core.screenshottesting.captureStoreScreenshot
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import li.mercury.bigbrother.core.ui.isShown
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
class OverviewScreenScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenDemoRecords_whenShownInEnglish_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "1_overview", searchBarFinding) { Overview() }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenDemoRecords_whenShownInChinese_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "1_overview", searchBarFinding) { Overview() }
    }

    @Test
    fun givenDemoRecords_whenShownOnEachDevice_thenCategoriesFillColumns() {
        composeTestRule.captureMultiDevice("Overview", searchBarFinding) { Overview() }
    }

    @Test
    fun givenDemoRecords_whenShownInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice(
            deviceName = "phone_dark",
            deviceSpec = DefaultTestDevices.PHONE.spec,
            screenshotName = "Overview",
            accessibilitySuppressions = searchBarFinding,
            darkMode = true,
        ) {
            Overview()
        }
    }

    /** The overview of the demo records, with the search bar it has in the app. */
    @Composable
    private fun Overview() {
        val records = remember { DemoRecords(clock) }
        val state = remember(records) {
            val shown = records.apps.filter { isShown(it, records.appInfo, showSystemApps = false, ownPackage = "") }
            val (files, categories) = overviewOf(shown)
            OverviewUiState(
                range = TimeRange.DAY,
                files = files,
                categories = categories,
                appInfo = records.appInfo,
                recording = true,
            )
        }
        OverviewScreen(
            state = state,
            topBar = { SearchBar() },
            onRange = {},
            onRefresh = {},
            onOpenCategory = {},
            onInstallModule = {},
            onUpdateModule = {},
            onStartDaemon = {},
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
