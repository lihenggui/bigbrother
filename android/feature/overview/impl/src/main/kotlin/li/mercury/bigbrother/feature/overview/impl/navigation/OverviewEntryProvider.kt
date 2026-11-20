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

package li.mercury.bigbrother.feature.overview.impl.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import li.mercury.bigbrother.core.navigation.Navigator
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.DetailPlaceholder
import li.mercury.bigbrother.feature.appdetail.api.navigation.AppDetailNavKey
import li.mercury.bigbrother.feature.appdetail.api.navigation.navigateToAppDetail
import li.mercury.bigbrother.feature.overview.api.navigation.CategoryNavKey
import li.mercury.bigbrother.feature.overview.api.navigation.OverviewNavKey
import li.mercury.bigbrother.feature.overview.api.navigation.navigateToCategory
import li.mercury.bigbrother.feature.overview.impl.CategoryScreen
import li.mercury.bigbrother.feature.overview.impl.CategoryViewModel
import li.mercury.bigbrother.feature.overview.impl.OverviewScreen
import li.mercury.bigbrother.feature.overview.impl.OverviewViewModel

/**
 * Registers the overview, which uses the whole window, and the page of one
 * category, the list pane beside the app it opens.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<NavKey>.overviewEntries(navigator: Navigator) {
    entry<OverviewNavKey> {
        OverviewScreen(
            onOpenCategory = { navigator.navigateToCategory(it.name) },
            viewModel = hiltViewModel<OverviewViewModel>(),
        )
    }
    entry<CategoryNavKey>(
        metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { DetailPlaceholder() }),
    ) { key ->
        val category = DataCategory.entries.firstOrNull { it.name == key.category } ?: DataCategory.FILES
        val selected = navigator.state.currentSubStack.firstOrNull { it is AppDetailNavKey } as? AppDetailNavKey
        CategoryScreen(
            selectedUid = selected?.uid,
            onBack = navigator::goBack,
            onOpenApp = { app, range ->
                navigator.navigateToAppDetail(app.uid, app.packageName, range, category.name)
            },
            viewModel = hiltViewModel<CategoryViewModel, CategoryViewModel.Factory>(key = category.name) { factory ->
                factory.create(category)
            },
        )
    }
}
