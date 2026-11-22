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

package li.mercury.bigbrother.hook

import java.io.File
import java.nio.file.Files
import java.nio.file.WatchService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxListTest {
    private val directory = Files.createTempDirectory("sandbox-list").toFile()
    private val file = File(directory, "big_brother_sandbox")
    private var errors = 0

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun list(verifyOwners: (Set<Int>) -> Unit = {}) = SandboxList(file, { errors++ }, { _, uids -> verifyOwners(uids) })

    private fun write(text: String) {
        val temporary = File(directory, "big_brother_sandbox.tmp")
        temporary.writeText(text)
        assertTrue(temporary.renameTo(file))
    }

    @Test
    fun givenNoFile_whenReloaded_thenNobodySandboxed() {
        val list = list()
        list.reload()
        assertFalse(list.hidesAppsFrom(10_205))
        assertTrue(list.snapshot.readable)
        assertEquals(0, errors)
    }

    @Test
    fun givenReplacement_whenReloaded_thenNewRevisionAndIdPublishedTogether() {
        write("# abc\n10205 0000000000000001\n")
        val list = list()
        list.reload()
        write("# def\n10205 0000000000000002\n")
        assertEquals("0000000000000001", list.androidIdOf(10_205))
        list.reload()
        assertEquals("def", list.snapshot.revision)
        assertEquals("0000000000000002", list.androidIdOf(10_205))
    }

    @Test
    fun givenPackageListChanged_whenRevalidationIsBlocked_thenLookupStillSeesPreviousPolicy() {
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val list = list { uids ->
            if (10_206 in uids) {
                checking.countDown()
                check(release.await(10, SECONDS))
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        val callers = Executors.newSingleThreadExecutor()
        val loaded = LinkedBlockingQueue<String>()
        val monitor = SandboxListWatcher(file.toPath(), list, worker, { loaded.add(list.snapshot.revision) }, {})
        val watchThread = Thread { monitor.watch() }
        try {
            write("# abc\n10205 0123456789abcdef\n")
            watchThread.start()
            assertEquals("abc", loaded.poll(5, SECONDS))

            // The in-place write is invisible to the watch until the package-list event.
            file.writeText("# def\n10206 0123456789abcdef\n")
            File(directory, "packages.list").writeText("")
            assertTrue(checking.await(5, SECONDS))
            val lookup = callers.submit<String?> { list.androidIdOf(10_205) }
            assertEquals("0123456789abcdef", lookup.get(2, SECONDS))
            assertTrue(list.withholdsAdIdFrom(10_205))
            assertTrue(list.hidesAppsFrom(10_205))

            release.countDown()
            assertEquals("def", loaded.poll(5, SECONDS))
            assertNull(list.androidIdOf(10_205))
            assertTrue(list.hidesAppsFrom(10_206))
        } finally {
            release.countDown()
            watchThread.interrupt()
            watchThread.join(5_000)
            callers.shutdownNow()
            worker.shutdownNow()
        }
    }

    @Test
    fun givenReloadInProgress_whenAnotherIsRequested_thenWorkerPublishesTheLatestRevision() {
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val list = list { uids ->
            if (10_205 in uids) {
                checking.countDown()
                check(release.await(10, SECONDS))
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        val loaded = LinkedBlockingQueue<String>()
        val monitor = SandboxListWatcher(file.toPath(), list, worker, { loaded.add(list.snapshot.revision) }, {})
        try {
            write("# abc\n10205 0123456789abcdef\n")
            monitor.requestReload()
            assertTrue(checking.await(5, SECONDS))
            write("# def\n10206 0123456789abcdef\n")
            repeat(3) { monitor.requestReload() }
            release.countDown()

            assertEquals("abc", loaded.poll(5, SECONDS))
            assertEquals("def", loaded.poll(5, SECONDS))
            worker.submit {}.get(5, SECONDS)
            assertTrue(loaded.isEmpty())
            assertNull(list.androidIdOf(10_205))
            assertTrue(list.hidesAppsFrom(10_206))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun givenWatchEnds_whenRegistrationIsRetried_thenReportsDegradedAndKeepsPolicyUntilReloaded() {
        val list = list()
        val worker = Executors.newSingleThreadExecutor()
        val loaded = LinkedBlockingQueue<String>()
        val lost = LinkedBlockingQueue<HookState>()
        val attempts = AtomicInteger()
        val allowRetry = CountDownLatch(1)
        val fileSystem = file.toPath().fileSystem
        val firstWatch = fileSystem.newWatchService()
        val openWatch: () -> WatchService = {
            if (attempts.incrementAndGet() == 1) {
                firstWatch
            } else {
                check(allowRetry.await(10, SECONDS))
                fileSystem.newWatchService()
            }
        }
        lateinit var monitor: SandboxListWatcher
        monitor = SandboxListWatcher(
            file.toPath(),
            list,
            worker,
            { loaded.add(list.snapshot.revision) },
            { lost.add(HookState.WORKING.withListReady(list.snapshot.readable && monitor.current)) },
        )
        val watchThread = Thread { monitor.watch(openWatch) }
        try {
            write("# abc\n10205 0123456789abcdef\n")
            watchThread.start()
            assertEquals("abc", loaded.poll(5, SECONDS))
            assertTrue(monitor.current)
            firstWatch.close()

            assertEquals(HookState.DEGRADED, lost.poll(5, SECONDS))
            assertEquals("0123456789abcdef", list.androidIdOf(10_205))
            assertTrue(list.snapshot.readable)

            write("# def\n10206 0123456789abcdef\n")
            allowRetry.countDown()
            assertEquals("def", loaded.poll(5, SECONDS))
            assertTrue(monitor.current)
            assertEquals(2, attempts.get())
            assertTrue(list.hidesAppsFrom(10_206))
        } finally {
            firstWatch.close()
            watchThread.interrupt()
            watchThread.join(5_000)
            worker.shutdownNow()
        }
    }

    @Test
    fun givenListDeleted_whenWorkerConfirmsItIsGone_thenPublishesEmptySnapshot() {
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val list = list { uids ->
            if (uids.isEmpty()) {
                checking.countDown()
                check(release.await(10, SECONDS))
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        val loaded = LinkedBlockingQueue<String>()
        val monitor = SandboxListWatcher(file.toPath(), list, worker, { loaded.add(list.snapshot.revision) }, {})
        val watchThread = Thread { monitor.watch() }
        try {
            write("# abc\n10205 0123456789abcdef\n")
            watchThread.start()
            assertEquals("abc", loaded.poll(5, SECONDS))

            assertTrue(file.delete())
            assertTrue(checking.await(5, SECONDS))
            assertTrue(list.hidesAppsFrom(10_205))
            release.countDown()

            assertEquals("none", loaded.poll(5, SECONDS))
            assertFalse(list.hidesAppsFrom(10_205))
            assertTrue(list.snapshot.readable)
        } finally {
            release.countDown()
            watchThread.interrupt()
            watchThread.join(5_000)
            worker.shutdownNow()
        }
    }

    @Test
    fun givenExistingEmptyFile_whenReloaded_thenKeepsOldPolicyAndReportsError() {
        write("10205 0123456789abcdef\n")
        val list = list()
        list.reload()
        write("")
        list.reload()
        assertTrue(list.hidesAppsFrom(10_205))
        assertFalse(list.snapshot.readable)
        assertEquals(1, errors)
    }

    @Test
    fun givenUnreadableReplacement_whenReloaded_thenKeepsOldPolicyAndReportsError() {
        write("10205 0123456789abcdef\n")
        val list = list()
        list.reload()
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        list.reload()
        assertTrue(list.hidesAppsFrom(10_205))
        assertFalse(list.snapshot.readable)
        assertEquals(1, errors)
    }
}
