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

package li.mercury.bigbrother.feature.jail.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.designsystem.icon.CageIcons
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.ui.AppIcon

/** Outer and inner corners of a row in a segmented group, as Material lists have them. */
private val OUTER_CORNER = 16.dp
private val INNER_CORNER = 4.dp

/** The shape of a row in a segmented group, for clipping its touch feedback. */
internal fun segmentShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) OUTER_CORNER else INNER_CORNER
    val bottom = if (index == count - 1) OUTER_CORNER else INNER_CORNER
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** A row that opens something, in a segmented group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LinkRow(
    title: String,
    supporting: String,
    onClick: () -> Unit,
    index: Int,
    count: Int,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    ListItem(
        modifier = modifier
            .clip(segmentShape(index, count))
            .clickable(onClick = onClick),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = if (selected) {
            ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.segmentedColors()
        },
        leadingContent = leading,
        supportingContent = { Text(supporting) },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(title) }
}

/** An app's icon, with the cage that marks it as isolated. */
@Composable
internal fun IsolatedAppIcon(info: AppInfo?, name: String, modifier: Modifier = Modifier, size: Int = 40) {
    Box(modifier) {
        AppIcon(info, name, size.dp)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size((size * 0.45f).dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.surfaceBright),
        ) {
            Icon(CageIcons.Badge, contentDescription = null, modifier = Modifier.padding((size * 0.09f).dp))
        }
    }
}

/**
 * One item of the sandbox: what it keeps from the app, a switch that turns it
 * on and off, and, opened, every mode it can have. Opened, the row stands
 * apart from its neighbours, with every corner rounded.
 *
 * [choice] is the mode chosen here, null for following the global settings
 * ([globalMode] non-null only where that can be chosen); [mode] is what
 * applies.
 */
@Composable
internal fun ItemRow(
    item: SandboxItem,
    mode: SandboxMode,
    choice: SandboxMode?,
    globalMode: SandboxMode?,
    inEffect: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onChoose: (SandboxMode?) -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.Dp>()
    val top by animateDpAsState(if (expanded || index == 0) OUTER_CORNER else INNER_CORNER, spatial, label = "top")
    val bottom by animateDpAsState(if (expanded || index == count - 1) OUTER_CORNER else INNER_CORNER, spatial, label = "bottom")
    val turn by animateFloatAsState(if (expanded) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "turn")
    val title = stringResource(item.title)
    Surface(
        modifier = modifier.padding(vertical = if (expanded) 6.dp else 0.dp),
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 72.dp)
                        .clickable(onClick = { onExpandedChange(!expanded) })
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ShapedIcon(item.icon)
                    Column(Modifier.weight(1f)) {
                        // On a narrow screen the tag goes under the title rather than squeeze it.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            if (globalMode != null && choice != null) CustomTag()
                        }
                        Text(
                            text = if (inEffect || !mode.isOn) modeLabel(item, mode) else stringResource(R.string.jail_not_in_effect),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (inEffect || !mode.isOn) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(turn),
                    )
                }
                VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 20.dp))
                Switch(
                    checked = mode.isOn,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .semantics { contentDescription = title },
                    thumbContent = {
                        Icon(
                            if (mode.isOn) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    },
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            ) {
                ModeChoices(item, choice, globalMode, onChoose)
            }
        }
    }
}

/** Every mode an item can be set to, one to be chosen. */
@Composable
private fun ModeChoices(
    item: SandboxItem,
    choice: SandboxMode?,
    globalMode: SandboxMode?,
    onChoose: (SandboxMode?) -> Unit,
) {
    Column(
        Modifier
            .selectableGroup()
            .padding(start = 60.dp, end = 12.dp, bottom = 12.dp),
    ) {
        if (globalMode != null) {
            ModeChoice(
                title = stringResource(R.string.jail_mode_global),
                detail = stringResource(R.string.jail_mode_global_detail, modeLabel(item, globalMode)),
                selected = choice == null,
                onClick = { onChoose(null) },
            )
        }
        for (mode in item.modes) {
            ModeChoice(
                title = modeLabel(item, mode),
                detail = modeDetail(item, mode),
                selected = choice == mode,
                onClick = { onChoose(mode) },
            )
        }
    }
}

@Composable
private fun ModeChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Marks an item an app has a mode of its own for. */
@Composable
private fun CustomTag() {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            stringResource(R.string.jail_custom),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** A warning about a part of the sandbox that does not work on this device. */
@Composable
internal fun StatusCard(title: String, body: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Warning, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** What applies to an app, in one line: which items are off, if any. */
@Composable
internal fun itemsSummary(items: Map<SandboxItem, SandboxMode>): String {
    val off = ITEM_ORDER.filter { items[it] == SandboxMode.OFF }
    return when {
        off.isEmpty() -> stringResource(R.string.jail_all_items_on)
        off.size == ITEM_ORDER.size -> stringResource(R.string.jail_no_items_on)
        else -> stringResource(R.string.jail_items_off, off.map { stringResource(it.title) }.joinToString(stringResource(R.string.jail_list_separator)))
    }
}
