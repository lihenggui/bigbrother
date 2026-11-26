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

package li.mercury.bigbrother.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Outer and inner corners of a row in a segmented group, as Material lists have them. */
fun segmentCornerSize(outer: Boolean): Dp = if (outer) 16.dp else 4.dp

/** The shape of a row in a segmented group, for clipping its touch feedback. */
fun segmentShape(index: Int, count: Int): RoundedCornerShape = when {
    index == 0 && count == 1 -> SingleSegmentShape
    index == 0 -> FirstSegmentShape
    index == count - 1 -> LastSegmentShape
    else -> MiddleSegmentShape
}

private val SingleSegmentShape = RoundedCornerShape(segmentCornerSize(outer = true))
private val FirstSegmentShape = RoundedCornerShape(
    topStart = segmentCornerSize(outer = true),
    topEnd = segmentCornerSize(outer = true),
    bottomStart = segmentCornerSize(outer = false),
    bottomEnd = segmentCornerSize(outer = false),
)
private val MiddleSegmentShape = RoundedCornerShape(segmentCornerSize(outer = false))
private val LastSegmentShape = RoundedCornerShape(
    topStart = segmentCornerSize(outer = false),
    topEnd = segmentCornerSize(outer = false),
    bottomStart = segmentCornerSize(outer = true),
    bottomEnd = segmentCornerSize(outer = true),
)

/**
 * The whole row toggles a setting that takes effect at once. The switch alone
 * shows the state, so the row keeps its color.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SegmentedSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leadingContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    showThumbIcon: Boolean = false,
) {
    ListItem(
        modifier = modifier
            .clip(segmentShape(index, count))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = leadingContent,
        supportingContent = { Text(summary) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = if (showThumbIcon) {
                    { SwitchStateIcon(checked) }
                } else {
                    null
                },
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(title) }
}

/** Marks both switch states where the UI uses a thumb icon. */
@Composable
fun SwitchStateIcon(checked: Boolean, modifier: Modifier = Modifier) {
    Icon(
        if (checked) Icons.Filled.Check else Icons.Filled.Close,
        contentDescription = null,
        modifier = modifier.size(SwitchDefaults.IconSize),
    )
}
