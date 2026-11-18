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
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoApp
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoLoadFailure
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureMultiDevice
import li.mercury.bigbrother.core.screenshottesting.screenshotName
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.dataCategories
import li.mercury.bigbrother.core.ui.isShown
import li.mercury.bigbrother.core.ui.readFiles
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CategoryScreenScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun givenAppsThatReadAndLookedForFiles_whenShownOnEachDevice_thenGroupedByWhetherTheyRead() {
        composeTestRule.captureMultiDevice("CategoryFiles") { Category(DataCategory.FILES, selected = DemoApp.WEATHER) }
    }

    @Test
    fun givenAppsThatAccessedDeviceIds_whenShown_thenListedWithWhatEachAccessed() {
        capturePhone("CategoryDeviceIds") { Category(DataCategory.DEVICE_IDS) }
    }

    @Test
    fun givenNoAppInCategory_whenShown_thenSaysNothingIsRecorded() {
        capturePhone("CategoryEmpty") { Category(CategoryUiState(DataCategory.FILES, apps = emptyList())) }
    }

    @Test
    fun givenFirstLoadRunning_whenShown_thenShowsLoadingIndicator() {
        capturePhone("CategoryLoading") { Category(CategoryUiState(DataCategory.FILES)) }
    }

    @Test
    fun givenLoadFailure_whenShown_thenSaysWhy() {
        val failure = DemoLoadFailure
        capturePhone("CategoryFailure${failure.screenshotName}") {
            // As the view model leaves it after a failed load.
            Category(CategoryUiState(DataCategory.FILES, apps = emptyList(), failure = failure))
        }
    }

    private fun capturePhone(name: String, body: @Composable () -> Unit) {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, name, body = body)
    }

    /** The demo apps that accessed [category], ordered as the view model orders them. */
    @Composable
    private fun Category(category: DataCategory, selected: DemoApp? = null) {
        val records = remember { DemoRecords(clock) }
        val apps = records.apps
            .filter { isShown(it, records.appInfo, showSystemApps = false, ownPackage = "") }
            .filter { category in it.dataCategories() }
            .sortedWith(
                compareByDescending<AppSummary> { category == DataCategory.FILES && it.readFiles }
                    .thenByDescending { it.lastSeen },
            )
        Category(CategoryUiState(category, apps = apps, appInfo = records.appInfo), selected?.uid)
    }

    @Composable
    private fun Category(state: CategoryUiState, selectedUid: Int? = null) {
        CategoryScreen(state = state, selectedUid = selectedUid, onBack = {}, onRetry = {}, onOpenApp = { _, _ -> })
    }
}
