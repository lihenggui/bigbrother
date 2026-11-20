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

package li.mercury.bigbrother.feature.appdetail.impl.tree

import li.mercury.bigbrother.core.model.Access

/**
 * A folder or file in the tree of paths an app touched.
 *
 * @param name what to show: a single path component, or several joined by
 *   `/` where a chain of folders holding nothing else has been collapsed.
 * @param path the full path, which identifies the node.
 * @param accesses what the app did to this exact path; empty for a folder
 *   that only appears because something inside it was touched.
 * @param children contents, folders first.
 * @param touchedCount number of touched paths below this node.
 */
internal data class FileNode(
    val name: String,
    val path: String,
    val accesses: List<Access>,
    val children: List<FileNode>,
    val touchedCount: Int,
) {
    val isFolder: Boolean get() = children.isNotEmpty()
}

private class Builder(val name: String, val path: String) {
    val children = LinkedHashMap<String, Builder>()
    val accesses = ArrayList<Access>()

    fun build(checkActive: () -> Unit): FileNode {
        checkActive()
        // Collapse "a" -> "b" -> "c" into one "a/b/c" row when the folders in
        // between were not touched themselves and hold nothing else.
        var node = this
        var label = name
        while (node.accesses.isEmpty() && node.children.size == 1) {
            val only = node.children.values.first()
            // Stop at a file, and at a folder that was itself touched: it
            // needs a row of its own to show what was done to it.
            if (only.children.isEmpty() || only.accesses.isNotEmpty()) break
            label = "$label/${only.name}"
            node = only
        }
        val built = node.children.values.map { it.build(checkActive) }
            .sortedWith(compareBy<FileNode> { !it.isFolder }.thenBy { it.name.lowercase() })
        return FileNode(
            name = label,
            path = node.path,
            accesses = node.accesses,
            children = built,
            touchedCount = built.sumOf { it.touchedCount + if (it.accesses.isEmpty()) 0 else 1 },
        )
    }
}

/** Arrange [accesses] into a tree by path. Returns the top-level nodes. */
internal fun buildFileTree(accesses: List<Access>, checkActive: () -> Unit = {}): List<FileNode> {
    val root = Builder("", "")
    for (access in accesses) {
        checkActive()
        var node = root
        for (component in access.path.split('/')) {
            if (component.isEmpty()) continue
            val parent = node
            node = parent.children.getOrPut(component) {
                Builder(component, "${parent.path}/$component")
            }
        }
        if (node !== root) node.accesses.add(access)
    }
    return root.children.values.map { it.build(checkActive) }
        .sortedWith(compareBy<FileNode> { !it.isFolder }.thenBy { it.name.lowercase() })
        // A leading slash marks the top-level names as absolute paths.
        .map { it.copy(name = "/${it.name}") }
}
