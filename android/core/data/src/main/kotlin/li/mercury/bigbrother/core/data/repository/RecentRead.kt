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

package li.mercury.bigbrother.core.data.repository

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keeps the most recently used reads and shares work across callers with different lifetimes. */
internal class RecentRead<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val maxAgeMs: Long,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Entry<V>(val readAt: Long, val value: V)
    private class Flight<V>(val forced: Boolean) {
        lateinit var result: Deferred<Result<V>>
    }

    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(CAPACITY, 0.75f, true)
    private val flights = mutableMapOf<K, Flight<V>>()

    suspend fun get(key: K, forceRefresh: Boolean = false, read: suspend () -> V): V {
        currentCoroutineContext().ensureActive()
        val result = synchronized(lock) {
            val flight = flights[key]
            when {
                flight != null && !flight.result.isCompleted && (!forceRefresh || flight.forced) -> flight.result

                forceRefresh -> startRead(key, true, read)

                else -> {
                    entries[key]?.let { if (nowMs() - it.readAt < maxAgeMs) return it.value }
                    startRead(key, false, read)
                }
            }
        }
        return result.await().getOrThrow()
    }

    fun invalidate() = synchronized(lock) {
        entries.clear()
        flights.clear()
    }

    private fun startRead(key: K, forceRefresh: Boolean, read: suspend () -> V): Deferred<Result<V>> {
        val flight = Flight<V>(forceRefresh)
        entries.remove(key)
        flights[key] = flight
        flight.result = scope.async(start = CoroutineStart.LAZY) {
            try {
                val value = read()
                ensureActive()
                synchronized(lock) {
                    // A forced read or invalidation may have superseded this read.
                    if (flights[key] === flight) {
                        entries[key] = Entry(nowMs(), value)
                        if (entries.size > CAPACITY) entries.remove(entries.keys.first())
                    }
                }
                Result.success(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        flight.result.invokeOnCompletion {
            synchronized(lock) {
                if (flights[key] === flight) flights.remove(key)
            }
        }
        return flight.result
    }

    private companion object {
        const val CAPACITY = 8
    }
}
