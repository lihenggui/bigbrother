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

package li.mercury.bigbrother.feature.jail.impl.navigation

import li.mercury.bigbrother.feature.appdetail.api.navigation.AppDetailNavKey
import li.mercury.bigbrother.feature.appdetail.api.navigation.RecordNavKey
import li.mercury.bigbrother.feature.jail.api.navigation.JailAppNavKey
import li.mercury.bigbrother.feature.jail.api.navigation.JailNavKey
import li.mercury.bigbrother.feature.jail.api.navigation.JailSettingsNavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShownBesideListTest {
    private val notes = JailAppNavKey(10156, "com.example.notes")

    @Test
    fun givenNothingOpened_whenListShown_thenNothingMarked() {
        assertNull(shownBesideList(listOf(JailNavKey)))
    }

    @Test
    fun givenAppPageOrSettingsOpened_whenListShown_thenItMarked() {
        assertEquals(notes, shownBesideList(listOf(JailNavKey, notes)))
        assertEquals(JailSettingsNavKey, shownBesideList(listOf(JailNavKey, JailSettingsNavKey)))
    }

    @Test
    fun givenRecordsOpenedFromAppPage_whenListShown_thenAppStillMarked() {
        val stack = listOf(JailNavKey, notes, AppDetailNavKey(10156, "com.example.notes", 24), RecordNavKey(10156, "com.example.notes", 24, "contacts", "PROVIDER"))

        assertEquals(notes, shownBesideList(stack))
    }

    @Test
    fun givenOtherAppOpenedFromSearch_whenListShown_thenNothingMarked() {
        assertNull(shownBesideList(listOf(JailNavKey, notes, AppDetailNavKey(10187, "com.example.weather", 24))))
        assertNull(shownBesideList(listOf(JailNavKey, JailSettingsNavKey, AppDetailNavKey(10156, "com.example.notes", 24))))
    }
}
