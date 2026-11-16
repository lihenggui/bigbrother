package li.mercury.bigbrother.feature.appdetail.impl.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.navigation.Navigator
import li.mercury.bigbrother.feature.appdetail.api.navigation.AppDetailNavKey
import li.mercury.bigbrother.feature.appdetail.impl.AppDetailScreen
import li.mercury.bigbrother.feature.appdetail.impl.AppDetailViewModel

/** Registers the app detail screen. On wide screens it is the detail pane. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<NavKey>.appDetailEntry(navigator: Navigator) {
    entry<AppDetailNavKey>(
        metadata = ListDetailSceneStrategy.detailPane(),
    ) { key ->
        AppDetailScreen(
            onBackClick = navigator::goBack,
            viewModel = hiltViewModel<AppDetailViewModel, AppDetailViewModel.Factory>(
                // One view model per app and time range.
                key = "${key.uid}:${key.rangeHours}",
            ) { factory ->
                factory.create(key.uid, key.packageName, TimeRange.fromHours(key.rangeHours))
            },
        )
    }
}
