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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.common.di.ApplicationScope
import li.mercury.bigbrother.core.common.di.FilesDir
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
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
    private val lines = Channel<String>(QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val available = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Any()
    private var output: BufferedOutputStream? = null
    private var openedFor: LocalDate? = null
    private var reportedFailure: LocalDate? = null
    private var writtenBytes = 0L

    init {
        scope.launch(ioDispatcher) {
            try {
                synchronized(writeLock) { deleteOldFiles() }
                for (signal in available) writePending()
            } finally {
                synchronized(writeLock) { closeOutput(today()) }
            }
        }
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority > Log.VERBOSE

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Timber has already appended the stack trace of t to the message.
        lines.trySend(format(priority, tag, message.take(MAX_LINE_CHARS)))
        available.trySend(Unit)
    }

    /**
     * Writes an uncaught exception at once, on the crashing thread, after
     * what was logged before it: the process ends right after.
     */
    fun logCrash(thread: Thread, error: Throwable) {
        val message = "Crash on thread ${thread.name}\n${error.stackTraceToString()}"
        writePending(crash = format(Log.ERROR, null, message.take(MAX_LINE_CHARS)))
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

    private fun writePending(crash: String? = null) = synchronized(writeLock) {
        val first = lines.tryReceive().getOrNull()
        if (first == null && crash == null) return@synchronized
        val day = today()
        try {
            val sink = outputFor(day)
            if (first != null) {
                append(sink, first, DAILY_LIMIT_BYTES - CRASH_RESERVE_BYTES)
                for (index in 1 until QUEUE_CAPACITY) {
                    val line = lines.tryReceive().getOrNull() ?: break
                    append(sink, line, DAILY_LIMIT_BYTES - CRASH_RESERVE_BYTES)
                }
            }
            if (crash != null) append(sink, crash, DAILY_LIMIT_BYTES)
            sink.flush()
        } catch (_: IOException) {
            reportFailure(day)
            closeOutput(day)
        }
    }

    private fun outputFor(day: LocalDate): BufferedOutputStream {
        if (openedFor == day) output?.let { return it }
        closeOutput(day)
        directory.mkdirs()
        val file = File(directory, "$day.log")
        writtenBytes = file.length()
        return FileOutputStream(file, true).buffered().also {
            output = it
            openedFor = day
            deleteOldFiles()
        }
    }

    private fun append(sink: BufferedOutputStream, line: String, limit: Long) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (writtenBytes + bytes.size > limit) return
        sink.write(bytes)
        writtenBytes += bytes.size
    }

    private fun closeOutput(day: LocalDate) {
        try {
            output?.close()
        } catch (_: IOException) {
            reportFailure(day)
        } finally {
            output = null
        }
    }

    // The log can't report its own failure through itself.
    @SuppressLint("LogNotTimber")
    private fun reportFailure(day: LocalDate) {
        if (reportedFailure == day) return
        reportedFailure = day
        Log.e(LOG_TAG, "Could not write the log")
    }

    /** Files are named by date, so the oldest sort first. */
    private fun deleteOldFiles() {
        val files = directory.listFiles { file -> file.name.endsWith(".log") } ?: return
        files.sortedBy { it.name }.dropLast(DAYS_KEPT).forEach { it.delete() }
    }

    internal companion object {
        const val DAYS_KEPT = 7
        private const val QUEUE_CAPACITY = 256
        private const val MAX_LINE_CHARS = 16_384
        private const val DAILY_LIMIT_BYTES = 2L * 1024 * 1024
        private const val CRASH_RESERVE_BYTES = 64 * 1024
        private const val LOG_TAG = "ReleaseTree"
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
