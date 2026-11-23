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

import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.InstalledModule
import li.mercury.bigbrother.core.data.repository.ModuleRepository

/** A module whose answers a test sets, counting what it was asked to do. */
class TestModuleRepository(
    var installed: InstalledModule = InstalledModule.CURRENT,
    var active: Boolean = true,
    var updatePending: Boolean = false,
    var installedByApp: Boolean = false,
) : ModuleRepository {
    var comparisonFailure: RecordsException? = null
    var installFailure: RecordsException? = null
    var removeFailure: RecordsException? = null
    var startFailure: RecordsException? = null
    var updateFailure: RecordsException? = null
    var versionFailure: RecordsException? = null

    var comparisons = 0
        private set
    var updates = 0
        private set

    override suspend fun isActive(): Boolean = active

    override suspend fun compareWithBundled(): InstalledModule {
        comparisons++
        comparisonFailure?.let { throw it }
        return installed
    }

    override suspend fun updateInPlace() {
        updates++
        updateFailure?.let { throw it }
        installed = InstalledModule.CURRENT
    }

    override suspend fun installedVersion(): String {
        versionFailure?.let { throw it }
        return "v0.1.6"
    }

    override suspend fun isUpdatePending(): Boolean = updatePending

    override suspend fun installBundled() {
        installFailure?.let { throw it }
        active = true
        installedByApp = true
    }

    override suspend fun isInstalledByApp(): Boolean = installedByApp

    override suspend fun remove() {
        removeFailure?.let { throw it }
        active = false
        installedByApp = false
    }

    override suspend fun startDaemon() {
        startFailure?.let { throw it }
    }
}
