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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import li.mercury.bigbrother.R
import li.mercury.bigbrother.feature.jail.impl.R as JailR
import li.mercury.bigbrother.feature.timeline.impl.R as TimelineR

/** A sample app: its name, what it read, when, and its own launcher colours. */
private class SampleApp(val name: Int, val data: Int, val time: String, val icon: ImageVector, val dataIcon: ImageVector, val color: Color, val glyph: Color)

private val sampleApps = listOf(
    SampleApp(R.string.guide_weather, R.string.guide_location, "14:41", Icons.Rounded.WbSunny, Icons.Outlined.LocationOn, Color(0xFF249DE9), Color(0xFFFFF59D)),
    SampleApp(R.string.guide_shop, R.string.guide_clipboard, "14:36", Icons.Rounded.ShoppingBag, Icons.Outlined.ContentPaste, Color(0xFFF4511E), Color.White),
    SampleApp(R.string.guide_chat, R.string.guide_files, "14:22", Icons.Rounded.ChatBubble, Icons.Outlined.Folder, Color(0xFF2E9D5B), Color.White),
    SampleApp(R.string.guide_flashlight, R.string.guide_device_id, "14:08", Icons.Rounded.FlashlightOn, Icons.Outlined.Fingerprint, Color(0xFF37474F), Color(0xFFFFD54F)),
)

/** What Weather gets once isolated: a kind of data and its isolated value. */
private class Isolated(val label: Int, val value: Int, val icon: ImageVector)

private val isolatedData = listOf(
    Isolated(R.string.guide_clipboard, JailR.string.jail_item_clipboard_accounts_on, Icons.Outlined.ContentPaste),
    Isolated(R.string.guide_device_id, JailR.string.jail_item_device_ids_on, Icons.Outlined.Fingerprint),
    Isolated(R.string.guide_files, JailR.string.jail_item_storage_on, Icons.Outlined.Folder),
)

@Composable
internal fun GuideIllustration(pager: PagerState, motion: GuideMotion, modifier: Modifier = Modifier) {
    val description = stringResource(
        when (pager.currentPage) {
            GuidePage.Access.ordinal -> R.string.guide_guard_description
            GuidePage.Timeline.ordinal -> R.string.guide_timeline_description
            else -> R.string.guide_jail_description
        },
    )
    BoxWithConstraints(modifier.clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (maxWidth > 0.dp && maxHeight > 0.dp) {
            // Only the illustration uses stage units; the guide's copy follows the system font scale.
            val unit = minOf(maxWidth / STAGE_WIDTH.dp, maxHeight / STAGE_HEIGHT.dp)
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density * unit, 1f),
                LocalLayoutDirection provides LayoutDirection.Ltr,
            ) {
                Stage(motion, Modifier.size(STAGE_WIDTH.dp, STAGE_HEIGHT.dp))
            }
        }
    }
}

@Composable
private fun Stage(motion: GuideMotion, modifier: Modifier = Modifier) {
    val shapes = remember { StageShapes() }
    val art = StageArt(
        guard = rememberGuardPainter(motion),
        iconBackground = painterResource(R.drawable.ic_launcher_background),
        iconForeground = painterResource(R.drawable.ic_launcher_foreground),
    )
    Box(
        modifier
            .onGloballyPositioned { motion.placeStage(it.positionInWindow(), it.size.width / STAGE_WIDTH) }
            .graphicsLayer { alpha = motion.stageAlpha() },
    ) {
        StageBack(motion, shapes, art)
        TimelineCard(motion)
        // Weather comes last so that it stays on top when it flies.
        for (index in sampleApps.indices.reversed()) AppIcon(motion, index)
        StageFront(motion, shapes)
        sampleApps.indices.forEach { DataChip(motion, it) }
        isolatedData.indices.forEach { IsolatedChip(motion, it) }
    }
}

@Composable
private fun TimelineCard(motion: GuideMotion) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .offset(16.dp, 88.dp)
            .size(296.dp)
            .graphicsLayer {
                val frame = motion.frame()
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationY = frame.cardDy * density
                scaleX = frame.cardScale
                scaleY = frame.cardScale
                alpha = frame.cardAlpha
            }
            .background(colors.surfaceContainer, RoundedCornerShape(24.dp)),
    ) {
        StageText(stringResource(TimelineR.string.today), 16, Modifier.offset(20.dp, 14.dp), weight = FontWeight.Medium)
        StageText("14:00", 12, Modifier.offset(20.dp, 42.dp), colors.onSurfaceVariant)
        sampleApps.forEachIndexed { index, app -> TimelineRow(motion, index, app) }
    }
}

@Composable
private fun TimelineRow(motion: GuideMotion, index: Int, app: SampleApp) {
    val colors = MaterialTheme.colorScheme
    val shape = when (index) {
        0 -> RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)
        sampleApps.lastIndex -> RoundedCornerShape(4.dp, 4.dp, 16.dp, 16.dp)
        else -> RoundedCornerShape(4.dp)
    }
    Box(
        Modifier
            .offset(12.dp, (70 + StageLayout.ROW_STEP * index).dp)
            .size(272.dp, 52.dp)
            .background(colors.surfaceContainerLowest, shape),
    ) {
        StageText(stringResource(app.name), 15, Modifier.offset(62.dp, 7.dp))
        StageText(
            stringResource(app.data),
            12,
            Modifier
                .offset(62.dp, 28.dp)
                .graphicsLayer { alpha = ramp(motion.frame().s, 0.7f + 0.06f * index, 0.82f + 0.06f * index) },
            colors.onSurfaceVariant,
        )
        StageText(app.time, 12, Modifier.align(Alignment.TopEnd).padding(top = 17.dp, end = 14.dp), colors.onSurfaceVariant)
    }
}

@Composable
private fun AppIcon(motion: GuideMotion, index: Int) {
    val app = sampleApps[index]
    Box(
        Modifier
            .size(StageFrame.APP_ICON_SIZE.dp)
            .graphicsLayer {
                val frame = motion.frame()
                frame.app(index, motion.apps[index].value)
                translationX = (frame.x - StageFrame.APP_ICON_SIZE / 2) * density
                translationY = (frame.y - StageFrame.APP_ICON_SIZE / 2) * density
                scaleX = frame.scale
                scaleY = frame.scale
                alpha = frame.alpha
            }
            .background(app.color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(app.icon, null, Modifier.size(22.dp), tint = app.glyph)
    }
}

private val chipShape = RoundedCornerShape(8.dp)

@Composable
private fun DataChip(motion: GuideMotion, index: Int) {
    val colors = MaterialTheme.colorScheme
    val app = sampleApps[index]
    Chip(
        app.dataIcon,
        colors.primary,
        Modifier
            .graphicsLayer {
                val frame = motion.frame()
                val width = size.width / density
                frame.dataChip(index, motion.pops[index].value, width)
                place(frame, width)
                shadowElevation = 2 * density
                shape = chipShape
            }
            .background(colors.surfaceContainerLowest, chipShape),
    ) {
        StageText(stringResource(app.data), 13, weight = FontWeight.Medium)
    }
}

@Composable
private fun IsolatedChip(motion: GuideMotion, index: Int) {
    val colors = MaterialTheme.colorScheme
    val item = isolatedData[index]
    val label = stringResource(item.label)
    val value = stringResource(item.value)
    val text = stringResource(R.string.guide_isolated_value, label, value)
    val start = text.lastIndexOf(value)
    val styled = buildAnnotatedString {
        append(text)
        if (start >= 0) addStyle(SpanStyle(color = colors.tertiary), start, start + value.length)
    }
    Chip(
        item.icon,
        colors.tertiary,
        Modifier
            .graphicsLayer {
                val frame = motion.frame()
                frame.jailChip(index)
                place(frame, size.width / density)
            }
            .background(colors.surface, chipShape)
            .border(1.dp, colors.outlineVariant, chipShape),
    ) {
        Text(styled, color = colors.onSurface, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp, maxLines = 1)
    }
}

private fun GraphicsLayerScope.place(frame: StageFrame, width: Float) {
    translationX = (frame.x - width / 2) * density
    translationY = (frame.y - 16) * density
    scaleX = frame.scale
    scaleY = frame.scale
    alpha = frame.alpha
}

@Composable
private fun Chip(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    Row(
        modifier.height(32.dp).padding(start = 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        label()
    }
}

/** Text drawn in stage units, sized like the prototype's. */
@Composable
private fun StageText(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(text, modifier, color = color, fontSize = size.sp, lineHeight = (size * 1.5f).sp, fontWeight = weight, letterSpacing = 0.sp, maxLines = 1)
}
