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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import li.mercury.bigbrother.core.common.di.AppPackageName
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.InstalledApp
import li.mercury.bigbrother.core.model.TimelineEntry
import li.mercury.bigbrother.core.model.UserData
import javax.inject.Inject

class GetAppVisibilityUseCase @Inject constructor(
    private val userDataRepository: UserDataRepository,
    @AppPackageName private val ownPackage: String,
) {
    val default = AppVisibility(showSystemApps = false, ownPackage = ownPackage)

    operator fun invoke(): Flow<AppVisibility> = userDataRepository.userData
        .map { it.showSystemApps }
        .distinctUntilChanged()
        .map { AppVisibility(it, ownPackage) }

    fun forSettings(userData: UserData): AppVisibility = AppVisibility(userData.showSystemApps, ownPackage)
}

data class AppVisibility(val showSystemApps: Boolean, private val ownPackage: String) {
    fun visibleApps(records: AppRecords): VisibleApps {
        var hiddenCount = 0
        val apps = records.apps.filter { app ->
            val shown = isShown(app.packageName, isHiddenByDefault(records.appInfo[app.packageName], app.isUnattributedIsolated))
            if (!shown && app.packageName != ownPackage) hiddenCount++
            shown
        }
        return VisibleApps(apps, hiddenCount)
    }

    fun visibleEntries(entries: List<TimelineEntry>, appInfo: Map<String, AppInfo>): List<TimelineEntry> = entries.filter { entry ->
        isShown(entry.packageName, isHiddenByDefault(appInfo[entry.packageName], entry.isUnattributedIsolated))
    }

    fun isShown(app: InstalledApp): Boolean = isShown(app.packageName, app.info.isSystem)

    /**
     * System apps read data as part of their work, and unattributed isolated
     * processes are mostly system sandboxes. Records for an uninstalled app
     * stay visible even when its package details are unknown.
     */
    private fun isHiddenByDefault(info: AppInfo?, isUnattributedIsolated: Boolean): Boolean = info?.isSystem == true || isUnattributedIsolated

    /** The app's own lookups stay hidden regardless of the system apps setting. */
    private fun isShown(packageName: String, hiddenByDefault: Boolean): Boolean = packageName != ownPackage && (showSystemApps || !hiddenByDefault)
}

/** [hiddenCount] excludes this app, whose records cannot be shown through settings. */
data class VisibleApps(val apps: List<AppSummary>, val hiddenCount: Int)
