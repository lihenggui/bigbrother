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

package li.mercury.bigbrother.feature.appdetail.impl

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.screenshottesting.DefaultTestDevices
import li.mercury.bigbrother.core.screenshottesting.DemoApp
import li.mercury.bigbrother.core.screenshottesting.DemoClockRule
import li.mercury.bigbrother.core.screenshottesting.DemoLoadFailure
import li.mercury.bigbrother.core.screenshottesting.DemoRecords
import li.mercury.bigbrother.core.screenshottesting.StoreScreenshotType
import li.mercury.bigbrother.core.screenshottesting.captureForDevice
import li.mercury.bigbrother.core.screenshottesting.captureStoreScreenshot
import li.mercury.bigbrother.core.screenshottesting.screenshotName
import li.mercury.bigbrother.core.ui.LocalWallClock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppFilesScreenScreenshotTests {
    @get:Rule(order = 0)
    val clock = DemoClockRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "en-rUS")
    fun givenPhotosRead_whenFolderShownInEnglish_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "4_files") { CameraFolder() }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun givenPhotosRead_whenFolderShownInChinese_thenMatchesStoreScreenshot() {
        composeTestRule.captureStoreScreenshot(StoreScreenshotType.PHONE, "4_files") { CameraFolder() }
    }

    @Test
    fun givenPhotosRead_whenFolderShownInDarkTheme_thenMatchesGolden() {
        composeTestRule.captureForDevice(
            deviceName = "phone_dark",
            deviceSpec = DefaultTestDevices.PHONE.spec,
            screenshotName = "AppFiles",
            darkMode = true,
        ) {
            CameraFolder()
        }
    }

    @Test
    fun givenFilesRead_whenOpenedAtTop_thenListsFoldersAndMediaLibrary() {
        capturePhone("AppFilesRoot") { Files(location = null) }
    }

    @Test
    fun givenFilesOpened_whenLoading_thenShowsLoadingIndicator() {
        capturePhone("AppFilesLoading") { Files { it.copy(browser = null) } }
    }

    @Test
    fun givenLoadFailure_whenShown_thenSaysWhy() {
        val failure = DemoLoadFailure
        capturePhone("AppFilesFailure${failure.screenshotName}") { Files { it.copy(browser = null, failure = failure) } }
    }

    @Test
    fun givenNoFileRecords_whenShown_thenSaysNothingIsRecorded() {
        capturePhone("AppFilesEmpty") { Files(location = null) { it.copy(browser = fileBrowserOf(emptyList())) } }
    }

    @Test
    fun givenOnlyLookups_whenShown_thenListsWhatWasLookedFor() {
        capturePhone("AppFilesLookedOnly") { Files(DemoApp.FLASHLIGHT, location = null) }
    }

    @Test
    fun givenMoreRecordsThanShown_whenShown_thenSaysListIsCut() {
        capturePhone("AppFilesTruncated") { Files(DemoApp.NOTES, location = null) { it.copy(truncated = true) } }
    }

    @Test
    fun givenFileDetailsOpening_whenShown_thenSheetShowsLoadingIndicator() {
        capturePhone("AppFilesDetailsLoading") { Files { it.copy(fileDetails = FileDetails(PHOTO)) } }
    }

    @Test
    fun givenFileDetailsFailed_whenShown_thenSheetSaysWhy() {
        capturePhone("AppFilesDetailsFailure") {
            Files { it.copy(fileDetails = FileDetails(PHOTO, failure = LoadFailure.Other("database is locked"))) }
        }
    }

    @Test
    fun givenFileDetails_whenShown_thenSheetListsEachAccess() {
        capturePhone("AppFilesDetails") {
            // The sheet shows the dates: fixed ones, not the day of the test.
            val periods = listOf(
                AccessPeriod(Action.READ, viaProvider = false, allowed = 1, denied = 0, time = clock.at(LocalDateTime.of(2026, 10, 5, 14, 41))),
                AccessPeriod(Action.READ, viaProvider = false, allowed = 1, denied = 0, time = clock.at(LocalDateTime.of(2026, 10, 5, 8, 30))),
            )
            Files { it.copy(fileDetails = FileDetails(PHOTO, periods = periods)) }
        }
    }

    private fun capturePhone(name: String, body: @Composable () -> Unit) {
        composeTestRule.captureForDevice("phone", DefaultTestDevices.PHONE.spec, name, body = body)
    }

    /** The weather app's files, open at the camera folder, as from its page. */
    @Composable
    private fun CameraFolder() {
        Files()
    }

    /**
     * [app]'s files, open at [location] as from its page, in the state
     * [state] makes of them.
     */
    @Composable
    private fun Files(
        app: DemoApp = DemoApp.WEATHER,
        location: String? = CAMERA,
        state: (AppFilesUiState) -> AppFilesUiState = { it },
    ) {
        val records = remember { DemoRecords(clock) }
        CompositionLocalProvider(LocalWallClock provides clock.shownClock) {
            AppFilesScreen(
                state = state(
                    AppFilesUiState(
                        packageName = app.packageName,
                        appInfo = records.appInfo.getValue(app.packageName),
                        browser = fileBrowserOf(records.accesses(app).filter { it.isFile() }),
                    ),
                ),
                uid = app.uid,
                initialLocation = location,
                onBack = {},
                onRetry = {},
                onOpenSearch = {},
                onCloseSearch = {},
                onQueryChange = {},
                onReadOnly = {},
                onFileDetails = {},
                onDismissFileDetails = {},
                onOpenFile = {},
            )
        }
    }

    private companion object {
        const val CAMERA = "/storage/emulated/0/DCIM/Camera"
        const val PHOTO = "$CAMERA/PXL_20261005_081133.jpg"
    }
}
