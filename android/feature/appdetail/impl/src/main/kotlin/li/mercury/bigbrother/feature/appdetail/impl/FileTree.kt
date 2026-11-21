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

import androidx.compose.material.icons.outlined.Folder
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.ui.VOLUME_ROOT
import li.mercury.bigbrother.feature.appdetail.impl.tree.FileNode
import li.mercury.bigbrother.feature.appdetail.impl.tree.buildFileTree

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
    checkActive: () -> Unit,
) {
    /** Folder path -> the folder, and -> the path of the folder containing it. */
    private val nodes = HashMap<String, FileNode>()
    private val parents = HashMap<String, String>()

    init {
        fun index(node: FileNode, parent: String) {
            checkActive()
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

internal class FileBrowser(val tree: FileTree, val readOnly: Boolean, val filtering: Boolean)

/** Filters and arranges the file records before the page renders them. */
internal fun fileBrowserOf(
    files: List<Access>,
    query: String = "",
    readOnly: Boolean? = null,
    checkActive: () -> Unit = {},
): FileBrowser {
    val shown = if (query.isEmpty()) {
        files
    } else {
        files.filter {
            checkActive()
            it.path.contains(query, ignoreCase = true)
        }
    }
    // Show what was read first, unless the app never opened a shared-storage file.
    val onlyReads = readOnly ?: shown.any { it.category == Category.STORAGE && it.isSuccessfulOpen() }
    val (storage, collections) = shown.partition { it.category == Category.STORAGE }
    val tree = FileTree(
        volumes = buildVolumeTrees(if (onlyReads) storage.filter { it.isSuccessfulOpen() } else storage, checkActive),
        collections = collections,
        checkActive = checkActive,
    )
    return FileBrowser(tree, onlyReads, query.isNotEmpty())
}

/** Whether a record is one of the user's files, on shared storage or through a provider. */
internal fun Access.isFile(): Boolean = category == Category.STORAGE || category == Category.PROVIDER

/** A file counts as read when the system let the app open it. */
internal fun Access.isSuccessfulOpen(): Boolean = allowed > 0 && (action == Action.READ || action == Action.WRITE || action == Action.READ_WRITE)

/**
 * One tree per storage volume, so that each starts at a root the user knows
 * ("Internal storage") instead of at `/storage`. Paths are arranged relative
 * to their volume.
 */
private fun buildVolumeTrees(storage: List<Access>, checkActive: () -> Unit): List<FileNode> = storage
    .groupBy {
        checkActive()
        VOLUME_ROOT.find(it.path)?.value.orEmpty()
    }
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
        val children = buildFileTree(below.map { it.copy(path = it.path.removePrefix(volume)) }, checkActive)
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

internal val PRIMARY_VOLUME = Regex("/storage/emulated/(\\d+)")
