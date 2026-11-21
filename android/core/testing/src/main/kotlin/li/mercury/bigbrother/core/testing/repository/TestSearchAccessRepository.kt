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

import kotlinx.coroutines.CoroutineDispatcher
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.domain.GetAppRecordsUseCase
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.domain.SearchRecordsUseCase
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.model.TimeRange

class TestSearchAccessRepository(
    records: TestAccessRepository,
    var paths: suspend (PathQuery) -> List<PathMatch> = { emptyList() },
) : AccessRepository by records {
    data class PathQuery(
        val text: String,
        val categories: Collection<Category>,
        val uids: Collection<Int>,
        val range: TimeRange,
        val limit: Int,
    )

    override suspend fun searchPaths(
        text: String,
        categories: Collection<Category>,
        uids: Collection<Int>,
        range: TimeRange,
        limit: Int,
    ): List<PathMatch> = paths(PathQuery(text, categories, uids, range, limit))
}

fun testSearchRecordsUseCase(
    dispatcher: CoroutineDispatcher,
    accessRepository: AccessRepository = TestAccessRepository(),
    appInfoRepository: AppInfoRepository = TestAppInfoRepository(),
    userDataRepository: UserDataRepository = TestUserDataRepository(),
    ownPackage: String = "com.example.recorder",
): SearchRecordsUseCase = SearchRecordsUseCase(
    accessRepository,
    GetAppRecordsUseCase(accessRepository, appInfoRepository),
    GetAppVisibilityUseCase(userDataRepository, ownPackage),
    dispatcher,
)
