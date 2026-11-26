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
import java.io.RandomAccessFile

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
internal class StartGuard(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * Returns whether hooks may be installed in this process, and if so
     * records the start. A start that is refused is not recorded: otherwise
     * the refusals themselves would keep the window full, and hooking would
     * never resume.
     */
    fun allowStart(): Boolean = synchronized(processLock) {
        allowStartLocked()
    }

    private fun allowStartLocked(): Boolean = try {
        directory.mkdirs()
        // Lock a separate file so the lock survives replacing the record.
        RandomAccessFile(File(directory, LOCK_FILE), "rw").use { file ->
            repeat(LOCK_ATTEMPTS) { attempt ->
                file.channel.tryExclusiveLock()?.use { return recordStart() }
                if (attempt < LOCK_ATTEMPTS - 1) Thread.sleep(LOCK_RETRY_MS)
            }
            false
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (_: Exception) {
        // If the guard itself can't work, err on the side of not hooking.
        false
    }

    private fun recordStart(): Boolean {
        val file = File(directory, FILE_NAME)
        val now = nowMillis()
        val recent = if (file.exists()) {
            file.useLines { lines ->
                lines.mapNotNull { it.toLongOrNull() }
                    // A backward clock correction must not leave the guard stuck.
                    .filter { it in 0..now && now - it <= WINDOW_MS }
                    .take(MAX_STARTS)
                    .toList()
            }
        } else {
            emptyList()
        }
        if (recent.size == MAX_STARTS) return false
        val temporary = File.createTempFile(FILE_NAME, ".tmp", directory)
        return try {
            temporary.writeText((recent + now).joinToString("\n"))
            temporary.renameTo(file)
        } finally {
            // After a failed rename the previous record still stands.
            temporary.delete()
        }
    }

    private companion object {
        val processLock = Any()
        const val FILE_NAME = "starts"
        const val LOCK_FILE = "starts.lock"
        const val LOCK_ATTEMPTS = 3
        const val LOCK_RETRY_MS = 25L
        const val WINDOW_MS = 5 * 60 * 1000L

        /** Earlier starts allowed within the window before hooking is suspended. */
        const val MAX_STARTS = 3
    }
}
