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

package li.mercury.bigbrother.feature.applist.impl.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import li.mercury.bigbrother.core.navigation.Navigator
import li.mercury.bigbrother.core.ui.DetailPlaceholder
import li.mercury.bigbrother.feature.appdetail.api.navigation.AppDetailNavKey
import li.mercury.bigbrother.feature.appdetail.api.navigation.navigateToAppDetail
import li.mercury.bigbrother.feature.applist.api.navigation.AppListNavKey
import li.mercury.bigbrother.feature.applist.impl.AppListScreen
import li.mercury.bigbrother.feature.applist.impl.AppListViewModel

/** Registers the app list. On wide screens it is the list pane of a list-detail layout. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<NavKey>.appListEntry(navigator: Navigator, topBar: @Composable () -> Unit) {
    entry<AppListNavKey>(
        metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { DetailPlaceholder() }),
    ) {
        val selected = navigator.state.currentSubStack.firstOrNull { it is AppDetailNavKey } as? AppDetailNavKey
        AppListScreen(
            topBar = topBar,
            selectedUid = selected?.uid,
            // With a category selected, the app opens at that category.
            onOpenApp = { app, range, category ->
                navigator.navigateToAppDetail(app.uid, app.packageName, range, category?.name)
            },
            viewModel = hiltViewModel<AppListViewModel>(),
        )
    }
}
