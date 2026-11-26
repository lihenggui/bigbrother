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
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import li.mercury.bigbrother.core.designsystem.theme.BigBrotherTheme
import li.mercury.bigbrother.core.domain.GetAppRecordsUseCase
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.screenshottesting.DemoApp
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.StoreScreenshotType
import li.mercury.bigbrother.core.screenshottesting.captureMultiDevice
import li.mercury.bigbrother.core.screenshottesting.captureStoreScreenshot
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestInstalledAppRepository
import li.mercury.bigbrother.core.testing.repository.TestSandboxRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import li.mercury.bigbrother.core.testing.repository.testSearchRecordsUseCase
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.feature.appdetail.api.navigation.AppDetailNavKey
import li.mercury.bigbrother.feature.appdetail.api.navigation.navigateToAppDetail
import li.mercury.bigbrother.feature.appdetail.impl.AppDetailScreen
import li.mercury.bigbrother.feature.appdetail.impl.AppDetailViewModel
import li.mercury.bigbrother.feature.applist.api.navigation.AppListNavKey
import li.mercury.bigbrother.feature.applist.impl.AppListScreen
import li.mercury.bigbrother.feature.applist.impl.AppListViewModel
import li.mercury.bigbrother.feature.jail.api.navigation.JailNavKey
import li.mercury.bigbrother.feature.overview.api.navigation.OverviewNavKey
import li.mercury.bigbrother.feature.search.impl.GlobalSearchBar
import li.mercury.bigbrother.feature.search.impl.SearchViewModel
import li.mercury.bigbrother.feature.timeline.api.navigation.TimelineNavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * The app list beside the app it opened, as on a tablet. The screens are
 * the real ones, run by their view models over the demo records; the rail
 * and the panes are laid out as BigBrotherApp lays them out, without its
 * navigation, which needs Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
// BigBrotherApplication would set up Hilt and its real repositories.
@Config(application = Application::class)
class ListDetailScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    /**
     * Robolectric starts activities from the app's own manifest, which,
     * unlike a library's test manifest, lacks the one the compose rule
     * starts; it is added here rather than to the debug build.
     */
    @get:Rule(order = 1)
    val testActivity = object : ExternalResource() {
        override fun before() {
            val application = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(application.packageManager)
                .addActivityIfNotPresent(ComponentName(application, ComponentActivity::class.java))
        }
    }

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenAppOpenedFromList_whenShownOnTabletInEnglish_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.TEN_INCH, "1_list_detail") { ListDetail() }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenAppOpenedFromList_whenShownOnTabletInChinese_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.TEN_INCH, "1_list_detail") { ListDetail() }
    }

    @Test
    fun givenAppListWithAppOpen_whenShownOnEachDevice_thenPanesAndNavigationFollowWindow() {
        composeTestRule.captureMultiDevice("ListDetail") { ListDetail(fitWindow = true) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w412dp-h880dp")
    fun givenPhoneGlobalSearch_whenSwitchingTabs_thenOneSearchBarStaysInPlaceDuringTransitions() {
        assertSearchStaysInPlace()
    }

    @Test
    @Config(qualifiers = "en-rUS-w1000dp-h800dp")
    fun givenTabletGlobalSearch_whenSwitchingTabs_thenOneSearchBarStaysInPlaceDuringTransitions() {
        assertSearchStaysInPlace()
    }

    private fun assertSearchStaysInPlace() {
        showNavigation()
        val searchBar = composeTestRule.onNodeWithContentDescription("Search apps and files")
        val header = searchBar.fetchSemanticsNode()
        val bounds = header.boundsInRoot
        val searchPixels = searchBar.captureToImage().asAndroidBitmap()
        val settings = composeTestRule.onNodeWithContentDescription("Settings")
        val settingsBounds = settings.fetchSemanticsNode().boundsInRoot
        val settingsPixels = settings.captureToImage().asAndroidBitmap()
        for ((tab, content) in listOf("Apps" to "Open app", "Timeline" to "Timeline content", "Jail" to "Jail content", "Overview" to "Overview content")) {
            composeTestRule.mainClock.autoAdvance = false
            composeTestRule.onNodeWithText(tab).performClick()
            repeat(4) { composeTestRule.mainClock.advanceTimeByFrame() }
            composeTestRule.onAllNodesWithContentDescription("Search apps and files").assertCountEquals(1)
            val duringTransition = searchBar.fetchSemanticsNode()
            assertEquals(header.id, duringTransition.id)
            assertEquals(bounds, duringTransition.boundsInRoot)
            assertEquals(settingsBounds, settings.fetchSemanticsNode().boundsInRoot)
            assertTrue(searchPixels.sameAs(searchBar.captureToImage().asAndroidBitmap()))
            assertTrue(settingsPixels.sameAs(settings.captureToImage().asAndroidBitmap()))
            composeTestRule.mainClock.autoAdvance = true
            composeTestRule.onNodeWithText(content).assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w412dp-h880dp")
    fun givenPhoneAppList_whenOpeningAndLeavingDetail_thenSearchReturnsWithTheList() {
        showNavigation()
        openApp()
        composeTestRule.onAllNodesWithContentDescription("Search apps and files").assertCountEquals(0)
        composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.onNodeWithContentDescription("Search apps and files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open app").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-w412dp-h880dp")
    fun givenPhoneDetail_whenPredictiveBackCancelsThenCommits_thenBarsFollowTheListPreview() {
        showNavigation()
        openApp()
        val detail = composeTestRule.onNodeWithText("App detail")
        val search = composeTestRule.onNodeWithContentDescription("Search apps and files")
        val apps = composeTestRule.onNodeWithText("Apps")
        val dispatcher = composeTestRule.activity.onBackPressedDispatcher
        val detailBounds = detail.fetchSemanticsNode().boundsInRoot

        composeTestRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(1f, 410f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(152f, 410f, 0.65f, BackEventCompat.EDGE_LEFT))
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Open app").assertExists()
        search.assertIsDisplayed()
        apps.assertIsDisplayed()
        check(detail.fetchSemanticsNode().boundsInRoot != detailBounds)

        composeTestRule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        composeTestRule.waitForIdle()
        detail.assertIsDisplayed()
        search.assertDoesNotExist()
        apps.assertDoesNotExist()
        assertEquals(detailBounds, detail.fetchSemanticsNode().boundsInRoot)

        composeTestRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(411f, 410f, 0f, BackEventCompat.EDGE_RIGHT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(260f, 410f, 0.65f, BackEventCompat.EDGE_RIGHT))
        }
        composeTestRule.waitForIdle()
        search.assertIsDisplayed()
        apps.assertIsDisplayed()
        composeTestRule.runOnUiThread { dispatcher.onBackPressed() }
        composeTestRule.onNodeWithText("Open app").assertIsDisplayed()
        detail.assertDoesNotExist()

        composeTestRule.runOnUiThread { dispatcher.onBackPressed() }
        composeTestRule.onNodeWithText("Overview content").assertIsDisplayed()
        composeTestRule.runOnIdle { check(!dispatcher.hasEnabledCallbacks()) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w412dp-h880dp")
    fun givenTimeline_whenOpeningAndClosingSearch_thenTimelineStaysSelected() {
        showNavigation()
        composeTestRule.onNodeWithText("Timeline").performClick()
        composeTestRule.onNodeWithContentDescription("Search apps and files").performClick()
        composeTestRule.onNode(hasContentDescription("Close search") and hasAnyAncestor(isDialog())).performClick()
        composeTestRule.onNodeWithText("Timeline content").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Search apps and files").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-w1000dp-h800dp")
    fun givenTabletAppList_whenOpeningDetailBesideIt_thenSearchStaysVisible() {
        showNavigation()
        openApp()
        val search = composeTestRule.onNodeWithContentDescription("Search apps and files").fetchSemanticsNode().boundsInRoot
        val settings = composeTestRule.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        val detail = composeTestRule.onNodeWithText("App detail").fetchSemanticsNode().boundsInRoot
        assertTrue(search.right < detail.left)
        assertTrue(settings.right <= detail.left)
        assertTrue(detail.top < search.bottom)
        composeTestRule.onNodeWithText("Open app").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-w1000dp-h800dp-mdpi")
    fun givenSeparatingHinge_whenShowingTwoPanes_thenSearchFollowsTheListWidth() {
        composeTestRule.setContent {
            BigBrotherTheme {
                val hinge = with(LocalDensity.current) { Rect(500.dp.toPx(), 0f, 520.dp.toPx(), 800.dp.toPx()) }
                ListDetail(fitWindow = true, hinge = hinge)
            }
        }

        val settings = composeTestRule.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        assertTrue("Settings is at the end of the pane before the hinge", settings.right in 460f..500f)
        val detail = composeTestRule.onNodeWithText("DCIM/Camera").fetchSemanticsNode().boundsInRoot
        assertTrue("The detail pane begins after the hinge", detail.left >= 520f)
    }

    @Test
    @Config(qualifiers = "en-rUS-w412dp-h915dp-mdpi")
    fun givenRecordingStopped_whenAnotherTabIsSelected_thenOverviewAnnouncesIt() {
        showNavigation(recordingStopped = true)
        composeTestRule.onNodeWithText("Apps").performClick()
        composeTestRule.onNodeWithText("Overview").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Recording stopped"),
        )
    }

    private fun openApp() {
        composeTestRule.onNodeWithText("Apps").performClick()
        composeTestRule.onNodeWithText("Open app").performClick()
        composeTestRule.onNodeWithText("App detail").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    private fun showNavigation(recordingStopped: Boolean = false) {
        composeTestRule.setContent {
            BigBrotherTheme {
                BigBrotherNavigation(
                    recordingStopped = recordingStopped,
                    registerEntries = { navigator ->
                        entry<OverviewNavKey> { Text("Overview content") }
                        entry<AppListNavKey>(metadata = ListDetailSceneStrategy.listPane()) {
                            Text(
                                text = "Open app",
                                modifier = Modifier.clickable {
                                    navigator.navigateToAppDetail(DemoApp.WEATHER.uid, DemoApp.WEATHER.packageName, TimeRange.ALL)
                                },
                            )
                        }
                        entry<TimelineNavKey>(metadata = ListDetailSceneStrategy.listPane()) { Text("Timeline content") }
                        entry<JailNavKey>(metadata = ListDetailSceneStrategy.listPane()) { Text("Jail content") }
                        entry<AppDetailNavKey>(metadata = ListDetailSceneStrategy.detailPane()) { Text("App detail") }
                    },
                    searchBar = { SearchBar() },
                )
            }
        }
    }

    /**
     * The app list and the weather app's page, as panes. With [fitWindow],
     * the window decides which are shown, as in the app with the list on
     * top: on a phone the list alone. Otherwise both are, as on a tablet.
     */
    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    @Composable
    private fun ListDetail(fitWindow: Boolean = false, hinge: Rect? = null) {
        val records = remember { DemoRecords(clock) }
        val appInfoRepository = remember {
            TestAppInfoRepository(records.appInfo, details = mapOf(DemoApp.WEATHER.packageName to records.packageDetails))
        }
        val listViewModel = remember {
            val settings = TestUserDataRepository()
            AppListViewModel(
                GetAppRecordsUseCase(TestAccessRepository(apps = records.apps), appInfoRepository),
                GetAppVisibilityUseCase(settings, ApplicationProvider.getApplicationContext<Application>().packageName),
                settings,
                Dispatchers.Unconfined,
            )
        }
        val detailViewModel = remember {
            AppDetailViewModel(
                accessRepository = TestAccessRepository(apps = records.apps, accesses = records.accesses(DemoApp.WEATHER)),
                appInfoRepository = appInfoRepository,
                installedAppRepository = TestInstalledAppRepository(),
                sandboxRepository = TestSandboxRepository(),
                ownPackage = ApplicationProvider.getApplicationContext<Application>().packageName,
                currentUserId = 0,
                defaultDispatcher = Dispatchers.Unconfined,
                uid = DemoApp.WEATHER.uid,
                packageName = DemoApp.WEATHER.packageName,
                range = TimeRange.DAY,
            )
        }
        val adaptiveInfo = currentWindowAdaptiveInfoV2()
        val directive = paneDirective(adaptiveInfo).let { if (hinge == null) it else it.copy(excludedBounds = listOf(hinge)) }
        val beside = !fitWindow || directive.maxHorizontalPartitions > 1
        NavigationSuiteScaffold(
            navigationItems = {
                for (destination in TOP_LEVEL_DESTINATIONS) {
                    val selected = destination.key == AppListNavKey
                    NavigationSuiteItem(
                        selected = selected,
                        onClick = {},
                        icon = {
                            Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null)
                        },
                        label = { Text(stringResource(destination.label)) },
                        navigationSuiteType = navigationSuiteType(adaptiveInfo),
                    )
                }
            },
            navigationSuiteType = navigationSuiteType(adaptiveInfo),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            CompositionLocalProvider(LocalShowsDetailBesideList provides beside) {
                SearchPaneLayout(directive, showSearch = true, topBar = { SearchBar() }) {
                    ListDetailPaneScaffold(
                        directive = directive,
                        value = if (fitWindow) {
                            calculateThreePaneScaffoldValue(
                                maxHorizontalPartitions = directive.maxHorizontalPartitions,
                                adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
                                currentDestination = ThreePaneScaffoldDestinationItem<Nothing>(ListDetailPaneScaffoldRole.List),
                            )
                        } else {
                            ThreePaneScaffoldValue(
                                primary = PaneAdaptedValue.Expanded,
                                secondary = PaneAdaptedValue.Expanded,
                                tertiary = PaneAdaptedValue.Hidden,
                            )
                        },
                        listPane = {
                            AnimatedPane {
                                SearchPaneContent(anchorSearch = beside) {
                                    AppListScreen(
                                        selectedUid = DemoApp.WEATHER.uid.takeIf { beside },
                                        onOpenApp = { _, _, _ -> },
                                        viewModel = listViewModel,
                                    )
                                }
                            }
                        },
                        detailPane = {
                            AnimatedPane {
                                AppDetailScreen(
                                    focusCategory = null,
                                    openRecord = null,
                                    onBack = {},
                                    onOpenFiles = {},
                                    onOpenRecord = { _, _ -> },
                                    onOpenJail = {},
                                    viewModel = detailViewModel,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The search bar the top-level screens have as their top bar, idle. */
@Composable
private fun SearchBar() {
    val viewModel = remember {
        SearchViewModel(testSearchRecordsUseCase(Dispatchers.Unconfined), Dispatchers.Unconfined)
    }
    GlobalSearchBar(
        onOpenApp = { _, _, _ -> },
        onOpenCategory = {},
        onOpenSettings = {},
        settingsBadge = false,
        viewModel = viewModel,
    )
}
