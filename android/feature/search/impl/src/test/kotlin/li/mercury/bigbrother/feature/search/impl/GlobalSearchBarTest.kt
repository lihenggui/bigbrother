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

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import li.mercury.bigbrother.core.designsystem.theme.BigBrotherTheme
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.topicTitle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.Locale
import kotlin.test.assertEquals
import li.mercury.bigbrother.core.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GlobalSearchBarTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "en-rUS-w1000dp-h800dp")
    fun givenSearchFailure_whenDockedPanelOpens_thenShowsFailureAndCanRetry() {
        var retries = 0
        composeTestRule.setContent {
            BigBrotherTheme {
                GlobalSearchBar(
                    state = SearchUiState(query = "camera", failure = LoadFailure.ModuleOutdated),
                    onQueryChange = { _, _ -> },
                    onResultClick = {},
                    onOpenSettings = {},
                    onRetry = { retries++ },
                    settingsBadge = false,
                    textFieldState = rememberTextFieldState("camera"),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.search_hint)).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(UiR.string.old_module_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(UiR.string.retry)).performClick()
        composeTestRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun givenTypedQuery_whenLocaleChanges_thenResolvesTitlesAgainForTheSameText() {
        val locale = mutableStateOf(Locale.US)
        val text = "相机"
        var submitted: Pair<String, Set<Int>>? = null
        composeTestRule.setContent {
            val context = composeTestRule.activity
            val localized = remember(locale.value) {
                val configuration = Configuration(context.resources.configuration).apply { setLocale(locale.value) }
                context.createConfigurationContext(configuration).resources
            }
            CompositionLocalProvider(LocalResources provides localized, LocalConfiguration provides localized.configuration) {
                BigBrotherTheme {
                    GlobalSearchBar(
                        state = SearchUiState(),
                        onQueryChange = { query, ids -> submitted = query to ids },
                        onResultClick = {},
                        onOpenSettings = {},
                        onRetry = {},
                        settingsBadge = false,
                        textFieldState = rememberTextFieldState(text),
                    )
                }
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(text to emptySet<Int>(), submitted)
            locale.value = Locale.SIMPLIFIED_CHINESE
        }

        composeTestRule.runOnIdle {
            assertEquals(text to setOf(DataCategory.SENSORS.title, requireNotNull(topicTitle("camera"))), submitted)
        }
    }
}
