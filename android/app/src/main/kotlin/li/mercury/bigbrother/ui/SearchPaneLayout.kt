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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.MutableWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

private data class PaneBounds(val left: Int, val width: Int, val layoutWidth: Int)

@OptIn(ExperimentalLayoutApi::class)
private class SearchPane {
    val insets = MutableWindowInsets()
    var layout: LayoutCoordinates? = null
    var bounds by mutableStateOf<PaneBounds?>(null)
    var windowLeft by mutableStateOf<Int?>(null)

    fun anchorTo(pane: LayoutCoordinates) {
        val host = layout?.takeIf { it.isAttached } ?: return
        val left = host.localPositionOf(pane, Offset.Zero).x.roundToInt()
        bounds = PaneBounds(left, pane.size.width, host.size.width)
    }
}

private val LocalSearchPane = staticCompositionLocalOf<SearchPane?> { null }

/** Places the header above the browse pane while every other pane keeps its full height. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun SearchPaneLayout(
    directive: PaneScaffoldDirective,
    showSearch: Boolean,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pane = remember(directive) { SearchPane() }
    val singlePane = directive.maxHorizontalPartitions == 1
    CompositionLocalProvider(LocalSearchPane provides pane) {
        Layout(
            contents = listOf(
                { if (showSearch) Box { topBar() } },
                { Box(propagateMinConstraints = true) { content() } },
            ),
            modifier = modifier
                .fillMaxSize()
                .onPlaced { pane.layout = it }
                .onGloballyPositioned { pane.windowLeft = it.positionInWindow().x.roundToInt() },
        ) { (header, body), constraints ->
            val layoutWidth = constraints.maxWidth
            val bounds = pane.bounds?.takeIf {
                it.layoutWidth == layoutWidth && it.left >= 0 && it.left + it.width <= layoutWidth && it.width > 0
            }
            val preferredWidth = if (singlePane) layoutWidth else directive.defaultPanePreferredWidth.roundToPx()
            val available = bounds ?: leadingPane(directive, pane.windowLeft, layoutWidth, preferredWidth, layoutDirection)

            val search = available?.let {
                header.singleOrNull()?.measure(constraints.copy(minWidth = it.width, maxWidth = it.width, minHeight = 0))
            }
            if (search != null && pane.insets.getTop(this) != search.height) {
                // Measure the header first so the browse pane gets the inset in this layout pass.
                pane.insets.insets = WindowInsets(top = search.height)
            }

            val navigation = body.single().measure(constraints)
            layout(navigation.width, navigation.height) {
                navigation.place(0, 0)
                if (search != null) search.place(available.left, 0, zIndex = 1f)
            }
        }
    }
}

/** A browse destination takes the header inset and can report its actual adaptive pane width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchPaneContent(
    anchorSearch: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pane = LocalSearchPane.current
    val anchor = if (pane != null && anchorSearch) Modifier.onGloballyPositioned(pane::anchorTo) else Modifier
    val inset = if (pane != null) Modifier.windowInsetsPadding(pane.insets) else Modifier
    Box(
        modifier = modifier.fillMaxSize().then(anchor).then(inset),
        propagateMinConstraints = true,
        content = { content() },
    )
}

/** Aligns Overview with the list column before a list has been laid out in this window. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun leadingPane(
    directive: PaneScaffoldDirective,
    windowLeft: Int?,
    layoutWidth: Int,
    preferredWidth: Int,
    direction: LayoutDirection,
): PaneBounds? {
    val isLtr = direction == LayoutDirection.Ltr
    val hinges = directive.excludedBounds.filter { it.height > it.width }
    val hinge = if (isLtr) hinges.firstOrNull() else hinges.lastOrNull()
    val split = hinge?.let {
        val origin = windowLeft ?: return null
        if (isLtr) it.left.roundToInt() - origin else origin + layoutWidth - it.right.roundToInt()
    }
    val width = split?.takeIf { it in 1 until layoutWidth } ?: preferredWidth.coerceIn(0, layoutWidth)
    return PaneBounds(if (isLtr) 0 else layoutWidth - width, width, layoutWidth)
}
