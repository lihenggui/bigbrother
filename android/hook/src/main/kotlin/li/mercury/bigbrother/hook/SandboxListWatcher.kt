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

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps notification failures separate from the policy the Binder hooks serve. */
internal class SandboxListWatcher(
    private val file: Path,
    private val list: SandboxList,
    private val worker: Executor,
    private val onReloaded: (SandboxList.Snapshot) -> Unit,
    private val onWatchLost: () -> Unit,
) {
    private val reloadQueued = AtomicBoolean(false)
    private val listName = file.fileName.toString()

    @Volatile
    private var watching: WatchService? = null

    @Volatile
    private var loadedWith: WatchService? = null

    val current: Boolean
        get() {
            val watch = watching
            return watch != null && watch === loadedWith
        }

    fun requestReload() {
        if (!reloadQueued.compareAndSet(false, true)) return
        worker.execute {
            // An event during validation needs another pass even if this read succeeds.
            reloadQueued.set(false)
            val watch = watching
            val previous = list.snapshot
            list.reload()
            loadedWith = watch
            onReloaded(previous)
        }
    }

    /** Only waits on a timer while notifications are unavailable. */
    fun watch(openWatch: () -> WatchService = { FileSystems.getDefault().newWatchService() }) {
        var delayMs = INITIAL_RETRY_MS
        while (!Thread.currentThread().isInterrupted) {
            try {
                openWatch().use { watch ->
                    file.parent.register(watch, ENTRY_CREATE, ENTRY_DELETE)
                    watching = watch
                    // Changes during the gap have no events, so read again after registering.
                    requestReload()
                    while (true) {
                        val key = watch.take()
                        val reload = key.pollEvents().any { event ->
                            val name = event.context()?.toString()
                            event.kind() == OVERFLOW || name == listName || name == PACKAGE_LIST
                        }
                        if (!key.reset()) break
                        if (reload) {
                            requestReload()
                            delayMs = INITIAL_RETRY_MS
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                // A failed registration needs the same recovery as a lost watch.
            } finally {
                watching = null
                worker.execute(onWatchLost)
            }

            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return
            }
            delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    private companion object {
        const val PACKAGE_LIST = "packages.list"
        const val INITIAL_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
    }
}

internal fun HookState.withListReady(ready: Boolean): HookState = if (!ready && this == HookState.WORKING) HookState.DEGRADED else this
