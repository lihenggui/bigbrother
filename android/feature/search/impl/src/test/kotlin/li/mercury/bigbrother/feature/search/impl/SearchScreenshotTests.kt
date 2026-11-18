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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoApp
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
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
    }

    @Test
    fun givenFilesAndKindOfDataMatch_whenShown_thenGroupedByKind() {
        capturePhone("SearchResults") { Results(cameraResults()) }
    }

    @Test
    fun givenAppMatches_whenShown_thenListsApp() {
        capturePhone("SearchResultsApps") {
            val records = remember { DemoRecords(clock) }
            Results(SearchResults(query = "news", apps = listOf(records.summary(DemoApp.NEWS)), appInfo = records.appInfo))
        }
    }

    @Test
    fun givenNewTextLookedUp_whenShown_thenKeepsPreviousResultsUnderSearchingLabel() {
        capturePhone("SearchResultsSearching") { Results(cameraResults().copy(complete = false)) }
    }

    @Test
    fun givenFirstSearchRunning_whenShown_thenSaysSearching() {
        capturePhone("SearchSearching") { Results(SearchResults(complete = false)) }
    }

    @Test
    fun givenNothingMatches_whenShown_thenSaysNoMatches() {
        capturePhone("SearchNoMatches") { Results(SearchResults(query = "passport")) }
    }

    private fun capturePhone(name: String, body: @Composable () -> Unit) {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, name, body = body)
    }

    /** What a search for "camera" finds among the demo records: files and a kind of data. */
    private fun cameraResults(): SearchResults {
        val records = DemoRecords(clock)
        val files = listOf(DemoApp.WEATHER, DemoApp.PHOTO_EDITOR).flatMap { app ->
            records.accesses(app)
                .filter { it.path.contains("camera", ignoreCase = true) && it.path.startsWith("/") }
                .map { PathMatch(app.uid, app.packageName, it) }
        }.take(MAX_FILES)
        return SearchResults(
            query = "camera",
            files = files,
            data = listOf(DataMatch(DataCategory.SENSORS, topic = "camera", apps = 1)),
            appInfo = records.appInfo,
        )
    }

    /** The results as the expanded search bar shows them, below its field. */
    @Composable
    private fun Results(results: SearchResults) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                Results(results = results, onOpenApp = { _, _ -> }, onOpenCategory = {})
            }
        }
    }

    @Composable
    private fun Bar(settingsBadge: Boolean) {
        val viewModel = remember {
            SearchViewModel(
                context = ApplicationProvider.getApplicationContext(),
                accessRepository = TestAccessRepository(),
                appInfoRepository = TestAppInfoRepository(),
                userDataRepository = TestUserDataRepository(),
            )
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            GlobalSearchBar(
                onOpenApp = { _, _, _ -> },
                onOpenCategory = {},
                onOpenSettings = {},
                settingsBadge = settingsBadge,
                viewModel = viewModel,
            )
        }
    }

    private companion object {
        const val MAX_FILES = 6
    }
}
