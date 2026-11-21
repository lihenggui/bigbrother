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
import li.mercury.bigbrother.core.data.repository.InstalledAppRepository
import li.mercury.bigbrother.core.model.InstalledApp

/** Installed apps, and the permissions each asks for, by package name; a package left out is not installed. */
class TestInstalledAppRepository(
    var apps: List<InstalledApp> = emptyList(),
    var permissions: Map<String, Set<String>> = emptyMap(),
) : InstalledAppRepository {
    var failure: RecordsException? = null

    override suspend fun getInstalledApps(): List<InstalledApp> {
        failure?.let { throw it }
        return apps
    }

    override suspend fun getRequestedPermissions(packageName: String): Set<String>? = permissions[packageName]
}
