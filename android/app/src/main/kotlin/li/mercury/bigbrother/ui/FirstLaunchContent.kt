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
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import li.mercury.bigbrother.core.ui.rememberPredictiveBackTransition

@Composable
internal fun FirstLaunchContent(showGuide: Boolean, onDismiss: () -> Unit, splash: SplashHandoff? = null, content: @Composable () -> Unit) {
    val pager = rememberPagerState { GuidePage.entries.size }
    var exit by remember { mutableStateOf<GuideExit?>(null) }
    val transition = rememberPredictiveBackTransition(
        current = showGuide,
        previous = false.takeIf { showGuide && pager.settledPage == 0 },
        onBack = onDismiss,
    )
    val scheme = MaterialTheme.motionScheme
    transition.AnimatedContent(transitionSpec = { exitTransform(exit, scheme) }) { show ->
        if (show) {
            FirstLaunchGuide(
                onDismiss = {
                    exit = it
                    onDismiss()
                },
                pager = pager,
                splash = splash,
            )
        } else {
            RevealedContent(exit as? GuideExit.Start, scheme, content)
        }
    }
}

private fun AnimatedContentTransitionScope<Boolean>.exitTransform(exit: GuideExit?, scheme: MotionScheme): ContentTransform = when (exit) {
    // Back, leaving the app, or the predictive back preview.
    null -> fadeIn() togetherWith (scaleOut(targetScale = 0.9f) + fadeOut())

    // Fade through: the guide is gone before the app comes in.
    GuideExit.Skip -> fadeIn(DelayedSpec(SKIP_DELAY_MS, scheme.defaultEffectsSpec())) togetherWith fadeOut(scheme.fastEffectsSpec())

    // The app is revealed inside the growing button by RevealedContent.
    is GuideExit.Start -> EnterTransition.None togetherWith fadeOut(scheme.fastEffectsSpec())
}

/**
 * Get started: the button's container grows to the window without a bounce, turning from primary to surface, and the
 * app appears inside it.
 */
@Composable
private fun AnimatedVisibilityScope.RevealedContent(exit: GuideExit.Start?, scheme: MotionScheme, content: @Composable () -> Unit) {
    val reveal = if (exit == null) {
        Modifier
    } else {
        val colors = MaterialTheme.colorScheme
        Modifier.revealFrom(
            exit.button,
            grow = transition.animateFloat({ MotionScheme.standard().slowSpatialSpec() }, label = "grow") { it.shown() },
            tint = transition.animateFloat({ scheme.defaultEffectsSpec() }, label = "tint") { it.shown() },
            fade = transition.animateFloat({ DelayedSpec(START_DELAY_MS, scheme.defaultEffectsSpec()) }, label = "fade") { it.shown() },
            from = colors.primary,
            to = colors.surface,
        )
    }
    Box(reveal) { content() }
}

private fun Modifier.revealFrom(button: Rect, grow: State<Float>, tint: State<Float>, fade: State<Float>, from: Color, to: Color) = fillMaxSize()
    .drawWithCache {
        val clip = Path()
        val radius = 28.dp.toPx()
        onDrawWithContent {
            val progress = grow.value
            if (progress >= 1f && tint.value >= 1f) {
                drawContent()
            } else {
                val corner = lerp(radius, 0f, progress.coerceIn(0f, 1f))
                clip.rewind()
                clip.addRoundRect(
                    RoundRect(
                        left = lerp(button.left, 0f, progress),
                        top = lerp(button.top, 0f, progress),
                        right = lerp(button.right, size.width, progress),
                        bottom = lerp(button.bottom, size.height, progress),
                        cornerRadius = CornerRadius(corner),
                    ),
                )
                clipPath(clip) {
                    drawRect(lerp(from, to, tint.value.coerceIn(0f, 1f)))
                    this@onDrawWithContent.drawContent()
                }
            }
        }
    }
    // Inside the clip, so that only the app fades in, not the container.
    .graphicsLayer { alpha = fade.value.coerceIn(0f, 1f) }

private fun EnterExitState.shown() = if (this == EnterExitState.Visible) 1f else 0f

private const val SKIP_DELAY_MS = 150
private const val START_DELAY_MS = 120
