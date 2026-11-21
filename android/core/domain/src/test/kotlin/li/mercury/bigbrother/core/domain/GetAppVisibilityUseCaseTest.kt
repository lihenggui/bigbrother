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

import android.content.pm.PackageInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.InstalledApp
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class GetAppVisibilityUseCaseTest {
    private val system = app(10100, "com.example.system")
    private val user = app(10101, "com.example.user")
    private val isolated = app(190_000, "")
    private val unknown = app(10102, "com.example.uninstalled")
    private val own = app(10103, OWN_PACKAGE)

    @Test
    fun givenLoadedRecords_whenSystemAppsAreEnabled_thenShowsThemInOrderWithoutReloading() = runTest {
        val access = TestAccessRepository(apps = listOf(system, user, isolated, own, unknown))
        val appInfo = TestAppInfoRepository(
            mapOf(system.packageName to info(isSystem = true), user.packageName to info(isSystem = false)),
        )
        val userData = TestUserDataRepository()
        val records = GetAppRecordsUseCase(access, appInfo)(TimeRange.DAY)
        val getAppVisibility = GetAppVisibilityUseCase(userData, OWN_PACKAGE)
        val emissions = mutableListOf<VisibleApps>()
        val collection = launch { getAppVisibility().map { it.visibleApps(records) }.take(2).toList(emissions) }

        testScheduler.runCurrent()
        userData.setShowSystemApps(true)
        collection.join()

        assertEquals(
            listOf(VisibleApps(listOf(user, unknown), 2), VisibleApps(listOf(system, user, isolated, unknown), 0)),
            emissions,
        )
        assertEquals(1, access.appReads)
    }

    @Test
    fun givenInstalledApps_whenSystemAppsAreEnabled_thenOwnAppStaysHidden() = runTest {
        val apps = listOf(
            InstalledApp(system.packageName, system.uid, info(isSystem = true)),
            InstalledApp(user.packageName, user.uid, info(isSystem = false)),
            InstalledApp(own.packageName, own.uid, info(isSystem = false)),
        )
        val userData = TestUserDataRepository()
        val getAppVisibility = GetAppVisibilityUseCase(userData, OWN_PACKAGE)

        assertEquals(listOf(apps[1]), apps.filter(getAppVisibility().first()::isShown))
        userData.setShowSystemApps(true)
        assertEquals(apps.take(2), apps.filter(getAppVisibility().first()::isShown))
    }

    private fun app(uid: Int, packageName: String) = AppSummary(uid, packageName, emptyMap(), lastSeen = 1)

    private fun info(isSystem: Boolean) = AppInfo("App", PackageInfo(), isSystem)

    private companion object {
        const val OWN_PACKAGE = "com.example.recorder"
    }
}
