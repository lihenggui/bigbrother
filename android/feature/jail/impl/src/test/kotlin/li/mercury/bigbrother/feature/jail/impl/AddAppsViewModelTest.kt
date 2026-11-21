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

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.domain.GetAppVisibilityUseCase
import li.mercury.bigbrother.core.model.UserData
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestInstalledAppRepository
import li.mercury.bigbrother.core.testing.repository.TestSandboxRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AddAppsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val installedAppRepository = TestInstalledAppRepository(
        apps = listOf(
            installed(10187, "com.example.weather", "Weather"),
            installed(10156, "com.example.notes", "Notes"),
            installed(10204, "com.example.shopping", "Shopping"),
            installed(10031, "com.example.system.backup", "Backup", isSystem = true),
            installed(10300, OWN_PACKAGE, "Big Brother"),
            // Shared by the system's own components: never offered.
            installed(1000, "android", "Android System", isSystem = true),
        ),
    )
    private val sandboxRepository = TestSandboxRepository(
        apps = listOf(
            sandboxed(10204, "com.example.shopping", enabled = true),
            sandboxed(10187, "com.example.weather", enabled = false),
        ),
    )

    private suspend fun viewModel(
        showSystemApps: Boolean = false,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        sandboxLoaded: Boolean = true,
    ): AddAppsViewModel {
        if (sandboxLoaded) sandboxRepository.refresh()
        return AddAppsViewModel(
            installedAppRepository = installedAppRepository,
            sandboxRepository = sandboxRepository,
            getAppVisibility = GetAppVisibilityUseCase(TestUserDataRepository(UserData(showSystemApps = showSystemApps)), OWN_PACKAGE),
            savedStateHandle = savedStateHandle,
            defaultDispatcher = mainDispatcherRule.testDispatcher,
        )
    }

    @Test
    fun givenInstalledApps_whenListed_thenIsolatedOwnAndSystemAppsLeftOutAndRestByName() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        assertEquals(listOf("Notes", "Weather"), viewModel.uiState.value.apps?.map { it.info.label })
    }

    @Test
    fun givenSystemAppsShown_whenListed_thenSystemAppsOffered() = runTest {
        val viewModel = viewModel(showSystemApps = true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        assertEquals(listOf("Backup", "Notes", "Weather"), viewModel.uiState.value.apps?.map { it.info.label })
    }

    @Test
    fun givenQuery_whenSearched_thenNameOrPackageMatches() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.setQuery("WEATH")
        assertEquals(listOf("Weather"), viewModel.uiState.value.apps?.map { it.info.label })

        viewModel.setQuery("example.notes")
        assertEquals(listOf("Notes"), viewModel.uiState.value.apps?.map { it.info.label })
    }

    @Test
    fun givenAppsChosen_whenCleared_thenNothingChosenOrSearched() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.toggle("com.example.notes")
        viewModel.toggle("com.example.weather")
        viewModel.toggle("com.example.weather")
        viewModel.setQuery("notes")

        assertEquals(listOf("com.example.notes"), viewModel.chosen().map { it.packageName })

        viewModel.clear()

        assertTrue(viewModel.chosen().isEmpty())
        assertEquals(listOf("Notes", "Weather"), viewModel.uiState.value.apps?.map { it.info.label })
    }

    @Test
    fun givenSavedSelection_whenRecreated_thenChoicesReturnAndClearTogether() = runTest {
        val saved = SavedStateHandle()
        val original = viewModel(savedStateHandle = saved)
        original.toggle("com.example.notes")
        original.toggle("com.example.weather")

        assertEquals(listOf(listOf("com.example.notes", "com.example.weather")), saved.keys().map { saved.get<Any>(it) })
        val restoredState = saved.restored()
        val restored = viewModel(savedStateHandle = restoredState)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.uiState.collect {} }
        restored.setQuery("weather")

        assertEquals(setOf("com.example.notes", "com.example.weather"), restored.uiState.value.selected)
        assertEquals(listOf("Weather"), restored.uiState.value.apps?.map { it.info.label })
        assertEquals(setOf("com.example.notes", "com.example.weather"), restored.chosen().map { it.packageName }.toSet())

        restored.clear()

        assertTrue(viewModel(savedStateHandle = restoredState.restored()).chosen().isEmpty())
    }

    @Test
    fun givenSavedSelection_whenAppsBecomeIneligible_thenTheyCannotBeConfirmed() = runTest {
        val saved = SavedStateHandle()
        val original = viewModel(savedStateHandle = saved)
        original.toggle("com.example.notes")
        original.toggle("com.example.weather")
        installedAppRepository.apps = installedAppRepository.apps.filterNot { it.packageName == "com.example.notes" }
        sandboxRepository.isolate("com.example.weather", userId = 0)

        val restored = viewModel(savedStateHandle = saved.restored())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.uiState.collect {} }

        assertTrue(restored.uiState.value.selected.isEmpty())
        assertTrue(restored.chosen().isEmpty())
    }

    @Test
    fun givenRestoredDraft_whenSandboxIsStillLoading_thenConfirmationWaits() = runTest {
        val saved = SavedStateHandle()
        viewModel(savedStateHandle = saved, sandboxLoaded = false).toggle("com.example.notes")
        val restored = viewModel(savedStateHandle = saved.restored(), sandboxLoaded = false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.uiState.collect {} }

        assertTrue(restored.uiState.value.selected.isEmpty())
        assertTrue(restored.chosen().isEmpty())

        sandboxRepository.refresh()

        assertEquals(setOf("com.example.notes"), restored.uiState.value.selected)
        assertEquals(listOf("com.example.notes"), restored.chosen().map { it.packageName })
    }

    private fun SavedStateHandle.restored() = SavedStateHandle(keys().associateWith { get<Any>(it) })

    private companion object {
        const val OWN_PACKAGE = "li.mercury.bigbrother"
    }
}
