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
import java.nio.file.NoSuchFileException

/** Binder threads read only this snapshot; the reload worker owns all installation checks. */
internal class SandboxList(
    private val file: File,
    private val onError: () -> Unit,
    private val verifyOwners: (String, Set<Int>) -> Unit,
) {
    data class Snapshot(
        val entries: Map<Int, SandboxedUid> = emptyMap(),
        val revision: String = "none",
        val readable: Boolean = false,
    )

    @Volatile
    var snapshot = Snapshot()
        private set

    fun androidIdOf(uid: Int): String? = snapshot.entries[uid]?.takeIf { it.ownAndroidId }?.androidId
    fun withholdsAdIdFrom(uid: Int): Boolean = snapshot.entries[uid]?.ownAndroidId == true
    fun hidesAppsFrom(uid: Int): Boolean = snapshot.entries[uid]?.hidesApps == true
    fun isolatesStorageOf(uid: Int): Boolean = snapshot.entries[uid]?.isolatesStorage == true

    /** Runs on the worker so a slow read never interrupts enforcement. */
    fun reload() {
        snapshot = try {
            val text = try {
                Files.readAllBytes(file.toPath()).decodeToString()
            } catch (_: NoSuchFileException) {
                null
            }
            val revision = text?.lineSequence()?.firstOrNull()?.takeIf { it.startsWith("# ") }?.removePrefix("# ") ?: "none"
            val entries = parseSandboxList(text.orEmpty())
            // The daemon removes the file when there is no policy to apply.
            check(text == null || entries.isNotEmpty())
            verifyOwners(text.orEmpty(), entries.keys)
            Snapshot(entries, revision, readable = true)
        } catch (_: Throwable) {
            onError()
            snapshot.copy(readable = false)
        }
    }
}
