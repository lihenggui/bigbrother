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

package li.mercury.bigbrother.core.common.logging

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** The directory, in the app's files, that holds the log files. */
const val LOG_DIR = "logs"

/**
 * Keeps the app's log in files, one per day for the last [DAYS_KEPT] days,
 * so that a problem can be looked into after it happened. Lines are written
 * in the order they were logged, by one writer off the main thread. Verbose
 * lines are left out.
 *
 * What the app records (paths, package names, search text) must not be
 * logged: only what the app did, how long it took and how it ended.
 */
@Singleton
class ReleaseTree @Inject constructor(
    @ApplicationContext context: Context,
    @Dispatcher(IO) ioDispatcher: CoroutineDispatcher,
) : Timber.Tree() {
    private val directory = File(context.filesDir, LOG_DIR)
    private val lines = Channel<String>(Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            directory.mkdirs()
            deleteOldFiles()
            for (line in lines) append(line)
        }
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority > Log.VERBOSE

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> 'A'
        }
        val time = LocalDateTime.now().format(TIME_FORMAT)
        // Timber has already appended the stack trace of t to the message.
        lines.trySend(if (tag == null) "$time $level $message" else "$time $level/$tag $message")
    }

    private fun append(line: String) {
        try {
            File(directory, "${LocalDate.now()}.log").appendText(line + "\n")
        } catch (e: IOException) {
            Log.e(LOG_TAG, "Could not write the log", e)
        }
    }

    /** Files are named by date, so the oldest sort first. */
    private fun deleteOldFiles() {
        val files = directory.listFiles { file -> file.name.endsWith(".log") } ?: return
        files.sortedBy { it.name }.dropLast(DAYS_KEPT).forEach { it.delete() }
    }

    private companion object {
        const val DAYS_KEPT = 7
        const val LOG_TAG = "ReleaseTree"
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
