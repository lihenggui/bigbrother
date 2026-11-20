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
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoLoadFailure
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.StoreScreenshotType
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureMultiDevice
import li.mercury.bigbrother.core.screenshottesting.captureStoreScreenshot
import li.mercury.bigbrother.core.screenshottesting.screenshotName
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import li.mercury.bigbrother.feature.search.impl.GlobalSearchBar
import li.mercury.bigbrother.feature.search.impl.SearchScaffold
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
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "3_timeline") { Timeline() }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenDayOfRecords_whenShownInChinese_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "3_timeline") { Timeline() }
    }

    @Test
    fun givenDayOfRecords_whenShownInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice(
            deviceName = "phone_dark",
            deviceSpec = DefaultTestDevices.PHONE.spec,
            screenshotName = "Timeline",
            darkMode = true,
        ) {
            Timeline()
        }
    }

    @Test
    fun givenDayOfRecords_whenShownOnEachDevice_thenMatchesGolden() {
        composeTestRule.captureMultiDevice("Timeline") { Timeline() }
    }

    @Test
    fun givenFirstPageLoading_whenShown_thenShowsLoadingIndicator() {
        capturePhone("TimelineLoading") { Timeline { TimelineUiState() } }
    }

    @Test
    fun givenLoadFailure_whenShown_thenSaysWhy() {
        val failure = DemoLoadFailure
        capturePhone("TimelineFailure${failure.screenshotName}") { Timeline { TimelineUiState(failure = failure) } }
    }

    @Test
    fun givenNoRecords_whenShown_thenSaysNothingIsRecorded() {
        capturePhone("TimelineEmpty") { Timeline { it.copy(entries = emptyList()) } }
    }

    @Test
    fun givenRefreshing_whenShown_thenShowsRefreshIndicatorOverRecords() {
        capturePhone("TimelineRefreshing") { Timeline { it.copy(refreshing = true) } }
    }

    @Test
    fun givenOlderPageLoading_whenShown_thenShowsLoadingIndicatorAtEnd() {
        capturePhone("TimelineLoadingMore") { Timeline { it.firstPage().copy(loadingMore = true) } }
    }

    @Test
    fun givenOlderPageFailed_whenShown_thenOffersToRetryAtEnd() {
        capturePhone("TimelineLoadMoreFailed") { Timeline { it.firstPage().copy(loadMoreFailed = true) } }
    }

    @Test
    fun givenSystemAppsShown_whenShown_thenListsThemToo() {
        capturePhone("TimelineSystemApps") { Timeline { it.copy(showSystemApps = true) } }
    }

    private fun capturePhone(name: String, body: @Composable () -> Unit) {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, name, body = body)
    }

    /** The first hours of the records, as a page with older ones still to load. */
    private fun TimelineUiState.firstPage(): TimelineUiState {
        val page = entries.orEmpty().take(FIRST_PAGE)
        return copy(entries = page, nextBefore = page.last().hourStart)
    }

    /** Today's demo records by the hour in the state [state] makes of them, with the search bar the timeline has in the app. */
    @Composable
    private fun Timeline(state: (TimelineUiState) -> TimelineUiState = { it }) {
        val records = remember { DemoRecords(clock) }
        SearchScaffold(topBar = { SearchBar() }) {
            TimelineScreen(
                state = state(TimelineUiState(entries = records.timeline, appInfo = records.appInfo)),
                selectedUid = null,
                onRefresh = {},
                onLoadMore = {},
                onOpenEntry = { _, _ -> },
            )
        }
    }

    private companion object {
        /** Entries few enough for the end of the list to show on a phone. */
        const val FIRST_PAGE = 4
    }
}

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
