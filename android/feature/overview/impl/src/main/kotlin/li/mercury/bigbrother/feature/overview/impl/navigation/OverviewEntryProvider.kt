package li.mercury.bigbrother.feature.overview.impl.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
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
fun EntryProviderScope<NavKey>.overviewEntries(navigator: Navigator, topBar: @Composable () -> Unit) {
    entry<OverviewNavKey> {
        OverviewScreen(
            topBar = topBar,
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
