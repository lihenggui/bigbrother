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

package li.mercury.bigbrother.feature.search.impl

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.dataCategories
import li.mercury.bigbrother.core.ui.isShown
import li.mercury.bigbrother.core.ui.topicCategory
import li.mercury.bigbrother.core.ui.topicTitle
import javax.inject.Inject

/** A kind of data whose name matched, and how many apps accessed it. */
data class DataMatch(
    val category: DataCategory,
    /** A topic id within the category, or null when the category's own name matched. */
    val topic: String?,
    val apps: Int,
)

data class SearchResults(
    val query: String = "",
    val apps: List<AppSummary> = emptyList(),
    val files: List<PathMatch> = emptyList(),
    val data: List<DataMatch> = emptyList(),
    val appInfo: Map<String, AppInfo> = emptyMap(),
    /**
     * Whether the results are for the text in the field; false while a new
     * text is being looked up, when they are still the previous text's.
     */
    val complete: Boolean = true,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessRepository: AccessRepository,
    private val appInfoRepository: AppInfoRepository,
    private val userDataRepository: UserDataRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    /** Every app with records, loaded on the first search after the field was empty. */
    private var apps: List<AppSummary>? = null

    private val found: Flow<SearchResults> = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(DEBOUNCE_MS)
        .mapLatest { text ->
            if (text.isEmpty()) {
                // A new search starts from fresh records.
                apps = null
                SearchResults()
            } else {
                search(text)
            }
        }

    val results: StateFlow<SearchResults> = combine(query.map { it.trim() }, found) { text, results ->
        when {
            text.isEmpty() -> SearchResults()
            else -> results.copy(complete = results.query == text)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun setQuery(text: String) {
        query.value = text
    }

    private suspend fun search(text: String): SearchResults {
        val all = apps ?: try {
            accessRepository.getApps(TimeRange.ALL).also { apps = it }
        } catch (_: RecordsException) {
            emptyList()
        }
        val appInfo = appInfoRepository.getAppInfo(all.map { it.packageName }.filter { it.isNotEmpty() }.toSet())
        val showSystemApps = userDataRepository.userData.first().showSystemApps
        val visible = all.filter { isShown(it, appInfo, showSystemApps, context.packageName) }
        val matchingApps = visible.filter { app ->
            app.packageName.contains(text, ignoreCase = true) ||
                appInfo[app.packageName]?.label?.contains(text, ignoreCase = true) == true
        }
        // Only the user's files: other records are stored under identifiers
        // meant for the daemon, and are found by the name of their kind.
        // Only the apps shown are searched, so that the limit is spent on
        // them. A single character would match nearly every path, and each
        // search scans the records through the one root shell the app shares.
        val files = if (text.length < MIN_PATH_QUERY) {
            emptyList()
        } else {
            try {
                accessRepository
                    .searchPaths(
                        text = text,
                        categories = PATH_CATEGORIES,
                        uids = visible.map { it.uid },
                        range = TimeRange.ALL,
                        // One row per kind of access: a file read and listed
                        // counts twice before the rows are merged below.
                        limit = MAX_PATHS * 2,
                    )
                    .distinctBy { it.uid to it.access.path }
                    .take(MAX_PATHS)
            } catch (_: RecordsException) {
                emptyList()
            }
        }
        return SearchResults(
            query = text,
            apps = matchingApps,
            files = files,
            data = dataMatches(text, visible),
            appInfo = appInfo,
        )
    }

    /** Categories and topics whose names contain [text], with the number of apps that accessed each. */
    private fun dataMatches(text: String, apps: List<AppSummary>): List<DataMatch> {
        val categories = DataCategory.entries
            .filter { context.getString(it.title).contains(text, ignoreCase = true) }
            .map { category -> DataMatch(category, null, apps.count { category in it.dataCategories() }) }
        val topics = apps.flatMap { it.topics }.distinct()
            .filter { topic ->
                val title = topicTitle(topic) ?: return@filter false
                context.getString(title).contains(text, ignoreCase = true)
            }
            .map { topic ->
                DataMatch(
                    category = topicCategory(topic) ?: DataCategory.DEVICE_PROFILE,
                    topic = topic,
                    apps = apps.count { topic in it.topics },
                )
            }
        return (categories + topics).filter { it.apps > 0 }
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val MAX_PATHS = 50
        const val MIN_PATH_QUERY = 2
        val PATH_CATEGORIES = listOf(Category.STORAGE, Category.PROVIDER)
    }
}
