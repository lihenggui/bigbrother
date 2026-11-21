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

package li.mercury.bigbrother.core.data.repository

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.common.logging.LOG_DIR
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.bbd.BbdDiagnostics
import li.mercury.bigbrother.core.data.bbd.TestBbdDataSource
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.testing.repository.TestModuleRepository
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZipDiagnosticsRepositoryTest {
    @get:Rule
    val directory = TemporaryFolder()

    private val module = TestModuleRepository()

    @Test
    fun givenAppAndDaemonLogs_whenExported_thenArchiveContainsDiagnosticsAndReplacesOldExport() = runTest {
        val files = directory.newFolder("files")
        val cache = directory.newFolder("cache")
        val logs = File(files, LOG_DIR).apply { mkdir() }
        File(logs, "latest.log").writeText("daemon query completed")
        File(logs, "previous.log").writeText("app started")
        val old = File(File(cache, "exports").apply { mkdir() }, "old.zip").apply { writeText("old") }

        val entries = repository(files, cache, BbdDiagnostics("recording started", """{"recording":true}"""))
            .exportLogs().readEntries()

        assertFalse(old.exists())
        assertEquals(setOf("about.txt", "daemon/bbd.log", "daemon/stats.json", "app/latest.log", "app/previous.log"), entries.keys)
        assertEquals("app started", entries["app/previous.log"])
        assertEquals("daemon query completed", entries["app/latest.log"])
        assertEquals("recording started", entries["daemon/bbd.log"])
        assertEquals("""{"recording":true}""", entries["daemon/stats.json"])
        val about = entries.getValue("about.txt")
        assertTrue(about.contains("Module: v0.1.6\n"))
        assertTrue(about.contains("Android: "))
    }

    @Test
    fun givenRootUnavailable_whenExported_thenArchiveContainsAvailableAppDiagnostics() = runTest {
        val files = directory.newFolder("files")
        val cache = directory.newFolder("cache")
        module.versionFailure = RecordsException.NoRoot()

        val entries = repository(files, cache, BbdDiagnostics(null, null)).exportLogs().readEntries()

        assertEquals(setOf("about.txt"), entries.keys)
        assertTrue(entries.getValue("about.txt").contains("Module: not installed or not readable\n"))
    }

    @Test
    fun givenCachePathIsAFile_whenExported_thenReportsArchiveWriteFailure() = runTest {
        val repository = repository(directory.newFolder("files"), directory.newFile("cache"), BbdDiagnostics(null, null))

        assertEquals(
            LoadFailure.Operation.LOG_EXPORT_FAILED,
            assertFailsWith<RecordsException.CommandFailed> {
                repository.exportLogs()
            }.toLoadFailure(),
        )
    }

    private fun TestScope.repository(files: File, cache: File, diagnostics: BbdDiagnostics) = ZipDiagnosticsRepository(
        RuntimeEnvironment.getApplication(),
        files,
        cache,
        object : TestBbdDataSource() {
            override suspend fun getDiagnostics() = diagnostics
        },
        module,
        StandardTestDispatcher(testScheduler),
    )

    private fun File.readEntries(): Map<String, String> = ZipFile(this).use { zip ->
        zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }
}
