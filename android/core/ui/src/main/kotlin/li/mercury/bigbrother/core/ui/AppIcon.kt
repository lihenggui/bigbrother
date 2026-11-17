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

import android.content.res.Resources
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Dp
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary

/**
 * The name to show for an app: its label, else its package, else, for an
 * isolated process that could not be traced to its app, one naming its uid.
 */
@Composable
fun appDisplayName(app: AppSummary, info: AppInfo?): String = appDisplayName(app, info, LocalResources.current)

/** [appDisplayName] outside composition, for sorting and searching by name. */
fun appDisplayName(app: AppSummary, info: AppInfo?, resources: Resources): String = when {
    info != null -> info.label
    app.packageName.isNotEmpty() -> app.packageName
    else -> resources.getString(R.string.isolated_process_uid, app.uid)
}

/** App icon, or a tonal tile with the name's first letter when there is none. */
@Composable
fun AppIcon(info: AppInfo?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val icon = info?.icon
    if (icon != null) {
        val bitmap = remember(icon) { icon.asImageBitmap() }
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
