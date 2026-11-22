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

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.designsystem.component.SwitchStateIcon
import li.mercury.bigbrother.core.designsystem.component.segmentCornerSize
import li.mercury.bigbrother.core.designsystem.component.segmentShape
import li.mercury.bigbrother.core.designsystem.icon.CageIcons
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.SandboxHookState
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxPart
import li.mercury.bigbrother.core.ui.AppIcon
import li.mercury.bigbrother.core.ui.ExpandChevron
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.Reveal

/**
 * A row that opens something, in a segmented group. [selected] is whether
 * it is what the pane beside the list shows, null without such a pane.
 */
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
    selected: Boolean? = null,
) {
    ListItem(
        modifier = modifier
            .clip(segmentShape(index, count))
            .clickable(onClick = onClick)
            // Told as selected or not only where one of the rows can be.
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = if (selected == true) {
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
 * applies. [checked] is what the switch shows: whether the item is on,
 * except for the network, whose switch says whether it is allowed.
 */
@Composable
internal fun ItemRow(
    item: SandboxItem,
    mode: SandboxMode,
    choice: SandboxMode?,
    globalMode: SandboxMode?,
    unmet: Set<SandboxPart>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onChoose: (SandboxMode?) -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    checked: Boolean = mode.isOn,
    appOpsChecking: Boolean = false,
    hookHealth: Map<SandboxPart, SandboxHookState> = emptyMap(),
) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val top by animateDpAsState(segmentCornerSize(outer = expanded || index == 0), spatial, label = "top")
    val bottom by animateDpAsState(segmentCornerSize(outer = expanded || index == count - 1), spatial, label = "bottom")
    // The gap around an opened row grows with its choices.
    val gap = animateDpAsState(if (expanded) 6.dp else 0.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "gap")
    val title = stringResource(item.title)
    Surface(
        modifier = modifier.verticalGap(gap),
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                ItemRowHeader(item, title, mode, globalMode != null && choice != null, unmet, expanded, appOpsChecking, hookHealth, onExpandedChange)
                VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 20.dp))
                ItemSwitch(title, checked, onCheckedChange)
            }
            Reveal(expanded) {
                ModeChoices(item, choice, globalMode, onChoose)
            }
        }
    }
}

/** Space of [gap] above and below, read while laying out so that it can animate without composing again. */
private fun Modifier.verticalGap(gap: State<Dp>): Modifier = layout { measurable, constraints ->
    val space = gap.value.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = -2 * space))
    layout(placeable.width, placeable.height + 2 * space) { placeable.place(0, space) }
}

@Composable
private fun RowScope.ItemRowHeader(
    item: SandboxItem,
    title: String,
    mode: SandboxMode,
    tagged: Boolean,
    unmet: Set<SandboxPart>,
    expanded: Boolean,
    appOpsChecking: Boolean,
    hookHealth: Map<SandboxPart, SandboxHookState>,
    onExpandedChange: (Boolean) -> Unit,
) {
    val state = stringResource(if (expanded) R.string.jail_choices_shown else R.string.jail_choices_hidden)
    val action = stringResource(if (expanded) R.string.jail_hide_choices else R.string.jail_show_choices)
    Row(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 72.dp)
            .clickable(onClickLabel = action, onClick = { onExpandedChange(!expanded) })
            .semantics { stateDescription = state }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ShapedIcon(item.icon)
        Column(Modifier.weight(1f)) {
            TitleWithTag(title, tagged = tagged)
            val inEffect = unmet.isEmpty() || !mode.isOn
            val checking = appOpsChecking && unmet == setOf(SandboxPart.APP_OPS) && mode.isOn
            val status = when {
                inEffect -> modeLabel(item, mode)

                checking -> stringResource(R.string.jail_checking_app_ops)

                else -> {
                    val reason = stringResource(notInEffect(item, unmet, hookHealth))
                    if (SandboxPart.MOUNT_LAUNCH_REFUSED in unmet) {
                        stringResource(R.string.jail_mount_launch_refused, reason)
                    } else {
                        reason
                    }
                }
            }
            val color = if (inEffect || checking) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            // A new status, or the same one turning into a problem, fades through in place.
            FadeThrough(status to color, kind = { it }) { (text, tint) ->
                Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
            }
        }
        ExpandChevron(expanded, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = title },
        thumbContent = { SwitchStateIcon(checked) },
    )
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

/**
 * An item's title, with the tag that marks a mode of its own after it, or,
 * on a narrow screen, under it rather than squeezing it. A layout of its
 * own: its height for a width, which the row's divider is sized by, is
 * that of the lines it lays out.
 */
@Composable
private fun TitleWithTag(title: String, tagged: Boolean) {
    val gap = 8.dp
    Layout(
        content = {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (tagged) CustomTag()
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val text = measurables[0].measure(loose)
        val tag = measurables.getOrNull(1)?.measure(loose)
        val space = gap.roundToPx()
        val sameLine = tag == null || text.width + space + tag.width <= constraints.maxWidth
        val width = when {
            tag == null -> text.width
            sameLine -> text.width + space + tag.width
            else -> maxOf(text.width, tag.width)
        }.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = if (tag == null) {
            text.height
        } else if (sameLine) {
            maxOf(text.height, tag.height)
        } else {
            text.height + tag.height
        }
        layout(width, height) {
            if (tag == null) {
                text.placeRelative(0, 0)
            } else if (sameLine) {
                val line = maxOf(text.height, tag.height)
                text.placeRelative(0, (line - text.height) / 2)
                tag.placeRelative(text.width + space, (line - tag.height) / 2)
            } else {
                text.placeRelative(0, 0)
                tag.placeRelative(0, text.height)
            }
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
internal fun SandboxWarningCard(title: String, body: String, modifier: Modifier = Modifier) {
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

/**
 * What applies to an app, in one line: which items are off, if any, then
 * whether it is kept from the network and whether its connections are
 * recorded. What is so by default is not said.
 */
@Composable
internal fun itemsSummary(items: Map<SandboxItem, SandboxMode>): String {
    val off = ITEM_ORDER.filter { items[it] == SandboxMode.OFF }
    val isolation = when {
        off.isEmpty() -> stringResource(R.string.jail_all_items_on)
        off.size == ITEM_ORDER.size -> stringResource(R.string.jail_no_items_on)
        else -> stringResource(R.string.jail_items_off, off.map { stringResource(it.title) }.joinToString(stringResource(R.string.jail_list_separator)))
    }
    val network = listOfNotNull(
        stringResource(R.string.jail_summary_no_network).takeIf { items[SandboxItem.NETWORK]?.isOn == true },
        stringResource(R.string.jail_summary_recording).takeIf { items[SandboxItem.NETWORK_RECORD]?.isOn == true },
    )
    return (listOf(isolation) + network).joinToString(stringResource(R.string.jail_summary_separator))
}
