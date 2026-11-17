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

package li.mercury.bigbrother.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.model.TimeRange

/** Short name of a time range, for a button: "24 hours". */
val TimeRange.shortLabel: Int
    get() = when (this) {
        TimeRange.HOUR -> R.string.range_hour_short
        TimeRange.DAY -> R.string.range_day_short
        TimeRange.WEEK -> R.string.range_week_short
        TimeRange.ALL -> R.string.range_all_short
    }

/**
 * The time ranges as a connected button group, one always selected. For a
 * screen whose content is the summary of the range, where the choice
 * should stay in view.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TimeRangeButtonGroup(range: TimeRange, onRange: (TimeRange) -> Unit, modifier: Modifier = Modifier) {
    val options = TimeRange.entries
    Row(
        // Material caps a connected group's width on large windows.
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == range,
                onCheckedChange = { onRange(option) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Text(stringResource(option.shortLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One option of a [MenuFilterChip]. */
class MenuOption<T>(val value: T, val label: String)

/**
 * A filter chip showing the current choice; the whole chip opens a menu of
 * the choices, as Material asks for in compact windows. [extra] adds items
 * below the choices, after a divider.
 */
@Composable
fun <T> MenuFilterChip(
    label: String,
    options: List<MenuOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    extra: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(
            selected = highlighted,
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                val isSelected = option.value == selected
                DropdownMenuItem(
                    text = { Text(option.label) },
                    // A check mark, in addition to the item's own selection
                    // style, says which choice is current.
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    },
                )
            }
            if (extra != null) {
                HorizontalDivider()
                extra { expanded = false }
            }
        }
    }
}
