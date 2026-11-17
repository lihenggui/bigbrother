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

package li.mercury.bigbrother.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineEntryTest {
    private fun providerEntry(modes: Int) = TimelineEntry(
        hourStart = 0,
        uid = 10100,
        packageName = "com.a",
        category = Category.PROVIDER,
        topic = null,
        paths = 2,
        filesRead = 0,
        allowed = 2,
        denied = 0,
        modes = modes,
        items = 0,
        folder = null,
        firstSeen = 0,
        lastSeen = 0,
    )

    @Test
    fun givenOnlyQueries_whenSummarised_thenIsQuery() {
        assertTrue(providerEntry(modes = QUERY).isQuery)
        // Made by one of the app's isolated processes.
        assertTrue(providerEntry(modes = QUERY or ISOLATED).isQuery)
    }

    @Test
    fun givenQueriesAndOpensThroughProvider_whenSummarised_thenIsNotQuery() {
        assertFalse(providerEntry(modes = QUERY or READ or VIA_PROVIDER).isQuery)
    }

    private companion object {
        // The daemon's mode bits.
        const val READ = 1
        const val VIA_PROVIDER = 32
        const val QUERY = 64
        const val ISOLATED = 1024
    }
}
