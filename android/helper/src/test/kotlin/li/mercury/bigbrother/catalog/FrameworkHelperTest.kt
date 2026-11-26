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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameworkHelperTest {
    @Test
    fun givenMixedAndMalformedFrames_whenRoutingThem_thenRepliesKeepTheirDomainsAndIds() {
        val output = mutableListOf<String>()
        val frames = HelperFrames(output::add)
        val queries = HelperQueries(frames) { interfaces, emit -> emit("${interfaces.single()}\t7\tmethod") }
        queries.usage = FakeUsage()
        frames.ready("catalog")

        queries.route("catalog 11 interface")
        queries.route("unknown")
        queries.route("usage wrong-id poll")
        queries.route("usage 12 configure android:camera,android:record_audio")
        queries.route("guard kind 10100 9 com.example.app")
        queries.route("usage 13 invalid")
        queries.route("usage 14 poll")

        assertEquals("kind 10100 9 com.example.app", queries.guard.poll())
        frames.guard("app 10100 9")
        repeat(4) { queries.answerNext() }
        assertEquals(
            listOf(
                "ready v1 catalog",
                "guard app 10100 9",
                "catalog 11 row interface\t7\tmethod",
                "catalog 11 end",
                "usage 12 ok",
                "usage 13 err IllegalArgumentException",
                "usage 14 row 10100\tcom.example.app\tandroid:camera\t9000\t-1",
                "usage 14 end",
            ),
            output,
        )
    }

    @Test
    fun givenFailingQueries_whenWorkersProcessFrames_thenGuardRemainsAvailableAndErrorsOmitDetails() {
        val output = mutableListOf<String>()
        val frames = HelperFrames(output::add)
        val queries = HelperQueries(frames) { _, _ -> error("/data/private") }
        queries.usage = FakeUsage(failing = true)
        queries.route("catalog 1 interface")
        queries.route("usage 2 configure android:camera")
        queries.route("usage 3 poll")
        repeat(3) { queries.answerNext() }
        queries.route("guard kind 10100 8 com.example.app")
        assertEquals("kind 10100 8 com.example.app", queries.guard.poll())
        frames.guard("app 10100 8")
        assertEquals(
            listOf("catalog 1 err IllegalStateException", "usage 2 ok", "usage 3 err IllegalStateException", "guard app 10100 8"),
            output,
        )
    }

    @Test
    fun givenAUsagePollWaitingOnAndroid_whenAGuardCommandArrives_thenItCanBeAnsweredWhileThePollWaits() {
        val started = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val output = mutableListOf<String>()
        val frames = HelperFrames(output::add)
        val queries = HelperQueries(frames) { _, _ -> }
        queries.usage = object : HelperUsage {
            override fun configure(ops: List<String>) = Unit

            override fun poll(emit: (String) -> Unit) {
                started.countDown()
                check(complete.await(5, TimeUnit.SECONDS))
            }
        }
        queries.route("usage 10 poll")
        val worker = thread(isDaemon = true) { queries.answerNext() }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            queries.route("guard kind 10100 4 com.example.app")
            assertEquals("kind 10100 4 com.example.app", queries.guard.poll(1, TimeUnit.SECONDS))
            frames.guard("app 10100 4")
        } finally {
            complete.countDown()
            worker.join(2000)
        }
        assertFalse(worker.isAlive)
        assertEquals(listOf("guard app 10100 4", "usage 10 end"), output)
    }

    @Test
    fun givenABurstAndAnInterveningPoll_whenNotifying_thenEventsCoalesceAndRespectTheMinimumInterval() {
        val emitted = mutableListOf<Long>()
        var now = 0L
        val dirty = UsageDirty(now = { now }, sleep = { now += it }) { emitted.add(now) }
        repeat(100) { dirty.mark() }
        dirty.notifyNext()
        repeat(100) { dirty.mark() }
        dirty.clear()
        dirty.mark()
        dirty.clear()
        dirty.notifyNext()
        repeat(100) { dirty.mark() }
        dirty.notifyNext()
        assertEquals(listOf(0L, TimeUnit.SECONDS.toNanos(5)), emitted)
    }

    @Test
    fun givenNoGuardedUids_whenTheFirstUidIsGuarded_thenAnUntimedWaitWakes() {
        val changes = GuardChanges()
        val token = "0123456789abcdef0123456789abcdef"
        val guard = AppOpsGuard(FakeAndroid(), isCurrent = { _, _ -> true }, printLine = {}, guardingChanged = changes::signal)
        val started = CountDownLatch(1)
        val woke = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            started.countDown()
            if (changes.awaitGuarded(guard::hasGuarded)) woke.countDown()
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (worker.state != Thread.State.WAITING && System.nanoTime() < until) Thread.yield()
            assertEquals(Thread.State.WAITING, worker.state)
            assertEquals(1L, woke.count)
            guard.handleLine("bound com.example.app $token verify 10100 android:camera=1")
            assertTrue(woke.await(1, TimeUnit.SECONDS))
            worker.join(1000)
            assertFalse(worker.isAlive)
        } finally {
            worker.interrupt()
        }
    }

    @Test
    fun givenAGuardedUid_whenItsInstallationIsForgotten_thenNoFurtherReadbackIsScheduled() {
        val output = mutableListOf<String>()
        val changes = mutableListOf<Boolean>()
        val token = "0123456789abcdef0123456789abcdef"
        val other = "fedcba9876543210fedcba9876543210"
        lateinit var guard: AppOpsGuard
        guard = AppOpsGuard(
            FakeAndroid(),
            isCurrent = { _, _ -> true },
            printLine = output::add,
            guardingChanged = { changes.add(guard.hasGuarded()) },
        )
        guard.handleLine("bound com.example.app $token verify 10100 android:camera=1")
        output.clear()
        guard.handleLine("forget 10100 $other")
        assertTrue(guard.hasGuarded())
        guard.handleLine("forget 10100 $token")
        assertFalse(guard.hasGuarded())
        assertEquals(listOf(true, false), changes)
        assertEquals(listOf("forgot 10100 $other", "forgot 10100 $token"), output)
    }

    private class FakeUsage(private val failing: Boolean = false) : HelperUsage {
        private var ops = emptyList<String>()

        override fun configure(ops: List<String>) {
            this.ops = ops
        }

        override fun poll(emit: (String) -> Unit) {
            check(!failing) { "com.example.app" }
            check("android:camera" in ops)
            emit("10100\tcom.example.app\tandroid:camera\t9000\t-1")
        }
    }
}
