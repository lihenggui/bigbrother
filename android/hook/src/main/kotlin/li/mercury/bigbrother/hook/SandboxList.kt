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
import java.util.concurrent.atomic.AtomicLong

/**
 * The sandboxed uids and their Android IDs, as last written by the daemon
 * (format: [parseSandboxList]).
 *
 * The daemon replaces the file by rename whenever an app is sandboxed or
 * released, so a change takes effect without a reboot. The callers are
 * binder threads on hot paths (every package visibility check goes through
 * here), so the file is looked at no more than once per [checkIntervalNanos],
 * by whichever thread first finds the interval over, and read again only when
 * its modification time or size has changed. Everyone else reads the last
 * snapshot without waiting.
 *
 * A missing file means nothing is sandboxed. A file that exists but cannot be
 * read keeps the last snapshot, reports through [onError], and is tried again
 * at the next check.
 */
internal class SandboxList(
    private val file: File,
    private val onError: (Throwable) -> Unit,
    private val checkIntervalNanos: Long = CHECK_INTERVAL_NANOS,
    private val clock: () -> Long = System::nanoTime,
    private val owners: HookInstallations? = null,
) {
    @Volatile
    private var entries: Map<Int, SandboxedUid> = emptyMap()

    private var stamp: java.nio.file.attribute.BasicFileAttributes? = null

    var revision: String = "none"
        private set

    var onLoaded: (() -> Unit)? = null

    /** Whether the last look at the file went well; a missing file counts. */
    @Volatile
    var readable: Boolean = true
        private set

    private val nextCheck: AtomicLong

    init {
        reload()
        nextCheck = AtomicLong(clock() + checkIntervalNanos)
    }

    /** The Android ID configured for [uid], or null if it keeps the real one. */
    fun androidIdOf(uid: Int): String? = entryOf(uid)?.takeIf { it.ownAndroidId }?.androidId

    /** Whether [uid] is to get no advertising ID: it is, with its own Android ID. */
    fun withholdsAdIdFrom(uid: Int): Boolean = entryOf(uid)?.ownAndroidId == true

    /** Whether [uid] is to see no installed app but itself and the system's. */
    fun hidesAppsFrom(uid: Int): Boolean = entryOf(uid)?.hidesApps == true

    /** Whether [uid] is held to the file grants of the moment. */
    fun isolatesStorageOf(uid: Int): Boolean = entryOf(uid)?.isolatesStorage == true

    private fun entryOf(uid: Int): SandboxedUid? {
        refresh()
        if (uid !in entries) return null
        return synchronized(this) { entries[uid]?.takeIf { owners?.matches(uid) != false } }
    }

    private fun refresh() {
        val due = nextCheck.get()
        val now = clock()
        // Compare by difference: nanoTime may be negative or wrap.
        if (now - due < 0) return
        if (!nextCheck.compareAndSet(due, now + checkIntervalNanos)) return
        reload(force = false)
    }

    @Synchronized
    fun reload(force: Boolean = true) {
        val previous = revision
        val wasReadable = readable
        try {
            val text = try {
                val attrs = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
                val last = stamp
                if (!force && last != null && attrs.fileKey() != null && attrs.fileKey() == last.fileKey() && attrs.lastModifiedTime() == last.lastModifiedTime() && attrs.size() == last.size()) return
                file.readText().also { stamp = attrs }
            } catch (_: java.nio.file.NoSuchFileException) {
                stamp = null
                ""
            }
            val nextRevision = text.lineSequence().firstOrNull()?.removePrefix("# ")?.takeIf { text.startsWith("# ") } ?: "none"
            val parsed = parseSandboxList(text)
            if (previous != nextRevision || !wasReadable || force) owners?.replace(text, parsed.keys)
            entries = parsed
            revision = nextRevision
            readable = true
        } catch (e: Exception) {
            stamp = null
            readable = false
            onError(e)
        }
        if (previous != revision || wasReadable != readable) onLoaded?.invoke()
    }

    private companion object {
        const val CHECK_INTERVAL_NANOS = 1_000_000_000L
    }
}
