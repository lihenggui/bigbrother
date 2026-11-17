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

/** Why records can't be shown. */
sealed interface LoadFailure {
    /** The app has not been granted root. */
    data object NoRoot : LoadFailure

    /** The root module, and with it the daemon, is not installed. */
    data object ModuleMissing : LoadFailure

    /** The installed daemon predates what this screen asks of it. */
    data object ModuleOutdated : LoadFailure

    data class Other(val message: String) : LoadFailure
}
