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

package li.mercury.bigbrother.feature.applist.impl

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoLoadFailures
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.assertSamplesCover
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureMultiDevice
import li.mercury.bigbrother.core.screenshottesting.screenshotName
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import li.mercury.bigbrother.feature.search.impl.GlobalSearchBar
import li.mercury.bigbrother.feature.search.impl.SearchViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppListScreenScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun givenDemoRecords_whenShownOnEachDevice_thenAppsThatReadFilesComeFirst() {
        composeTestRule.captureMultiDevice("AppList") { AppList() }
    }

    @Test
    fun givenDemoRecords_whenShownInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice("phone_dark", DefaultTestDevices.PHONE.spec, "AppList", darkMode = true) { AppList() }
    }

    @Test
    fun givenSystemAppsShown_whenShown_thenListsThemToo() {
        capturePhone("AppListSystemApps") { AppList { it.copy(showSystemApps = true) } }
    }

    @Test
    fun givenRecordsOnlyOfSystemApps_whenShown_thenOffersToShowThem() {
        capturePhone("AppListOnlySystemApps") {
            AppList { state -> state.copy(apps = state.apps.orEmpty().filter { app -> state.appInfo[app.packageName]?.isSystem == true }) }
        }
    }

    @Test
    fun givenNoRecords_whenShown_thenSaysNothingIsRecorded() {
        capturePhone("AppListEmpty") { AppList { it.copy(apps = emptyList()) } }
    }

    @Test
    fun givenFirstLoadRunning_whenShown_thenShowsLoadingIndicator() {
        capturePhone("AppListLoading") { AppList { AppListUiState() } }
    }

    @Test
    fun givenRefreshing_whenShown_thenShowsRefreshIndicatorOverList() {
        capturePhone("AppListRefreshing") { AppList { it.copy(refreshing = true) } }
    }

    @Test
    fun givenEachLoadFailure_whenShown_thenSaysWhy() {
        // The other screens show the same message, and are captured with DemoLoadFailure only.
        for (failure in DemoLoadFailures) {
            capturePhone("AppListFailure${failure.screenshotName}") { AppList { AppListUiState(failure = failure) } }
        }
    }

    @Test
    fun givenLoadFailureCases_whenChecked_thenEveryKindHasOne() {
        assertSamplesCover(LoadFailure::class.java, DemoLoadFailures)
    }

    private fun capturePhone(name: String, body: @Composable () -> Unit) {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, name, body = body)
    }

    /** The list of the demo apps in the state [state] makes of it, with the search bar it has in the app. */
    @Composable
    private fun AppList(state: (AppListUiState) -> AppListUiState = { it }) {
        val records = remember { DemoRecords(clock) }
        AppListScreen(
            state = state(AppListUiState(apps = records.apps, appInfo = records.appInfo)),
            topBar = { SearchBar() },
            selectedUid = null,
            onRange = {},
            onRefresh = {},
            onOpen = { _, _, _ -> },
            onShowSystemApps = {},
        )
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
