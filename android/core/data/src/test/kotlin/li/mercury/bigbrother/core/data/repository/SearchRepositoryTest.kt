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

package li.mercury.bigbrother.core.data.repository

import android.content.pm.PackageInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.bbd.BbdAccess
import li.mercury.bigbrother.core.data.bbd.BbdAppSummary
import li.mercury.bigbrother.core.data.bbd.BbdPathMatch
import li.mercury.bigbrother.core.data.bbd.TestBbdDataSource
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PackageDetails
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.UserData
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class SearchRepositoryTest {
    @Test
    fun givenCameraRecords_whenSearchingByName_thenReturnsMatchesAndVisibleApps() = runTest {
        val dataSource = SearchBbdDataSource()
        val repository = repository(dataSource)
        val result = repository.search("cAmErA")

        assertEquals(listOf("com.example.photos", "com.example.camera"), result.apps.map { it.packageName })
        assertEquals(listOf("/storage/emulated/0/DCIM/Camera/photo.jpg"), result.files.map { it.access.path })
        assertEquals(listOf(10001, 10002, 10005), result.visibleApps.map { it.uid })
        assertEquals(listOf(Category.STORAGE, Category.PROVIDER), dataSource.categories)
        assertEquals(listOf(10001, 10002, 10005), dataSource.uids)
        assertEquals(100, dataSource.limit)
    }

    @Test
    fun givenSystemAppsShown_whenSearching_thenIncludesSystemAndIsolatedRecordsExceptOwnApp() = runTest {
        val dataSource = SearchBbdDataSource()
        val repository = repository(dataSource, showSystemApps = true)
        val result = repository.search("camera")

        assertEquals(listOf(10001, 10002, 10003, 99001, 10005), result.visibleApps.map { it.uid })
        assertEquals(listOf(10001, 10002, 10003, 99001, 10005), dataSource.uids)
        assertEquals(100, dataSource.limit)
    }

    @Test
    fun givenPathCommandUnsupported_whenSearching_thenPropagatesModuleOutdated() = runTest {
        val repository = repository(SearchBbdDataSource(failPaths = true))

        assertFailsWith<RecordsException.ModuleOutdated> { repository.search("camera") }
    }

    private fun TestScope.repository(dataSource: SearchBbdDataSource, showSystemApps: Boolean = false): SearchRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RecordsSearchRepository(
            accessRepository = RootAccessRepository(dataSource, dispatcher),
            appInfoRepository = SearchAppInfoRepository(),
            userDataRepository = SearchSettingsRepository(showSystemApps),
            ownPackage = "com.example.recorder",
            defaultDispatcher = dispatcher,
        )
    }

    private class SearchAppInfoRepository : AppInfoRepository {
        private val apps = mapOf(
            "com.example.photos" to AppInfo("Camera roll", PackageInfo(), isSystem = false),
            "com.example.system" to AppInfo("Camera service", PackageInfo(), isSystem = true),
        )

        override suspend fun getAppInfo(packageName: String): AppInfo? = apps[packageName]
        override suspend fun getAppInfo(packageNames: Collection<String>): Map<String, AppInfo> = apps.filterKeys { it in packageNames }
        override suspend fun getPackageDetails(packageName: String): PackageDetails? = null
    }

    private class SearchSettingsRepository(showSystemApps: Boolean) : UserDataRepository {
        override val userData = MutableStateFlow(UserData(showSystemApps = showSystemApps))
        override suspend fun setTimeRange(range: TimeRange) = error("Unexpected settings write")
        override suspend fun setShowSystemApps(show: Boolean) = error("Unexpected settings write")
        override suspend fun setUseDynamicColor(use: Boolean) = error("Unexpected settings write")
    }

    private class SearchBbdDataSource(private val failPaths: Boolean = false) : TestBbdDataSource() {
        var categories = emptyList<Category>()
            private set
        var uids = emptyList<Int>()
            private set
        var limit = 0
            private set

        override suspend fun getApps(range: TimeRange): List<BbdAppSummary> = listOf(
            app(10001, "com.example.photos"),
            app(10002, "com.example.camera"),
            app(10003, "com.example.system"),
            app(99001, ""),
            app(10004, "com.example.recorder"),
            app(10005, "com.example.reader"),
        )

        override suspend fun searchPaths(
            text: String,
            categories: Collection<Category>,
            uids: Collection<Int>,
            range: TimeRange,
            limit: Int,
        ): List<BbdPathMatch> {
            this.categories = categories.toList()
            this.uids = uids.toList()
            this.limit = limit
            if (failPaths) throw RecordsException.ModuleOutdated()
            return listOf(path(1), path(4))
        }

        private fun app(uid: Int, pkg: String) = BbdAppSummary(uid, pkg, emptyMap(), 1, 0, emptyList(), 0, emptyList())

        private fun path(mode: Int) = BbdPathMatch(
            uid = 10001,
            packageName = "com.example.photos",
            access = BbdAccess(
                category = Category.STORAGE,
                path = "/storage/emulated/0/DCIM/Camera/photo.jpg",
                mode = mode,
                items = 0,
                allowed = 1,
                denied = 0,
                lastSeen = 5,
                topic = null,
            ),
        )
    }
}
