package li.mercury.bigbrother.feature.settings.impl.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import li.mercury.bigbrother.core.navigation.Navigator
import li.mercury.bigbrother.feature.settings.api.navigation.SettingsNavKey
import li.mercury.bigbrother.feature.settings.impl.SettingsScreen
import li.mercury.bigbrother.feature.settings.impl.SettingsViewModel

/** Registers the settings, which use the whole window. */
fun EntryProviderScope<NavKey>.settingsEntry(navigator: Navigator) {
    entry<SettingsNavKey> {
        SettingsScreen(onBack = navigator::goBack, viewModel = hiltViewModel<SettingsViewModel>())
    }
}
