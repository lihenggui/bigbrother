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

import android.os.SystemClock
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.model.LoadFailure
import timber.log.Timber
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a root command. */
class CommandResult(val isSuccess: Boolean, val code: Int, val output: String, val error: String)

/**
 * Runs commands in a root shell. The single place the app touches root;
 * open so data source tests can answer in its place.
 */
@Singleton
open class RootShell @Inject constructor(
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) {
    private val shellExecutor = ioDispatcher.asExecutor()

    /**
     * Runs [command], logging how long it took under [label]. The label is
     * all that is logged of it: commands carry paths and search text.
     *
     * @throws RecordsException.NoRoot if the app has not been granted root.
     * @throws RecordsException.CommandFailed if the wait for the command times out.
     */
    open suspend fun run(command: String, label: String = "root command"): CommandResult = withContext(ioDispatcher) {
        val start = SystemClock.elapsedRealtime()
        val output = ShellOutput()
        val error = ShellOutput()
        val result = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { submit(command, output, error) }
        val elapsed = SystemClock.elapsedRealtime() - start
        if (result == null) {
            Timber.w("%s: timed out after %d ms", label, elapsed)
            throw RecordsException.CommandFailed(LoadFailure.Operation.TIMED_OUT)
        }
        Timber.d("%s: exit %d in %d ms, %d lines", label, result.code, elapsed, output.lineCount)
        CommandResult(result.isSuccess, result.code, output.text(), error.text())
    }

    private suspend fun submit(command: String, output: ShellOutput, error: ShellOutput): Shell.Result {
        shellSlot.acquire()
        val finished = CompletableDeferred<Shell.Result>()
        try {
            // Opening su may block while the root manager asks for a grant.
            // The caller can time out even if that prompt is still active.
            shellExecutor.execute { startCommand(command, output, error, finished) }
        } catch (e: Exception) {
            shellSlot.release()
            throw e
        }
        try {
            return finished.await()
        } finally {
            finished.cancel()
        }
    }

    private fun startCommand(command: String, output: ShellOutput, error: ShellOutput, finished: CompletableDeferred<Shell.Result>) {
        var submitted = false
        try {
            if (!finished.isActive) return
            val shell = rootShell()
            if (!finished.isActive) return
            shell.newJob().add(command).to(output, error).submit(null) { result ->
                shellSlot.release()
                finished.complete(result)
            }
            submitted = true
        } catch (e: Exception) {
            finished.completeExceptionally(e)
        } finally {
            // libsu 6 cannot abort a submitted job. Keep the slot occupied
            // until the callback so cancelled commands cannot build up.
            if (!submitted) shellSlot.release()
        }
    }

    /**
     * A shell with root, or [RecordsException.NoRoot]. The shell slot also
     * serializes opening and closing the cached shell across requests.
     *
     * libsu caches the shell it opens. If root was denied when that happened,
     * the cached shell stays unprivileged even after the user grants root, so
     * an unprivileged shell is closed and a new one requested each time; the
     * new one picks up a grant made in the meantime.
     */
    private fun rootShell(): Shell {
        val shell = try {
            val cached = Shell.getCachedShell()
            if (cached?.isRoot == true) return cached
            cached?.close()
            Shell.getShell()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // libsu could not start a shell at all.
            throw RecordsException.NoRoot()
        }
        if (!shell.isRoot) throw RecordsException.NoRoot()
        return shell
    }

    private class ShellOutput : CallbackList<String>(Executor { it.run() }) {
        private val buffer = StringBuilder()
        var lineCount = 0
            private set

        override fun onAddElement(line: String) {
            if (lineCount++ > 0) buffer.append('\n')
            buffer.append(line)
        }

        fun text(): String = buffer.toString()
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 60_000L
        val shellSlot = Semaphore(1)
    }
}

/**
 * This string as a single shell word. Inside single quotes the shell
 * interprets nothing, so the only character needing care is the single quote
 * itself, which is written by closing the quotes, escaping it, and reopening.
 */
internal fun String.shellQuoted(): String = "'" + replace("'", "'\\''") + "'"

/**
 * Where the module's files are: flashed in a root manager (KernelSU, Magisk
 * and APatch use the same layout under /data/adb), or else installed by this
 * app with root alone (module/direct.sh). A module can be flashed or removed
 * while the app runs, so the root shell works out which in each command:
 * one that names these paths starts with [LOCATE]. A manager keeps a
 * directory without the daemon for a module it applies at the next boot.
 */
internal object ModulePaths {
    const val DIRECT_DIR = "/data/adb/big_brother_direct"
    const val LOCATE = "bb=/data/adb/modules/big_brother; [ -e \$bb/bin/bbd ] || bb=$DIRECT_DIR"
    const val MODULE_DIR = "\$bb"
    const val BBD = "$MODULE_DIR/bin/bbd"
    const val MODULE_PROP = "$MODULE_DIR/module.prop"

    /** Where a module update is staged until the next boot. */
    const val PENDING_UPDATE_DIR = "/data/adb/modules_update/big_brother"
}
