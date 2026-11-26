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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RecentReadTest {
    @Test
    fun givenCachedValue_whenTtlExpires_thenReadsAgain() = runTest {
        var now = 100L
        var reads = 0
        val cache = RecentRead<String, Int>(backgroundScope, maxAgeMs = 5_000, nowMs = { now })

        assertEquals(1, cache.get("apps") { ++reads })
        now += 4_999
        assertEquals(1, cache.get("apps") { ++reads })
        now++
        assertEquals(2, cache.get("apps") { ++reads })
    }

    @Test
    fun givenFreshValue_whenForced_thenReplacesCachedValue() = runTest {
        val cache = RecentRead<String, Int>(backgroundScope, maxAgeMs = 5_000, nowMs = { 1L })

        cache.get("apps") { 1 }
        assertEquals(2, cache.get("apps", forceRefresh = true) { 2 })
        assertEquals(2, cache.get("apps") { error("Unexpected read") })
    }

    @Test
    fun givenInterleavedKeys_whenReadAgain_thenEachKeepsItsValue() = runTest {
        val cache = RecentRead<String, Int>(backgroundScope, maxAgeMs = 5_000, nowMs = { 1L })

        cache.get("first") { 1 }
        cache.get("second") { 2 }

        assertEquals(1, cache.get("first") { error("Unexpected read") })
        assertEquals(2, cache.get("second") { error("Unexpected read") })
    }

    @Test
    fun givenFullCache_whenAddingAKey_thenLeastRecentlyUsedValueIsEvicted() = runTest {
        val cache = RecentRead<Int, Int>(backgroundScope, maxAgeMs = 5_000, nowMs = { 1L })
        (1..8).forEach { cache.get(it) { it } }
        cache.get(1) { error("Unexpected read") }

        cache.get(9) { 9 }

        assertEquals(1, cache.get(1) { error("Unexpected read") })
        assertEquals(10, cache.get(2) { 10 })
    }

    @Test
    fun givenTwoConcurrentCallers_whenOneCancels_thenOtherReceivesTheSharedRead() = runTest {
        val gate = CompletableDeferred<Int>()
        var reads = 0
        val cache = RecentRead<String, Int>(backgroundScope, maxAgeMs = 5_000, nowMs = { 1L })
        val first = async {
            cache.get("apps") {
                reads++
                gate.await()
            }
        }
        val second = async {
            cache.get("apps") {
                reads++
                gate.await()
            }
        }
        runCurrent()

        first.cancelAndJoin()
        gate.complete(42)

        assertEquals(42, second.await())
        assertEquals(1, reads)
    }

    @Test
    fun givenOlderReadInFlight_whenRefreshed_thenOlderAnswerCannotReplaceFreshValue() = runTest {
        val gate = CompletableDeferred<Int>()
        val cache = RecentRead<String, Int>(backgroundScope, maxAgeMs = 5_000, nowMs = { 1L })
        val old = async { cache.get("apps") { gate.await() } }
        runCurrent()

        assertEquals(2, cache.get("apps", forceRefresh = true) { 2 })
        gate.complete(1)
        assertEquals(1, old.await())
        assertEquals(2, cache.get("apps") { error("Unexpected read") })
    }

    @Test
    fun givenCachedValues_whenInvalidated_thenAllKeysAreReadAgain() = runTest {
        val cache = RecentRead<String, Int>(backgroundScope, maxAgeMs = 5_000, nowMs = { 1L })
        cache.get("apps") { 1 }
        cache.get("accesses") { 2 }

        cache.invalidate()

        assertEquals(3, cache.get("apps") { 3 })
        assertEquals(4, cache.get("accesses") { 4 })
    }
}
