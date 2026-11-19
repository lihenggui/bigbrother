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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import li.mercury.bigbrother.feature.jail.impl.R

/**
 * Blocks [name] with one tap, and unblocks it with another. Blocked, the
 * round button turns square and takes the tertiary color, the shape and
 * color springing as the toggle's own motion has them.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BlockToggle(name: String, blocked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    FilledIconToggleButton(
        checked = blocked,
        onCheckedChange = onChange,
        modifier = modifier,
        shapes = IconButtonDefaults.toggleableShapes(),
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = colors.surfaceContainerHigh,
            contentColor = colors.onSurfaceVariant,
            checkedContainerColor = colors.tertiary,
            checkedContentColor = colors.onTertiary,
        ),
    ) {
        Icon(Icons.Outlined.Block, contentDescription = stringResource(R.string.jail_net_block_cd, name))
    }
}
