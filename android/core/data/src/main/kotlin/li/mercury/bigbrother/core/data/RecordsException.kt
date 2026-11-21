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

package li.mercury.bigbrother.core.data

import li.mercury.bigbrother.core.model.LoadFailure

/** Why a repository call could not be completed. */
sealed class RecordsException(internal val failure: LoadFailure) : Exception(failure.toString()) {
    /** The app has not been granted root. */
    class NoRoot : RecordsException(LoadFailure.NoRoot)

    /** The root module, and with it the daemon binary, is not installed. */
    class ModuleMissing : RecordsException(LoadFailure.ModuleMissing)

    /** The installed daemon does not know a command or option this app uses. */
    class ModuleOutdated : RecordsException(LoadFailure.ModuleOutdated)

    class CommandFailed(reason: LoadFailure.Operation = LoadFailure.Operation.COMMAND_FAILED) : RecordsException(reason)

    /** The daemon accepted the request but its response could not be decoded. */
    class BadResponse : RecordsException(LoadFailure.Operation.BAD_RESPONSE)
}

/** The user-facing reason behind an exception from a repository. */
fun RecordsException.toLoadFailure(): LoadFailure = failure
