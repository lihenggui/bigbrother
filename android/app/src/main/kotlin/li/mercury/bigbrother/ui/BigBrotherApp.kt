package li.mercury.bigbrother.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.Modifier
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import li.mercury.bigbrother.core.navigation.Navigator
import li.mercury.bigbrother.core.navigation.rememberNavigationState
import li.mercury.bigbrother.core.navigation.toEntries
import li.mercury.bigbrother.feature.appdetail.impl.navigation.appDetailEntry
import li.mercury.bigbrother.feature.applist.api.navigation.AppListNavKey
import li.mercury.bigbrother.feature.applist.impl.navigation.appListEntry

/** The screen underneath moves this fraction of the distance, for depth. */
private const val PARALLAX = 4

/**
 * Root of the UI: hosts navigation between the feature screens.
 *
 * The app list is the only top-level destination; an app's detail opens
 * above it. On wide screens the two are shown side by side.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun BigBrotherApp() {
    val navigationState = rememberNavigationState(
        startKey = AppListNavKey,
        topLevelKeys = setOf(AppListNavKey),
    )
    val navigator = remember(navigationState) { Navigator(navigationState) }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()

    val entryProvider = entryProvider {
        appListEntry(navigator)
        appDetailEntry(navigator)
    }

    // Screen transitions use the standard motion scheme: expressive springs
    // overshoot, which suits components but not a whole screen moving.
    // Going forward, the new screen slides in over the old one; going back
    // reverses it. Predictive back keeps the navigation library's default,
    // which follows the system's back preview.
    val motion = MotionScheme.standard()
    val slide = motion.defaultSpatialSpec<IntOffset>()
    val fade = motion.defaultEffectsSpec<Float>()
    val forward = slideInHorizontally(slide) { it } + fadeIn(fade) togetherWith
        slideOutHorizontally(slide) { -it / PARALLAX } + fadeOut(fade)
    val backward = slideInHorizontally(slide) { -it / PARALLAX } + fadeIn(fade) togetherWith
        slideOutHorizontally(slide) { it } + fadeOut(fade)

    NavDisplay(
        entries = navigationState.toEntries(entryProvider),
        // Screens slide during transitions; this is what shows between them.
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        sceneStrategies = listOf(listDetailStrategy),
        onBack = { navigator.goBack() },
        transitionSpec = { forward },
        popTransitionSpec = { backward },
    )
}
