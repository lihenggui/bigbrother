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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuite
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.NavigationBackHandler
import androidx.navigation3.scene.rememberNavigationEventState
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventTransitionState.InProgress
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
import li.mercury.bigbrother.feature.jail.api.navigation.navigateToJailApp
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
import kotlin.math.roundToInt

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
            appDetailEntries(navigator, navigator::navigateToJailApp)
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

    val sceneState = rememberSceneState(
        entries = navigationState.toEntries(entryProvider),
        sceneStrategies = listOf(bottomSheetStrategy, listDetailStrategy),
        onBack = navigator::goBack,
    )
    val navigationEventState = rememberNavigationEventState(sceneState)
    NavigationBackHandler(sceneState, navigationEventState, onBackCompleted = navigator::goBack)

    val rootKeys = remember(entryProvider) { topLevelKeys.associateBy { entryProvider(it).contentKey } }
    val currentRoot = navigationState.currentTopLevelKey
    val progress = (navigationEventState.transitionState as? InProgress)?.latestEvent?.progress
    val previousScene = sceneState.previousScenes.lastOrNull()?.takeIf { progress != null }
    val previousEntries = sceneState.currentScene.previousEntries
    val previousRoot = previousScene?.let {
        previousEntries.findLast { it.contentKey in rootKeys }?.let { rootKeys[it.contentKey] }
    }
    // A top-level list can still be visible beside a detail pane.
    val showSearch = sceneState.currentScene.entries.any { rootKeys[it.contentKey] == currentRoot }
    val previousShowsSearch = previousScene?.entries?.any { rootKeys[it.contentKey] == previousRoot && previousRoot != null }
    val searchVisibility = rememberBarVisibility(showSearch, previousShowsSearch, progress)

    val suiteType = navigationSuiteType(adaptiveInfo)
    val isBar = suiteType == NavigationSuiteType.ShortNavigationBarCompact ||
        suiteType == NavigationSuiteType.ShortNavigationBarMedium
    val hint = remember { TransitionHint() }
    val navigationItems: @Composable () -> Unit = {
        for (destination in TOP_LEVEL_DESTINATIONS) {
            val selected = (previousRoot ?: currentRoot) == destination.key
            NavigationSuiteItem(
                selected = selected,
                onClick = {
                    if (previousScene == null) {
                        hint.topLevelSwitch = destination.key != currentRoot
                        navigator.navigate(destination.key)
                    }
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
    }
    val content: @Composable () -> Unit = {
        // A list and its detail share the window only if a list is on the
        // stack: an app opened from the search on the overview stands alone,
        // and keeps its back arrow.
        val listOnStack = navigationState.currentSubStack.any { it in LIST_PANE_KEYS || it is CategoryNavKey }
        CompositionLocalProvider(LocalShowsDetailBesideList provides (!singlePane && listOnStack)) {
            val motion = MotionScheme.standard()
            val slide = motion.defaultSpatialSpec<IntOffset>()
            val fade = motion.defaultEffectsSpec<Float>()
            val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
            val forward = slideInHorizontally(slide) { it * direction } + fadeIn(fade) togetherWith
                slideOutHorizontally(slide) { -it * direction / PARALLAX } + fadeOut(fade)
            val backward = slideInHorizontally(slide) { -it * direction / PARALLAX } + fadeIn(fade) togetherWith
                slideOutHorizontally(slide) { it * direction } + fadeOut(fade)
            val switch: ContentTransform = fadeIn(fade) togetherWith fadeOut(motion.fastEffectsSpec())

            SearchPaneLayout(
                directive = directive,
                showSearch = showSearch || previousShowsSearch == true || searchVisibility.value > 0f,
                topBar = {
                    Box(Modifier.revealBar(searchVisibility, top = true)) {
                        searchBar(navigator)
                    }
                },
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

    if (isBar) {
        val showBar = navigationState.currentKey in topLevelKeys
        val previousShowsBar = previousScene?.let { previousEntries.lastOrNull()?.contentKey in rootKeys }
        val barVisibility = rememberBarVisibility(showBar, previousShowsBar, progress)
        Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBar || previousShowsBar == true || barVisibility.value > 0f) {
                    NavigationSuite(
                        navigationSuiteType = suiteType,
                        modifier = Modifier.revealBar(barVisibility, top = false),
                        content = navigationItems,
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
        }
    } else {
        NavigationSuiteScaffold(
            modifier = modifier,
            navigationItems = navigationItems,
            navigationSuiteType = suiteType,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            content = content,
        )
    }
}

/** Settle from the last measured gesture frame when the user releases or cancels. */
@Composable
private fun rememberBarVisibility(current: Boolean, previous: Boolean?, progress: Float?): State<Float> {
    val resting = if (current) 1f else 0f
    val visibility = remember { Animatable(resting) }
    val motion = MotionScheme.standard()
    LaunchedEffect(current, previous, progress) {
        if (previous != null && progress != null) {
            visibility.snapTo(lerp(resting, if (previous) 1f else 0f, progress))
        } else {
            visibility.animateTo(resting, motion.fastEffectsSpec())
        }
    }
    return visibility.asState()
}

/** The bar occupies only its revealed height, keeping the destination's insets in sync. */
private fun Modifier.revealBar(visibility: State<Float>, top: Boolean): Modifier = clipToBounds().layout { measurable, constraints ->
    val bar = measurable.measure(constraints)
    val height = (bar.height * visibility.value).roundToInt()
    layout(bar.width, height) {
        bar.placeRelative(0, if (top) height - bar.height else 0)
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
