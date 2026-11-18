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

import li.mercury.bigbrother.core.model.SandboxMode.COARSE
import li.mercury.bigbrother.core.model.SandboxMode.FOREGROUND
import li.mercury.bigbrother.core.model.SandboxMode.OFF
import li.mercury.bigbrother.core.model.SandboxMode.ON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxTest {
    @Test
    fun givenEachItem_whenModesListed_thenOnlyWhileInUseForLocationCameraAndMicrophone() {
        assertEquals(listOf(ON, COARSE, FOREGROUND, OFF), SandboxItem.LOCATION.modes)
        assertEquals(listOf(ON, FOREGROUND, OFF), SandboxItem.CAMERA.modes)
        assertEquals(listOf(ON, FOREGROUND, OFF), SandboxItem.MICROPHONE.modes)
        val others = SandboxItem.entries - setOf(SandboxItem.LOCATION, SandboxItem.CAMERA, SandboxItem.MICROPHONE)
        others.forEach { assertEquals(listOf(ON, OFF), it.modes, it.id) }
    }

    @Test
    fun givenModeNamesFromTheDaemon_whenParsed_thenEachModeAndNothingElse() {
        SandboxMode.entries.forEach { assertEquals(it, SandboxMode.fromId(it.id)) }
        assertEquals(FOREGROUND, SandboxMode.fromId("foreground"))
        assertNull(SandboxMode.fromId("deny"))
    }

    @Test
    fun givenOnlyWhileInUse_whenAskedWhetherOn_thenOn() {
        assertTrue(FOREGROUND.isOn)
        assertTrue(COARSE.isOn)
    }
}
