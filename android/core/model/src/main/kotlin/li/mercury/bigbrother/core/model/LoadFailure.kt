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

package li.mercury.bigbrother.core.model

/** Why a load or action could not be completed. */
sealed interface LoadFailure {
    /** The app has not been granted root. */
    data object NoRoot : LoadFailure

    /** The root module, and with it the daemon, is not installed. */
    data object ModuleMissing : LoadFailure

    /** The installed daemon predates what this screen asks of it. */
    data object ModuleOutdated : LoadFailure

    enum class Operation : LoadFailure {
        COMMAND_FAILED,
        BAD_RESPONSE,
        DATABASE_BUSY,
        DAEMON_UNAVAILABLE,
        DESTINATION_MISSING,
        APP_MISSING,
        TIMED_OUT,
        INVALID_PERIOD,
        MODULE_DISABLED,
        MODULE_REMOVE_FAILED,
        MODULE_VERSION_UNREADABLE,
        UNSUPPORTED_ARCHITECTURE,
        KERNEL_BTF_MISSING,
        KERNEL_TOO_OLD,
        MODULE_ARCHIVE_INVALID,
        DATABASE_INCOMPATIBLE,
        MODULE_INSTALL_FAILED,
        MODULE_UPDATE_FAILED,
        DAEMON_START_FAILED,
        DAEMON_NOT_REPORTING,
        FILE_ACCESS_DENIED,
        INSTALLED_APPS_UNAVAILABLE,
        LOG_EXPORT_FAILED,
    }
}
