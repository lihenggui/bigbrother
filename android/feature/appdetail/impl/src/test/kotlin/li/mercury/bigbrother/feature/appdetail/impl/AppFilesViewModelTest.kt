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

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.repository.FileAccessRepository
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.testing.MainDispatcherRule
import li.mercury.bigbrother.core.testing.repository.TestAccessRepository
import li.mercury.bigbrother.core.testing.repository.TestAppInfoRepository
import org.junit.Rule
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppFilesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun givenFullRecordBatch_whenFilesAreBuilt_thenWorkerPreparesTreeAndKeepsTruncation() = runTest {
        val worker = QueuedDispatcher()
        val viewModel = viewModel(
            List(MAX_ACCESSES - 2) { access("$FOLDER/probed-$it.txt", Action.PROBE) } +
                access("$FOLDER/favorite.txt") + access("media:images", Action.QUERY, Category.PROVIDER),
            worker,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()
        assertNull(viewModel.uiState.value.browser)
        worker.runNext()
        runCurrent()
        assertNull(viewModel.uiState.value.browser)
        worker.runNext()
        runCurrent()

        val reads = assertNotNull(viewModel.uiState.value.browser)
        assertTrue(reads.readOnly)
        assertEquals(listOf("favorite.txt"), reads.tree.folder(FOLDER)?.children?.map { it.name })
        assertEquals(listOf("media:images"), reads.tree.collections.map { it.path })
        assertTrue(viewModel.uiState.value.truncated)

        viewModel.showReadOnly(false)
        runCurrent()
        assertSame(reads, viewModel.uiState.value.browser)
        worker.runNext()
        runCurrent()
        val all = assertNotNull(viewModel.uiState.value.browser)
        assertFalse(all.readOnly)
        assertEquals(MAX_ACCESSES - 1, all.tree.folder(FOLDER)?.children?.size)
        assertTrue(viewModel.uiState.value.truncated)
    }

    @Test
    fun givenPendingFilters_whenChanged_thenOnlyTheLatestTreeIsPublished() = runTest {
        val worker = QueuedDispatcher()
        val viewModel = viewModel(
            listOf(access("$FOLDER/read.txt"), access("$FOLDER/favorite.txt", Action.PROBE)),
            worker,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()
        worker.runNext()
        runCurrent()
        worker.runNext()
        runCurrent()
        val original = assertNotNull(viewModel.uiState.value.browser)

        viewModel.openSearch()
        viewModel.search("absent")
        runCurrent()
        viewModel.search("  FAVORITE  ")
        runCurrent()
        viewModel.showReadOnly(false)
        runCurrent()

        worker.runNext()
        runCurrent()
        assertSame(original, viewModel.uiState.value.browser)
        worker.runNext()
        runCurrent()
        assertSame(original, viewModel.uiState.value.browser)
        worker.runNext()
        runCurrent()
        val browser = assertNotNull(viewModel.uiState.value.browser)
        assertTrue(browser.filtering)
        assertFalse(browser.readOnly)
        assertNotNull(browser.tree.folder("$FOLDER/favorite.txt"))
        assertNull(browser.tree.folder("$FOLDER/read.txt"))
    }

    @Test
    fun givenFileAccessRequested_whenCollectionStops_thenResultIsAvailableOnReturn() = runTest {
        val access = CompletableDeferred<Boolean>()
        val viewModel = viewModel(emptyList(), mainDispatcherRule.testDispatcher) { access.await() }
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()

        val path = "$FOLDER/read.txt"
        viewModel.ensureFileAccess(path)
        collector.cancel()
        advanceTimeBy(5_001)
        access.complete(true)
        runCurrent()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()
        val result = assertNotNull(viewModel.uiState.value.fileAccessResult)
        assertEquals(FileAccessResult.Granted(path), result)

        viewModel.onFileAccessHandled(result)
        runCurrent()
        assertNull(viewModel.uiState.value.fileAccessResult)
    }

    private fun viewModel(
        accesses: List<Access>,
        worker: CoroutineDispatcher,
        ensureAccess: suspend () -> Boolean = { true },
    ) = AppFilesViewModel(
        accessRepository = TestAccessRepository(accesses = accesses),
        appInfoRepository = TestAppInfoRepository(),
        fileAccessRepository = object : FileAccessRepository {
            override suspend fun ensureAllFilesAccess() = ensureAccess()
        },
        savedState = SavedStateHandle(),
        defaultDispatcher = worker,
        uid = 10100,
        packageName = "com.example.reader",
        range = TimeRange.DAY,
    )

    private fun access(path: String, action: Action = Action.READ, category: Category = Category.STORAGE) = Access(
        category = category,
        path = path,
        action = action,
        viaProvider = false,
        items = 0,
        allowed = 1,
        denied = 0,
        lastSeen = 2000,
    )

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            work.addLast(block)
        }

        fun runNext() = work.removeFirst().run()
    }

    companion object {
        private const val FOLDER = "/storage/emulated/0/Documents"
    }
}
