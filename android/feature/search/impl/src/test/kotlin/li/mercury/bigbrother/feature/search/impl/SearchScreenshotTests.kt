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

package li.mercury.bigbrother.feature.search.impl

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import li.mercury.bigbrother.core.designsystem.theme.BigBrotherTheme
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoApp
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.ui.DataCategory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * The search bar as the top-level screens have it, and the results it
 * shows once opened, for each state of a search.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SearchScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun givenRecordingStopped_whenBarShown_thenSettingsIsMarked() {
        capturePhone("SearchBarSettingsBadge") { Bar(settingsBadge = true) }
        composeTestRule.onNodeWithContentDescription("Settings").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Recording stopped"),
        )
    }

    @Test
    fun givenFilesAndKindOfDataMatch_whenShown_thenGroupedByKind() {
        capturePhone("SearchResults") { Results(cameraResults()) }
    }

    @Test
    fun givenAppMatches_whenShown_thenListsApp() {
        capturePhone("SearchResultsApps") {
            val records = remember { DemoRecords(clock) }
            Results(SearchUiState(query = "news", apps = listOf(records.summary(DemoApp.NEWS)), appInfo = records.appInfo))
        }
    }

    @Test
    fun givenNewTextLookedUp_whenShown_thenKeepsPreviousResultsUnderSearchingLabel() {
        capturePhone("SearchResultsSearching") { Results(cameraResults().copy(complete = false)) }
    }

    @Test
    fun givenFirstSearchRunning_whenShown_thenSaysSearching() {
        capturePhone("SearchSearching") { Results(SearchUiState(complete = false)) }
    }

    @Test
    fun givenNothingMatches_whenShown_thenSaysNoMatches() {
        capturePhone("SearchNoMatches") { Results(SearchUiState(query = "passport")) }
    }

    @Test
    fun givenModuleOutdated_whenSearchFails_thenExplainsWhyAndOffersRetry() {
        capturePhone("SearchFailureModuleOutdated") {
            Results(SearchUiState(query = "camera", failure = LoadFailure.ModuleOutdated))
        }
    }

    @Test
    fun givenLookupFinishing_whenLabelFadesThrough_thenOnlyTheNewLabelIsAnnounced() {
        var results by mutableStateOf(cameraResults().copy(complete = false))
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { BigBrotherTheme { Results(results) } }
        composeTestRule.mainClock.advanceTimeByFrame()

        results = cameraResults()
        // Written outside composition: hand it over now, so that the fade starts on the next frame.
        Snapshot.sendApplyNotifications()
        composeTestRule.mainClock.advanceTimeBy(MID_FADE_MILLIS)

        composeTestRule.onAllNodesWithText(composeTestRule.activity.getString(R.string.searching)).assertCountEquals(0)
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.results))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    private fun capturePhone(name: String, body: @Composable () -> Unit) {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, name, body = body)
    }

    /** What a search for "camera" finds among the demo records: files and a kind of data. */
    private fun cameraResults(): SearchUiState {
        val records = DemoRecords(clock)
        val files = listOf(DemoApp.WEATHER, DemoApp.PHOTO_EDITOR).flatMap { app ->
            records.accesses(app)
                .filter { it.path.contains("camera", ignoreCase = true) && it.path.startsWith("/") }
                .map { PathMatch(app.uid, app.packageName, it) }
        }.take(MAX_FILES)
        return SearchUiState(
            query = "camera",
            files = files,
            data = listOf(DataMatch(DataCategory.SENSORS, topic = "camera", apps = 1)),
            appInfo = records.appInfo,
        )
    }

    /** The results as the expanded search bar shows them, below its field. */
    @Composable
    private fun Results(results: SearchUiState) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                Results(results = results, onResultClick = {}, onRetry = {})
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Bar(settingsBadge: Boolean) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            GlobalSearchBar(
                state = SearchUiState(),
                onQueryChange = { _, _ -> },
                onResultClick = {},
                onRetry = {},
                onOpenSettings = {},
                settingsBadge = settingsBadge,
            )
        }
    }

    private companion object {
        /** Part way through the label's fade, while the old label is still drawn. */
        const val MID_FADE_MILLIS = 50L

        const val MAX_FILES = 6
    }
}
