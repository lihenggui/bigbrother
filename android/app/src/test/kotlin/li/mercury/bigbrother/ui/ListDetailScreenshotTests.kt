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
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.PackageDetails
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.screenshottesting.DemoApp
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.StoreScreenshotType
import li.mercury.bigbrother.core.screenshottesting.captureMultiDevice
import li.mercury.bigbrother.core.screenshottesting.captureStoreScreenshot
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.feature.appdetail.impl.AppDetailScreen
import li.mercury.bigbrother.feature.appdetail.impl.AppDetailViewModel
import li.mercury.bigbrother.feature.applist.api.navigation.AppListNavKey
import li.mercury.bigbrother.feature.applist.impl.AppListScreen
import li.mercury.bigbrother.feature.applist.impl.AppListViewModel
import li.mercury.bigbrother.feature.search.impl.GlobalSearchBar
import li.mercury.bigbrother.feature.search.impl.SearchViewModel
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

    /**
     * The app list and the weather app's page, as panes. With [fitWindow],
     * the window decides which are shown, as in the app with the list on
     * top: on a phone the list alone. Otherwise both are, as on a tablet.
     */
    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    @Composable
    private fun ListDetail(fitWindow: Boolean = false) {
        val records = remember { DemoRecords(clock) }
        val appInfoRepository = remember { DemoAppInfoRepository(records) }
        val listViewModel = remember {
            AppListViewModel(TestAccessRepository(apps = records.apps), appInfoRepository, TestUserDataRepository())
        }
        val detailViewModel = remember {
            AppDetailViewModel(
                accessRepository = TestAccessRepository(apps = records.apps, accesses = records.accesses(DemoApp.WEATHER)),
                appInfoRepository = appInfoRepository,
                uid = DemoApp.WEATHER.uid,
                packageName = DemoApp.WEATHER.packageName,
                range = TimeRange.DAY,
            )
        }
        val adaptiveInfo = currentWindowAdaptiveInfoV2()
        val directive = paneDirective(adaptiveInfo)
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
                            AppListScreen(
                                topBar = { SearchBar(appInfoRepository) },
                                selectedUid = DemoApp.WEATHER.uid.takeIf { beside },
                                onOpenApp = { _, _, _ -> },
                                viewModel = listViewModel,
                            )
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
                                viewModel = detailViewModel,
                            )
                        }
                    },
                )
            }
        }
    }
}

/** The search bar the top-level screens have as their top bar, idle. */
@Composable
private fun SearchBar(appInfoRepository: AppInfoRepository) {
    val viewModel = remember {
        SearchViewModel(
            context = ApplicationProvider.getApplicationContext(),
            accessRepository = TestAccessRepository(),
            appInfoRepository = appInfoRepository,
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

/** The demo apps, as installed, with package details for the app whose page is open. */
private class DemoAppInfoRepository(private val records: DemoRecords) : AppInfoRepository {
    override suspend fun getAppInfo(packageName: String): AppInfo? = records.appInfo[packageName]

    override suspend fun getAppInfo(packageNames: Collection<String>): Map<String, AppInfo> = records.appInfo.filterKeys { it in packageNames }

    override suspend fun getPackageDetails(packageName: String): PackageDetails? = records.packageDetails.takeIf { packageName == DemoApp.WEATHER.packageName }
}
