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

package li.mercury.bigbrother.feature.appdetail.impl

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.rememberPredictiveBackTransition
import li.mercury.bigbrother.feature.appdetail.impl.tree.FileNode

/**
 * The files tab: shared storage browsed like a file manager, then the media
 * library. Files that were read are shown by default; every access to files
 * is one tap away.
 */
@Composable
internal fun FilesPage(
    browser: FileBrowser,
    padding: PaddingValues,
    truncated: Boolean,
    location: String,
    handleBack: Boolean,
    onNavigate: (String) -> Unit,
    onReadOnly: (Boolean) -> Unit,
    onFileDetails: (path: String) -> Unit,
) {
    val tree = browser.tree
    val here = tree.resolve(location)
    val parent = tree.parentOf(here)
    val transition = rememberPredictiveBackTransition(
        current = here,
        previous = parent.takeIf { handleBack && here != Location.ROOT },
        onBack = { onNavigate(parent) },
    )
    Column(Modifier.fillMaxSize()) {
        ActivityFilter(browser.readOnly, onReadOnly)
        // Entering a folder slides the new list in from the end, leaving it
        // slides back, like turning pages.
        val motion = MaterialTheme.motionScheme
        val forward = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
        transition.AnimatedContent(
            transitionSpec = {
                val deeper = targetState.length >= initialState.length
                val direction = if (deeper) forward else -forward
                val slide = motion.defaultSpatialSpec<IntOffset>()
                val fade = motion.fastEffectsSpec<Float>()
                slideInHorizontally(slide) { it / 3 * direction } + fadeIn(fade) togetherWith
                    slideOutHorizontally(slide) { -it / 3 * direction } + fadeOut(fade)
            },
        ) { shown ->
            Column {
                if (shown != Location.ROOT) Breadcrumb(tree, shown, onNavigate)
                Listing(
                    content = tree,
                    padding = padding,
                    location = shown,
                    readOnly = browser.readOnly,
                    filtering = browser.filtering,
                    truncated = truncated,
                    onNavigate = onNavigate,
                    onShowAll = { onReadOnly(false) },
                    onFileDetails = onFileDetails,
                )
            }
        }
    }
}

/** Chooses between files that were read and every access to files, as a connected button group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActivityFilter(readOnly: Boolean, onReadOnly: (Boolean) -> Unit) {
    val options = listOf(true to R.string.filter_read, false to R.string.filter_all)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (value, label) ->
            ToggleButton(
                checked = readOnly == value,
                onCheckedChange = { onReadOnly(value) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = if (index == 0) {
                    ButtonGroupDefaults.connectedLeadingButtonShapes()
                } else {
                    ButtonGroupDefaults.connectedTrailingButtonShapes()
                },
            ) {
                Text(stringResource(label))
            }
        }
    }
}

/** The path to the current folder; each part goes back to that folder. */
@Composable
private fun Breadcrumb(content: FileTree, location: String, onNavigate: (String) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val single = content.volumes.singleOrNull()
        TextButton(onClick = { onNavigate(Location.ROOT) }) {
            Text(if (single != null) storageLabel(single) else stringResource(R.string.storage_root))
        }
        val trail: List<Pair<String, String>> = when (location) {
            Location.ROOT -> emptyList()

            Location.MEDIA -> listOf(location to stringResource(R.string.group_media))

            else -> content.trail(location).map { node ->
                node.path to if (node in content.volumes) storageLabel(node) else node.name
            }
        }
        for ((target, label) in trail) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onNavigate(target) }) { Text(label) }
        }
    }
}

/** A volume root in words: "Internal storage", or the volume's id for removable storage. */
@Composable
internal fun storageLabel(node: FileNode): String {
    val user = PRIMARY_VOLUME.matchEntire(node.path)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        user == 0 -> stringResource(R.string.internal_storage)

        user != null -> stringResource(R.string.internal_storage_user, user)

        node.path.startsWith("/storage/") ->
            stringResource(R.string.removable_storage, node.path.removePrefix("/storage/"))

        else -> node.name
    }
}
