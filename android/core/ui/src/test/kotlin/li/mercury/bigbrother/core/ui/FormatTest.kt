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

package li.mercury.bigbrother.core.ui

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "en-rUS")
class FormatTest {
    @Test
    fun givenTimeFiveMinutesAgo_whenFormatted_thenReportsMinutes() {
        assertEquals("5 minutes ago", relativeTime(epochSeconds = 100, nowMillis = 400_000))
    }

    @Test
    fun givenEmulatedAndRemovablePaths_whenMatchingVolume_thenFindsTheirRoots() {
        assertEquals("/storage/emulated/10", VOLUME_ROOT.find("/storage/emulated/10/DCIM/photo.jpg")?.value)
        assertEquals("/storage/1234-ABCD", VOLUME_ROOT.find("/storage/1234-ABCD/photo.jpg")?.value)
        assertNull(VOLUME_ROOT.find("/data/local/tmp"))
    }
}
