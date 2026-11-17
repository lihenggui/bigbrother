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

package li.mercury.bigbrother.core.testing.repository

import li.mercury.bigbrother.core.data.repository.InstalledModule
import li.mercury.bigbrother.core.data.repository.ModuleRepository

/** A module whose answers a test sets, counting what it was asked to do. */
class TestModuleRepository(
    var installed: InstalledModule = InstalledModule.CURRENT,
    var active: Boolean = true,
    var updatePending: Boolean = false,
) : ModuleRepository {
    var comparisons = 0
        private set
    var updates = 0
        private set

    override suspend fun isActive(): Boolean = active

    override suspend fun compareWithBundled(): InstalledModule {
        comparisons++
        return installed
    }

    override suspend fun updateInPlace(): Boolean {
        updates++
        installed = InstalledModule.CURRENT
        return true
    }

    override suspend fun installedVersion(): String? = "v0.1.6"

    override suspend fun isUpdatePending(): Boolean = updatePending

    override suspend fun installBundled(): String = ""

    override suspend fun startDaemon(): Boolean = true
}
