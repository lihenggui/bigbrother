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

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.di.CacheDir
import li.mercury.bigbrother.core.common.di.FilesDir
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.common.logging.LOG_DIR
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.ModulePaths.BBD
import li.mercury.bigbrother.core.data.root.RootShell
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** What is needed to look into a problem, gathered for a bug report. */
interface DiagnosticsRepository {
    /**
     * A zip, in the cache directory under `exports/`, of the app's log, the
     * end of the daemon's log, the daemon's status, and the versions of the
     * app, the module and the system. The user decides whether to share it.
     */
    suspend fun exportLogs(): File
}

internal class ZipDiagnosticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @FilesDir private val filesDir: File,
    @CacheDir private val cacheDir: File,
    private val rootShell: RootShell,
    private val moduleRepository: ModuleRepository,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : DiagnosticsRepository {
    override suspend fun exportLogs(): File {
        // Read through root before anything is written: each is optional,
        // and a device without root still gets the app's own log.
        val daemonLog = rootOutput("tail -n $DAEMON_LOG_LINES $DAEMON_LOG", label = "daemon log")
        val daemonStatus = rootOutput("$BBD stats", label = "bbd stats")
        val about = about(moduleRepository.installedVersion())
        return withContext(ioDispatcher) {
            val directory = File(cacheDir, EXPORT_DIR)
            // One export at a time: an earlier one has been shared, or not.
            directory.deleteRecursively()
            directory.mkdirs()
            val name = "big-brother-logs-${LocalDateTime.now().format(FILE_TIME)}.zip"
            val zip = File(directory, name)
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                out.add("about.txt", about)
                daemonLog?.let { out.add("daemon/bbd.log", it) }
                daemonStatus?.let { out.add("daemon/stats.json", it) }
                File(filesDir, LOG_DIR).listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                    out.putNextEntry(ZipEntry("app/${file.name}"))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            Timber.i("Logs exported: %d KB", zip.length() / 1024)
            zip
        }
    }

    private suspend fun rootOutput(command: String, label: String): String? = try {
        rootShell.run("$command 2>&1", label).output
    } catch (e: RecordsException) {
        // Without root: the app's own log is still worth having.
        Timber.w("Not exported, %s: %s", label, e.message)
        null
    }

    private fun about(moduleVersion: String?): String {
        val app = context.packageManager.getPackageInfo(context.packageName, 0)
        return buildString {
            appendLine("App: ${app.versionName} (${app.longVersionCode})")
            appendLine("Module: ${moduleVersion ?: "not installed or not readable"}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Kernel: ${System.getProperty("os.version")}")
        }
    }

    private fun ZipOutputStream.add(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray())
        closeEntry()
    }

    private companion object {
        const val EXPORT_DIR = "exports"
        const val DAEMON_LOG = "/data/adb/big_brother/bbd.log"
        const val DAEMON_LOG_LINES = 5000
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
