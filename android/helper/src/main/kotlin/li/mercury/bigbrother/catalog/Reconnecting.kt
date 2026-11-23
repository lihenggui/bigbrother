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
 * first asked for: [connect] is tried again until it returns one. The
 * daemon starts its helpers while the system is booting, and a service
 * registered late would otherwise be missing for as long as the helper
 * runs.
 *
 * The wait before the next attempt starts at [firstNanos] and doubles
 * while it stays within [regularNanos], how often the caller asks for the
 * connection anyway. A service that comes seconds into the boot is found
 * within seconds. One that never comes costs those few attempts: after
 * them none is scheduled ([retryInNanos]), and one is made each time the
 * caller asks again after [firstNanos]. An attempt asked for before it is
 * due is not made.
 */
internal class Reconnecting<T : Any>(
    private val firstNanos: Long,
    private val regularNanos: Long,
    private val now: () -> Long = System::nanoTime,
    private val connect: () -> T?,
) {
    init {
        // A wait of nothing would have the caller wake without pause.
        require(firstNanos > 0)
    }

    private var connected: T? = null
    private var wait = firstNanos
    private var dueAt: Long? = null
    private var scheduling = true

    @Synchronized
    fun get(): T? {
        connected?.let { return it }
        val time = now()
        if (dueAt?.let { time - it < 0 } == true) return null
        val found = connect()
        if (found == null) {
            when {
                // Left to the caller: only what it asks again within one
                // check is held back, never its next check.
                !scheduling -> dueAt = time + firstNanos

                wait > regularNanos -> {
                    scheduling = false
                    dueAt = time + firstNanos
                }

                else -> {
                    dueAt = time + wait
                    wait *= 2
                }
            }
        }
        connected = found
        return found
    }

    /**
     * How long until an attempt the caller should wake for; `null` once
     * connected, or when the attempts are left to the caller's own asking.
     */
    @Synchronized
    fun retryInNanos(): Long? = if (connected != null || !scheduling) null else dueAt?.let { maxOf(0, it - now()) } ?: 0
}
