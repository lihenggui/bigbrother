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

package li.mercury.bigbrother.core.domain

import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.TimeRange
import javax.inject.Inject

data class AppRecords(val apps: List<AppSummary>, val appInfo: Map<String, AppInfo>)

class GetAppRecordsUseCase @Inject constructor(
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
) {
    suspend operator fun invoke(range: TimeRange): AppRecords {
        val apps = accessRepository.getApps(range)
        val packageNames = apps.mapNotNullTo(mutableSetOf()) { it.packageName.ifEmpty { null } }
        return AppRecords(apps, appInfoRepository.getAppInfo(packageNames))
    }
}
