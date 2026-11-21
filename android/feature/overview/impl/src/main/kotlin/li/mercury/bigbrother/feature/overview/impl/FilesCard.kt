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

package li.mercury.bigbrother.feature.overview.impl

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.ui.AppIcon
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.appDisplayName
import li.mercury.bigbrother.core.ui.R as UiR

/** Apps shown on the files card before the rest are counted. */
private const val ICONS_SHOWN = 5

/**
 * The overview's one emphasized element: how many apps read the user's
 * files, which of them, and from where. Opens the list of those apps.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FilesCard(files: FilesOverview, appInfo: Map<String, AppInfo>, wide: Boolean, onClick: () -> Unit) {
    val anyRead = files.readers.isNotEmpty()
    // Without reads there is nothing to draw attention to.
    val container = if (anyRead) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (anyRead) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapedIcon(
                    icon = Icons.Outlined.FolderOpen,
                    containerColor = if (anyRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (anyRead) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(DataCategory.FILES.title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
            if (anyRead) {
                val count = files.readers.size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                        Text(count.toString(), style = MaterialTheme.typography.displayMediumEmphasized)
                        Text(
                            text = pluralStringResource(R.plurals.readers_label, count),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                        )
                    }
                    if (wide) IconStack(files, appInfo)
                }
                if (!wide) IconStack(files, appInfo)
                val places = buildList {
                    if (files.folders.isNotEmpty()) add(files.folders.joinToString(stringResource(UiR.string.summary_separator)))
                    if (files.mediaLibrary) add(stringResource(UiR.string.summary_media_library))
                }
                if (places.isNotEmpty()) {
                    Text(places.joinToString(stringResource(UiR.string.dot_separator)), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(stringResource(R.string.no_reads), style = MaterialTheme.typography.headlineSmall)
            }
            if (files.lookedOnly > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ManageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = pluralStringResource(R.plurals.looked_only, files.lookedOnly, files.lookedOnly),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** The icons of the first apps that read files, overlapping, then how many more. */
@Composable
private fun IconStack(files: FilesOverview, appInfo: Map<String, AppInfo>) {
    val ring = MaterialTheme.colorScheme.primaryContainer
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Overlapping(overlap = 8.dp) {
            for (app in files.readers.take(ICONS_SHOWN)) {
                val info = appInfo[app.packageName]
                AppIcon(
                    info = info,
                    name = appDisplayName(app, info),
                    size = 32.dp,
                    modifier = Modifier.ring(ring, 2.dp),
                )
            }
        }
        val more = files.readers.size - ICONS_SHOWN
        if (more > 0) {
            Text(stringResource(R.string.more_count, more), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Lays its children out in a row, each overlapping the previous one by [overlap], the first on top. */
@Composable
private fun Overlapping(overlap: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val step = overlap.roundToPx()
        val width = (placeables.sumOf { it.width } - step * (placeables.size - 1)).coerceAtLeast(0)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2, zIndex = (placeables.size - index).toFloat())
                x += placeable.width - step
            }
        }
    }
}

/** A ring in [color] around a round icon, setting it off the icon it overlaps. */
private fun Modifier.ring(color: Color, width: Dp): Modifier = this
    .clip(CircleShape)
    .border(width, color, CircleShape)
