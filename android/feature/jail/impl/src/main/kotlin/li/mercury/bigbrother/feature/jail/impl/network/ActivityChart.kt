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

package li.mercury.bigbrother.feature.jail.impl.network

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.model.ActivityBucket
import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.feature.jail.impl.R

/**
 * Connections period by period as bars: those made, and above them, in the
 * tertiary color, those refused. With [onSelect], a tap on a bar chooses
 * it, and a tap on the bar chosen lets it go: the others fade while one is
 * chosen. No bar grows in; the chart is redrawn as it is.
 */
@Composable
internal fun ActivityChart(
    buckets: List<ActivityBucket>,
    bucketSeconds: Int,
    selected: Long?,
    onSelect: ((Long?) -> Unit)?,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
) {
    val colors = MaterialTheme.colorScheme
    val connected = colors.primary
    val refused = colors.tertiary
    val band = colors.secondaryContainer
    val axis = colors.outlineVariant
    // Others fade while a bar is chosen, as an effect: no overshoot.
    val faded by animateFloatAsState(if (selected != null) 0.3f else 1f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "faded")
    val max = buckets.maxOfOrNull { it.connections + it.refused }?.coerceAtLeast(1) ?: 1
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .then(
                    if (onSelect != null && buckets.isNotEmpty()) {
                        Modifier.pointerInput(buckets, selected) {
                            detectTapGestures { offset ->
                                val index = (offset.x / size.width * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
                                val start = buckets[index].start
                                onSelect(start.takeIf { it != selected })
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (buckets.isEmpty()) return@Canvas
            val gap = if (buckets.size > 48) 1.dp.toPx() else 3.dp.toPx()
            val width = ((size.width - gap * (buckets.size - 1)) / buckets.size).coerceAtLeast(1f)
            val corner = CornerRadius(minOf(2.dp.toPx(), width / 2))
            buckets.forEachIndexed { index, bucket ->
                val x = index * (width + gap)
                val chosen = bucket.start == selected
                if (chosen) {
                    drawRoundRect(band, Offset(x - gap / 2 - 1, 0f), Size(width + gap + 2, size.height), CornerRadius(4.dp.toPx()))
                }
                val alpha = if (selected == null || chosen) 1f else faded
                val made = bucket.connections.toFloat() / max * size.height
                val refusedHeight = bucket.refused.toFloat() / max * size.height
                if (made > 0) drawRoundRect(connected, Offset(x, size.height - made), Size(width, made), corner, alpha = alpha)
                if (refusedHeight > 0) {
                    val top = size.height - made - refusedHeight - if (made > 0) 1.dp.toPx() else 0f
                    drawRoundRect(refused, Offset(x, top), Size(width, refusedHeight), corner, alpha = alpha)
                }
            }
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
        AxisLabels(buckets, bucketSeconds)
    }
}

/** A few times under the chart: its start, three between, and now at its end. */
@Composable
private fun AxisLabels(buckets: List<ActivityBucket>, bucketSeconds: Int) {
    if (buckets.size < 2) return
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val marks = listOf(0, buckets.size / 4, buckets.size / 2, buckets.size * 3 / 4).distinct()
    Row(Modifier.fillMaxWidth()) {
        marks.forEach { index ->
            val start = buckets[index].start
            val label = if (bucketSeconds >= BucketSize.DAY.seconds) shortDate(start) else clockTime(start)
            Text(label, style = style, color = color, modifier = Modifier.weight(1f), maxLines = 1)
        }
        Text(stringResource(R.string.jail_net_now), style = style, color = color, textAlign = TextAlign.End, maxLines = 1)
    }
}

/** What the chart's colors stand for. */
@Composable
internal fun ChartLegend(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendKey(MaterialTheme.colorScheme.primary, stringResource(R.string.jail_net_legend_connected))
        LegendKey(MaterialTheme.colorScheme.tertiary, stringResource(R.string.jail_net_legend_refused))
    }
}

@Composable
private fun LegendKey(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
