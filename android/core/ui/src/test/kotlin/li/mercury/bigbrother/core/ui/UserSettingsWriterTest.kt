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

package li.mercury.bigbrother.core.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class UserSettingsWriterTest {
    @Test
    fun givenFailedWrite_whenRetried_thenRetriesSameWriteAndClearsMessage() = runTest {
        var attempts = 0
        val warnings = mutableListOf<String>()
        val writer = UserSettingsWriter(this) { warnings += it }
        writer.save(UserSettingsWriter.Setting.TIME_RANGE) {
            if (++attempts == 1) throw IOException("disk unavailable")
        }
        runCurrent()

        writer.resolve(assertNotNull(writer.message.first()), retry = true)
        runCurrent()

        assertEquals(2, attempts)
        assertEquals(1, warnings.size)
        assertNull(writer.message.first())
    }

    @Test
    fun givenWriteInFlight_whenNewChoiceIsSaved_thenSerializesTheWrites() = runTest {
        val allowFirstWrite = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        val writer = UserSettingsWriter(this) { error(it) }
        writer.save(UserSettingsWriter.Setting.TIME_RANGE) {
            allowFirstWrite.await()
            writes += 1
        }
        writer.save(UserSettingsWriter.Setting.TIME_RANGE) { writes += 2 }
        runCurrent()
        assertEquals(emptyList(), writes)

        allowFirstWrite.complete(Unit)
        runCurrent()

        assertEquals(listOf(1, 2), writes)
        assertNull(writer.message.first())
    }

    @Test
    fun givenReplacedFailure_whenOldMessageIsRetried_thenNewerChoiceStaysSaved() = runTest {
        val writer = UserSettingsWriter(this) {}
        var attempts = 0
        writer.save(UserSettingsWriter.Setting.SHOW_SYSTEM_APPS) {
            attempts++
            throw IOException("disk unavailable")
        }
        runCurrent()
        val old = assertNotNull(writer.message.first())
        writer.save(UserSettingsWriter.Setting.SHOW_SYSTEM_APPS) { attempts++ }

        writer.resolve(old, retry = true)
        runCurrent()

        assertEquals(2, attempts)
        assertNull(writer.message.first())
    }
}
