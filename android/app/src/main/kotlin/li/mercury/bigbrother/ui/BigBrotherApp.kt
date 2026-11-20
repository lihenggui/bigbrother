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

import androidx.annotation.StringRes
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.NavigationBackHandler
import androidx.navigation3.scene.rememberNavigationEventState
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import li.mercury.bigbrother.R
import li.mercury.bigbrother.core.designsystem.icon.CageIcons
import li.mercury.bigbrother.core.navigation.BottomSheetSceneStrategy
import li.mercury.bigbrother.core.navigation.Navigator
import li.mercury.bigbrother.core.navigation.rememberNavigationState
import li.mercury.bigbrother.core.navigation.toEntries
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.feature.appdetail.api.navigation.navigateToAppDetail
import li.mercury.bigbrother.feature.appdetail.impl.navigation.appDetailEntries
import li.mercury.bigbrother.feature.applist.api.navigation.AppListNavKey
import li.mercury.bigbrother.feature.applist.impl.navigation.appListEntry
import li.mercury.bigbrother.feature.jail.api.navigation.JailNavKey
import li.mercury.bigbrother.feature.jail.impl.navigation.jailEntries
import li.mercury.bigbrother.feature.overview.api.navigation.CategoryNavKey
import li.mercury.bigbrother.feature.overview.api.navigation.OverviewNavKey
import li.mercury.bigbrother.feature.overview.api.navigation.navigateToCategory
import li.mercury.bigbrother.feature.overview.impl.navigation.overviewEntries
import li.mercury.bigbrother.feature.search.impl.GlobalSearchBar
import li.mercury.bigbrother.feature.settings.api.navigation.SettingsNavKey
import li.mercury.bigbrother.feature.settings.impl.navigation.settingsEntry
import li.mercury.bigbrother.feature.timeline.api.navigation.TimelineNavKey
import li.mercury.bigbrother.feature.timeline.impl.navigation.timelineEntry

/** A destination in the navigation bar or rail. */
internal class TopLevelDestination(
    val key: NavKey,
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

/**
 * What was accessed, by whom, and when: the three ways into the records;
 * and the jail, where apps are put in the sandbox. Internal, like the layout
 * decisions below, for the screenshot tests.
 */
internal val TOP_LEVEL_DESTINATIONS = listOf(
    TopLevelDestination(OverviewNavKey, R.string.nav_overview, Icons.Outlined.SpaceDashboard, Icons.Filled.SpaceDashboard),
    TopLevelDestination(AppListNavKey, R.string.nav_apps, Icons.Outlined.Apps, Icons.Filled.Apps),
    TopLevelDestination(TimelineNavKey, R.string.nav_timeline, Icons.Outlined.History, Icons.Filled.History),
    TopLevelDestination(JailNavKey, R.string.nav_jail, CageIcons.Outlined, CageIcons.Filled),
)

/** Destinations shown as the list pane of a list-detail layout. */
private val LIST_PANE_KEYS = setOf<NavKey>(AppListNavKey, TimelineNavKey, JailNavKey)

/** The screen underneath moves this fraction of the distance, for depth. */
private const val PARALLAX = 4

/** Lets the transition spec tell a switch between top-level destinations from going forward. */
private class TransitionHint {
    var topLevelSwitch = false
}

/**
 * Root of the UI: the navigation bar or rail, and the screens.
 *
 * Four top-level destinations: the overview (what was accessed), the app
 * list (by whom), the timeline (when) and the jail (the apps put in the
 * sandbox). Compact windows show one pane and
 * a navigation bar; wider ones a navigation rail and, from the expanded
 * breakpoint, a list beside the app it opens; extra-large ones also a third
 * pane for a record or the files.
 */
@Composable
fun BigBrotherApp(recordingStopped: Boolean, modifier: Modifier = Modifier) {
    BigBrotherNavigation(
        recordingStopped = recordingStopped,
        modifier = modifier,
        registerEntries = { navigator ->
            overviewEntries(navigator)
            appListEntry(navigator)
            timelineEntry(navigator)
            jailEntries(navigator)
            appDetailEntries(navigator)
            settingsEntry(navigator)
        },
        searchBar = { navigator ->
            GlobalSearchBar(
                onOpenApp = { app, range, category ->
                    navigator.navigateToAppDetail(app.uid, app.packageName, range, category?.name)
                },
                onOpenCategory = { navigator.navigateToCategory(it.name) },
                onOpenSettings = { navigator.navigate(SettingsNavKey) },
                settingsBadge = recordingStopped,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun BigBrotherNavigation(
    recordingStopped: Boolean,
    registerEntries: EntryProviderScope<NavKey>.(Navigator) -> Unit,
    modifier: Modifier = Modifier,
    searchBar: @Composable (Navigator) -> Unit,
) {
    val topLevelKeys = remember { TOP_LEVEL_DESTINATIONS.map { it.key }.toSet() }
    val navigationState = rememberNavigationState(startKey = OverviewNavKey, topLevelKeys = topLevelKeys)
    val navigator = remember(navigationState) { Navigator(navigationState) }

    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive = remember(adaptiveInfo) { paneDirective(adaptiveInfo) }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)
    val singlePane = directive.maxHorizontalPartitions == 1
    // Material keeps bottom sheets to windows with a single pane; wider ones
    // show a record as a pane instead.
    val bottomSheetStrategy = remember(singlePane) { BottomSheetSceneStrategy<NavKey>(enabled = singlePane) }

    val entryProvider = remember(navigator, registerEntries, singlePane) {
        val entries = entryProvider<NavKey> { registerEntries(navigator) }
        val withSearchInset: (NavKey) -> NavEntry<NavKey> = { key ->
            val entry = entries(key)
            if (key in topLevelKeys) {
                NavEntry(navEntry = entry) {
                    val anchorSearch = !singlePane && key in LIST_PANE_KEYS && key == navigationState.currentTopLevelKey
                    SearchPaneContent(anchorSearch = anchorSearch) { entry.Content() }
                }
            } else {
                entry
            }
        }
        withSearchInset
    }

    val suiteType = navigationSuiteType(adaptiveInfo)
    // A navigation bar makes room on the screens below a destination; a
    // rail stays, being out of the way at the side.
    val scaffoldState = rememberNavigationSuiteScaffoldState()
    val isBar = suiteType == NavigationSuiteType.ShortNavigationBarCompact ||
        suiteType == NavigationSuiteType.ShortNavigationBarMedium
    val hideBar = isBar && navigationState.currentKey !in topLevelKeys
    LaunchedEffect(hideBar) { if (hideBar) scaffoldState.hide() else scaffoldState.show() }

    val hint = remember { TransitionHint() }
    NavigationSuiteScaffold(
        modifier = modifier,
        navigationItems = {
            for (destination in TOP_LEVEL_DESTINATIONS) {
                val selected = navigationState.currentTopLevelKey == destination.key
                NavigationSuiteItem(
                    selected = selected,
                    onClick = {
                        hint.topLevelSwitch = destination.key != navigationState.currentTopLevelKey
                        navigator.navigate(destination.key)
                    },
                    icon = {
                        Icon(
                            if (selected) destination.selectedIcon else destination.icon,
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(destination.label)) },
                    navigationSuiteType = suiteType,
                    // While recording is stopped, the overview, which says
                    // so, is marked from the other destinations.
                    badge = if (recordingStopped && destination.key == OverviewNavKey && !selected) {
                        { Badge() }
                    } else {
                        null
                    },
                )
            }
        },
        navigationSuiteType = suiteType,
        state = scaffoldState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // A list and its detail share the window only if a list is on the
        // stack: an app opened from the search on the overview stands alone,
        // and keeps its back arrow.
        val listOnStack = navigationState.currentSubStack.any { it in LIST_PANE_KEYS || it is CategoryNavKey }
        CompositionLocalProvider(LocalShowsDetailBesideList provides (!singlePane && listOnStack)) {
            // Screen transitions use the standard motion scheme: expressive
            // springs overshoot, which suits components but not a whole
            // screen moving. Going forward, the new screen slides in over the
            // old one; going back reverses it; switching destinations fades.
            // Predictive back keeps the navigation library's default, which
            // follows the system's back preview.
            val motion = MotionScheme.standard()
            val slide = motion.defaultSpatialSpec<IntOffset>()
            val fade = motion.defaultEffectsSpec<Float>()
            val forward = slideInHorizontally(slide) { it } + fadeIn(fade) togetherWith
                slideOutHorizontally(slide) { -it / PARALLAX } + fadeOut(fade)
            val backward = slideInHorizontally(slide) { -it / PARALLAX } + fadeIn(fade) togetherWith
                slideOutHorizontally(slide) { it } + fadeOut(fade)
            val switch: ContentTransform = fadeIn(fade) togetherWith fadeOut(motion.fastEffectsSpec())
            val sceneState = rememberSceneState(
                entries = navigationState.toEntries(entryProvider),
                sceneStrategies = listOf(bottomSheetStrategy, listDetailStrategy),
                onBack = navigator::goBack,
            )
            val navigationEventState = rememberNavigationEventState(sceneState)
            NavigationBackHandler(sceneState, navigationEventState, onBackCompleted = navigator::goBack)

            // A top-level list can still be visible beside a detail pane.
            val rootContentKey = entryProvider(navigationState.currentTopLevelKey).contentKey
            val showSearch = sceneState.currentScene.entries.any { it.contentKey == rootContentKey }
            SearchPaneLayout(
                directive = directive,
                showSearch = showSearch,
                topBar = { searchBar(navigator) },
            ) {
                NavDisplay(
                    sceneState = sceneState,
                    navigationEventState = navigationEventState,
                    // Screens slide during transitions; this is what shows between them.
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                    transitionSpec = {
                        if (hint.topLevelSwitch) {
                            hint.topLevelSwitch = false
                            switch
                        } else {
                            forward
                        }
                    },
                    popTransitionSpec = { backward },
                )
            }
        }
    }
}

/**
 * The navigation component for the window: Material's choice (a bar in
 * compact windows and in short ones, a collapsed rail otherwise), with the
 * rail expanded in extra-large windows, where there is room for labels.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun navigationSuiteType(adaptiveInfo: WindowAdaptiveInfo): NavigationSuiteType {
    val type = NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
    val extraLarge = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXTRA_LARGE_LOWER_BOUND)
    return if (type == NavigationSuiteType.WideNavigationRailCollapsed && extraLarge) {
        NavigationSuiteType.WideNavigationRailExpanded
    } else {
        type
    }
}

/**
 * Panes per Material's breakpoints: one below expanded; two from expanded,
 * with the list fixed at 360dp, or 412dp from large; three from extra-large.
 * Panes keep clear of a separating hinge on foldables.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun paneDirective(adaptiveInfo: WindowAdaptiveInfo): PaneScaffoldDirective {
    val base = calculatePaneScaffoldDirective(adaptiveInfo)
    val sizeClass = adaptiveInfo.windowSizeClass
    val partitions = when {
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXTRA_LARGE_LOWER_BOUND) -> 3
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> 2
        else -> 1
    }
    val paneWidth = if (sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND)) 412.dp else 360.dp
    return base.copy(maxHorizontalPartitions = partitions, defaultPanePreferredWidth = paneWidth)
}
