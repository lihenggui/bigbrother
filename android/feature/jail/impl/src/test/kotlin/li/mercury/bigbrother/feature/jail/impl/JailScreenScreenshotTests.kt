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

package li.mercury.bigbrother.feature.jail.impl

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import li.mercury.bigbrother.core.model.InstalledApp
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoApp
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureMultiDevice
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import li.mercury.bigbrother.feature.search.impl.GlobalSearchBar
import li.mercury.bigbrother.feature.search.impl.SearchViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class JailScreenScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun givenIsolatedApps_whenShownOnEachDevice_thenMatchesGolden() {
        composeTestRule.captureMultiDevice("Jail") { Jail(isolatedApps()) }
    }

    @Test
    fun givenIsolatedApps_whenShownInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice("phone_dark", DefaultTestDevices.PHONE.spec, "Jail", darkMode = true) {
            Jail(isolatedApps())
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenIsolatedApps_whenShownInChinese_thenMatchesGolden() {
        composeTestRule.captureForDevice("phone_zh", DefaultTestDevices.PHONE.spec, "Jail") { Jail(isolatedApps()) }
    }

    @Test
    fun givenNoIsolatedApp_whenShown_thenExplainsIsolationAndOffersToAdd() {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, "JailEmpty") { Jail(emptyList()) }
    }

    @Test
    fun givenHookNotActive_whenShown_thenSaysWhatIsNotInEffect() {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, "JailHookMissing") {
            Jail(isolatedApps(), SandboxStatus(gate = true, appOps = true, androidIdHook = false, appListHook = false))
        }
    }

    @Test
    fun givenIsolatedApp_whenOpened_thenItemsItAsksForShownFirst() {
        composeTestRule.captureMultiDevice("JailApp") { JailApp() }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenIsolatedApp_whenOpenedInChinese_thenMatchesGolden() {
        composeTestRule.captureForDevice("phone_zh", DefaultTestDevices.PHONE.spec, "JailApp") { JailApp() }
    }

    @Test
    fun givenIsolatedApp_whenOpenedInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice("phone_dark", DefaultTestDevices.PHONE.spec, "JailApp", darkMode = true) { JailApp() }
    }

    @Test
    fun givenGlobalSettings_whenShown_thenItemsGroupedByKind() {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, "JailSettings") {
            JailSettingsScreen(
                state = JailSettingsUiState(
                    global = SandboxItem.entries.associateWith { SandboxMode.ON } + (SandboxItem.CLIPBOARD_ACCOUNTS to SandboxMode.OFF),
                    status = FULL_STATUS,
                    isolated = 3,
                ),
                showBack = true,
                onBack = {},
                onRefresh = {},
                onSetOn = { _, _ -> },
                onChoose = { _, _ -> },
                onSetAutoIsolate = {},
                onReleaseAll = {},
                onErrorDismiss = {},
            )
        }
    }

    @Test
    fun givenInstalledApps_whenAdding_thenListedByNameWithChoiceCounted() {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, "JailAddApps") {
            val locale = demoLocale()
            val apps = listOf(DemoApp.CHAT, DemoApp.PUZZLE, DemoApp.NEWS, DemoApp.KEYBOARD, DemoApp.NOTES, DemoApp.PHOTO_EDITOR, DemoApp.STEP_COUNTER)
                .map { InstalledApp(it.packageName, it.uid, it.appInfo(locale)) }
                .sortedByName { it.info.label }
            AddAppsSheet(
                state = AddAppsUiState(apps = apps, selected = setOf(DemoApp.PUZZLE.packageName, DemoApp.NEWS.packageName)),
                query = rememberTextFieldState(),
                compact = true,
                onToggle = {},
                onDismiss = {},
                onConfirm = {},
            )
        }
    }

    /** Three apps put in the sandbox over the last days, one with settings of its own. */
    private fun isolatedApps(): List<IsolatedApp> {
        val locale = demoLocale()
        val day = 24 * 60 * 60L
        return listOf(
            Triple(DemoApp.WEATHER, clock.todayAt(9, 12) - 2 * day, mapOf(SandboxItem.LOCATION to SandboxMode.COARSE)),
            Triple(DemoApp.SHOPPING, clock.todayAt(20, 5) - 6 * day, mapOf(SandboxItem.CAMERA to SandboxMode.OFF)),
            Triple(DemoApp.FLASHLIGHT, clock.todayAt(11, 40), emptyMap()),
        ).map { (app, created, settings) ->
            IsolatedApp(sandboxedApp(app, created, settings), app.appInfo(locale))
        }.sortedByName { it.name }
    }

    @Composable
    private fun Jail(apps: List<IsolatedApp>, status: SandboxStatus = FULL_STATUS) {
        JailScreen(
            state = JailUiState(apps = apps, status = status),
            message = null,
            topBar = { SearchBar() },
            selectedUid = null,
            settingsSelected = false,
            onRefresh = {},
            onOpenApp = {},
            onOpenSettings = {},
            onAdd = {},
            onUndo = {},
            onMessageDismiss = {},
        )
    }

    @Composable
    private fun JailApp() {
        val app = DemoApp.WEATHER
        val settings = mapOf(SandboxItem.LOCATION to SandboxMode.COARSE)
        JailAppScreen(
            state = JailAppUiState(
                packageName = app.packageName,
                app = sandboxedApp(app, clock.todayAt(9, 12) - 2 * 24 * 60 * 60L, settings),
                info = app.appInfo(demoLocale()),
                status = FULL_STATUS,
                requested = setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
                loading = false,
            ),
            showBack = true,
            onBack = {},
            onRefresh = {},
            onOpenRecords = { _, _ -> },
            onRelease = {},
            onSetOn = { _, _ -> },
            onChoose = { _, _ -> },
            onReset = {},
            onErrorDismiss = {},
            initiallyExpanded = SandboxItem.LOCATION,
        )
    }

    private fun sandboxedApp(app: DemoApp, created: Long, settings: Map<SandboxItem, SandboxMode>) = SandboxedApp(
        uid = app.uid,
        packageName = app.packageName,
        enabled = true,
        created = created,
        settings = settings,
        items = SandboxItem.entries.associateWith { settings[it] ?: SandboxMode.ON },
    )

    /** The language the screenshot is taken in, as the test's qualifiers set it. */
    private fun demoLocale(): Locale = RuntimeEnvironment.getApplication().resources.configuration.locales[0]

    private companion object {
        val FULL_STATUS = SandboxStatus(gate = true, appOps = true, androidIdHook = true, appListHook = true)
    }
}

/** The search bar the top-level screens have as their top bar, idle. */
@Composable
private fun SearchBar() {
    val viewModel = remember {
        SearchViewModel(
            context = ApplicationProvider.getApplicationContext(),
            accessRepository = TestAccessRepository(),
            appInfoRepository = TestAppInfoRepository(),
            userDataRepository = TestUserDataRepository(),
        )
    }
    GlobalSearchBar(
        onOpenApp = { _, _, _ -> },
        onOpenCategory = {},
        onOpenSettings = {},
        settingsBadge = false,
        viewModel = viewModel,
    )
}
