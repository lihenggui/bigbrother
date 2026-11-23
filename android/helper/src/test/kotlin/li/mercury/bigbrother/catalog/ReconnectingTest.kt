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

package li.mercury.bigbrother.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReconnectingTest {
    @Test
    fun givenServiceRegisteredLate_whenAskedAgain_thenConnectsOnceItIsThereAndNoMoreOftenThanAllowed() {
        var now = 0L
        var registered = false
        var attempts = 0
        val service = Reconnecting(retryNanos = 5, now = { now }) {
            attempts++
            "service".takeIf { registered }
        }

        assertNull(service.get())
        now = 4
        registered = true
        assertNull(service.get())
        assertEquals(1, attempts)

        now = 5
        assertEquals("service", service.get())
        assertEquals(2, attempts)
    }

    @Test
    fun givenConnectedService_whenAskedAgain_thenItIsNotLookedForAgain() {
        var attempts = 0
        val service = Reconnecting(retryNanos = 0, now = { 0 }) {
            attempts++
            "service"
        }

        repeat(3) { assertEquals("service", service.get()) }

        assertEquals(1, attempts)
    }
}
