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

import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.PackageDetails

/** Installed apps by package name; any other package is not installed. */
class TestAppInfoRepository(var apps: Map<String, AppInfo> = emptyMap()) : AppInfoRepository {
    override suspend fun getAppInfo(packageName: String): AppInfo? = apps[packageName]

    override suspend fun getAppInfo(packageNames: Collection<String>): Map<String, AppInfo> = apps.filterKeys { it in packageNames }

    override suspend fun getPackageDetails(packageName: String): PackageDetails? = null
}
