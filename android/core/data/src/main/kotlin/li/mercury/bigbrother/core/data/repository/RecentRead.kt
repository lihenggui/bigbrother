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

/**
 * The last value read, reused for the same key for [maxAgeMs]: screens
 * shown one after another ask for the same records within moments, and
 * each read is a root command.
 */
internal class RecentRead<K : Any, V : Any>(private val maxAgeMs: Long) {
    private class Entry<K, V>(val key: K, val readAt: Long, val value: V)

    @Volatile
    private var last: Entry<K, V>? = null

    suspend fun get(key: K, read: suspend () -> V): V {
        last?.let { if (it.key == key && SystemClock.elapsedRealtime() - it.readAt < maxAgeMs) return it.value }
        return read().also { last = Entry(key, SystemClock.elapsedRealtime(), it) }
    }
}
