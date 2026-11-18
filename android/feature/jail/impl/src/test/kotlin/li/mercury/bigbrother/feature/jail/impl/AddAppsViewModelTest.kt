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

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.model.UserData
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestInstalledAppRepository
import li.mercury.bigbrother.core.testing.repository.TestSandboxRepository
import li.mercury.bigbrother.core.testing.repository.TestUserDataRepository
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    private val sandboxRepository = TestSandboxRepository(apps = listOf(sandboxed(10204, "com.example.shopping", enabled = true)))

    private suspend fun viewModel(showSystemApps: Boolean = false): AddAppsViewModel {
        sandboxRepository.refresh()
        return AddAppsViewModel(
            installedAppRepository = installedAppRepository,
            sandboxRepository = sandboxRepository,
            userDataRepository = TestUserDataRepository(UserData(showSystemApps = showSystemApps)),
            ownPackage = OWN_PACKAGE,
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
        assertEquals("", viewModel.uiState.value.query)
    }

    private companion object {
        const val OWN_PACKAGE = "li.mercury.bigbrother"
    }
}
