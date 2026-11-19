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
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxPart
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoApp
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoLoadFailure
import li.mercury.bigbrother.core.screenshottesting.assertSamplesCover
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureMultiDevice
import li.mercury.bigbrother.core.screenshottesting.screenshotName
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
            Jail(isolatedApps(), SandboxStatus(gate = true, appOps = true, androidIdHook = false, appListHook = false, uriGrantsHook = false, adIdHook = false))
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
    fun givenItemsNotInEffect_whenOpened_thenSaysWhyForEach() {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, "JailAppNotInEffect") {
            JailApp(
                unmet = mapOf(
                    SandboxItem.DEVICE_IDS to setOf(SandboxPart.ANDROID_ID_HOOK),
                    SandboxItem.STORAGE to setOf(SandboxPart.APP_OPS),
                ),
                expanded = null,
            )
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenItemsNotInEffect_whenOpenedInChinese_thenSaysWhyForEach() {
        composeTestRule.captureForDevice("phone_zh", DefaultTestDevices.PHONE.spec, "JailAppNotInEffect") {
            JailApp(
                unmet = mapOf(
                    SandboxItem.DEVICE_IDS to setOf(SandboxPart.ANDROID_ID_HOOK),
                    SandboxItem.STORAGE to setOf(SandboxPart.APP_OPS),
                ),
                expanded = null,
            )
        }
    }

    @Test
    fun givenGlobalSettings_whenShown_thenItemsGroupedByKind() {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, "JailSettings") {
            JailSettingsScreen(
                state = JailSettingsUiState(
                    global = SandboxItem.entries.associateWith { it.default } + (SandboxItem.CLIPBOARD_ACCOUNTS to SandboxMode.OFF),
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
                onAllowNetwork = {},
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

    @Test
    fun givenSandboxNotYetRead_whenShown_thenShowsLoadingIndicator() {
        capturePhone("JailLoading") { Jail(JailUiState()) }
    }

    @Test
    fun givenLoadFailure_whenShown_thenSaysWhy() {
        val failure = DemoLoadFailure
        capturePhone("JailFailure${failure.screenshotName}") { Jail(JailUiState(failure = failure)) }
    }

    @Test
    fun givenRefreshing_whenShown_thenShowsRefreshIndicatorOverList() {
        capturePhone("JailRefreshing") { Jail(JailUiState(apps = isolatedApps(), status = FULL_STATUS, refreshing = true)) }
    }

    @Test
    fun givenNothingInPlace_whenShown_thenSaysWhatIsNotInEffect() {
        capturePhone("JailNothingInPlace") { Jail(isolatedApps(), NOTHING_IN_PLACE) }
    }

    @Test
    fun givenStatusNotYetKnown_whenShown_thenListsAppsWithoutStatus() {
        capturePhone("JailStatusUnknown") { Jail(JailUiState(apps = isolatedApps())) }
    }

    @Test
    fun givenAppOfAnotherUser_whenShown_thenNamesTheUser() {
        capturePhone("JailOtherUser") {
            val apps = isolatedApps()
            Jail(apps.take(1) + apps.drop(1).map { it.copy(otherUser = OTHER_USER) }, FULL_STATUS)
        }
    }

    @Test
    fun givenEachMessage_whenShown_thenSnackbarSaysWhatHappened() {
        for ((name, message) in messages()) {
            capturePhone("JailMessage$name") {
                JailScreen(
                    state = JailUiState(apps = isolatedApps(), status = FULL_STATUS),
                    message = message,
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
        }
    }

    @Test
    fun givenMessageCases_whenChecked_thenEveryKindHasOne() {
        assertSamplesCover(JailMessage::class.java, messages().map { it.second })
    }

    @Test
    fun givenAppPageOpening_whenShown_thenShowsLoadingIndicator() {
        capturePhone("JailAppLoading") { JailApp { JailAppUiState(packageName = it.packageName) } }
    }

    @Test
    fun givenLoadFailureOnAppPage_whenShown_thenSaysWhy() {
        val failure = DemoLoadFailure
        capturePhone("JailAppFailure${failure.screenshotName}") {
            JailApp { JailAppUiState(packageName = it.packageName, loading = false, failure = failure) }
        }
    }

    @Test
    fun givenAppLeftSandbox_whenShownWhileLeaving_thenShowsOnlyTitle() {
        capturePhone("JailAppLeaving") { JailApp { JailAppUiState(packageName = it.packageName, loading = false, left = true) } }
    }

    @Test
    fun givenPermissionsNotKnown_whenOpened_thenListsEveryItem() {
        capturePhone("JailAppAllItems") { JailApp(expanded = null) { it.copy(requested = null) } }
    }

    @Test
    fun givenAppOfAnotherUserWithoutOwnSettings_whenOpened_thenNamesUserWithoutReset() {
        capturePhone("JailAppOtherUser") {
            JailApp(expanded = null) { state -> state.copy(otherUser = OTHER_USER, app = state.app?.copy(settings = emptyMap())) }
        }
    }

    @Test
    fun givenChangeFailure_whenShownOnAppPage_thenSnackbarSaysWhy() {
        val failure = DemoLoadFailure
        capturePhone("JailAppError${failure.screenshotName}") { JailApp(expanded = null) { it.copy(error = failure) } }
    }

    @Test
    fun givenSettingsNotYetRead_whenShown_thenShowsLoadingIndicator() {
        capturePhone("JailSettingsLoading") { JailSettings(JailSettingsUiState()) }
    }

    @Test
    fun givenLoadFailureOnSettings_whenShown_thenSaysWhy() {
        val failure = DemoLoadFailure
        capturePhone("JailSettingsFailure${failure.screenshotName}") { JailSettings(JailSettingsUiState(failure = failure)) }
    }

    @Test
    fun givenSandboxPartsMissing_whenSettingsShownWhole_thenItemsAndStatusSayWhy() {
        composeTestRule.captureForDevice("phone_whole", WHOLE_PAGE, "JailSettingsNothingInPlace") {
            JailSettings(globalSettings().copy(status = NOTHING_IN_PLACE))
        }
    }

    @Test
    fun givenStatusNotYetKnown_whenSettingsShownWhole_thenStatusIsNeitherGoodNorBad() {
        composeTestRule.captureForDevice("phone_whole", WHOLE_PAGE, "JailSettingsStatusUnknown") {
            JailSettings(globalSettings().copy(status = null))
        }
    }

    @Test
    fun givenChangeFailed_whenSettingsShown_thenSnackbarSaysWhy() {
        capturePhone("JailSettingsError") { JailSettings(globalSettings().copy(error = LoadFailure.NoRoot)) }
    }

    @Test
    fun givenAppsLeftAfterReleasingAll_whenSettingsShown_thenSnackbarCountsThem() {
        capturePhone("JailSettingsNotReleased") { JailSettings(globalSettings().copy(notReleased = 2)) }
    }

    @Test
    fun givenInstalledAppsNotYetRead_whenAdding_thenShowsLoadingIndicator() {
        capturePhone("JailAddAppsLoading") { AddApps(AddAppsUiState()) }
    }

    @Test
    fun givenNoAppMatches_whenAdding_thenSaysSo() {
        capturePhone("JailAddAppsEmpty") { AddApps(AddAppsUiState(apps = emptyList())) }
    }

    @Test
    fun givenNothingChosen_whenAddingOnTablet_thenShownAsCardWithConfirmDisabled() {
        composeTestRule.captureForDevice("tablet", DefaultTestDevices.TABLET.spec, "JailAddApps") {
            AddApps(AddAppsUiState(apps = installedApps()), compact = false)
        }
    }

    private fun capturePhone(name: String, body: @Composable () -> Unit) {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, name, body = body)
    }

    /** One of each message the jail tells in a snackbar, by the name its screenshot takes. */
    private fun messages(): List<Pair<String, JailMessage>> {
        val apps = installedApps()
        val names = apps.map { it.info.label }
        return listOf(
            "Isolated" to JailMessage.Isolated(names.take(2), apps.take(2)),
            "IsolatedPartly" to JailMessage.Isolated(names.take(1), apps.take(1), notIsolated = names.subList(1, 2)),
            "IsolatedPartlyCount" to JailMessage.Isolated(names.take(1), apps.take(1), notIsolated = names.subList(1, 4)),
            "NotIsolated" to JailMessage.NotIsolated(names.take(1), LoadFailure.ModuleOutdated),
            "NotIsolatedCount" to JailMessage.NotIsolated(names.take(3), LoadFailure.Other("timed out")),
            "NotReleased" to JailMessage.NotReleased(names.take(1), LoadFailure.NoRoot),
        )
    }

    private fun installedApps(): List<InstalledApp> {
        val locale = demoLocale()
        return listOf(DemoApp.CHAT, DemoApp.PUZZLE, DemoApp.NEWS, DemoApp.KEYBOARD, DemoApp.NOTES, DemoApp.PHOTO_EDITOR, DemoApp.STEP_COUNTER)
            .map { InstalledApp(it.packageName, it.uid, it.appInfo(locale)) }
            .sortedByName { it.info.label }
    }

    private fun globalSettings() = JailSettingsUiState(
        global = SandboxItem.entries.associateWith { it.default } + (SandboxItem.CLIPBOARD_ACCOUNTS to SandboxMode.OFF),
        status = FULL_STATUS,
        isolated = 3,
    )

    @Composable
    private fun JailSettings(state: JailSettingsUiState) {
        JailSettingsScreen(
            state = state,
            showBack = true,
            onBack = {},
            onRefresh = {},
            onSetOn = { _, _ -> },
            onChoose = { _, _ -> },
            onSetAutoIsolate = {},
            onReleaseAll = {},
            onErrorDismiss = {},
            onAllowNetwork = {},
        )
    }

    @Composable
    private fun AddApps(state: AddAppsUiState, compact: Boolean = true) {
        AddAppsSheet(state = state, query = rememberTextFieldState(), compact = compact, onToggle = {}, onDismiss = {}, onConfirm = {})
    }

    /** Three apps put in the sandbox over the last days, one with settings of its own. */
    private fun isolatedApps(): List<IsolatedApp> {
        val locale = demoLocale()
        val day = 24 * 60 * 60L
        return listOf(
            Triple(DemoApp.WEATHER, clock.todayAt(9, 12) - 2 * day, mapOf(SandboxItem.LOCATION to SandboxMode.COARSE)),
            Triple(
                DemoApp.SHOPPING,
                clock.todayAt(20, 5) - 6 * day,
                mapOf(SandboxItem.CAMERA to SandboxMode.OFF, SandboxItem.NETWORK_RECORD to SandboxMode.ON),
            ),
            Triple(DemoApp.FLASHLIGHT, clock.todayAt(11, 40), mapOf(SandboxItem.NETWORK to SandboxMode.ON)),
        ).map { (app, created, settings) ->
            IsolatedApp(sandboxedApp(app, created, settings), app.appInfo(locale))
        }.sortedByName { it.name }
    }

    @Composable
    private fun Jail(apps: List<IsolatedApp>, status: SandboxStatus = FULL_STATUS) {
        Jail(JailUiState(apps = apps, status = status))
    }

    @Composable
    private fun Jail(state: JailUiState) {
        JailScreen(
            state = state,
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
    private fun JailApp(
        unmet: Map<SandboxItem, Set<SandboxPart>> = emptyMap(),
        expanded: SandboxItem? = SandboxItem.LOCATION,
        state: (JailAppUiState) -> JailAppUiState = { it },
    ) {
        val app = DemoApp.WEATHER
        val settings = mapOf(SandboxItem.LOCATION to SandboxMode.COARSE)
        JailAppScreen(
            state = state(
                JailAppUiState(
                    packageName = app.packageName,
                    app = sandboxedApp(app, clock.todayAt(9, 12) - 2 * 24 * 60 * 60L, settings).copy(unmet = unmet),
                    info = app.appInfo(demoLocale()),
                    requested = setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
                    loading = false,
                ),
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
            onAllowNetwork = {},
            onSetRecording = {},
            onOpenNetwork = { _, _ -> },
            initiallyExpanded = expanded,
        )
    }

    private fun sandboxedApp(app: DemoApp, created: Long, settings: Map<SandboxItem, SandboxMode>) = SandboxedApp(
        uid = app.uid,
        packageName = app.packageName,
        enabled = true,
        created = created,
        settings = settings,
        items = SandboxItem.entries.associateWith { settings[it] ?: it.default },
    )

    /** The language the screenshot is taken in, as the test's qualifiers set it. */
    private fun demoLocale(): Locale = RuntimeEnvironment.getApplication().resources.configuration.locales[0]

    private companion object {
        val FULL_STATUS = SandboxStatus(
            gate = true,
            appOps = true,
            androidIdHook = true,
            appListHook = true,
            uriGrantsHook = true,
            adIdHook = true,
            network = true,
            netRecord = true,
            dnsNames = true,
            traffic = true,
        )
        val NOTHING_IN_PLACE = SandboxStatus(gate = false, appOps = false, androidIdHook = false, appListHook = false, uriGrantsHook = false, adIdHook = false)

        /** An Android user other than the one the app runs as: a work profile. */
        const val OTHER_USER = 10

        /** A phone tall enough for the whole of the global settings, down to the status at their end. */
        const val WHOLE_PAGE = "spec:shape=Normal,width=412,height=2600,unit=dp,dpi=480"
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
