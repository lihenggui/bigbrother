package li.mercury.bigbrother.feature.appdetail.impl.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.navigation.BottomSheetSceneStrategy
import li.mercury.bigbrother.core.navigation.Navigator
import li.mercury.bigbrother.feature.appdetail.api.navigation.AppDetailNavKey
import li.mercury.bigbrother.feature.appdetail.api.navigation.AppFilesNavKey
import li.mercury.bigbrother.feature.appdetail.api.navigation.RecordNavKey
import li.mercury.bigbrother.feature.appdetail.api.navigation.navigateToAppFiles
import li.mercury.bigbrother.feature.appdetail.api.navigation.navigateToRecord
import li.mercury.bigbrother.feature.appdetail.impl.AppDetailScreen
import li.mercury.bigbrother.feature.appdetail.impl.AppDetailViewModel
import li.mercury.bigbrother.feature.appdetail.impl.AppFilesScreen
import li.mercury.bigbrother.feature.appdetail.impl.AppFilesViewModel
import li.mercury.bigbrother.feature.appdetail.impl.RecordScreen
import li.mercury.bigbrother.feature.appdetail.impl.RecordViewModel

/**
 * Registers an app's page as the detail pane, and its file browser and
 * records as the extra pane beside it. In compact and medium windows a
 * record opens in a bottom sheet over the page instead.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<NavKey>.appDetailEntries(navigator: Navigator) {
    entry<AppDetailNavKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
        val range = TimeRange.fromHours(key.rangeHours)
        // The record open beside the page, whose row is marked as selected.
        val openRecord = navigator.state.currentSubStack.lastOrNull() as? RecordNavKey
        AppDetailScreen(
            focusCategory = key.category,
            openRecord = openRecord?.takeIf { it.uid == key.uid }?.let { "${it.topic}:${it.source}" },
            onBack = navigator::goBack,
            onOpenFiles = { location -> navigator.navigateToAppFiles(key.uid, key.packageName, range, location) },
            onOpenRecord = { topic, source ->
                navigator.navigateToRecord(key.uid, key.packageName, range, topic, source)
            },
            viewModel = hiltViewModel<AppDetailViewModel, AppDetailViewModel.Factory>(
                key = "${key.uid}:${key.rangeHours}",
            ) { factory -> factory.create(key.uid, key.packageName, range) },
        )
    }
    entry<AppFilesNavKey>(metadata = ListDetailSceneStrategy.extraPane()) { key ->
        AppFilesScreen(
            uid = key.uid,
            initialLocation = key.location,
            onBack = navigator::goBack,
            viewModel = hiltViewModel<AppFilesViewModel, AppFilesViewModel.Factory>(
                key = "${key.uid}:${key.rangeHours}",
            ) { factory -> factory.create(key.uid, key.packageName, TimeRange.fromHours(key.rangeHours)) },
        )
    }
    entry<RecordNavKey>(
        metadata = ListDetailSceneStrategy.extraPane() + BottomSheetSceneStrategy.bottomSheet(),
    ) { key ->
        RecordScreen(
            uid = key.uid,
            packageName = key.packageName,
            onClose = navigator::goBack,
            viewModel = hiltViewModel<RecordViewModel, RecordViewModel.Factory>(
                key = "${key.uid}:${key.rangeHours}:${key.topic}:${key.source}",
            ) { factory ->
                factory.create(key.uid, key.packageName, TimeRange.fromHours(key.rangeHours), key.topic, key.source)
            },
        )
    }
}
