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

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuite
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.util.lerp
import androidx.navigation3.runtime.NavKey
import li.mercury.bigbrother.feature.overview.api.navigation.OverviewNavKey
import kotlin.math.roundToInt
import li.mercury.bigbrother.core.ui.R as UiR

@Composable
internal fun NavigationItems(
    recordingStopped: Boolean,
    suiteType: NavigationSuiteType,
    selectedRoot: NavKey,
    onSelect: (NavKey) -> Unit,
) {
    for (destination in TOP_LEVEL_DESTINATIONS) {
        val selected = selectedRoot == destination.key
        // Mark the overview from other destinations while recording is stopped.
        val stopped = recordingStopped && destination.key == OverviewNavKey && !selected
        NavigationDestinationItem(destination, suiteType, selected, stopped) { onSelect(destination.key) }
    }
}

@Composable
private fun NavigationDestinationItem(
    destination: TopLevelDestination,
    suiteType: NavigationSuiteType,
    selected: Boolean,
    stopped: Boolean,
    onClick: () -> Unit,
) {
    val status = stringResource(UiR.string.recording_stopped)
    val modifier = if (stopped) Modifier.semantics { stateDescription = status } else Modifier
    NavigationSuiteItem(
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                if (selected) destination.selectedIcon else destination.icon,
                contentDescription = null,
            )
        },
        label = { Text(stringResource(destination.label)) },
        navigationSuiteType = suiteType,
        badge = if (stopped) {
            { Badge() }
        } else {
            null
        },
    )
}

@Composable
internal fun BarLayout(
    suiteType: NavigationSuiteType,
    showBar: Boolean,
    previousShowsBar: Boolean?,
    progress: Float?,
    navigationItems: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val navigationColors = NavigationSuiteDefaults.colors()
    val bottomInsets = ShortNavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
    val barVisibility = rememberBarVisibility(showBar, previousShowsBar, progress)
    val barRevealed by remember(barVisibility) { derivedStateOf { barVisibility.value > 0f } }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar || previousShowsBar == true || barRevealed) {
                NavigationSuite(
                    navigationSuiteType = suiteType,
                    colors = navigationColors,
                    modifier = Modifier
                        .background(navigationColors.shortNavigationBarContainerColor)
                        .windowInsetsPadding(bottomInsets)
                        .revealBar(barVisibility, top = false),
                    content = navigationItems,
                )
            }
        },
    ) { padding ->
        // The bar includes the bottom system inset, so child scaffolds get only any unconsumed space.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
    }
}

@Composable
internal fun RailLayout(
    suiteType: NavigationSuiteType,
    navigationItems: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    NavigationSuiteScaffold(
        modifier = modifier,
        navigationItems = navigationItems,
        navigationSuiteType = suiteType,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

/** Settle from the last measured gesture frame when the user releases or cancels. */
@Composable
internal fun rememberBarVisibility(current: Boolean, previous: Boolean?, progress: Float?): State<Float> {
    val resting = if (current) 1f else 0f
    val visibility = remember { Animatable(resting) }
    val motion = remember { MotionScheme.standard() }
    LaunchedEffect(current, previous, progress) {
        if (previous != null && progress != null) {
            visibility.snapTo(lerp(resting, if (previous) 1f else 0f, progress))
        } else {
            // The visibility sets the bar's height, so it moves as space does.
            visibility.animateTo(resting, motion.fastSpatialSpec())
        }
    }
    return visibility.asState()
}

/** The bar occupies only its revealed height, keeping the destination's insets in sync. */
internal fun Modifier.revealBar(visibility: State<Float>, top: Boolean): Modifier = clipToBounds().layout { measurable, constraints ->
    val bar = measurable.measure(constraints)
    // A spring can pass its target by a fraction; the bar never grows past itself.
    val height = (bar.height * visibility.value.coerceIn(0f, 1f)).roundToInt()
    layout(bar.width, height) {
        bar.placeRelative(0, if (top) height - bar.height else 0)
    }
}
