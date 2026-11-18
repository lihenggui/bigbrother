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

class SandboxStatusTest {
    private val appOpsNotHeld = SandboxStatus(gate = true, appOps = false, androidIdHook = true, appListHook = true)

    @Test
    fun givenAppOpsNotHeld_whenAnAppKeepsPersonalData_thenPersonalDataNotInEffect() {
        val status = appOpsNotHeld.relevantTo(listOf(app(enabled = true, SandboxItem.entries.associateWith { SandboxMode.ON })))

        assertFalse(status.appOps)
        assertEquals(setOf(SandboxPart.APP_OPS), status.unmet(SandboxItem.CONTACTS))
        assertEquals(setOf(SandboxPart.APP_OPS), status.unmet(SandboxItem.STORAGE))
        assertEquals(emptySet(), status.unmet(SandboxItem.CPU_INFO))
    }

    @Test
    fun givenAppOpsNotHeld_whenNoAppKeepsPersonalData_thenNothingMissing() {
        val personalOff = SandboxItem.entries.associateWith { if (SandboxPart.APP_OPS in it.parts) SandboxMode.OFF else SandboxMode.ON }

        val status = appOpsNotHeld.relevantTo(listOf(app(enabled = true, personalOff), app(enabled = false, emptyMap())))

        assertTrue(status.appOps)
        assertEquals(emptySet(), status.unmet(SandboxItem.CONTACTS))
    }

    @Test
    fun givenHookMissing_whenItemsChecked_thenOnlyTheHookPartsMissing() {
        val status = SandboxStatus(gate = true, appOps = true, androidIdHook = false, appListHook = true)

        assertEquals(setOf(SandboxPart.ANDROID_ID_HOOK), status.unmet(SandboxItem.DEVICE_IDS))
        assertEquals(emptySet(), status.unmet(SandboxItem.INSTALLED_APPS))
    }

    private fun app(enabled: Boolean, items: Map<SandboxItem, SandboxMode>) = SandboxedApp(10156, "com.example.notes", enabled, created = 0, settings = emptyMap(), items = items)
}
