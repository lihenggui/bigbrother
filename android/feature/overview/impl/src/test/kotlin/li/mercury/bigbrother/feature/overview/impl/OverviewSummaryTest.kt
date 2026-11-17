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

package li.mercury.bigbrother.feature.overview.impl

import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.ui.DataCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class OverviewSummaryTest {
    private fun app(
        uid: Int,
        lastSeen: Long,
        storage: Int = 0,
        provider: Int = 0,
        filesRead: Int = 0,
        folders: List<String> = emptyList(),
        topics: List<String> = emptyList(),
    ) = AppSummary(
        uid = uid,
        packageName = "app$uid",
        counts = mapOf(Category.STORAGE to storage, Category.PROVIDER to provider),
        lastSeen = lastSeen,
        filesRead = filesRead,
        folders = folders,
        topics = topics,
    )

    @Test
    fun givenAppsWithAndWithoutReads_whenSummarised_thenReadersMostRecentFirst() {
        val (files, _) = overviewOf(
            listOf(
                app(1, lastSeen = 10, storage = 2, filesRead = 2, folders = listOf("DCIM")),
                app(2, lastSeen = 30, provider = 1),
                app(3, lastSeen = 20, storage = 5),
                app(4, lastSeen = 40, topics = listOf("android_id")),
            ),
        )
        assertEquals(listOf(2, 1), files.readers.map { it.uid })
        assertEquals(true, files.mediaLibrary)
        // App 3 touched files without reading any; app 4 touched none.
        assertEquals(1, files.lookedOnly)
    }

    @Test
    fun givenReadersInSeveralFolders_whenSummarised_thenFoldersRankedByReaders() {
        val (files, _) = overviewOf(
            listOf(
                app(1, 1, storage = 1, filesRead = 1, folders = listOf("Download", "DCIM")),
                app(2, 2, storage = 1, filesRead = 1, folders = listOf("DCIM", "Music")),
                app(3, 3, storage = 1, filesRead = 1, folders = listOf("DCIM", "Pictures", "Download")),
            ),
        )
        assertEquals(listOf("DCIM", "Download", "Music"), files.folders)
    }

    @Test
    fun givenAppsAcrossCategories_whenSummarised_thenCategoriesInOrderWithAppCounts() {
        val (_, categories) = overviewOf(
            listOf(
                app(1, 1, topics = listOf("android_id", "location")),
                app(2, 2, topics = listOf("android_id", "mac_address")),
                app(3, 3, storage = 1, filesRead = 1),
            ),
        )
        assertEquals(listOf(DataCategory.LOCATION, DataCategory.DEVICE_IDS), categories.map { it.category })
        val ids = categories.single { it.category == DataCategory.DEVICE_IDS }
        assertEquals(2, ids.apps)
        assertEquals(listOf("android_id", "mac_address"), ids.topics)
    }
}
