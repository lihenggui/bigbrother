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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxListTest {
    private val directory = Files.createTempDirectory("sandbox-list").toFile()
    private val file = File(directory, "big_brother_sandbox")
    private var now = 0L
    private val errors = mutableListOf<Throwable>()

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun list() = SandboxList(file, { errors += it }, checkIntervalNanos = SECOND, clock = { now })

    /** Replaces the file the way the daemon does, with a modification time that differs. */
    private fun write(text: String, modified: Long) {
        val temporary = File(directory, "big_brother_sandbox.tmp")
        temporary.writeText(text)
        temporary.setLastModified(modified)
        assertTrue(temporary.renameTo(file))
    }

    @Test
    fun givenNoFile_whenAsked_thenNobodySandboxed() {
        val list = list()
        assertFalse(list.hidesAppsFrom(10_205))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun givenFile_whenAsked_thenItsIdReturned() {
        write("10205 0123456789abcdef\n", modified = 1_000_000)
        assertEquals("0123456789abcdef", list().androidIdOf(10_205))
    }

    @Test
    fun givenChangedFile_whenAskedWithinASecond_thenOldSnapshotKept() {
        write("10205 0123456789abcdef\n", modified = 1_000_000)
        val list = list()
        write("10206 0123456789abcdef\n", modified = 2_000_000)
        now += SECOND / 2
        assertTrue(list.hidesAppsFrom(10_205))
        assertFalse(list.hidesAppsFrom(10_206))
    }

    @Test
    fun givenChangedFile_whenAskedAfterASecond_thenReread() {
        write("10205 0123456789abcdef\n", modified = 1_000_000)
        val list = list()
        write("10206 0123456789abcdef\n", modified = 2_000_000)
        now += SECOND
        assertFalse(list.hidesAppsFrom(10_205))
        assertTrue(list.hidesAppsFrom(10_206))
    }

    @Test
    fun givenRemovedFile_whenAskedAfterASecond_thenNobodySandboxed() {
        write("10205 0123456789abcdef\n", modified = 1_000_000)
        val list = list()
        assertTrue(file.delete())
        now += SECOND
        assertNull(list.androidIdOf(10_205))
    }

    @Test
    fun givenUnreadableFile_whenLoaded_thenErrorReportedAndNobodySandboxed() {
        // A directory in its place exists but cannot be read as text.
        assertTrue(file.mkdir())
        val list = list()
        assertFalse(list.hidesAppsFrom(10_205))
        assertEquals(1, errors.size)
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
