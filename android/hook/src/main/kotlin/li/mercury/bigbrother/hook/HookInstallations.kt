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

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

/** Keep the directory inode alive across uninstall so a reused UID and inode cannot inherit policy. */
internal class HookInstallations(private val bootId: File) {
    private var owners: Map<Int, FileDescriptor> = emptyMap()

    fun replace(text: String, uids: Set<Int>) {
        val next = mutableMapOf<Int, FileDescriptor>()
        try {
            if (uids.isNotEmpty()) {
                val boot = bootId.readText().trim()
                check(boot.isNotEmpty() && text.lineSequence().any { it == "boot $boot" })
            }
            for (line in text.lineSequence().filter { it.startsWith("owner ") }) {
                val fields = line.split(' ')
                check(fields.size == 5)
                val uid = fields[1].toInt()
                val pkg = fields[2]
                check(uid in uids && uid !in next && pkg.matches(PACKAGE))
                val path = "/data/user_de/${uid / PER_USER_RANGE}/$pkg"
                val fd = Os.open(path, O_PATH or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
                try {
                    val stat = Os.fstat(fd)
                    check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == uid && stat.st_dev == fields[3].toLong() && stat.st_ino == fields[4].toLong())
                    next[uid] = fd
                } catch (e: Throwable) {
                    closeInstallationDescriptors(listOf(fd), Os::close)
                    throw e
                }
            }
            check(next.keys == uids)
        } catch (e: Throwable) {
            closeInstallationDescriptors(next.values, Os::close)
            throw e
        }
        val previous = owners
        owners = next
        closeInstallationDescriptors(previous.values, Os::close)
    }

    private companion object {
        // Linux O_PATH opens the directory for metadata without reading app files.
        const val O_PATH = 0x200000
        val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    }
}

internal fun closeInstallationDescriptors(descriptors: Iterable<FileDescriptor>, close: (FileDescriptor) -> Unit) {
    for (fd in descriptors) {
        try {
            close(fd)
        } catch (_: Throwable) {
            // A failing close must leave the other descriptors reachable for cleanup.
        }
    }
}
