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

/**
 * A connection to a system service that may not be there yet when it is
 * first asked for: [connect] is tried again when asked later, no more
 * often than every [retryNanos], until it returns one. The daemon starts
 * its helpers while the system is booting, and a service registered late
 * would otherwise be missing for as long as the helper runs.
 */
internal class Reconnecting<T : Any>(
    private val retryNanos: Long,
    private val now: () -> Long = System::nanoTime,
    private val connect: () -> T?,
) {
    private var connected: T? = null
    private var triedAt: Long? = null

    @Synchronized
    fun get(): T? {
        connected?.let { return it }
        val time = now()
        if (triedAt?.let { time - it < retryNanos } == true) return null
        triedAt = time
        return connect().also { connected = it }
    }
}
