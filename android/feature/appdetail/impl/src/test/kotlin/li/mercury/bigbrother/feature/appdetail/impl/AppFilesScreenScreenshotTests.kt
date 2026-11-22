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

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import li.mercury.bigbrother.core.designsystem.theme.BigBrotherTheme
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun givenLongHistoryLoading_whenItArrives_thenSheetOpensToHalfHeightWithoutReachingFullHeight() {
        var details by mutableStateOf(FileDetails(PHOTO))
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { BigBrotherTheme { Files { it.copy(fileDetails = details) } } }
        val shown = (0 until FRAMES).map { frame ->
            if (frame == LOADED_AT_FRAME) details = FileDetails(PHOTO, periods = longHistory())
            composeTestRule.mainClock.advanceTimeByFrame()
            shownFraction()
        }

        assertEquals(0f, shown[LOADED_AT_FRAME - 1], "The sheet opened before the history loaded")
        assertTrue(shown.max() < 0.6f, "The sheet reached ${shown.max()} of the window while opening")
        // The path sits about 90dp below the top of the sheet, which rests at half the window.
        assertTrue(shown.last() in 0.35f..0.5f, "The sheet came to rest at ${shown.last()} of the window")
    }

    @Test
    fun givenFileDetailsFailed_whenShown_thenSheetSaysWhy() {
        capturePhone("AppFilesDetailsFailure") {
            Files { it.copy(fileDetails = FileDetails(PHOTO, failure = LoadFailure.Operation.DATABASE_BUSY)) }
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

    @Test
    fun givenCameraFolder_whenPredictiveBackCancelsThenCommits_thenOnlyCommitLeavesTheFolder() {
        composeTestRule.setContent { BigBrotherTheme { CameraFolder() } }
        val photo = composeTestRule.onNodeWithText(PHOTO.substringAfterLast('/'))
        photo.assertIsDisplayed()
        val fullBounds = photo.fetchSemanticsNode().boundsInRoot

        swipeBack()
        check(photo.fetchSemanticsNode().boundsInRoot != fullBounds)
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        photo.assertIsDisplayed()
        check(photo.fetchSemanticsNode().boundsInRoot == fullBounds)

        swipeBack()
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        photo.assertDoesNotExist()
        composeTestRule.onNodeWithText("DCIM/Camera").assertIsDisplayed()
    }

    @Test
    fun givenFolderSearch_whenPredictiveBackCancelsThenCommits_thenOnlySearchCloses() {
        val searching = mutableStateOf(true)
        composeTestRule.setContent {
            BigBrotherTheme {
                Files(onCloseSearch = { searching.value = false }) { it.copy(searching = searching.value) }
            }
        }
        val title = composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.files_title))
        val search = composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.search_paths))
        search.assertIsDisplayed()
        title.assertDoesNotExist()

        swipeBack()
        title.assertExists()
        composeTestRule.runOnIdle { check(searching.value) }
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        search.assertIsDisplayed()
        title.assertDoesNotExist()

        swipeBack()
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        title.assertIsDisplayed()
        composeTestRule.onNodeWithText(PHOTO.substringAfterLast('/')).assertIsDisplayed()
    }

    /** An access a day, taller than the window. */
    private fun longHistory() = (1L..40L).map { day ->
        val time = clock.at(LocalDateTime.of(2026, 10, 1, 9, 0).minusDays(day))
        AccessPeriod(Action.READ, viaProvider = false, allowed = 1, denied = 0, time = time)
    }

    /** How much of its window the sheet covers, measured at the path under its title; 0 while it is not shown. */
    private fun shownFraction(): Float {
        val node = composeTestRule.onAllNodesWithText(PHOTO).fetchSemanticsNodes().singleOrNull() ?: return 0f
        val window = node.layoutInfo.coordinates.findRootCoordinates().size.height.toFloat()
        return if (window == 0f) 0f else (window - node.boundsInRoot.top) / window
    }

    private fun swipeBack() {
        val dispatcher = composeTestRule.activity.onBackPressedDispatcher
        composeTestRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(1f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(140f, 400f, 0.65f, BackEventCompat.EDGE_LEFT))
        }
        composeTestRule.waitForIdle()
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
        onCloseSearch: () -> Unit = {},
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
                onCloseSearch = onCloseSearch,
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

        /** Frames until the opening animation has long come to rest. */
        const val FRAMES = 90

        /** The history arrives a few frames after the tap. */
        const val LOADED_AT_FRAME = 6
    }
}
