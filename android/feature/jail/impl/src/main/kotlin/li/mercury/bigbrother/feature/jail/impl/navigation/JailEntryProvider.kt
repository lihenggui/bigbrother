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
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.navigation.Navigator
import li.mercury.bigbrother.core.ui.DetailPlaceholder
import li.mercury.bigbrother.feature.appdetail.api.navigation.navigateToAppDetail
import li.mercury.bigbrother.feature.jail.api.navigation.JailAppNavKey
import li.mercury.bigbrother.feature.jail.api.navigation.JailNavKey
import li.mercury.bigbrother.feature.jail.api.navigation.JailSettingsNavKey
import li.mercury.bigbrother.feature.jail.api.navigation.navigateToJailApp
import li.mercury.bigbrother.feature.jail.api.navigation.navigateToJailSettings
import li.mercury.bigbrother.feature.jail.impl.JailAppScreen
import li.mercury.bigbrother.feature.jail.impl.JailAppViewModel
import li.mercury.bigbrother.feature.jail.impl.JailScreen
import li.mercury.bigbrother.feature.jail.impl.JailSettingsScreen
import li.mercury.bigbrother.feature.jail.impl.JailSettingsViewModel
import li.mercury.bigbrother.feature.jail.impl.JailViewModel
import li.mercury.bigbrother.feature.jail.impl.R

/**
 * Registers the jail. On wide screens its list of apps is the list pane, and
 * an app's settings or the global settings the detail pane beside it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<NavKey>.jailEntries(navigator: Navigator, topBar: @Composable () -> Unit) {
    entry<JailNavKey>(
        metadata = ListDetailSceneStrategy.listPane(
            detailPlaceholder = { DetailPlaceholder(text = stringResource(R.string.jail_select)) },
        ),
    ) {
        // An app's records opened over its settings leave the app selected.
        val shown = navigator.state.currentSubStack.lastOrNull { it is JailAppNavKey || it is JailSettingsNavKey }
        JailScreen(
            topBar = topBar,
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
            onLeave = navigator::goBack,
            viewModel = hiltViewModel<JailAppViewModel, JailAppViewModel.Factory>(key = "jail:${key.uid}") { factory ->
                factory.create(key.uid, key.packageName)
            },
        )
    }
    entry<JailSettingsNavKey>(metadata = ListDetailSceneStrategy.detailPane()) {
        JailSettingsScreen(onBack = navigator::goBack, viewModel = hiltViewModel<JailSettingsViewModel>())
    }
}
