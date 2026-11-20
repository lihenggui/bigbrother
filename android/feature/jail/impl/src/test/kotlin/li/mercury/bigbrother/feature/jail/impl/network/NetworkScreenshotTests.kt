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

package li.mercury.bigbrother.feature.jail.impl.network

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.model.ActivityBucket
import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.DestinationDetail
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.PortActivity
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxPart
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoApp
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.feature.jail.impl.JailAppScreen
import li.mercury.bigbrother.feature.jail.impl.JailAppUiState
import li.mercury.bigbrother.feature.jail.impl.JailSettingsScreen
import li.mercury.bigbrother.feature.jail.impl.JailSettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NetworkScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Long get() = clock.todayAt(15, 0)

    // The app's page.

    @Test
    fun givenRecordingApp_whenPageShown_thenNetworkSectionAfterItems() {
        capture("JailAppNetwork", "phone_whole", WHOLE_PAGE) { App() }
    }

    @Test
    fun givenRecordingApp_whenPageShownInDarkTheme_thenMatchesGolden() {
        capture("JailAppNetwork", "phone_whole_dark", WHOLE_PAGE, dark = true) { App() }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenRecordingApp_whenPageShownInChinese_thenMatchesGolden() {
        capture("JailAppNetwork", "phone_whole_zh", WHOLE_PAGE) { App() }
    }

    @Test
    fun givenAppKeptFromNetwork_whenPageShown_thenSwitchOffWithChoicesOpen() {
        capture("JailAppNoNetwork", "phone_whole", WHOLE_PAGE) {
            App(DemoApp.FLASHLIGHT, settings = mapOf(SandboxItem.NETWORK to SandboxMode.ON), rules = 0, recordingSince = null, expanded = SandboxItem.NETWORK)
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenPacketFilterMissing_whenPageShownInChinese_thenSaysNotInEffect() {
        capture("JailAppNetworkNotInEffect", "phone_whole_zh", WHOLE_PAGE) {
            App(
                DemoApp.FLASHLIGHT,
                settings = mapOf(SandboxItem.NETWORK to SandboxMode.ON),
                rules = 0,
                recordingSince = null,
                unmet = mapOf(SandboxItem.NETWORK to setOf(SandboxPart.PACKET_FILTER)),
            )
        }
    }

    @Test
    fun givenAppWithoutInternetPermission_whenPageShown_thenSectionSaysSo() {
        capture("JailAppNoInternetPermission", "phone_whole", WHOLE_PAGE) {
            App(DemoApp.FLASHLIGHT, settings = emptyMap(), rules = 0, recordingSince = null, requested = setOf("android.permission.CAMERA"))
        }
    }

    @Test
    fun givenGlobalSettings_whenShownWhole_thenNetworkGroupBeforeStatus() {
        capture("JailSettingsNetwork", "phone_whole", "spec:shape=Normal,width=412,height=2800,unit=dp,dpi=480") {
            JailSettingsScreen(
                state = JailSettingsUiState(
                    global = SandboxItem.entries.associateWith { it.default },
                    status = SandboxStatus(
                        gate = true,
                        appOps = true,
                        androidIdHook = true,
                        appListHook = true,
                        uriGrantsHook = true,
                        adIdHook = true,
                        network = false,
                    ),
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
    @Config(qualifiers = "en-rUS")
    fun givenInteractiveChart_whenUsingAccessibilityKeyboardAndTouch_thenEachCanSelectAPeriod() {
        val periods = listOf(ActivityBucket(3600, 1, 0), ActivityBucket(7200, 2, 1))
        val selections = mutableListOf<Long?>()
        lateinit var inputMode: InputModeManager
        composeTestRule.setContent {
            inputMode = LocalInputModeManager.current
            val selection = remember { mutableStateOf<Long?>(null) }
            ActivityChart(periods, 3600, ZoneId.of("UTC"), selection.value, { start ->
                selection.value = start
                selections.add(start)
            })
        }
        val chart = composeTestRule.onNodeWithContentDescription("Choose a period")
        composeTestRule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        chart.performSemanticsAction(SemanticsActions.RequestFocus)
        chart.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        composeTestRule.onNodeWithText("All periods").assertIsSelected()
        composeTestRule.onNodeWithText("2 connections · 1 refused").performClick()
        chart.performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText("2 connections · 1 refused").assertIsSelected()
        composeTestRule.onNodeWithText("All periods").performClick()
        chart.performTouchInput { click(centerLeft) }
        composeTestRule.onNodeWithText("All periods").assertDoesNotExist()
        composeTestRule.runOnIdle { assertEquals(listOf(7200L, null, 3600L), selections) }
    }

    // The activity.

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenDailyChart_whenZoneAndLocaleDiffer_thenLabelsUseChartZoneAndComposeLocale() {
        val zone = ZoneId.of("America/Los_Angeles")
        val first = Instant.parse("2026-03-08T08:00:00Z").epochSecond
        val next = Instant.parse("2026-03-09T07:00:00Z").epochSecond
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
        try {
            composeTestRule.setContent {
                MaterialTheme {
                    Column {
                        ActivityChart(
                            buckets = listOf(ActivityBucket(first, 1, 0), ActivityBucket(next, 2, 0)),
                            bucketSeconds = BucketSize.DAY.seconds,
                            zone = zone,
                            selected = first,
                            onSelect = null,
                        )
                        Text(periodLabel(first, BucketSize.DAY.seconds, zone))
                        val chinese = Configuration(LocalConfiguration.current).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
                        CompositionLocalProvider(LocalConfiguration provides chinese) {
                            Text(periodLabel(first, BucketSize.DAY.seconds, zone))
                        }
                    }
                }
            }

            composeTestRule.onAllNodesWithText("3月8日").assertCountEquals(1)
            composeTestRule.onAllNodesWithText("Mar 8").assertCountEquals(2)
            composeTestRule.onAllNodesWithText("Mar 9").assertCountEquals(1)
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun givenDayOfActivity_whenShown_thenSummaryChartAndGroupedLists() {
        capture("NetworkActivity", "phone") { Activity(shopState()) }
    }

    @Test
    fun givenDayOfActivity_whenShownInDarkTheme_thenMatchesGolden() {
        capture("NetworkActivity", "phone_dark", dark = true) { Activity(shopState()) }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenDayOfActivity_whenShownInChinese_thenMatchesGolden() {
        capture("NetworkActivity", "phone_zh") { Activity(shopState()) }
    }

    @Test
    fun givenDayOfActivity_whenShownWhole_thenEveryPartDown_toTheFooter() {
        capture("NetworkActivity", "phone_whole", ACTIVITY_WHOLE) { Activity(shopState()) }
    }

    @Test
    fun givenDayOfActivity_whenShownOnFoldable_thenMatchesGolden() {
        capture("NetworkActivity", "foldable", DefaultTestDevices.FOLDABLE.spec) { Activity(shopState()) }
    }

    @Test
    fun givenDestinationOpen_whenShownOnTablet_thenDetailBesideActivity() {
        capture("NetworkActivity", "tablet", DefaultTestDevices.TABLET.spec) {
            CompositionLocalProvider(LocalShowsDetailBesideList provides true) {
                Row {
                    Box(Modifier.weight(1f)) { Activity(shopState(), selectedTarget = "domain:metrics.example") }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(360.dp).fillMaxHeight()) {
                        Destination(metricsState())
                    }
                }
            }
        }
    }

    @Test
    fun givenHourChosen_whenShown_thenListHasThatHourOnly() {
        capture("NetworkActivityBucketSelected", "phone") {
            val day = shopActivity(now)
            Activity(shopState().copy(bucket = day.buckets[12].start, period = shopNight(now)).withShown())
        }
    }

    @Test
    fun givenBlockedFilter_whenShown_thenEveryRuleIncludingThoseWithoutAttempts() {
        capture("NetworkActivityBlockedOnly", "phone") {
            val state = shopState()
            Activity(state.copy(blockedOnly = true, blockedShown = state.activity!!.rules.map { it.target }.toSet()).withShown())
        }
    }

    @Test
    fun givenRecordingJustStarted_whenShown_thenSaysSinceWhenWithoutChart() {
        capture("NetworkActivityEmpty", "phone") { Activity(state(emptyActivity(now))) }
    }

    @Test
    fun givenRecordingStopped_whenShown_thenSaysUpToWhenAndOffersToRecord() {
        capture("NetworkActivityStopped", "phone") {
            val activity = shopActivity(now)
            Activity(state(activity.copy(recording = activity.recording.copy(on = false))))
        }
    }

    @Test
    fun givenAppKeptFromNetwork_whenShown_thenAttemptsRefusedAsAddresses() {
        capture("NetworkActivityNoNetwork", "phone") { Activity(state(flashlightActivity(now), DemoApp.FLASHLIGHT)) }
    }

    @Test
    fun givenNamesUnavailable_whenShown_thenSaysSoAndListsAddresses() {
        capture("NetworkActivityNamesMissing", "phone") {
            val activity = shopActivity(now)
            Activity(state(activity.copy(names = false, domains = emptyList())))
        }
    }

    @Test
    fun givenTrafficNotCounted_whenShown_thenConnectionCounts() {
        capture("NetworkActivityCountsOnly", "phone") { Activity(state(shopActivity(now, traffic = false))) }
    }

    @Test
    fun givenActivityBeingRead_whenShown_thenOnlyRangeAndIndicator() {
        capture("NetworkActivityLoading", "phone") { Activity(NetworkActivityUiState(packageName = DemoApp.SHOPPING.packageName, info = info(DemoApp.SHOPPING))) }
    }

    @Test
    fun givenModuleWithoutNetCommands_whenShown_thenModuleUpdateNeeded() {
        capture("NetworkActivityFailureModuleOutdated", "phone") {
            Activity(NetworkActivityUiState(packageName = DemoApp.SHOPPING.packageName, failure = LoadFailure.ModuleOutdated))
        }
    }

    @Test
    fun givenSearchWithoutMatch_whenShown_thenSaysSo() {
        capture("NetworkActivityNoResults", "phone") {
            Activity(shopState().copy(query = "track.example.org").withShown(), searching = true)
        }
    }

    @Test
    fun givenBlockDialog_whenShown_thenFieldWithWhatItTakes() {
        capture("BlockTargetDialog", "phone") {
            Activity(shopState(), dialog = NetworkDialog.BLOCK)
        }
    }

    @Test
    fun givenBlocked_whenShown_thenSnackbarOffersUndo() {
        // Whole, so that the snackbar does not cover half a row.
        capture("NetworkActivityBlocked", "phone_whole", ACTIVITY_WHOLE) {
            val target = NetworkTarget.Domain("cdn.shopcdn.example", exact = true)
            val state = shopState()
            Activity(state.copy(activity = state.activity!!.withRule(target, true, now), message = NetworkMessage.Blocked(target)).withShown())
        }
    }

    // A destination.

    @Test
    fun givenDomain_whenDetailShown_thenActionLimitsStatsAndParts() {
        capture("Destination", "phone") { Sheet { Destination(metricsState()) } }
    }

    @Test
    fun givenDomain_whenDetailShownInDarkTheme_thenMatchesGolden() {
        capture("Destination", "phone_dark", dark = true) { Sheet { Destination(metricsState()) } }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenDomain_whenDetailShownInChinese_thenMatchesGolden() {
        capture("Destination", "phone_zh") { Sheet { Destination(metricsState()) } }
    }

    @Test
    fun givenDomainSharingAddresses_whenDetailShown_thenNamesThoseBlockedToo() {
        capture("DestinationSharedAddress", "phone") {
            Sheet { Destination(metricsState().copy(ownResolver = false)) }
        }
    }

    @Test
    fun givenAddress_whenDetailShown_thenEachPortBlockable() {
        capture("DestinationAddressPorts", "phone") {
            val address = address("198.51.100.53", connections = 41, sent = 6 * 1024L, received = 46 * 1024L, port = 8443, last = now - 200).let {
                it.copy(
                    ports = it.ports + PortActivity(443, 9, 0, 2_048, 14_336, null),
                    first = now - 5 * 3_600,
                )
            }
            Sheet {
                Destination(
                    DestinationUiState(
                        target = address.target,
                        detail = DestinationDetail(address, shopActivity(now).buckets.map { it.copy(connections = it.connections / 8, refused = 0) }),
                    ),
                )
            }
        }
    }

    private fun capture(name: String, device: String, spec: String = DefaultTestDevices.PHONE.spec, dark: Boolean = false, body: @Composable () -> Unit) {
        composeTestRule.captureForDevice(device, spec, name, darkMode = dark, body = body)
    }

    private fun info(app: DemoApp) = app.appInfo(locale())

    private fun state(activity: NetworkActivity, app: DemoApp = DemoApp.SHOPPING) = NetworkActivityUiState(
        packageName = app.packageName,
        info = info(app),
        activity = activity,
    ).withShown()

    private fun shopState() = state(shopActivity(now))

    private fun metricsState(): DestinationUiState {
        val activity = shopActivity(now)
        val metrics = activity.domains.single { it.target == NetworkTarget.Domain("metrics.example") }
        return DestinationUiState(
            target = metrics.target,
            detail = DestinationDetail(metrics, activity.buckets.mapIndexed { index, bucket -> bucket.copy(connections = bucket.connections / 6 + if (index % 5 == 0) 1 else 0, refused = 0) }),
            sharedWith = activity.sharingAddressesWith(metrics),
            ownResolver = activity.addresses.isNotEmpty(),
        )
    }

    @Composable
    private fun Activity(
        state: NetworkActivityUiState,
        selectedTarget: String? = null,
        searching: Boolean = false,
        dialog: NetworkDialog? = null,
    ) {
        NetworkActivityScreen(
            state = state,
            selectedTarget = selectedTarget,
            actions = NetworkActivityActions(),
            initiallySearching = searching,
            initialDialog = dialog,
        )
    }

    @Composable
    private fun Destination(state: DestinationUiState) {
        DestinationScreen(
            state = state,
            onClose = {},
            onRetry = {},
            onBlock = {},
            onUnblock = {},
            onUndo = {},
            onMessageDismiss = {},
            onErrorDismiss = {},
        )
    }

    /** The detail as the bottom sheet shows it: on the sheet's surface. */
    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { content() }
    }

    @Composable
    private fun App(
        app: DemoApp = DemoApp.SHOPPING,
        settings: Map<SandboxItem, SandboxMode> = mapOf(SandboxItem.NETWORK_RECORD to SandboxMode.ON),
        rules: Int = 3,
        recordingSince: Long? = now - 6 * 3_600 - 1_680,
        unmet: Map<SandboxItem, Set<SandboxPart>> = emptyMap(),
        requested: Set<String> = setOf("android.permission.INTERNET", "android.permission.CAMERA"),
        expanded: SandboxItem? = null,
    ) {
        JailAppScreen(
            state = JailAppUiState(
                packageName = app.packageName,
                app = SandboxedApp(
                    uid = app.uid,
                    packageName = app.packageName,
                    enabled = true,
                    created = now - 6 * 86_400,
                    settings = settings,
                    items = SandboxItem.entries.associateWith { settings[it] ?: it.default },
                    unmet = unmet,
                    networkRules = rules,
                    recordingSince = recordingSince,
                ),
                info = info(app),
                requested = requested,
                destinations = 38,
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
            onAllowNetwork = {},
            onSetRecording = {},
            onOpenNetwork = { _, _ -> },
            initiallyExpanded = expanded,
        )
    }

    private fun locale(): Locale = RuntimeEnvironment.getApplication().resources.configuration.locales[0]

    private companion object {
        /** Tall enough for an app's page down to the end of the network section. */
        const val WHOLE_PAGE = "spec:shape=Normal,width=412,height=1500,unit=dp,dpi=480"

        /** Tall enough for the activity down to its footer. */
        const val ACTIVITY_WHOLE = "spec:shape=Normal,width=412,height=2200,unit=dp,dpi=480"
    }
}
