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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.ModulePaths.BBD
import org.json.JSONException

internal const val BBD_MISSING_MARKER = "bbd-not-installed"

internal fun CommandResult.bbdFailure(): RecordsException? {
    if (isSuccess) return null
    if (error.lineSequence().any { it == BBD_MISSING_MARKER } || output.lineSequence().any { it == BBD_MISSING_MARKER }) {
        return RecordsException.ModuleMissing()
    }

    val reason = error.removePrefix("Error: ")
    // Clap can quote the rejected value in its diagnostics, so look at the diagnostic's form.
    val headline = reason.substringBefore('\n')
    val cliRejectedRequest = headline.startsWith("error: unrecognized subcommand ") ||
        headline.startsWith("error: unexpected argument ") ||
        (headline.startsWith("error: the argument ") && headline.endsWith(" cannot be used multiple times"))
    if (cliRejectedRequest || headline == "unknown command") return RecordsException.ModuleOutdated()
    return RecordsException.CommandFailed(reason.ifBlank { "bbd exited with $code" })
}

/**
 * Runs `bbd <command> <args> --json` as root and returns what it printed.
 * Everything in [args] that comes from an app or the user must be quoted
 * already. Only [command] and the first word of [args] are logged.
 *
 * @throws RecordsException.ModuleMissing if the daemon is not installed.
 * @throws RecordsException.ModuleOutdated if it does not know the command or an option.
 * @throws RecordsException.CommandFailed with the daemon's reason otherwise.
 */
internal suspend fun RootShell.bbd(command: String, args: String): String {
    val result = run(
        "if [ -x $BBD ]; then $BBD $command $args --json; else echo $BBD_MISSING_MARKER >&2; false; fi",
        label = "bbd $command ${args.substringBefore(' ')}",
    )
    result.bbdFailure()?.let { throw it }
    return result.output
}

/** Decode and map the daemon's response on the CPU dispatcher, even when called from the UI. */
internal suspend fun <T> CoroutineDispatcher.parseBbdOutput(block: suspend () -> T): T = withContext(this) {
    try {
        block()
    } catch (e: JSONException) {
        throw RecordsException.CommandFailed("unexpected output from bbd: ${e.message}")
    }
}
