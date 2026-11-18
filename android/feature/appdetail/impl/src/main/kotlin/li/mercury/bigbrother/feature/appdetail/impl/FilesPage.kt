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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.VOLUME_ROOT
import li.mercury.bigbrother.core.ui.exactDateTime
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.relativeTime
import li.mercury.bigbrother.feature.appdetail.impl.tree.FileNode
import li.mercury.bigbrother.feature.appdetail.impl.tree.buildFileTree
import li.mercury.bigbrother.core.ui.R as UiR

/** The location shown in the files tab, navigated like a file browser. */
internal object Location {
    /** The top level: storage volumes (or the single volume's contents) and the media library. */
    const val ROOT = ""

    /** What the app reached through the media library. */
    const val MEDIA = "media:"

    // Any other value is the path of a folder on shared storage.
}

/**
 * What one app touched on shared storage, as a browsable tree: one tree per
 * storage volume, plus the media library collections it reached.
 */
internal class FileTree(
    /** One tree per storage volume. */
    val volumes: List<FileNode>,
    /** Collections reached through the media library and other providers. */
    val collections: List<Access>,
) {
    /** Folder path -> the folder, and -> the path of the folder containing it. */
    private val nodes = HashMap<String, FileNode>()
    private val parents = HashMap<String, String>()

    init {
        fun index(node: FileNode, parent: String) {
            nodes[node.path] = node
            parents[node.path] = parent
            node.children.forEach { index(it, node.path) }
        }
        volumes.forEach { index(it, Location.ROOT) }
        // A single volume is not worth a level of its own: its contents are
        // shown at the top, so its children's parent is the top.
        volumes.singleOrNull()?.children?.forEach { parents[it.path] = Location.ROOT }
    }

    fun folder(path: String): FileNode? = nodes[path]

    /** Where "up" leads from [location]. */
    fun parentOf(location: String): String = parents[location] ?: Location.ROOT

    /** Folders and files shown at the top level. */
    val rootEntries: List<FileNode> get() = volumes.singleOrNull()?.children ?: volumes

    /** The folders from the top down to [path], for the breadcrumb. */
    fun trail(path: String): List<FileNode> {
        val trail = ArrayList<FileNode>()
        var current = path
        while (current != Location.ROOT) {
            trail.add(nodes[current] ?: break)
            current = parentOf(current)
        }
        return trail.asReversed()
    }

    /** Where to show [location], or the top if it is no longer in the tree. */
    fun resolve(location: String): String = when {
        location == Location.MEDIA -> if (collections.isEmpty()) Location.ROOT else location
        folder(location) != null -> location
        else -> Location.ROOT
    }
}

/** Arranges the file records; with [readOnly], only files that were opened. */
internal fun fileTreeOf(accesses: List<Access>, readOnly: Boolean): FileTree = FileTree(
    volumes = buildVolumeTrees(
        accesses.filter { it.category == Category.STORAGE && (!readOnly || it.isSuccessfulOpen()) },
    ),
    collections = accesses.filter { it.category == Category.PROVIDER },
)

/** Whether a record is one of the user's files, on shared storage or through a provider. */
internal fun Access.isFile(): Boolean = category == Category.STORAGE || category == Category.PROVIDER

/** Whether the app opened any file on shared storage. */
private fun readAnyFile(accesses: List<Access>): Boolean = accesses.any { it.category == Category.STORAGE && it.isSuccessfulOpen() }

/**
 * The number for the files tab, matching what the tab opens on: the files
 * read and the collections reached, or, if none were read, every file touched.
 */
internal fun fileTabCount(accesses: List<Access>): Int {
    val files = accesses.filter { it.isFile() }
    val read = files.filter { it.category == Category.PROVIDER || it.isSuccessfulOpen() }
    return (read.ifEmpty { files }).distinctBy { it.path }.size
}

/** A file counts as read when the system let the app open it. */
internal fun Access.isSuccessfulOpen(): Boolean = allowed > 0 && (action == Action.READ || action == Action.WRITE || action == Action.READ_WRITE)

/**
 * One tree per storage volume, so that each starts at a root the user knows
 * ("Internal storage") instead of at `/storage`. Paths are arranged relative
 * to their volume.
 */
private fun buildVolumeTrees(storage: List<Access>): List<FileNode> = storage
    .groupBy { VOLUME_ROOT.find(it.path)?.value.orEmpty() }
    .toList()
    // Internal storage first, by user number; then removable volumes.
    .sortedWith(
        compareBy(
            { (volume, _) -> PRIMARY_VOLUME.matchEntire(volume) == null },
            { (volume, _) -> PRIMARY_VOLUME.matchEntire(volume)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            { (volume, _) -> volume },
        ),
    )
    .map { (volume, accesses) ->
        val (atRoot, below) = accesses.partition { it.path == volume }
        // Trim the volume prefix so the tree starts below it, then put
        // the full paths back: they identify the nodes.
        val children = buildFileTree(below.map { it.copy(path = it.path.removePrefix(volume)) })
            .map { it.rebase(volume) }
        FileNode(
            name = volume,
            path = volume,
            accesses = atRoot,
            children = children,
            touchedCount = children.sumOf { it.touchedCount + if (it.accesses.isEmpty()) 0 else 1 },
        )
    }

/** Restore the volume prefix on a subtree built from volume-relative paths. */
private fun FileNode.rebase(volume: String): FileNode = copy(
    // Top-level names carry a leading slash from the tree builder.
    name = name.removePrefix("/"),
    path = volume + path,
    children = children.map { it.rebaseBelow(volume) },
)

private fun FileNode.rebaseBelow(volume: String): FileNode = copy(path = volume + path, children = children.map { it.rebaseBelow(volume) })

/**
 * The files tab: shared storage browsed like a file manager, then the media
 * library. Files that were read are shown by default; every access to files
 * is one tap away.
 */
@Composable
internal fun FilesPage(
    accesses: List<Access>,
    filtering: Boolean,
    truncated: Boolean,
    location: String,
    handleBack: Boolean,
    onNavigate: (String) -> Unit,
    onFileDetails: (path: String) -> Unit,
) {
    // What the user came for is which files were read, so that is the
    // default; checks and denied attempts are one tap away. An app that read
    // none opens on everything it did, rather than on an empty list.
    var readOnly by rememberSaveable { mutableStateOf(readAnyFile(accesses)) }
    val tree = remember(accesses, readOnly) { fileTreeOf(accesses, readOnly) }
    val here = tree.resolve(location)
    BackHandler(enabled = handleBack && here != Location.ROOT) { onNavigate(tree.parentOf(here)) }
    Column(Modifier.fillMaxSize()) {
        ActivityFilter(readOnly, onReadOnly = { readOnly = it })
        // At the top the path says nothing; it appears once inside a folder.
        if (here != Location.ROOT) Breadcrumb(tree, here, onNavigate)
        // Entering a folder slides the new list in from the end, leaving it
        // slides back, like turning pages.
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = here,
            transitionSpec = {
                val deeper = targetState.length >= initialState.length
                val direction = if (deeper) 1 else -1
                val slide = motion.defaultSpatialSpec<IntOffset>()
                val fade = motion.fastEffectsSpec<Float>()
                slideInHorizontally(slide) { it / 3 * direction } + fadeIn(fade) togetherWith
                    slideOutHorizontally(slide) { -it / 3 * direction } + fadeOut(fade)
            },
            label = "folder",
        ) { shown ->
            Listing(
                content = tree,
                location = shown,
                readOnly = readOnly,
                filtering = filtering,
                truncated = truncated,
                onNavigate = onNavigate,
                onShowAll = { readOnly = false },
                onFileDetails = onFileDetails,
            )
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

/** The contents of one location: a folder, the media library group or the device group. */
@Composable
private fun Listing(
    content: FileTree,
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
        contentPadding = listContentPadding(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        when (location) {
            Location.MEDIA -> itemsIndexed(
                content.collections,
                // Records of one collection can share an action (opened
                // directly and through the provider), so the position is part of the key.
                key = { index, access -> "collection:$index:${access.path}" },
            ) { index, access ->
                CollectionRow(access, index, content.collections.size)
            }

            else -> {
                itemsIndexed(entries, key = { _, node -> node.path }) { index, node ->
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
                    val groups = buildList {
                        if (content.collections.isNotEmpty()) {
                            add(Group(Location.MEDIA, R.string.group_media, Icons.Outlined.PhotoLibrary, content.collections.size))
                        }
                    }
                    if (entries.isEmpty()) {
                        item(key = "no-files") {
                            NoFiles(readOnly, filtering, onShowAll, Modifier.fillParentMaxHeight(if (groups.isEmpty()) 0.7f else 0.35f))
                        }
                    }
                    itemsIndexed(groups, key = { _, group -> group.location }) { index, group ->
                        GroupRow(group, index, groups.size, onOpen = { onNavigate(group.location) })
                    }
                }
            }
        }
        if (truncated) {
            item(key = "truncated") {
                Text(
                    text = stringResource(R.string.results_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
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

/** A non-file group of records listed at the top level, opened like a folder. */
private class Group(val location: String, val title: Int, val icon: ImageVector, val size: Int)

@Composable
private fun LazyItemScope.GroupRow(group: Group, index: Int, count: Int, onOpen: () -> Unit) {
    ListItem(
        onClick = onOpen,
        modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { Icon(group.icon, contentDescription = null) },
        trailingContent = { CountWithChevron(group.size) },
    ) {
        Text(stringResource(group.title))
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
    val name = @Composable { Text(if (isVolume) storageLabel(node) else node.name) }
    val detail: (@Composable () -> Unit)? = node.accesses.takeIf { it.isNotEmpty() }?.let { accesses ->
        if (readOnly) {
            // Only reads are listed here; the trailing count gives their number.
            { Text(relativeTime(accesses.maxOf { it.lastSeen })) }
        } else {
            { AccessLines(accesses) }
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
                { Text(stringResource(R.string.read_count, reads)) }
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
        if (count > 0) Text(count.toString())
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A volume root in words: "Internal storage", or the volume's id for removable storage. */
@Composable
private fun storageLabel(node: FileNode): String {
    val user = PRIMARY_VOLUME.matchEntire(node.path)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        user == 0 -> stringResource(R.string.internal_storage)

        user != null -> stringResource(R.string.internal_storage_user, user)

        node.path.startsWith("/storage/") ->
            stringResource(R.string.removable_storage, node.path.removePrefix("/storage/"))

        else -> node.name
    }
}

private val PRIMARY_VOLUME = Regex("/storage/emulated/(\\d+)")

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
    val time = remember(lastSeen) { relativeTime(lastSeen) }
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

/** When one file was accessed: a line per access, most recent first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileDetailsSheet(details: FileDetails, onOpen: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = details.path.substringAfterLast('/'),
                style = MaterialTheme.typography.titleLarge,
            )
            SelectionContainer {
                Text(
                    text = details.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val periods = details.periods
            when {
                details.failure != null -> Text(
                    text = (details.failure as? LoadFailure.Other)?.message
                        ?: stringResource(UiR.string.error_title),
                    color = MaterialTheme.colorScheme.error,
                )

                periods == null -> BigBrotherLoadingIndicator(Modifier.padding(vertical = 24.dp))

                else -> LazyColumn(
                    // Leaves room for the button below on a long history.
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(periods) { period -> PeriodRow(period) }
                }
            }
            // A folder that was only listed has nothing to show in a viewer.
            val viewable = periods?.none { it.action == Action.LIST } ?: true
            if (viewable) {
                FilledTonalButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_file))
                }
            }
        }
    }
}

@Composable
private fun PeriodRow(period: AccessPeriod) {
    val label = actionLabel(period.action, period.viaProvider)
    Column {
        Text(text = exactDateTime(period.time), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = when {
                period.allowed == 0 -> stringResource(R.string.access_blocked, label, period.denied)

                period.denied > 0 -> stringResource(
                    R.string.access_partly_blocked,
                    label,
                    period.allowed + period.denied,
                    period.denied,
                )

                else -> stringResource(R.string.access_allowed, label, period.allowed)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
            Text(access.path, fontFamily = FontFamily.Monospace)
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
