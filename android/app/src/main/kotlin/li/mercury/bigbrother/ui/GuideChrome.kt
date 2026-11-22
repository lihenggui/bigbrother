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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.R
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.util.lerp as lerpFloat

private val ButtonHeight = 56.dp
private val ButtonGap = 12.dp

/** The opening fades the chrome in and lifts it 16dp. */
internal fun Modifier.guideChrome(motion: GuideMotion) = graphicsLayer {
    alpha = motion.chromeAlpha.value
    translationY = (1 - motion.chromeRise.value) * 16.dp.toPx()
}

/** A fixed indicator: each dot widens and takes the primary colour as the pager position nears its page. */
@Composable
internal fun GuideIndicator(position: () -> Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val count = GuidePage.entries.size
    val page by remember(position) { derivedStateOf { position().roundToInt().coerceIn(0, count - 1) } }
    val description = stringResource(R.string.guide_progress, page + 1, count)
    Spacer(
        modifier
            .size((8 * count + 20 + 6 * (count - 1)).dp, 8.dp)
            .semantics { contentDescription = description }
            .drawBehind {
                val p = position()
                var x = 0f
                for (index in 0 until count) {
                    val near = (1 - abs(p - index)).coerceIn(0f, 1f)
                    val width = (8 + 20 * near).dp.toPx()
                    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - x - width else x
                    drawRoundRect(lerp(colors.outlineVariant, colors.primary, near), Offset(left, 0f), Size(width, size.height), CornerRadius(size.height / 2))
                    x += width + 6.dp.toPx()
                }
            },
    )
}

/** The copy follows the finger exactly; the tallest page sets the height so that the art does not resize. */
@Composable
internal fun GuideCopy(pager: PagerState, modifier: Modifier = Modifier) {
    Box(modifier) {
        GuidePage.entries.forEach { CopyPage(it, Modifier.alpha(0f).clearAndSetSemantics {}) }
        HorizontalPager(state = pager, modifier = Modifier.matchParentSize(), verticalAlignment = Alignment.Top) { index ->
            CopyPage(GuidePage.entries[index])
        }
    }
}

@Composable
private fun CopyPage(page: GuidePage, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        // Balanced lines, so that a two-line title does not leave one word on its own.
        Text(stringResource(page.title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium.copy(lineBreak = LineBreak.Heading))
        Spacer(Modifier.height(10.dp))
        Text(stringResource(page.body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Where the Next button is, for the container transform when it becomes the app. */
private class Bounds {
    var rect = Rect.Zero
}

/**
 * The back button grows in with the position; the Next button fills the row on the last page, where its label becomes
 * Get started. Both follow the pager position, not the settled page.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GuideFooter(position: () -> Float, onBack: () -> Unit, onNext: () -> Unit, onStart: (Rect) -> Unit, modifier: Modifier = Modifier) {
    val last = GuidePage.entries.lastIndex
    val atEnd by remember(position) { derivedStateOf { position() > last - 0.5f } }
    val showBack by remember(position) { derivedStateOf { position() > 0.01f } }
    val fill = animateFloatAsState(if (atEnd) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "fill")
    val bounds = remember { Bounds() }
    Box(modifier.fillMaxWidth().height(ButtonHeight)) {
        if (showBack) BackButton(position, onBack)
        Button(
            onClick = { if (position() > last - 0.5f) onStart(bounds.rect) else onNext() },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillRow(fill, ButtonHeight + ButtonGap)
                .onGloballyPositioned { bounds.rect = it.boundsInRoot() },
            contentPadding = ButtonDefaults.MediumContentPadding,
        ) {
            NextLabel(atEnd)
        }
    }
}

@Composable
private fun BackButton(position: () -> Float, onBack: () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    OutlinedIconButton(
        onClick = onBack,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(ButtonHeight).graphicsLayer {
            val shown = position().coerceIn(0f, 1f)
            transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0.5f)
            scaleX = lerpFloat(0.6f, 1f, shown)
            scaleY = scaleX
            alpha = ramp(shown, 0.15f, 0.85f)
        },
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.guide_back), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NextLabel(atEnd: Boolean) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = atEnd,
        transitionSpec = {
            // The old label leaves fast and the new one arrives at the default speed; the button's width is sprung separately.
            fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) using SizeTransform(clip = false) { _, _ -> snap() }
        },
        contentAlignment = Alignment.Center,
        label = "next label",
    ) { end ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (end) R.string.guide_start else R.string.guide_next), style = ButtonDefaults.textStyleFor(ButtonHeight))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(20.dp))
        }
    }
}

/** Measures the button between its own width and the row's, less the back button, by [fill]; read at layout only. */
private fun Modifier.fillRow(fill: State<Float>, reserved: Dp) = layout { measurable, constraints ->
    val height = ButtonHeight.roundToPx()
    val natural = measurable.maxIntrinsicWidth(height)
    val full = (constraints.maxWidth - reserved.roundToPx()).coerceAtLeast(natural)
    val width = lerpFloat(natural.toFloat(), full.toFloat(), fill.value).roundToInt().coerceIn(0, constraints.maxWidth)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
