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

package li.mercury.bigbrother.feature.overview.impl

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoLoadFailure
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.SearchScaffold
import li.mercury.bigbrother.core.screenshottesting.StoreScreenshotType
import li.mercury.bigbrother.core.screenshottesting.assertSamplesCover
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureMultiDevice
import li.mercury.bigbrother.core.screenshottesting.captureStoreScreenshot
import li.mercury.bigbrother.core.screenshottesting.screenshotName
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import li.mercury.bigbrother.core.ui.isShown
import li.mercury.bigbrother.core.ui.readFiles
import li.mercury.bigbrother.feature.search.impl.GlobalSearchBar
import li.mercury.bigbrother.feature.search.impl.SearchViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OverviewScreenScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenDemoRecords_whenShownInEnglish_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "1_overview") { Overview() }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenDemoRecords_whenShownInChinese_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "1_overview") { Overview() }
    }

    @Test
    fun givenDemoRecords_whenShownOnEachDevice_thenCategoriesFillColumns() {
        composeTestRule.captureMultiDevice("Overview") { Overview() }
    }

    @Test
    fun givenDemoRecords_whenShownInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice(
            deviceName = "phone_dark",
            deviceSpec = DefaultTestDevices.PHONE.spec,
            screenshotName = "Overview",
            darkMode = true,
        ) {
            Overview()
        }
    }

    @Test
    fun givenNoRecords_whenShownOnEachDevice_thenSaysNothingIsRecorded() {
        composeTestRule.captureMultiDevice("OverviewEmpty") { Overview(empty = true) }
    }

    @Test
    fun givenOnlyLookups_whenShown_thenSaysNoAppReadFiles() {
        capturePhone("OverviewLookedOnly") {
            Overview { _ ->
                val records = DemoRecords(clock)
                val shown = records.apps.filter { isShown(it, records.appInfo, showSystemApps = false, ownPackage = "") }
                overviewState(records, shown.filter { !it.readFiles })
            }
        }
    }

    @Test
    fun givenFirstLoadRunning_whenShown_thenShowsLoadingIndicator() {
        capturePhone("OverviewLoading") { Overview { OverviewUiState() } }
    }

    @Test
    fun givenRefreshing_whenShown_thenShowsRefreshIndicatorOverRecords() {
        capturePhone("OverviewRefreshing") { Overview { it.copy(refreshing = true) } }
    }

    @Test
    fun givenLoadFailure_whenShown_thenSaysWhy() {
        val failure = DemoLoadFailure
        capturePhone("OverviewFailure${failure.screenshotName}") { Overview { OverviewUiState(failure = failure) } }
    }

    @Test
    fun givenModuleMissing_whenEachSetupStepShown_thenMatchesGolden() {
        for (setup in MODULE_SETUPS) {
            capturePhone("OverviewModuleSetup${setup.screenshotName}") {
                Overview { OverviewUiState(failure = LoadFailure.ModuleMissing, moduleSetup = setup) }
            }
        }
    }

    @Test
    fun givenDaemonStopped_whenShown_thenOffersToStartIt() {
        capturePhone("OverviewStopped") { Overview { it.copy(recording = false) } }
    }

    @Test
    fun givenDaemonStarting_whenShown_thenShowsProgress() {
        capturePhone("OverviewStoppedStarting") { Overview { it.copy(recording = false, daemonStarting = true) } }
    }

    @Test
    fun givenEachStartFailure_whenShown_thenSaysWhy() {
        for (failure in START_FAILURES) {
            capturePhone("OverviewStopped${failure.screenshotName}") {
                Overview { it.copy(recording = false, daemonStartError = failure) }
            }
        }
    }

    @Test
    fun givenUpdateWaitingForRestart_whenShown_thenAsksForRestart() {
        capturePhone("OverviewPendingReboot") { Overview { it.copy(modulePendingReboot = true, recording = false) } }
    }

    @Test
    fun givenEachModuleUpdateStep_whenShown_thenMatchesGolden() {
        for (update in MODULE_UPDATES) {
            capturePhone("OverviewModuleUpdate${update.screenshotName}") { Overview { it.copy(moduleUpdate = update) } }
        }
    }

    @Test
    fun givenModuleSetupCases_whenChecked_thenEveryStepHasOne() {
        assertSamplesCover(ModuleSetup::class.java, MODULE_SETUPS)
    }

    @Test
    fun givenStartFailureCases_whenChecked_thenEveryKindHasOne() {
        assertSamplesCover(StartFailure::class.java, START_FAILURES)
    }

    @Test
    fun givenModuleUpdateCases_whenChecked_thenEveryStepHasOne() {
        // Idle is the overview without a card, as in every other case.
        assertSamplesCover(ModuleUpdate::class.java, MODULE_UPDATES, excluded = setOf(ModuleUpdate.Idle::class.java))
    }

    private fun capturePhone(name: String, body: @Composable () -> Unit) {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, name, body = body)
    }

    /** The overview of the demo records, or of none, with the search bar it has in the app. */
    @Composable
    private fun Overview(empty: Boolean = false) {
        Overview { state -> if (empty) overviewState(DemoRecords(clock), emptyList()) else state }
    }

    /** The overview in the state [state] makes of the demo records' overview, with the app's search bar. */
    @Composable
    private fun Overview(state: (OverviewUiState) -> OverviewUiState) {
        val records = remember { DemoRecords(clock) }
        val shown = remember(records) {
            records.apps.filter { isShown(it, records.appInfo, showSystemApps = false, ownPackage = "") }
        }
        SearchScaffold(topBar = { SearchBar() }) {
            OverviewScreen(
                state = remember(records) { state(overviewState(records, shown)) },
                onRange = {},
                onRefresh = {},
                onOpenCategory = {},
                onInstallModule = {},
                onUpdateModule = {},
                onStartDaemon = {},
            )
        }
    }

    private fun overviewState(records: DemoRecords, apps: List<AppSummary>): OverviewUiState {
        val (files, categories) = overviewOf(apps)
        return OverviewUiState(
            range = TimeRange.DAY,
            files = files,
            categories = categories,
            appInfo = records.appInfo,
            recording = true,
        )
    }

    private companion object {
        /** What Magisk prints when an installation fails: progress, then the errors marked with "!". */
        const val INSTALLER_OUTPUT = "- Copying zip to temp directory\n- Installing big_brother.zip\n! Unsupported kernel: no BTF\n! Installation failed"

        val MODULE_SETUPS = listOf(
            ModuleSetup.Idle,
            ModuleSetup.Installing,
            ModuleSetup.NeedsReboot,
            ModuleSetup.Failed(INSTALLER_OUTPUT),
        )

        val START_FAILURES = listOf(
            StartFailure.ModuleDisabled,
            StartFailure.NotReporting,
            StartFailure.Failed("bbd: loading the eBPF programs failed: Operation not permitted"),
        )

        val MODULE_UPDATES = listOf(
            ModuleUpdate.Updating,
            ModuleUpdate.Disabled,
            ModuleUpdate.NotReporting,
            ModuleUpdate.Failed(INSTALLER_OUTPUT),
        )
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
