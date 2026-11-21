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

import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.getValue
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.label

/** One row of the page, in order. */
internal sealed interface Item {
    val key: String

    data class Header(val title: Int, val lastSeen: Long?, val category: DataCategory? = null) : Item {
        override val key: String get() = "header:${category?.name ?: title}"
    }

    data class Folder(val folder: FolderSummary, val index: Int, val count: Int) : Item {
        override val key: String get() = "folder:${folder.path}"
    }

    data class Collection(val access: Access, val index: Int, val count: Int) : Item {
        override val key: String get() = "collection:$index:${access.path}"
    }

    data class BrowseFiles(val totals: FileTotals, val index: Int, val count: Int) : Item {
        override val key: String get() = "browse"
    }

    data class Topic(val group: TopicGroup, val index: Int, val count: Int) : Item {
        override val key: String get() = "topic:${group.key}"
    }

    data class Info(val label: Int, val value: String, val index: Int, val count: Int) : Item {
        override val key: String get() = "info:$label"
    }

    data object Launch : Item {
        override val key: String get() = "launch"
    }
}

/** Folders named before the rest of the files are left to the browser. */
private const val FOLDERS_SHOWN = 3

internal class AppDetailRecords(val items: List<Item>, val empty: Boolean) {
    private val sectionIndices = items.mapIndexedNotNull { index, item ->
        (item as? Item.Header)?.category?.let { it.name to index }
    }.toMap()

    fun indexOf(category: String): Int? = sectionIndices[category]
}

internal fun summarizeAccesses(accesses: List<Access>): AppDetailRecords {
    val files = accesses.filter { it.isFile() }
    val folders = topFolders(files, FOLDERS_SHOWN)
    val totals = fileTotals(files)
    val collections = files.filter { it.category == Category.PROVIDER }
    val categories = groupByCategory(accesses)
    val items = buildList {
        if (files.isNotEmpty()) {
            add(Item.Header(DataCategory.FILES.title, files.maxOf { it.lastSeen }, DataCategory.FILES))
            val count = folders.size + collections.size + 1
            folders.forEachIndexed { i, folder -> add(Item.Folder(folder, i, count)) }
            collections.forEachIndexed { i, access -> add(Item.Collection(access, folders.size + i, count)) }
            add(Item.BrowseFiles(totals, count - 1, count))
        }
        for ((category, groups) in categories) {
            add(Item.Header(category.title, groups.maxOf { it.lastSeen }, category))
            groups.forEachIndexed { i, group -> add(Item.Topic(group, i, groups.size)) }
        }
    }
    return AppDetailRecords(items, accesses.isEmpty())
}
