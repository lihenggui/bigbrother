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

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.common.di.ApplicationScope
import li.mercury.bigbrother.core.common.di.FilesDir
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
 * in the order they were logged, by one writer off the main thread, except
 * a crash, which is written before the process ends. Verbose lines are left
 * out.
 *
 * What the app records (paths, package names, search text) must not be
 * logged: only what the app did, how long it took and how it ended.
 */
@Singleton
class ReleaseTree internal constructor(
    filesDir: File,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    private val today: () -> LocalDate,
    private val now: () -> LocalDateTime,
) : Timber.Tree() {
    @Inject
    constructor(
        @FilesDir filesDir: File,
        @ApplicationScope scope: CoroutineScope,
        @Dispatcher(IO) ioDispatcher: CoroutineDispatcher,
    ) : this(filesDir, scope, ioDispatcher, LocalDate::now, LocalDateTime::now)

    private val directory = File(filesDir, LOG_DIR)
    private val lines = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch(ioDispatcher) {
            directory.mkdirs()
            deleteOldFiles()
            for (line in lines) append(line)
        }
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority > Log.VERBOSE

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Timber has already appended the stack trace of t to the message.
        lines.trySend(format(priority, tag, message))
    }

    /**
     * Writes an uncaught exception at once, on the crashing thread, after
     * what was logged before it: the process ends right after.
     */
    fun logCrash(thread: Thread, error: Throwable) {
        while (true) append(lines.tryReceive().getOrNull() ?: break)
        append(format(Log.ERROR, null, "Crash on thread ${thread.name}\n${error.stackTraceToString()}"))
    }

    private fun format(priority: Int, tag: String?, message: String): String {
        val level = when (priority) {
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> 'A'
        }
        val time = now().format(TIME_FORMAT)
        return if (tag == null) "$time $level $message" else "$time $level/$tag $message"
    }

    // The log can't report its own failure through itself.
    @SuppressLint("LogNotTimber")
    @Synchronized
    private fun append(line: String) {
        try {
            directory.mkdirs()
            File(directory, "${today()}.log").appendText(line + "\n")
        } catch (e: IOException) {
            Log.e(LOG_TAG, "Could not write the log", e)
        }
    }

    /** Files are named by date, so the oldest sort first. */
    private fun deleteOldFiles() {
        val files = directory.listFiles { file -> file.name.endsWith(".log") } ?: return
        files.sortedBy { it.name }.dropLast(DAYS_KEPT).forEach { it.delete() }
    }

    internal companion object {
        const val DAYS_KEPT = 7
        private const val LOG_TAG = "ReleaseTree"
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
