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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.model.TimeRange
import javax.inject.Inject

data class SearchResult(
    val apps: List<AppSummary> = emptyList(),
    val files: List<PathMatch> = emptyList(),
    val appInfo: Map<String, AppInfo> = emptyMap(),
    val visibleApps: List<AppSummary> = emptyList(),
)

class SearchRecordsUseCase @Inject constructor(
    private val accessRepository: AccessRepository,
    private val getAppRecords: GetAppRecordsUseCase,
    private val getAppVisibility: GetAppVisibilityUseCase,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) {
    /** Finds matching apps and files among the visible apps with records. */
    suspend operator fun invoke(text: String): SearchResult = withContext(defaultDispatcher) {
        if (text.isBlank()) return@withContext SearchResult()

        val records = getAppRecords(TimeRange.ALL)
        val appInfo = records.appInfo
        val visible = getAppVisibility().first().visibleApps(records).apps
        SearchResult(
            apps = visible.filter { app ->
                app.packageName.contains(text, ignoreCase = true) ||
                    appInfo[app.packageName]?.label?.contains(text, ignoreCase = true) == true
            },
            files = searchPaths(text, visible),
            appInfo = appInfo,
            visibleApps = visible,
        )
    }

    private suspend fun searchPaths(text: String, apps: List<AppSummary>): List<PathMatch> {
        // One character would match nearly every path. Spend the search limit on
        // files touched by visible apps.
        if (text.length < MIN_PATH_QUERY || apps.isEmpty()) return emptyList()
        return accessRepository.searchPaths(
            text = text,
            categories = PATH_CATEGORIES,
            uids = apps.map { it.uid },
            range = TimeRange.ALL,
            // A file read and listed has two rows before they are merged.
            limit = MAX_PATHS * 2,
        ).distinctBy { it.uid to it.access.path }.take(MAX_PATHS)
    }

    private companion object {
        const val MAX_PATHS = 50
        const val MIN_PATH_QUERY = 2
        val PATH_CATEGORIES = listOf(Category.STORAGE, Category.PROVIDER)
    }
}
