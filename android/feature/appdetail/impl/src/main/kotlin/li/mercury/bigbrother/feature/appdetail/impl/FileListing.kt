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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.LocalWallClock
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.animatedRow
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.ltrContent
import li.mercury.bigbrother.core.ui.rawTextSemantics
import li.mercury.bigbrother.core.ui.relativeTime
import li.mercury.bigbrother.feature.appdetail.impl.tree.FileNode
import li.mercury.bigbrother.core.ui.R as UiR

/** The contents of one location: a folder, the media library group or the device group. */
@Composable
internal fun Listing(
    content: FileTree,
    padding: PaddingValues,
    location: String,
    readOnly: Boolean,
    filtering: Boolean,
    truncated: Boolean,
    onNavigate: (String) -> Unit,
    onShowAll: () -> Unit,
    onFileDetails: (path: String) -> Unit,
) {
    val entries = when (location) {
        Location.ROOT -> content.rootEntries
        Location.MEDIA -> emptyList()
        else -> content.folder(location)?.children.orEmpty()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp + padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        when (location) {
            Location.MEDIA -> itemsIndexed(
                content.collections,
                // Records of one collection can share an action (opened
                // directly and through the provider), so the position is part of the key.
                key = { index, access -> "collection:$index:${access.path}" },
                contentType = { _, _ -> "collection" },
            ) { index, access ->
                CollectionRow(access, index, content.collections.size)
            }

            else -> {
                itemsIndexed(
                    entries,
                    key = { _, node -> node.path },
                    contentType = { _, node -> if (node.isFolder) "folder" else "file" },
                ) { index, node ->
                    EntryRow(
                        node = node,
                        isVolume = node in content.volumes,
                        readOnly = readOnly,
                        index = index,
                        count = entries.size,
                        // A folder is entered; a file shows when it was accessed.
                        onOpen = {
                            if (node.isFolder) onNavigate(node.path) else onFileDetails(node.path)
                        },
                    )
                }
                if (location == Location.ROOT) {
                    // What the app reached through the media library sits
                    // below the files, as a folder of its own.
                    val collections = content.collections.size
                    if (entries.isEmpty()) {
                        item(key = "no-files", contentType = "empty") {
                            NoFiles(readOnly, filtering, onShowAll, Modifier.animatedRow(this, MaterialTheme.motionScheme).fillParentMaxHeight(if (collections == 0) 0.7f else 0.35f))
                        }
                    }
                    if (collections > 0) {
                        item(key = Location.MEDIA, contentType = "media-library") {
                            MediaLibraryRow(collections, onOpen = { onNavigate(Location.MEDIA) })
                        }
                    }
                }
            }
        }
        if (truncated) {
            item(key = "truncated", contentType = "note") {
                Text(
                    text = stringResource(R.string.results_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme).padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Shown where the file list would be when there is nothing to list. */
@Composable
private fun NoFiles(readOnly: Boolean, filtering: Boolean, onShowAll: () -> Unit, modifier: Modifier = Modifier) {
    when {
        filtering -> Message(stringResource(UiR.string.no_matches), "", modifier)

        // Nothing was read, but there may be checks and blocked attempts.
        readOnly -> Message(
            title = stringResource(R.string.no_files_read),
            body = "",
            modifier = modifier,
            actionLabel = stringResource(R.string.filter_all),
            onAction = onShowAll,
        )

        else -> Message(stringResource(UiR.string.empty_title), "", modifier)
    }
}

@Composable
private fun LazyItemScope.MediaLibraryRow(collections: Int, onOpen: () -> Unit) {
    ListItem(
        onClick = onOpen,
        modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
        trailingContent = { CountWithChevron(collections) },
    ) {
        Text(stringResource(R.string.group_media))
    }
}

/** A folder, which opens, or a file, which shows what was done to it. */
@Composable
private fun LazyItemScope.EntryRow(
    node: FileNode,
    isVolume: Boolean,
    readOnly: Boolean,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val colors = ListItemDefaults.segmentedColors()
    val name = @Composable {
        if (isVolume) {
            val label = storageLabel(node)
            Text(label, modifier = Modifier.rawTextSemantics(label))
        } else {
            Text(node.name, style = LocalTextStyle.current.ltrContent())
        }
    }
    val detail: (@Composable () -> Unit)? = node.accesses.takeIf { it.isNotEmpty() }?.let { accesses ->
        {
            // Switching between reads and all accesses fades the line
            // through, and the row grows or shrinks to the new one.
            FadeThrough(readOnly, kind = { it }) { onlyReads ->
                if (onlyReads) {
                    // Only reads are listed here; the trailing count gives their number.
                    Text(relativeTime(accesses.maxOf { it.lastSeen }, LocalWallClock.current.millis()))
                } else {
                    AccessLines(accesses)
                }
            }
        }
    }
    // How many times the file was read.
    val reads = node.accesses.filter { it.isSuccessfulOpen() }.sumOf { it.allowed }
    if (node.isFolder) {
        ListItem(
            onClick = onOpen,
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            shapes = shapes,
            colors = colors,
            leadingContent = {
                Icon(
                    if (isVolume) Icons.Outlined.Storage else Icons.Outlined.Folder,
                    contentDescription = null,
                )
            },
            supportingContent = detail,
            trailingContent = { CountWithChevron(node.touchedCount) },
            content = name,
        )
    } else {
        ListItem(
            onClick = onOpen,
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            shapes = shapes,
            colors = colors,
            leadingContent = {
                Icon(
                    // A path that was listed is a folder, even with nothing recorded inside.
                    if (node.accesses.any { it.action == Action.LIST }) {
                        Icons.Outlined.Folder
                    } else {
                        Icons.AutoMirrored.Outlined.InsertDriveFile
                    },
                    contentDescription = null,
                )
            },
            supportingContent = detail,
            trailingContent = if (reads > 0) {
                { FadeThrough(reads, kind = { it }) { Text(stringResource(R.string.read_count, it)) } }
            } else {
                null
            },
            content = name,
        )
    }
}

/** The number of entries inside, and a chevron saying the row opens. */
@Composable
private fun CountWithChevron(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A count that comes or changes fades in place, and the row makes room for it.
        FadeThrough(count.takeIf { it > 0 }, kind = { it }) { shown -> if (shown != null) Text(shown.toString()) }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the app did to a path, on as few lines as possible: the actions with
 * their counts ("Read 3×; Checked 12×, blocked"), then when it last happened.
 */
@Composable
private fun AccessLines(accesses: List<Access>) {
    val actions = accesses.map { access ->
        val label = actionLabel(access)
        when {
            access.action == Action.QUERY -> pluralStringResource(
                R.plurals.access_query,
                access.allowed,
                access.allowed,
                access.items,
            )

            // An access the system refused every time is a failed attempt.
            access.allowed == 0 -> stringResource(R.string.access_blocked, label, access.denied)

            access.denied > 0 -> stringResource(
                R.string.access_partly_blocked,
                label,
                access.allowed + access.denied,
                access.denied,
            )

            else -> stringResource(R.string.access_allowed, label, access.allowed)
        }
    }
    val lastSeen = accesses.maxOf { it.lastSeen }
    val clock = LocalWallClock.current
    val time = remember(lastSeen, clock) { relativeTime(lastSeen, clock.millis()) }
    Column {
        Text(
            text = actions.joinToString(stringResource(R.string.access_separator)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = time, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun actionLabel(access: Access): String = actionLabel(access.action, access.viaProvider)

@Composable
internal fun actionLabel(action: Action, viaProvider: Boolean): String = stringResource(
    when (action) {
        Action.READ -> if (viaProvider) R.string.action_read_provider else R.string.action_read

        Action.WRITE -> if (viaProvider) R.string.action_write_provider else R.string.action_write

        Action.READ_WRITE ->
            if (viaProvider) R.string.action_write_provider else R.string.action_read_write

        Action.LIST -> R.string.action_list

        Action.PROBE -> R.string.action_probe

        Action.WATCH -> R.string.action_watch

        Action.QUERY -> R.string.action_query

        Action.CONNECT -> R.string.action_connect

        Action.EXEC -> R.string.action_exec

        Action.CALL -> R.string.action_call
    },
)

/** A media library collection (photos, videos...) the app listed or opened items of. */
@Composable
private fun LazyItemScope.CollectionRow(access: Access, index: Int, count: Int) {
    val label = collectionLabel(access.path)
    ListItem(
        modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        supportingContent = { AccessLines(listOf(access)) },
    ) {
        if (label != null) {
            Text(stringResource(label))
        } else {
            Text(access.path, fontFamily = FontFamily.Monospace, style = LocalTextStyle.current.ltrContent())
        }
    }
}

/** A readable name for a content URI's collection, if it is a well-known one. */
internal fun collectionLabel(uri: String): Int? {
    val authority = uri.substringAfter("://").substringBefore('/')
    val path = uri.substringAfter("://").substringAfter('/', "")
    return when {
        authority == "media" && "/images" in "/$path" -> R.string.collection_images
        authority == "media" && "/video" in "/$path" -> R.string.collection_videos
        authority == "media" && "/audio" in "/$path" -> R.string.collection_audio
        authority == "media" && "/downloads" in "/$path" -> R.string.collection_downloads
        authority == "media" -> R.string.collection_files
        "externalstorage" in authority -> R.string.collection_documents
        "downloads" in authority -> R.string.collection_downloads
        else -> null
    }
}
