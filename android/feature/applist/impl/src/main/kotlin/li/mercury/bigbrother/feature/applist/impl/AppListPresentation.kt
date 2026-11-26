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

package li.mercury.bigbrother.feature.applist.impl

import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.dataCategories
import li.mercury.bigbrother.core.ui.readFiles

/** How the app list is ordered. */
internal enum class SortOrder(val label: Int) {
    /** Apps that read files first, then by their most sensitive category, most recent first within. */
    SENSITIVITY(R.string.sort_sensitivity),
    RECENT(R.string.sort_recent),
    NAME(R.string.sort_name),
}

/** An app with the categories of private data it accessed, computed once per load. */
internal data class AppListEntry(val app: AppSummary, val categories: List<DataCategory>) {
    /**
     * Position in the sensitivity order: apps that read the user's files
     * lead; the rest follow their most sensitive other category. Merely
     * looking for files, which nearly every app does, does not count.
     */
    val rank: Int = when {
        app.readFiles -> DataCategory.FILES.ordinal
        else -> categories.firstOrNull { it != DataCategory.FILES }?.ordinal ?: DataCategory.FILES.ordinal
    }
}

internal data class AppListFilter(val sort: SortOrder = SortOrder.SENSITIVITY, val category: DataCategory? = null)

internal data class AppListPresentation(
    val state: AppListUiState = AppListUiState(),
    val filter: AppListFilter = AppListFilter(),
    val groups: List<Pair<Int?, List<AppListEntry>>>? = null,
    val categories: List<DataCategory> = emptyList(),
)

internal fun AppListUiState.present(filter: AppListFilter, displayName: (AppSummary, AppInfo?) -> String): AppListPresentation {
    val entries = apps?.map { AppListEntry(it, it.dataCategories()) } ?: return AppListPresentation(this, filter)
    val (sort, category) = filter
    // A category stays in the menu when nothing is left in it, so that the
    // filter can always be cleared where it was set.
    val present = DataCategory.entries.filter { c -> c == category || entries.any { c in it.categories } }
    val matching = entries.filter { category == null || category in it.categories }
    val shown = when (sort) {
        SortOrder.SENSITIVITY -> matching.sortedWith(compareBy<AppListEntry> { it.rank }.thenByDescending { it.app.lastSeen })

        // The daemon already lists the most recent first.
        SortOrder.RECENT -> matching

        SortOrder.NAME -> matching.sortedBy { displayName(it.app, appInfo[it.app.packageName]).lowercase() }
    }
    // In sensitivity order the apps that read files form a group of their
    // own, which says why they come first.
    val groups: List<Pair<Int?, List<AppListEntry>>> = if (sort == SortOrder.SENSITIVITY && category == null) {
        val (read, other) = shown.partition { it.app.readFiles }
        listOf(R.plurals.list_group_read_files to read, R.plurals.list_group_other to other).filter { it.second.isNotEmpty() }
    } else {
        listOf(null to shown)
    }
    return AppListPresentation(this, filter, groups, present)
}
