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

package li.mercury.bigbrother.core.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class BottomSheetSceneStrategyTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun givenContentGrowingWhileSheetOpens_whenShown_thenOpensToHalfHeightWithoutReachingFullHeight() {
        var loaded by mutableStateOf(false)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            NavDisplay(
                backStack = listOf(Page, Record),
                onBack = {},
                sceneStrategies = listOf(BottomSheetSceneStrategy(enabled = true)),
                entryProvider = entryProvider {
                    entry<Page> { Box(Modifier.fillMaxSize()) }
                    entry<Record>(metadata = BottomSheetSceneStrategy.bottomSheet()) {
                        // A loading indicator, then a history taller than the window.
                        Box(Modifier.testTag(SHEET).fillMaxWidth().height(if (loaded) 2000.dp else 120.dp))
                    }
                },
            )
        }
        val shown = (0 until FRAMES).map { frame ->
            if (frame == LOADED_AT_FRAME) loaded = true
            composeTestRule.mainClock.advanceTimeByFrame()
            shownFraction()
        }

        assertTrue(shown.max() < 0.6f, "The sheet reached ${shown.max()} of the window while opening")
        assertTrue(shown.last() in 0.4f..0.55f, "The sheet came to rest at ${shown.last()} of the window")
    }

    /** How much of its window the sheet covers; nothing before the window is laid out. */
    private fun shownFraction(): Float {
        val node = composeTestRule.onNodeWithTag(SHEET).fetchSemanticsNode()
        val window = node.layoutInfo.coordinates.findRootCoordinates().size.height.toFloat()
        return if (window == 0f) 0f else (window - node.boundsInRoot.top) / window
    }

    private data object Page

    private data object Record

    private companion object {
        const val SHEET = "sheet"

        /** Frames until the opening animation has long come to rest. */
        const val FRAMES = 90

        /** The content arrives while the sheet is still on its way up. */
        const val LOADED_AT_FRAME = 6
    }
}
