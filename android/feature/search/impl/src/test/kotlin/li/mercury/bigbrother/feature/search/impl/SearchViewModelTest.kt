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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.SearchResult
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestSearchRepository
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.topicTitle
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun givenVisibleAppsWithRecords_whenQuerySettles_thenCountsMatchingDataPerApp() = runTest {
        val category = DataCategory.SENSORS
        val topic = "camera"
        val topicId = requireNotNull(topicTitle(topic))
        val app = AppSummary(10001, "com.example.camera", emptyMap(), lastSeen = 1, topics = listOf(topic, topic, "microphone"))
        val other = AppSummary(10002, "com.example.recorder", emptyMap(), lastSeen = 1, topics = listOf("microphone"))
        val viewModel = collectState(
            TestSearchRepository { text ->
                assertEquals("camera", text)
                SearchResult(
                    apps = listOf(app),
                    visibleApps = listOf(app, other),
                )
            },
        )

        viewModel.setQuery(" camera ", setOf(category.title, topicId, DataCategory.CONTACTS.title))
        advanceUntilIdle()

        assertEquals(
            SearchUiState(query = "camera", apps = listOf(app), data = listOf(DataMatch(category, null, 2), DataMatch(category, topic, 1))),
            viewModel.uiState.value,
        )
    }

    @Test
    fun givenResults_whenQueryCleared_thenClearsWithoutSearchingForBlankText() = runTest {
        val queries = mutableListOf<String>()
        val app = AppSummary(10001, "com.example.notes", emptyMap(), lastSeen = 1)
        val viewModel = collectState(
            TestSearchRepository { text ->
                queries += text
                SearchResult(apps = listOf(app))
            },
        )
        viewModel.setQuery("notes", emptySet())
        advanceUntilIdle()

        viewModel.setQuery("   ", emptySet())
        runCurrent()

        assertEquals(SearchUiState(), viewModel.uiState.value)
        advanceUntilIdle()
        assertEquals(listOf("notes"), queries)
    }

    @Test
    fun givenModuleOutdated_whenSearchFails_thenExposesLoadFailure() = runTest {
        val viewModel = collectState(TestSearchRepository { throw RecordsException.ModuleOutdated() })

        viewModel.setQuery("camera", emptySet())
        advanceUntilIdle()

        assertEquals(SearchUiState(query = "camera", failure = LoadFailure.ModuleOutdated), viewModel.uiState.value)
    }

    @Test
    fun givenSearchFailed_whenRetried_thenRunsSameQueryAndClearsFailure() = runTest {
        val repository = TestSearchRepository { throw RecordsException.NoRoot() }
        val viewModel = collectState(repository)
        viewModel.setQuery("camera", emptySet())
        advanceUntilIdle()
        assertEquals(LoadFailure.NoRoot, viewModel.uiState.value.failure)

        repository.results = { text ->
            assertEquals("camera", text)
            SearchResult()
        }
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(SearchUiState(query = "camera"), viewModel.uiState.value)
    }

    @Test
    fun givenSearchInProgress_whenQueryChanges_thenCancelsBeforeNextDebounce() = runTest {
        val calls = mutableListOf<String>()
        var cancelled = false
        val release = CompletableDeferred<Unit>()
        val viewModel = collectState(
            TestSearchRepository { text ->
                calls += text
                if (text == "camera") {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                        withContext(NonCancellable) { release.await() }
                    }
                }
                SearchResult()
            },
        )
        viewModel.setQuery("camera", emptySet())
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf("camera"), calls)

        viewModel.setQuery("contacts", emptySet())
        runCurrent()

        assertTrue(cancelled)
        assertFalse(viewModel.uiState.value.complete)
        assertEquals(listOf("camera"), calls)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("camera", "contacts"), calls)
        assertEquals(SearchUiState(query = "contacts"), viewModel.uiState.value)
    }

    private fun TestScope.collectState(repository: TestSearchRepository): SearchViewModel {
        val viewModel = SearchViewModel(repository, StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }
}
