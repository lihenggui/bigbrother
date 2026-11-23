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
    fun givenServiceNeverRegistered_whenRetried_thenTheWaitDoublesAndThenNoAttemptIsScheduled() {
        var now = 0L
        var attempts = 0
        val service = Reconnecting<String>(firstNanos = 250, regularNanos = 1500, now = { now }) {
            attempts++
            null
        }

        assertEquals(0, service.retryInNanos())
        assertNull(service.get())
        assertEquals(250, service.retryInNanos())
        now += 250
        assertNull(service.get())
        assertEquals(500, service.retryInNanos())
        now += 500
        assertNull(service.get())
        assertEquals(1000, service.retryInNanos())
        now += 1000
        assertNull(service.get())
        assertNull(service.retryInNanos())
        assertEquals(4, attempts)
    }

    @Test
    fun givenAttemptsLeftToTheCaller_whenItsNextCheckComesSoon_thenThatCheckTriesOnce() {
        var now = 0L
        var attempts = 0
        val service = Reconnecting<String>(firstNanos = 250, regularNanos = 1500, now = { now }) {
            attempts++
            null
        }
        while (service.retryInNanos() != null) {
            now += checkNotNull(service.retryInNanos())
            service.get()
        }
        val scheduled = attempts

        // Sooner than the last scheduled wait, which must not hold it back.
        now += 250
        repeat(4) { assertNull(service.get()) }

        assertEquals(scheduled + 1, attempts)
        assertNull(service.retryInNanos())
    }

    @Test
    fun givenAttemptNotDue_whenAsked_thenNoAttemptIsMade() {
        var now = 0L
        var registered = false
        var attempts = 0
        val service = Reconnecting(firstNanos = 250, regularNanos = 1500, now = { now }) {
            attempts++
            "service".takeIf { registered }
        }

        assertNull(service.get())
        registered = true
        now = 249
        assertNull(service.get())
        assertEquals(1, service.retryInNanos())
        assertEquals(1, attempts)

        now = 250
        assertEquals("service", service.get())
        assertEquals(2, attempts)
    }

    @Test
    fun givenConnectedService_whenAskedAgain_thenItIsNotLookedForAgain() {
        var attempts = 0
        val service = Reconnecting(firstNanos = 250, regularNanos = 1500, now = { 0 }) {
            attempts++
            "service"
        }

        repeat(3) { assertEquals("service", service.get()) }

        assertEquals(1, attempts)
        assertNull(service.retryInNanos())
    }
}
