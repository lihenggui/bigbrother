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
import android.graphics.drawable.Animatable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import li.mercury.bigbrother.R
import li.mercury.bigbrother.core.designsystem.icon.CageIcons
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import li.mercury.bigbrother.feature.jail.impl.R as JailR
import li.mercury.bigbrother.feature.timeline.impl.R as TimelineR

private fun ramp(value: Float, start: Float, end: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

@Composable
internal fun GuideIllustration(pager: PagerState, playedOpening: Boolean, onOpeningStart: () -> Unit, modifier: Modifier = Modifier) {
    // The pager invalidates drawing on every drag frame. Compose new content only when a layer enters or leaves.
    val motion = remember(pager) {
        derivedStateOf {
            val fraction = if (ValueAnimator.areAnimatorsEnabled()) pager.currentPageOffsetFraction else 0f
            GuideMotion((pager.currentPage + fraction).coerceIn(0f, GuidePage.entries.lastIndex.toFloat()))
        }
    }
    val showArtwork by remember(motion) { derivedStateOf { motion.value.setup < 1f } }
    val showEffects by remember(motion) { derivedStateOf { motion.value.setup > 0f } }
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (showArtwork && maxWidth > 0.dp && maxHeight > 0.dp) {
            // Only the miniature artwork uses illustration coordinates. All guide copy follows the system font scale.
            val unit = minOf(maxWidth / 400.dp, maxHeight / 342.dp)
            val description = stringResource(
                when (pager.currentPage) {
                    0 -> R.string.guide_guard_description
                    1 -> R.string.guide_timeline_description
                    else -> R.string.guide_jail_description
                },
            )
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density * unit, 1f),
                LocalLayoutDirection provides LayoutDirection.Ltr,
            ) {
                Artwork({ motion.value }, playedOpening, onOpeningStart, Modifier.size(400.dp, 342.dp).clearAndSetSemantics { contentDescription = description })
            }
        }
        if (showEffects) {
            val semantics = if (pager.currentPage == GuidePage.Framework.ordinal) Modifier else Modifier.clearAndSetSemantics {}
            Column(
                Modifier.graphicsLayer { alpha = motion.value.setup }.then(semantics).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Effect(R.string.guide_android_id, R.string.guide_android_id_result, RoundedCornerShape(18.dp, 18.dp, 4.dp, 4.dp))
                Effect(R.string.guide_ad_id, R.string.guide_ad_id_result, RoundedCornerShape(4.dp))
                Effect(R.string.guide_apps, R.string.guide_apps_result, RoundedCornerShape(4.dp))
                Effect(R.string.guide_files, R.string.guide_files_result, RoundedCornerShape(4.dp, 4.dp, 18.dp, 18.dp))
            }
        }
    }
}

@Composable
private fun Effect(title: Int, body: Int, shape: RoundedCornerShape) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, shape).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class GuideMotion(progress: Float) {
    private val shrink = progress.coerceIn(0f, 1f)
    val travel = (progress - 1f).coerceIn(0f, 1f)
    val setup = ramp(progress, 2f, 3f)
    private val slide = ramp(shrink, .04f, 1f)
    val x = lerp(lerp(200f, 65f, slide), 345f, travel)
    val y = lerp(lerp(166f, 172f, slide) - 14f * sin(PI * slide).toFloat(), 44f, travel) - 12f * sin(PI * travel).toFloat()
    val scale = (1f / .15f).pow(1f - shrink)
    val reveal = ramp(shrink, .18f, .84f)
    val panelOpacity = ramp(shrink, .18f, .6f) * (1 - travel)
    val rowsOpacity = ramp(shrink, .55f, .94f) * (1 - travel)
    val weather = ramp(shrink, .36f, .87f)
    val badgeOpacity = ramp(shrink, .78f, 1f)
    val guardOpacity = 1 - ramp(shrink, .84f, .87f)
}

@Composable
private fun Artwork(motion: () -> GuideMotion, playedOpening: Boolean, onOpeningStart: () -> Unit, modifier: Modifier = Modifier) {
    val showTimeline by remember(motion) { derivedStateOf { motion().rowsOpacity > 0f } }
    val showJail by remember(motion) { derivedStateOf { motion().travel > 0f } }
    val showPortrait by remember(motion) { derivedStateOf { motion().guardOpacity > 0f } }
    val showWeather by remember(motion) { derivedStateOf { motion().weather > 0f } }
    val inJail by remember(motion) { derivedStateOf { motion().travel > .5f } }
    Box(modifier) {
        Box(
            Modifier.size(364.dp, 291.dp).graphicsLayer {
                val frame = motion()
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = lerp(frame.x - 18, 18f, frame.reveal).dp.toPx()
                translationY = lerp(frame.y - 18, 16f, frame.reveal).dp.toPx()
                scaleX = lerp(36f / 364, 1f, frame.reveal)
                scaleY = lerp(36f / 291, 1f, frame.reveal)
                alpha = frame.panelOpacity
            }.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)),
        )
        if (showTimeline) {
            TimelineExcerpt(
                Modifier.graphicsLayer {
                    translationX = (-32 * motion().travel).dp.toPx()
                    alpha = motion().rowsOpacity
                },
            )
        }
        if (showJail) {
            JailExcerpt(
                Modifier.graphicsLayer {
                    translationX = ((1 - motion().travel) * 40).dp.toPx()
                    alpha = motion().travel * (1 - motion().setup)
                },
            )
        }
        if (showPortrait) {
            val preview = LocalInspectionMode.current
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        val animate = !preview && !playedOpening && ValueAnimator.areAnimatorsEnabled()
                        setImageResource(if (animate) R.drawable.guide_guard_flex else R.drawable.guide_guard)
                        if (animate) (drawable as Animatable).start()
                        onOpeningStart()
                    }
                },
                modifier = Modifier.size(280.dp).graphicsLayer {
                    val frame = motion()
                    translationX = (frame.x - 140).dp.toPx()
                    translationY = (frame.y - 140).dp.toPx()
                    scaleX = .15f * frame.scale
                    scaleY = scaleX
                    alpha = frame.guardOpacity
                },
            )
        }
        if (showWeather) {
            Box(
                Modifier.size(36.dp).graphicsLayer {
                    val frame = motion()
                    translationX = (frame.x - 18).dp.toPx()
                    translationY = (frame.y - 18).dp.toPx()
                    scaleX = frame.scale
                    scaleY = scaleX
                    alpha = frame.weather * (1 - frame.setup)
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFF249DE9), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.WbSunny, null, Modifier.fillMaxSize(.56f), tint = Color(0xFFFFF59D))
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(16.dp).graphicsLayer { alpha = motion().badgeOpacity }
                        .background(if (inJail) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (inJail) CageIcons.Filled else Icons.Outlined.Folder,
                        null,
                        Modifier.size(11.dp),
                        tint = if (inJail) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineExcerpt(modifier: Modifier = Modifier) {
    val palette = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize()) {
        Row(Modifier.offset(34.dp, 35.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (label in listOf(TimelineR.string.all_data, TimelineR.string.records_access)) {
                Box(Modifier.border(1.dp, palette.outlineVariant, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    MiniText(stringResource(label) + " ▾", 14)
                }
            }
        }
        MiniText(stringResource(TimelineR.string.today), 18, Modifier.offset(48.dp, 77.dp))
        MiniText("14:00", 14, Modifier.offset(48.dp, 109.dp))
        SampleRow(143, R.string.guide_weather, R.string.guide_weather_summary, "14:41", true)
        SampleRow(202, R.string.guide_flashlight, R.string.guide_flashlight_summary, "14:22", false)
        Caption(R.string.guide_timeline_caption, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SampleRow(y: Int, label: Int, summary: Int, time: String, first: Boolean) {
    val shape = if (first) RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp) else RoundedCornerShape(4.dp, 4.dp, 16.dp, 16.dp)
    Box(Modifier.offset(34.dp, y.dp).size(332.dp, 57.dp).background(MaterialTheme.colorScheme.surface, shape)) {
        if (!first) {
            Box(Modifier.offset(13.dp, 11.dp).size(36.dp).background(Color(0xFF37474F), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.FlashlightOn, null, Modifier.size(22.dp), tint = Color(0xFFFFD54F))
            }
        }
        MiniText(stringResource(label), 17, Modifier.offset(63.dp, 5.dp))
        MiniText(stringResource(summary), 13, Modifier.offset(63.dp, 31.dp))
        MiniText(time, 13, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
    }
}

@Composable
private fun JailExcerpt(modifier: Modifier = Modifier) {
    val palette = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize()) {
        Box(Modifier.offset(18.dp, 10.dp).size(364.dp, 300.dp).background(palette.surfaceContainer, RoundedCornerShape(24.dp)))
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.offset(33.dp, 25.dp).size(19.dp), tint = palette.onSurface)
        MiniText(stringResource(R.string.guide_weather), 24, Modifier.offset(35.dp, 49.dp))
        MiniText("com.example.weather", 13, Modifier.offset(35.dp, 80.dp))
        Box(Modifier.offset(34.dp, 108.dp).size(161.dp, 30.dp).border(1.dp, palette.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
            MiniText(stringResource(JailR.string.jail_release), 13)
        }
        Box(Modifier.offset(201.dp, 108.dp).size(165.dp, 30.dp).background(palette.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            MiniText(stringResource(JailR.string.jail_records), 13)
        }
        MiniText(stringResource(JailR.string.jail_section_items), 14, Modifier.offset(45.dp, 148.dp))
        MiniText(stringResource(JailR.string.jail_reset), 13, Modifier.align(Alignment.TopEnd).padding(end = 42.dp, top = 149.dp))
        MiniText(stringResource(JailR.string.jail_items_summary), 11, Modifier.offset(45.dp, 174.dp).width(312.dp))
        IsolationRow(210, Icons.Outlined.Folder, JailR.string.jail_item_storage, JailR.string.jail_item_storage_on)
        IsolationRow(256, Icons.Outlined.ContentPaste, JailR.string.jail_item_clipboard_accounts, JailR.string.jail_item_clipboard_accounts_on)
        Caption(R.string.guide_jail_caption, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun IsolationRow(y: Int, icon: ImageVector, title: Int, value: Int) {
    val palette = MaterialTheme.colorScheme
    Row(
        Modifier.offset(34.dp, y.dp).size(332.dp, 43.dp).background(palette.surface, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(29.dp).background(palette.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(16.dp), tint = palette.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            MiniText(stringResource(title), 14)
            MiniText(stringResource(value), 11)
        }
        Box(Modifier.size(38.dp, 22.dp).background(palette.primary, CircleShape).padding(3.dp), contentAlignment = Alignment.CenterEnd) {
            Icon(Icons.Outlined.Check, null, Modifier.size(16.dp).background(palette.onPrimary, CircleShape).padding(2.dp), tint = palette.primary)
        }
    }
}

@Composable
private fun Caption(label: Int, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surface, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp)) {
        MiniText(stringResource(label), 12)
    }
}

@Composable
private fun MiniText(text: String, size: Int, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        fontSize = size.sp,
        lineHeight = (size + 3).sp,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Normal,
    )
}
