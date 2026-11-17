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

/**
 * Keeps the hook from being part of a crash loop.
 *
 * The hooked processes are system components; the media provider also serves
 * shared storage. If such a process starts over and over in a short time,
 * something is crashing it, and whether or not the hook is to blame, the
 * safe response is to stop hooking until things calm down.
 *
 * Each start is recorded in a small file in the process's data directory.
 */
internal class StartGuard(private val directory: File) {

    /**
     * Returns whether hooks may be installed in this process, and if so
     * records the start. A start that is refused is not recorded: otherwise
     * the refusals themselves would keep the window full, and hooking would
     * never resume.
     */
    fun allowStart(): Boolean = try {
        directory.mkdirs()
        val file = File(directory, FILE_NAME)
        val now = System.currentTimeMillis()
        val recent = (if (file.exists()) file.readLines() else emptyList())
            .mapNotNull { it.toLongOrNull() }
            // Timestamps from the future mean the clock changed; ignore them.
            .filter { it in (now - WINDOW_MS)..now }
        val allowed = recent.size < MAX_STARTS
        if (allowed) file.writeText((recent + now).joinToString("\n"))
        allowed
    } catch (_: Exception) {
        // If the guard itself can't work, err on the side of not hooking.
        false
    }

    private companion object {
        const val FILE_NAME = "starts"
        const val WINDOW_MS = 5 * 60 * 1000L

        /** Earlier starts allowed within the window before hooking is suspended. */
        const val MAX_STARTS = 3
    }
}
