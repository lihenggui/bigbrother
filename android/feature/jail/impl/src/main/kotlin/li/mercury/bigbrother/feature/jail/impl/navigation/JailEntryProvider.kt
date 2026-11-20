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

package li.mercury.bigbrother.feature.jail.impl.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.navigation.BottomSheetSceneStrategy
import li.mercury.bigbrother.core.navigation.Navigator
import li.mercury.bigbrother.core.ui.DetailPlaceholder
import li.mercury.bigbrother.feature.appdetail.api.navigation.AppDetailNavKey
import li.mercury.bigbrother.feature.appdetail.api.navigation.AppFilesNavKey
import li.mercury.bigbrother.feature.appdetail.api.navigation.RecordNavKey
import li.mercury.bigbrother.feature.appdetail.api.navigation.navigateToAppDetail
import li.mercury.bigbrother.feature.jail.api.navigation.JailAppNavKey
import li.mercury.bigbrother.feature.jail.api.navigation.JailDestinationNavKey
import li.mercury.bigbrother.feature.jail.api.navigation.JailNavKey
import li.mercury.bigbrother.feature.jail.api.navigation.JailNetworkNavKey
import li.mercury.bigbrother.feature.jail.api.navigation.JailSettingsNavKey
import li.mercury.bigbrother.feature.jail.api.navigation.closeJailApp
import li.mercury.bigbrother.feature.jail.api.navigation.navigateToJailApp
import li.mercury.bigbrother.feature.jail.api.navigation.navigateToJailDestination
import li.mercury.bigbrother.feature.jail.api.navigation.navigateToJailNetwork
import li.mercury.bigbrother.feature.jail.api.navigation.navigateToJailSettings
import li.mercury.bigbrother.feature.jail.impl.JailAppScreen
import li.mercury.bigbrother.feature.jail.impl.JailAppViewModel
import li.mercury.bigbrother.feature.jail.impl.JailScreen
import li.mercury.bigbrother.feature.jail.impl.JailSettingsScreen
import li.mercury.bigbrother.feature.jail.impl.JailSettingsViewModel
import li.mercury.bigbrother.feature.jail.impl.JailViewModel
import li.mercury.bigbrother.feature.jail.impl.R
import li.mercury.bigbrother.feature.jail.impl.network.DestinationScreen
import li.mercury.bigbrother.feature.jail.impl.network.DestinationViewModel
import li.mercury.bigbrother.feature.jail.impl.network.NetworkActivityScreen
import li.mercury.bigbrother.feature.jail.impl.network.NetworkActivityViewModel

/**
 * Registers the jail. On wide screens its list of apps is the list pane, and
 * an app's settings or the global settings the detail pane beside it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<NavKey>.jailEntries(navigator: Navigator) {
    entry<JailNavKey>(
        metadata = ListDetailSceneStrategy.listPane(
            detailPlaceholder = { DetailPlaceholder(text = stringResource(R.string.jail_select)) },
        ),
    ) {
        val shown = shownBesideList(navigator.state.currentSubStack)
        JailScreen(
            selectedUid = (shown as? JailAppNavKey)?.uid,
            settingsSelected = shown is JailSettingsNavKey,
            onOpenApp = { navigator.navigateToJailApp(it.app.uid, it.app.packageName) },
            onOpenSettings = navigator::navigateToJailSettings,
            viewModel = hiltViewModel<JailViewModel>(),
        )
    }
    entry<JailAppNavKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
        JailAppScreen(
            onBack = navigator::goBack,
            onOpenRecords = { uid, packageName -> navigator.navigateToAppDetail(uid, packageName, TimeRange.DAY) },
            onOpenNetwork = navigator::navigateToJailNetwork,
            onLeave = { navigator.closeJailApp(key.uid, key.packageName) },
            viewModel = hiltViewModel<JailAppViewModel, JailAppViewModel.Factory>(key = "jail:${key.uid}") { factory ->
                factory.create(key.uid, key.packageName)
            },
        )
    }
    entry<JailSettingsNavKey>(metadata = ListDetailSceneStrategy.detailPane()) {
        JailSettingsScreen(onBack = navigator::goBack, viewModel = hiltViewModel<JailSettingsViewModel>())
    }
    entry<JailNetworkNavKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
        // The destination open beside the page, whose row is marked as selected.
        val open = navigator.state.currentSubStack.lastOrNull() as? JailDestinationNavKey
        NetworkActivityScreen(
            selectedTarget = open?.takeIf { it.uid == key.uid }?.target,
            onBack = navigator::goBack,
            onOpenDestination = { target, rangeHours -> navigator.navigateToJailDestination(key.uid, key.packageName, target, rangeHours) },
            viewModel = hiltViewModel<NetworkActivityViewModel, NetworkActivityViewModel.Factory>(key = "network:${key.uid}") { factory ->
                factory.create(key.uid, key.packageName)
            },
        )
    }
    // In compact and medium windows a destination opens in a bottom sheet
    // over the activity, in wider ones beside it, as an app's records do.
    entry<JailDestinationNavKey>(
        metadata = ListDetailSceneStrategy.extraPane() + BottomSheetSceneStrategy.bottomSheet(),
    ) { key ->
        DestinationScreen(
            onClose = navigator::goBack,
            viewModel = hiltViewModel<DestinationViewModel, DestinationViewModel.Factory>(
                key = "destination:${key.uid}:${key.rangeHours}:${key.target}",
            ) { factory -> factory.create(key.uid, key.packageName, key.target, TimeRange.fromHours(key.rangeHours)) },
        )
    }
}

/**
 * Which of the jail's screens the pane beside the list shows, for the list
 * to mark it: the last one on [stack], or below it the page of the app
 * whose records or network activity it shows, opened from there. Anything
 * else, such as another app opened from the search, leaves nothing marked.
 */
internal fun shownBesideList(stack: List<NavKey>): NavKey? {
    val index = stack.indexOfLast { it is JailAppNavKey || it is JailSettingsNavKey }
    if (index < 0) return null
    val shown = stack[index]
    val above = stack.subList(index + 1, stack.size)
    return shown.takeIf { above.all { key -> shown is JailAppNavKey && key.recordsOf() == shown.uid } }
}

/** The uid of the app whose records or network this screen shows, if it is one of them. */
private fun NavKey.recordsOf(): Int? = when (this) {
    is AppDetailNavKey -> uid
    is AppFilesNavKey -> uid
    is RecordNavKey -> uid
    is JailNetworkNavKey -> uid
    is JailDestinationNavKey -> uid
    else -> null
}
