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

import android.database.Cursor
import android.database.MatrixCursor
import io.github.libxposed.api.XposedInterface
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class SandboxHookTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun givenTypedColumnsInAnAndroidIdRow_whenCopied_thenOnlyTheIdChangesAndAllTypesArePreserved() {
        val blob = byteArrayOf(0x00, 0x42, 0x7f)
        val largeInteger = Int.MAX_VALUE.toLong() + 1
        val columns = arrayOf("name", "value", "missing", "count", "ratio", "label", "bytes")
        val original = MatrixCursor(columns).apply {
            addRow(arrayOf("android_id", "original", null, largeInteger, 1.25, "sample", blob))
            addRow(arrayOf("another_setting", "preserved", null, 1L, 2.5, "sample", blob))
        }
        val list = SandboxList(File(temporary.root, "sandbox"), {}, { _, _ -> })
        val hooker = SandboxHook.AndroidIdQueryHooker(SandboxHook.Health {}, list)

        hooker.withAndroidId(original, AndroidIdQuery.TABLE, "replacement").use { copy ->
            assertContentEquals(columns, copy.columnNames)
            assertTrue(copy.moveToFirst())
            assertEquals("replacement", copy.getString(1))
            assertEquals(Cursor.FIELD_TYPE_NULL, copy.getType(2))
            assertTrue(copy.isNull(2))
            assertEquals(Cursor.FIELD_TYPE_INTEGER, copy.getType(3))
            assertEquals(largeInteger, copy.getLong(3))
            assertEquals(Cursor.FIELD_TYPE_FLOAT, copy.getType(4))
            assertEquals(1.25, copy.getDouble(4))
            assertEquals(Cursor.FIELD_TYPE_STRING, copy.getType(5))
            assertEquals("sample", copy.getString(5))
            assertEquals(Cursor.FIELD_TYPE_BLOB, copy.getType(6))
            assertContentEquals(blob, copy.getBlob(6))
            assertTrue(copy.moveToNext())
            assertEquals("preserved", copy.getString(1))
            assertFalse(original.isClosed)
        }
        assertTrue(original.isClosed)
    }

    @Test
    fun givenAThrowingReplacementAndDiagnostic_whenIntercepting_thenOriginalRunsAndTheHookStopsReplacing() {
        var replacements = 0
        val health = SandboxHook.Health { error("diagnostic failed") }
        val hooker = object : SandboxHook.FailSafeHooker(health, "test") {
            override fun replacement(chain: XposedInterface.Chain): Any? {
                replacements++
                throw AssertionError("replacement failed")
            }
        }
        val original = Any()
        val chain = TestHookChain { original }

        assertSame(original, hooker.intercept(chain))
        assertSame(original, hooker.intercept(chain))
        assertEquals(2, chain.originalCalls)
        assertEquals(1, replacements)
        assertEquals(HookState.DEGRADED, health.state)
    }

    @Test
    fun givenAnOriginalMethodException_whenFallingThrough_thenItsExceptionPassesThroughOnce() {
        val health = SandboxHook.Health {}
        val hooker = object : SandboxHook.FailSafeHooker(health, "test") {
            override fun replacement(chain: XposedInterface.Chain): Any? = null
        }
        val failure = IllegalArgumentException("from original")
        val chain = TestHookChain { throw failure }

        assertSame(failure, assertFailsWith<IllegalArgumentException> { hooker.intercept(chain) })
        assertEquals(1, chain.originalCalls)
        assertEquals(HookState.WORKING, health.state)
    }

    @Test
    fun givenAWorkingHook_whenDegradedTwice_thenOnlyItsFirstTransitionIsReported() {
        val messages = mutableListOf<String>()
        val health = SandboxHook.Health { messages += it }
        assertEquals(HookState.WORKING, health.state)
        assertTrue(health.active)

        health.degrade("first")
        health.degrade("second")

        assertEquals(HookState.DEGRADED, health.state)
        assertFalse(health.active)
        assertEquals(listOf("first"), messages)
    }

    @Test
    fun givenAnIncompatibleHook_whenDegradationIsReported_thenIncompatibleStatusIsKept() {
        val messages = mutableListOf<String>()
        val health = SandboxHook.Health { messages += it }
        health.markIncompatible()

        health.degrade("failed")

        assertEquals(HookState.INCOMPATIBLE, health.state)
        assertFalse(health.active)
        assertTrue(messages.isEmpty())
    }
}
