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
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a root command. */
class CommandResult(val isSuccess: Boolean, val code: Int, val output: String, val error: String)

/** Runs commands in a root shell. The single place the app touches root. */
@Singleton
class RootShell @Inject constructor(
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * Runs [command], logging how long it took under [label]. The label is
     * all that is logged of it: commands carry paths and search text.
     *
     * @throws RecordsException.NoRoot if the app has not been granted root.
     */
    suspend fun run(command: String, label: String = command.substringBefore(' ')): CommandResult = withContext(ioDispatcher) {
        // A job only captures output into lists it is given.
        val output = ArrayList<String>()
        val error = ArrayList<String>()
        val start = SystemClock.elapsedRealtime()
        val shell = rootShell()
        val opened = SystemClock.elapsedRealtime()
        val result = shell.newJob().add(command).to(output, error).exec()
        val elapsed = SystemClock.elapsedRealtime() - opened
        // Opening the shell is slow once, then free.
        val opening = if (opened - start > 0) " after ${opened - start} ms to open the shell" else ""
        Timber.d("%s: exit %d in %d ms, %d lines%s", label, result.code, elapsed, output.size, opening)
        CommandResult(
            isSuccess = result.isSuccess,
            code = result.code,
            output = output.joinToString("\n"),
            error = error.joinToString("\n"),
        )
    }

    /**
     * A shell with root, or [RecordsException.NoRoot].
     *
     * libsu caches the shell it opens. If root was denied when that happened,
     * the cached shell stays unprivileged even after the user grants root, so
     * an unprivileged shell is closed and a new one requested each time; the
     * new one picks up a grant made in the meantime.
     */
    private fun rootShell(): Shell {
        val shell = try {
            val cached = Shell.getShell()
            if (cached.isRoot) return cached
            cached.close()
            Shell.getShell()
        } catch (_: Exception) {
            // libsu could not start a shell at all.
            throw RecordsException.NoRoot()
        }
        if (!shell.isRoot) throw RecordsException.NoRoot()
        return shell
    }
}

/**
 * This string as a single shell word. Inside single quotes the shell
 * interprets nothing, so the only character needing care is the single quote
 * itself, which is written by closing the quotes, escaping it, and reopening.
 */
internal fun String.shellQuoted(): String = "'" + replace("'", "'\\''") + "'"

/**
 * Where the module's files live once it is installed. KernelSU, Magisk and
 * APatch use the same layout under /data/adb.
 */
internal object ModulePaths {
    const val MODULE_DIR = "/data/adb/modules/big_brother"
    const val BBD = "$MODULE_DIR/bin/bbd"
    const val MODULE_PROP = "$MODULE_DIR/module.prop"

    /** Where a module update is staged until the next boot. */
    const val PENDING_UPDATE_DIR = "/data/adb/modules_update/big_brother"
}
