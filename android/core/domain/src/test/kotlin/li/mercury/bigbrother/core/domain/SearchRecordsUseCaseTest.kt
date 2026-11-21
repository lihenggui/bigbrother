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

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.AppIconSource
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.UserData
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestSearchAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import li.mercury.bigbrother.core.testing.repository.testSearchRecordsUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SearchRecordsUseCaseTest {
    private val access = TestSearchAccessRepository(
        TestAccessRepository(
            apps = listOf(
                app(10001, "com.example.photos"),
                app(10002, "com.example.camera"),
                app(10003, "com.example.system"),
                app(99001, ""),
                app(10004, "com.example.recorder"),
                app(10005, "com.example.reader"),
            ),
        ),
    )
    private val info = TestAppInfoRepository(
        mapOf(
            "com.example.photos" to AppInfo("Camera roll", AppIconSource("com.example.photos", 10001, 0), isSystem = false),
            "com.example.system" to AppInfo("Camera service", AppIconSource("com.example.system", 10003, 0), isSystem = true),
        ),
    )

    @Test
    fun givenCameraRecords_whenSearchingByName_thenReturnsMatchesAndSearchesVisibleUids() = runTest {
        val match = PathMatch(
            uid = 10001,
            packageName = "com.example.photos",
            access = Access(
                category = Category.STORAGE,
                path = "/storage/emulated/0/DCIM/Camera/photo.jpg",
                action = Action.READ,
                viaProvider = false,
                items = 0,
                allowed = 1,
                denied = 0,
                lastSeen = 5,
            ),
        )
        access.paths = { query ->
            assertEquals(
                TestSearchAccessRepository.PathQuery(
                    "cAmErA",
                    listOf(Category.STORAGE, Category.PROVIDER),
                    listOf(10001, 10002, 10005),
                    TimeRange.ALL,
                    100,
                ),
                query,
            )
            listOf(match, match.copy(access = match.access.copy(action = Action.LIST)))
        }

        val result = search()("cAmErA")

        assertEquals(listOf("com.example.photos", "com.example.camera"), result.apps.map { it.packageName })
        assertEquals(listOf(match), result.files)
        assertEquals(listOf(10001, 10002, 10005), result.visibleApps.map { it.uid })
    }

    @Test
    fun givenSystemAppsShown_whenSearching_thenIncludesSystemAndIsolatedRecordsExceptOwnApp() = runTest {
        val visibleUids = listOf(10001, 10002, 10003, 99001, 10005)
        access.paths = { query ->
            assertEquals(visibleUids, query.uids.toList())
            emptyList()
        }

        val result = search(showSystemApps = true)("camera")

        assertEquals(visibleUids, result.visibleApps.map { it.uid })
    }

    @Test
    fun givenPathCommandUnsupported_whenSearching_thenPropagatesModuleOutdated() = runTest {
        access.paths = { throw RecordsException.ModuleOutdated() }

        assertFailsWith<RecordsException.ModuleOutdated> { search()("camera") }
    }

    private fun TestScope.search(showSystemApps: Boolean = false) = testSearchRecordsUseCase(
        StandardTestDispatcher(testScheduler),
        access,
        info,
        TestUserDataRepository(UserData(showSystemApps = showSystemApps)),
    )

    private fun app(uid: Int, packageName: String) = AppSummary(uid, packageName, emptyMap(), lastSeen = 1)
}
