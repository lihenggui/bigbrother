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

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal class GuardChanges {
    private val changes = ArrayBlockingQueue<Unit>(1)

    fun signal() {
        changes.offer(Unit)
    }

    /** Reports whether the fallback deadline needs resetting after an idle period. */
    fun awaitGuarded(hasGuarded: () -> Boolean): Boolean {
        if (hasGuarded()) return false
        while (!hasGuarded()) changes.take()
        return true
    }

    fun awaitChange(nanos: Long): Boolean = changes.poll(nanos, TimeUnit.NANOSECONDS) != null

    fun clear() = changes.clear()
}
