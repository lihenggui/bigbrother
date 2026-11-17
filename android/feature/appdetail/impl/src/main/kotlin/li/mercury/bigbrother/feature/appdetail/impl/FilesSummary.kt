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

import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.ui.VOLUME_ROOT

/** A folder the app read files in, for the summary at the top of the app's page. */
internal data class FolderSummary(
    /** The folder's full path, which the file browser opens at. */
    val path: String,
    /** The path below the storage volume, as shown: `DCIM/Camera`. */
    val label: String,
    /** Distinct files read in it, not counting subfolders. */
    val filesRead: Int,
    val lastSeen: Long,
)

/** How much an app did with files, beyond the folders named in the summary. */
internal data class FileTotals(
    /** Distinct user files on shared storage it opened. */
    val read: Int,
    /** Distinct paths it only listed, probed or watched, or was denied. */
    val lookedFor: Int,
)

/**
 * The folders whose files the app read most, most files first, then most
 * recent; at most [limit].
 */
internal fun topFolders(accesses: List<Access>, limit: Int): List<FolderSummary> = accesses
    .filter { it.category == Category.STORAGE && it.isSuccessfulOpen() }
    .distinctBy { it.path }
    .groupBy { it.path.substringBeforeLast('/') }
    .map { (folder, files) ->
        FolderSummary(
            path = folder,
            label = VOLUME_ROOT.find(folder)?.let { folder.removePrefix(it.value).removePrefix("/") }
                ?.ifEmpty { null } ?: folder,
            filesRead = files.size,
            lastSeen = files.maxOf { it.lastSeen },
        )
    }
    .sortedWith(compareByDescending<FolderSummary> { it.filesRead }.thenByDescending { it.lastSeen })
    .take(limit)

/** Files read, and paths only looked for, each counted once. */
internal fun fileTotals(accesses: List<Access>): FileTotals {
    val storage = accesses.filter { it.category == Category.STORAGE }
    val read = storage.filter { it.isSuccessfulOpen() }.mapTo(HashSet()) { it.path }
    val other = storage.mapTo(HashSet()) { it.path } - read
    return FileTotals(read = read.size, lookedFor = other.size)
}
