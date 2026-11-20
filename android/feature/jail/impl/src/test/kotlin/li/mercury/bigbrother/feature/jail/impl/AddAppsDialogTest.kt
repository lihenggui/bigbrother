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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import li.mercury.bigbrother.core.testing.repository.TestInstalledAppRepository
import li.mercury.bigbrother.core.testing.repository.TestSandboxRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AddAppsDialogTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun givenListNotWorkedOutYet_whenTyped_thenFieldKeepsTextAndListFollowsIt() {
        // Runs only when told to: the list is not worked out while typing.
        val filtering = StandardTestDispatcher()
        val sandboxRepository = TestSandboxRepository()
        runBlocking { sandboxRepository.refresh() }
        val viewModel = AddAppsViewModel(
            installedAppRepository = TestInstalledAppRepository(
                apps = listOf(installed(10187, "com.example.weather", "Weather"), installed(10156, "com.example.notes", "Notes")),
            ),
            sandboxRepository = sandboxRepository,
            userDataRepository = TestUserDataRepository(),
            savedStateHandle = SavedStateHandle(),
            ownPackage = "li.mercury.bigbrother",
            defaultDispatcher = filtering,
        )
        composeTestRule.setContent { AddAppsDialog(onDismiss = {}, onIsolate = {}, viewModel = viewModel) }
        filtering.scheduler.advanceUntilIdle()
        composeTestRule.onNodeWithText("Notes").assertExists()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("we")
        composeTestRule.onNode(hasSetTextAction()).performTextInput("a")

        composeTestRule.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("wea")))
        filtering.scheduler.advanceUntilIdle()
        composeTestRule.onNodeWithText("Weather").assertExists()
        composeTestRule.onNodeWithText("Notes").assertDoesNotExist()
    }
}
