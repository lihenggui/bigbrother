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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.CommandResult
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.model.AppIconSource
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.PackageDetails
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.UserData
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SearchRepositoryTest {
    @Test
    fun givenCameraRecords_whenSearchingByName_thenReturnsMatchesAndVisibleApps() = runTest {
        val shell = SearchRootShell()
        val repository = repository(shell)
        val result = repository.search("cAmErA")

        assertEquals(listOf("com.example.photos", "com.example.camera"), result.apps.map { it.packageName })
        assertEquals(listOf("/storage/emulated/0/DCIM/Camera/photo.jpg"), result.files.map { it.access.path })
        assertEquals(listOf(10001, 10002, 10005), result.visibleApps.map { it.uid })
        assertTrue(shell.pathCommand.contains("--cat storage --cat provider --uid 10001 --uid 10002 --uid 10005 --limit 100"))
    }

    @Test
    fun givenSystemAppsShown_whenSearching_thenIncludesSystemAndIsolatedRecordsExceptOwnApp() = runTest {
        val shell = SearchRootShell()
        val repository = repository(shell, showSystemApps = true)
        val result = repository.search("camera")

        assertEquals(listOf(10001, 10002, 10003, 99001, 10005), result.visibleApps.map { it.uid })
        assertTrue(shell.pathCommand.contains("--uid 10001 --uid 10002 --uid 10003 --uid 99001 --uid 10005 --limit 100"))
    }

    @Test
    fun givenPathCommandUnsupported_whenSearching_thenPropagatesModuleOutdated() = runTest {
        val repository = repository(SearchRootShell(failPaths = true))

        assertFailsWith<RecordsException.ModuleOutdated> { repository.search("camera") }
    }

    private fun TestScope.repository(shell: SearchRootShell, showSystemApps: Boolean = false): SearchRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RecordsSearchRepository(
            accessRepository = RootAccessRepository(shell, dispatcher),
            appInfoRepository = SearchAppInfoRepository(),
            userDataRepository = SearchSettingsRepository(showSystemApps),
            ownPackage = "com.example.recorder",
            defaultDispatcher = dispatcher,
        )
    }

    private class SearchAppInfoRepository : AppInfoRepository {
        private val apps = mapOf(
            "com.example.photos" to AppInfo("Camera roll", AppIconSource("com.example.photos", 10001, 0), isSystem = false),
            "com.example.system" to AppInfo("Camera service", AppIconSource("com.example.system", 10003, 0), isSystem = true),
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

    private class SearchRootShell(private val failPaths: Boolean = false) : RootShell(Dispatchers.Unconfined) {
        var pathCommand = ""
            private set

        override suspend fun run(command: String, label: String): CommandResult = when (label) {
            "bbd apps" -> CommandResult(isSuccess = true, code = 0, output = APPS, error = "")

            "bbd files" -> {
                pathCommand = command
                if (failPaths) {
                    CommandResult(isSuccess = false, code = 2, output = "", error = "error: unexpected argument '--contains' found")
                } else {
                    CommandResult(isSuccess = true, code = 0, output = PATHS, error = "")
                }
            }

            else -> error("Unexpected read: $label")
        }
    }

    private companion object {
        val APPS = """
            [
              {"uid":10001,"pkg":"com.example.photos","last_ts":5,"files_read":1,"topics":["camera","camera"]},
              {"uid":10002,"pkg":"com.example.camera","last_ts":4,"files_read":0,"topics":["camera"]},
              {"uid":10003,"pkg":"com.example.system","last_ts":3,"files_read":0,"topics":["camera"]},
              {"uid":99001,"pkg":"","last_ts":2,"files_read":0,"topics":["camera"]},
              {"uid":10004,"pkg":"com.example.recorder","last_ts":1,"files_read":0,"topics":["camera"]},
              {"uid":10005,"pkg":"com.example.reader","last_ts":1,"files_read":0}
            ]
        """.trimIndent()
        val PATHS = """
            [
              {"uid":10001,"pkg":"com.example.photos","cat":"storage","path":"/storage/emulated/0/DCIM/Camera/photo.jpg","mode":1,"ok":1,"fail":0,"last_ts":5},
              {"uid":10001,"pkg":"com.example.photos","cat":"storage","path":"/storage/emulated/0/DCIM/Camera/photo.jpg","mode":4,"ok":1,"fail":0,"last_ts":4}
            ]
        """.trimIndent()
    }
}
