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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UidsTest {
    @Test
    fun givenPrimaryAndSecondaryUserUids_whenUserIdRead_thenReturnsEachUser() {
        assertEquals(0, userIdOf(10_231))
        assertEquals(1, userIdOf(100_000))
        assertEquals(10, userIdOf(1_010_231))
    }

    @Test
    fun givenSameAppUnderDifferentUsers_whenAppIdRead_thenReturnsSameAppId() {
        assertEquals(10_231, appIdOf(10_231))
        assertEquals(10_231, appIdOf(1_010_231))
        assertEquals(0, appIdOf(100_000))
    }

    @Test
    fun givenUidsAroundApplicationRange_whenChecked_thenOnlyRegularAppsQualify() {
        assertFalse(isApplicationUid(-1))
        assertFalse(isApplicationUid(9_999))
        assertTrue(isApplicationUid(10_000))
        assertTrue(isApplicationUid(1_019_999))
        assertFalse(isApplicationUid(1_020_000))
        assertFalse(isApplicationUid(1_099_999))
    }

    @Test
    fun givenUidAtApplicationThreshold_whenChecked_thenOnlyNonCoreUidsQualify() {
        assertFalse(isNonCoreUid(9_999))
        assertTrue(isNonCoreUid(10_000))
        assertFalse(isNonCoreUid(1_009_999))
        assertTrue(isNonCoreUid(1_010_000))
        assertTrue(isNonCoreUid(1_099_999))
        assertFalse(isNonCoreUid(-1))
    }
}
