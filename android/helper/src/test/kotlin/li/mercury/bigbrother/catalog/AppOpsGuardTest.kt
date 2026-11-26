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

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppOpsGuardTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun givenACompletedRestoreItem_whenRecoveryRestarts_thenTheChangeRunsOnlyOnce() {
        val journal = File(temporary.root, "restore")
        var changes = 0
        AppOpsGuard(FakeAndroid(), journal).restoreItem("android:camera") { changes++ }

        val restarted = AppOpsGuard(FakeAndroid(), journal)
        restarted.restoreItem("android:camera") { changes++ }

        assertEquals(1, changes)
        assertEquals(listOf("android:camera"), journal.readLines())
    }

    @Test
    fun givenARestoreActionFails_whenRecoveryRetries_thenOnlyTheSuccessfulActionIsJournaled() {
        val journal = File(temporary.root, "restore")
        val guard = AppOpsGuard(FakeAndroid(), journal)
        var attempts = 0

        assertFailsWith<IllegalStateException> {
            guard.restoreItem("android:camera") {
                attempts++
                error("failure before completion")
            }
        }
        guard.restoreItem("android:camera") { attempts++ }
        guard.restoreItem("android:camera") { attempts++ }

        assertEquals(2, attempts)
        assertEquals(listOf("android:camera"), journal.readLines())
    }

    @Test
    fun givenAnInvalidBoundLineContainingAPackageName_whenHandlingIt_thenOnlyTheFixedDiagnosticIsPrinted() {
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val guard = AppOpsGuard(FakeAndroid(), printLine = { output += it }, warn = { errors += it })

        guard.handleLine("bound com.example.private invalid-token restore $UID")

        assertEquals(listOf("invalid bound helper command"), errors)
        assertTrue(output.isEmpty())
    }

    @Test
    fun givenABoundVerifyAndCapture_whenHandlingThem_thenReportsAndRepliesUseTheirOwnFrames() {
        val output = mutableListOf<String>()
        lateinit var guard: AppOpsGuard
        val system = object : AppOpsSystem by FakeAndroid() {
            override fun emit(line: String) = guard.emit(line)
        }
        guard = AppOpsGuard(system, isCurrent = { _, _ -> true }, printLine = { output += it })

        guard.handleLine("$BINDING verify $UID android:camera=1")
        guard.handleLine("$BINDING request 7 capture $UID 8 android:camera=1")
        guard.emit("ready")

        assertEquals("report $TOKEN checking $UID", output.first())
        assertTrue("report $TOKEN unheld $UID -" in output)
        assertTrue(output.any { it.startsWith("reply 7 $TOKEN captured $UID 8 ") })
        assertEquals("ready", output.last())
        assertTrue(output.none { '\n' in it || '\r' in it })
    }

    @Test
    fun givenAPermissionSyncAfterCapture_whenSettingTheOp_thenTheBoundStaleReplyAllowsAFreshCapture() {
        val output = mutableListOf<String>()
        val android = FakeAndroid()
        lateinit var guard: AppOpsGuard
        val system = object : AppOpsSystem by android {
            override fun emit(line: String) = guard.emit(line)
        }
        guard = AppOpsGuard(system, isCurrent = { _, _ -> true }, printLine = output::add)
        android.pmGrant(FakeAndroid.CAMERA, deferPolicy = true)
        guard.handleLine("$BINDING request 1 capture $UID 1 android:camera=1")
        android.settle()
        guard.handleLine("$BINDING request 2 set $UID android:camera=1")
        assertEquals("reply 2 $TOKEN stale $UID", output.last())
        assertTrue(android.helperWrites.isEmpty())

        guard.handleLine("$BINDING request 3 capture $UID 3 android:camera=1")
        guard.handleLine("$BINDING request 4 set $UID android:camera=1")

        assertTrue(output.last().startsWith("reply 4 $TOKEN saved $UID "))
        assertEquals(mapOf("android:camera" to 1), android.helperWrites)
    }

    @Test
    fun givenAPeriodicCheckIsBlocked_whenAnotherAppCaptures_thenItCanReplyAndBothFramesKeepTheirTokens() {
        val output = LinkedBlockingQueue<String>()
        val checkStarted = CountDownLatch(1)
        val resumeCheck = CountDownLatch(1)
        val checkFinished = CountDownLatch(1)
        val blocking = AtomicBoolean(false)
        val android = FakeAndroid()
        lateinit var guard: AppOpsGuard
        val system = object : AppOpsSystem by android {
            override fun emit(line: String) = guard.emit(line)

            override fun uidModes(uid: Int, switchCodes: IntArray): Map<Int, Int> {
                if (blocking.get()) {
                    if (uid == UID) {
                        checkStarted.countDown()
                        check(resumeCheck.await(5, SECONDS))
                    } else {
                        resumeCheck.countDown()
                        check(checkFinished.await(5, SECONDS))
                    }
                }
                return android.uidModes(uid, switchCodes)
            }
        }
        guard = AppOpsGuard(system, isCurrent = { _, _ -> true }, printLine = { output.add(it) })
        guard.handleLine("$BINDING verify $UID android:camera=1")
        output.clear()
        blocking.set(true)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val periodic = executor.submit {
                guard.checkAll()
                checkFinished.countDown()
            }
            assertTrue(checkStarted.await(5, SECONDS))
            val capture = executor.submit {
                guard.handleLine("bound com.example.second $SECOND_TOKEN request 7 capture ${UID + 1} 8 android:camera=1")
            }
            capture.get(5, SECONDS)
            periodic.get(5, SECONDS)

            assertEquals("report $TOKEN checking $UID", output.remove())
            assertEquals("report $TOKEN unheld $UID -", output.remove())
            assertTrue(output.remove().startsWith("reply 7 $SECOND_TOKEN captured ${UID + 1} 8 "))
            assertTrue(output.isEmpty())
        } finally {
            resumeCheck.countDown()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val UID = 10_100
        const val TOKEN = "0123456789abcdef0123456789abcdef"
        const val SECOND_TOKEN = "fedcba9876543210fedcba9876543210"
        const val BINDING = "bound com.example.first $TOKEN"
    }
}
