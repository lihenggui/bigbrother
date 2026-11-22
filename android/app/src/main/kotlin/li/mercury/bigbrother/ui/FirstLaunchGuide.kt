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

import android.animation.ValueAnimator
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import li.mercury.bigbrother.R

internal enum class GuidePage(val title: Int, val body: Int) {
    Access(R.string.guide_access_title, R.string.guide_access_body),
    Timeline(R.string.guide_timeline_title, R.string.guide_timeline_body),
    Jail(R.string.guide_jail_title, R.string.guide_jail_body),
}

/** How the guide was left: Skip fades through, Get started grows its button into the app. */
internal sealed interface GuideExit {
    data object Skip : GuideExit

    data class Start(val button: Rect) : GuideExit
}

@Composable
internal fun FirstLaunchGuide(
    onDismiss: (GuideExit) -> Unit,
    modifier: Modifier = Modifier,
    pager: PagerState = rememberPagerState { GuidePage.entries.size },
    splash: SplashHandoff? = null,
) {
    val scope = rememberCoroutineScope()
    val page = pager.settledPage
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    val still = !animate || LocalInspectionMode.current
    var playedOpening by rememberSaveable { mutableStateOf(false) }
    val opening = remember { !still && !playedOpening }
    val position = remember(pager) { { (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, GuidePage.entries.lastIndex.toFloat()) } }
    val motion = remember(pager) { GuideMotion(position, reduced = !animate, opening = opening, splash = splash.takeIf { opening }) }
    GuideEffects(motion, opening, still, position) { playedOpening = true }

    fun navigate(target: Int) {
        scope.launch {
            if (animate) pager.animateScrollToPage(target) else pager.scrollToPage(target)
        }
    }
    GuideBackHandler(pager, page, ::navigate)

    Surface(modifier.fillMaxSize().graphicsLayer { alpha = motion.guideAlpha.value }) {
        Column(Modifier.safeDrawingPadding().wrapContentWidth().widthIn(max = 1120.dp).fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(44.dp).guideChrome(motion)) {
                TextButton(onClick = { onDismiss(GuideExit.Skip) }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp)) {
                    Text(stringResource(R.string.guide_skip))
                }
            }
            GuideBody(
                pager = pager,
                motion = motion,
                position = position,
                onBack = { navigate((position() + 0.5f).toInt() - 1) },
                onNext = { navigate((position() + 0.5f).toInt() + 1) },
                onStart = { onDismiss(GuideExit.Start(it)) },
            )
        }
    }
}

@Composable
private fun GuideEffects(motion: GuideMotion, opening: Boolean, still: Boolean, position: () -> Float, onOpeningStart: () -> Unit) {
    val scheme = MaterialTheme.motionScheme
    val specs = remember(scheme) { GuideSpecs(scheme) }
    LaunchedEffect(motion) { motion.follow(specs) }
    if (opening) {
        LaunchedEffect(motion) {
            onOpeningStart()
            motion.open(specs)
        }
    }
    // The beam keeps moving only while the first page is on screen.
    val nearFirstPage by remember(position) { derivedStateOf { position() < 0.5f } }
    if (!still && motion.watching && nearFirstPage) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(motion, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { motion.watch(specs) }
        }
    }
}

@Composable
private fun GuideBody(pager: PagerState, motion: GuideMotion, position: () -> Float, onBack: () -> Unit, onNext: () -> Unit, onStart: (Rect) -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val art: @Composable (Modifier) -> Unit = { area ->
        GuideIllustration(
            pager,
            motion,
            area.scrollable(
                state = pager,
                orientation = Orientation.Horizontal,
                reverseDirection = !rtl,
                flingBehavior = PagerDefaults.flingBehavior(pager),
            ),
        )
    }
    val text: @Composable (Modifier, Dp) -> Unit = { area, gap ->
        Column(area.guideChrome(motion)) {
            GuideIndicator(position, Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            GuideCopy(pager, Modifier.fillMaxWidth())
            Spacer(Modifier.height(gap))
            GuideFooter(position, onBack, onNext, onStart, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= 740.dp || (maxWidth >= 480.dp && maxHeight < 420.dp)
        if (sideBySide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                art(Modifier.weight(1f).heightIn(max = 540.dp).fillMaxHeight().padding(16.dp))
                text(Modifier.weight(1f), 32.dp)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                art(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp))
                Spacer(Modifier.height(18.dp))
                text(Modifier.fillMaxWidth(), 8.dp)
            }
        }
    }
}

@Composable
private fun GuideBackHandler(pager: PagerState, page: Int, navigate: (Int) -> Unit) {
    PredictiveBackHandler(enabled = page > 0) { events ->
        try {
            pager.scroll(MutatePriority.UserInput) {
                // Keep the same settled page until the gesture commits or rolls back.
                events.collect { if (page > 0) with(pager) { updateCurrentPage(page, -0.45f * it.progress) } }
            }
        } catch (cancelled: CancellationException) {
            navigate(page)
            throw cancelled
        }
        if (page > 0) navigate(page - 1)
    }
}
