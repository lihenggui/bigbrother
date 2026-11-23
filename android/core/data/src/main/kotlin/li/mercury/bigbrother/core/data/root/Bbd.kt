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

import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.ModulePaths.BBD
import li.mercury.bigbrother.core.data.root.ModulePaths.LOCATE
import li.mercury.bigbrother.core.model.LoadFailure

private const val BBD_MISSING_MARKER = "bbd-not-installed"

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
    return daemonCommandFailure(error)
}

/** Recognizes daemon errors without retaining output that can include paths or package names. */
internal fun daemonCommandFailure(error: String): RecordsException.CommandFailed {
    val headline = error.removePrefix("Error: ").lineSequence().firstOrNull().orEmpty()
    val reason = when {
        headline == "the daemon is not running" -> LoadFailure.Operation.DAEMON_UNAVAILABLE
        headline == "database is locked" -> LoadFailure.Operation.DATABASE_BUSY
        headline.startsWith("unsupported database schema ") -> LoadFailure.Operation.DATABASE_INCOMPATIBLE
        headline.endsWith(" is not installed") -> LoadFailure.Operation.APP_MISSING
        headline == "no such destination" -> LoadFailure.Operation.DESTINATION_MISSING
        headline == "timed out" -> LoadFailure.Operation.TIMED_OUT
        else -> LoadFailure.Operation.COMMAND_FAILED
    }
    return RecordsException.CommandFailed(reason)
}

/**
 * Runs a daemon command and returns what it printed. Arguments from apps or
 * users must already be quoted. [args] starts with a space when present.
 * With [json], the first argument is a static subcommand that can be logged.
 */
internal suspend fun RootShell.bbd(command: String, args: String = "", json: Boolean = false): String {
    val format = if (json) " --json" else ""
    val label = if (json) "bbd $command ${args.trimStart().substringBefore(' ')}" else "bbd $command"
    val result = run(
        "$LOCATE; if [ -x $BBD ]; then $BBD $command$args$format; else echo $BBD_MISSING_MARKER >&2; false; fi",
        label = label,
    )
    result.bbdFailure()?.let { throw it }
    return result.output
}

/** Keep combined output in the archive even when the command exits with an error. */
internal suspend fun RootShell.bbdStatsForDiagnostics(): String = run("$LOCATE; $BBD stats 2>&1", label = "bbd stats").output
