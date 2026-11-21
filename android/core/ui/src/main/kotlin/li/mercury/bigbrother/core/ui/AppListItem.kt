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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary

/**
 * Whether lists are shown next to the detail of their selected item. Lists
 * mark the selected item only then; on their own, a tap opens it.
 */
val LocalShowsDetailBesideList = compositionLocalOf { false }

/**
 * A row naming an app: its icon, its name and, below, [supporting] text,
 * with [trailing] text such as a time at the end. [badge] marks the icon with
 * a kind of data. Part of a segmented list: [index] and [count] place it in
 * its group.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppListItem(
    app: AppSummary,
    info: AppInfo?,
    supporting: String?,
    trailing: String?,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    badge: ImageVector? = null,
) {
    val name = appDisplayName(app, info)
    ListItem(
        selected = selected && LocalShowsDetailBesideList.current,
        onClick = onClick,
        modifier = modifier,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { BadgedAppIcon(app, info, name, badge = badge) },
        supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = trailing?.let { { Text(it, style = MaterialTheme.typography.labelMedium) } },
    ) {
        Text(appNameWithUser(app, name), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The name to list an app under. The same app can run as several Android
 * users (clones, work profile); without the user their rows would look the
 * same. An isolated process's name already gives its uid.
 */
@Composable
fun appNameWithUser(app: AppSummary, name: String): String = appNameWithUser(
    name,
    app.userId.takeUnless { it == 0 || app.isUnattributedIsolated },
)

/** Adds the Android user when the caller needs to distinguish an app's copy. */
@Composable
fun appNameWithUser(name: String, otherUser: Int?): String = if (otherUser == null) name else stringResource(R.string.app_name_with_user, name, otherUser)

/** An app's icon, with a work badge for another Android user and an optional badge for a kind of data. */
@Composable
fun BadgedAppIcon(
    app: AppSummary,
    info: AppInfo?,
    name: String,
    modifier: Modifier = Modifier,
    badge: ImageVector? = null,
) {
    Box(modifier) {
        AppIcon(info, name, size = 40.dp)
        val corner = badge ?: Icons.Filled.Work.takeIf { app.userId != 0 && !app.isUnattributedIsolated }
        if (corner != null) {
            val isUser = badge == null
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    // A thin ring in the list's color sets the badge off the icon.
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(
                            if (isUser) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = corner,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = if (isUser) {
                            MaterialTheme.colorScheme.onTertiary
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }
        }
    }
}
