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

package li.mercury.bigbrother.core.data.root

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellQuotingTest {
    /** Runs `printf %s <quoted>` in a real shell and returns what the shell passed on. */
    private fun throughShell(value: String): String {
        val process = ProcessBuilder("sh", "-c", "printf %s ${value.shellQuoted()}").start()
        return process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }

    @Test
    fun givenPlainPath_whenQuoted_thenOnlyWrappedInQuotes() {
        assertEquals("'/storage/emulated/0/DCIM/a.jpg'", "/storage/emulated/0/DCIM/a.jpg".shellQuoted())
    }

    @Test
    fun givenShellSyntaxInFileName_whenPassedThroughShell_thenArrivesUnchanged() {
        for (name in listOf(
            "/sdcard/it's here.txt",
            "/sdcard/\$(reboot).txt",
            "/sdcard/`id`.txt",
            "/sdcard/a; rm -rf b",
            "/sdcard/a\"b'c\\d",
            "/sdcard/two\nlines",
            "/sdcard/''",
        )) {
            assertEquals(name, throughShell(name))
        }
    }
}
