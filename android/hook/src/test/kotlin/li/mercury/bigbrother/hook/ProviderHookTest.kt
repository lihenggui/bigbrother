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

import android.database.MatrixCursor
import android.net.Uri
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class ProviderHookTest {
    private val uri get() = Uri.parse("content://media/files")

    @Test
    fun givenStringsBeforeAndAfterTheUri_whenReadingMode_thenTheStringAfterTheUriWins() {
        assertEquals("rw", openMode(listOf("source", uri, null, "rw")))
        assertEquals("r", openMode(listOf("source", uri, null)))
        assertEquals("r", openMode(listOf("source")))
    }

    @Test
    fun givenCursorAndNonCursorResults_whenCountingRows_thenOnlyTheCursorHasRows() {
        MatrixCursor(arrayOf("name")).use { cursor ->
            repeat(3) { cursor.addRow(arrayOf("row")) }
            assertEquals(3, rowCount(cursor))
        }
        assertEquals(0, rowCount(null))
        assertEquals(0, rowCount(Any()))
    }

    @Test
    fun givenUidsFromDifferentUsers_whenFiltering_thenOnlyOtherApplicationUidsAreEligible() {
        assertFalse(isProviderCaller(12_345, 12_345))
        assertFalse(isProviderCaller(0, 12_345))
        assertFalse(isProviderCaller(1_000, 12_345))
        assertFalse(isProviderCaller(101_000, 12_345))
        assertTrue(isProviderCaller(10_000, 12_345))
        assertTrue(isProviderCaller(110_000, 12_345))
    }

    @Test
    fun givenAnAppCallerAndChangedBinderIdentity_whenOriginalReturns_thenRecordingUsesTheInitialUid() {
        ShadowProcess.setUid(12_345)
        ShadowBinder.setCallingUid(12_346)
        val recorded = mutableListOf<Int>()
        val hooker = ProviderHook.RecordingHooker({ error("unexpected recording error") }) { uid, _, _, _ -> recorded += uid }
        val result = Any()
        val chain = TestHookChain(listOf(uri)) {
            ShadowBinder.setCallingUid(12_345)
            result
        }

        assertSame(result, hooker.intercept(chain))
        assertEquals(listOf(12_346), recorded)
        assertEquals(1, chain.originalCalls)
    }

    @Test
    fun givenASystemCaller_whenOriginalReturnsAsAnAppUid_thenRecordingNeverReadsItsArguments() {
        ShadowProcess.setUid(12_345)
        ShadowBinder.setCallingUid(1_000)
        var recordings = 0
        val hooker = ProviderHook.RecordingHooker({}) { _, _, _, _ -> recordings++ }
        val result = Any()
        val chain = TestHookChain(listOf(uri)) {
            ShadowBinder.setCallingUid(12_346)
            result
        }

        assertSame(result, hooker.intercept(chain))
        assertEquals(0, recordings)
        assertEquals(0, chain.argumentReads)
        assertEquals(1, chain.originalCalls)
    }

    @Test
    fun givenRecordingAndLoggingThrow_whenIntercepting_thenTheProviderOutcomeSurvives() {
        ShadowProcess.setUid(12_345)
        ShadowBinder.setCallingUid(12_346)
        val hooker = ProviderHook.RecordingHooker({ error("log failed") }) { _, _, _, _ -> throw AssertionError("recording failed") }
        val result = Any()
        val chain = TestHookChain(listOf(uri)) { result }

        assertSame(result, hooker.intercept(chain))
        assertEquals(1, chain.originalCalls)
        val failure = IllegalStateException("from original")
        val throwing = TestHookChain(listOf(uri)) { throw failure }
        assertSame(failure, assertFailsWith<IllegalStateException> { hooker.intercept(throwing) })
        assertEquals(1, throwing.originalCalls)
    }
}
