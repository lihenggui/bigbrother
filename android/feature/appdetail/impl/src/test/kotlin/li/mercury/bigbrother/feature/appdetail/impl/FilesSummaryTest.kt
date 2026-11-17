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
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import kotlin.test.Test
import kotlin.test.assertEquals

class FilesSummaryTest {
    private fun access(path: String, action: Action = Action.READ, allowed: Int = 1, lastSeen: Long = 0) = Access(
        category = Category.STORAGE,
        path = path,
        action = action,
        viaProvider = false,
        items = 0,
        allowed = allowed,
        denied = if (allowed == 0) 1 else 0,
        lastSeen = lastSeen,
    )

    @Test
    fun givenReadsInSeveralFolders_whenSummarised_thenRankedByFilesReadAndNamedBelowVolume() {
        val folders = topFolders(
            listOf(
                access("/storage/emulated/0/DCIM/Camera/a.jpg"),
                access("/storage/emulated/0/DCIM/Camera/b.jpg"),
                // The same file read twice counts once.
                access("/storage/emulated/0/DCIM/Camera/b.jpg", lastSeen = 5),
                access("/storage/emulated/0/Download/c.pdf", lastSeen = 9),
                // Probed or refused files were not read.
                access("/storage/emulated/0/Music/d.mp3", action = Action.PROBE),
                access("/storage/emulated/0/Documents/e.txt", allowed = 0),
            ),
            limit = 3,
        )
        assertEquals(listOf("DCIM/Camera" to 2, "Download" to 1), folders.map { it.label to it.filesRead })
        assertEquals("/storage/emulated/0/DCIM/Camera", folders.first().path)
    }

    @Test
    fun givenSeveralActionsOnOnePath_whenTotalled_thenPathCountsOnce() {
        val totals = fileTotals(
            listOf(
                access("/storage/emulated/0/DCIM/a.jpg"),
                access("/storage/emulated/0/DCIM/a.jpg", action = Action.PROBE),
                access("/storage/emulated/0/Music", action = Action.LIST),
                access("/storage/emulated/0/x", allowed = 0),
            ),
        )
        assertEquals(FileTotals(read = 1, lookedFor = 2), totals)
    }
}
