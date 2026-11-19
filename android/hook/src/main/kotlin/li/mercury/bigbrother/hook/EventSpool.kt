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

package li.mercury.bigbrother.hook

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hands records to the daemon through a file.
 *
 * The hook runs inside a system provider's process and cannot reach the
 * daemon's database, so it appends lines to a file in that provider's own
 * data directory; the daemon, running as root, collects them from there.
 *
 * Callers are binder threads in the middle of serving a request, so [add]
 * only enqueues; a background thread does the I/O in batches.
 *
 * Line format (keep in sync with `bbd/src/spool.rs`), tab separated:
 * `1  <unix time>  <caller uid>  <q|o>  <mode>  <items>  <uri>  <path>`
 */
internal class EventSpool(private val directory: File) {
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)

    init {
        Thread(::writeLoop, "big-brother-spool").apply {
            isDaemon = true
            start()
        }
    }

    fun addQuery(uid: Int, uri: String, items: Int) = add(queryLine(now(), uid, uri, items))

    fun addOpen(uid: Int, uri: String, mode: String, path: String) = add(openLine(now(), uid, uri, mode, path))

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun add(line: String) {
        // If the writer can't keep up, dropping records beats stalling the provider.
        if (line.isNotEmpty()) queue.offer(line)
    }

    private fun writeLoop() {
        val batch = ArrayList<String>()
        while (true) {
            try {
                batch.add(queue.take())
                // Let more records arrive so one write covers a burst.
                TimeUnit.MILLISECONDS.sleep(BATCH_WINDOW_MS)
                queue.drainTo(batch, 127)
                write(batch)
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                // Losing a batch is acceptable; taking down the provider is not.
            } finally {
                batch.clear()
            }
        }
    }

    private fun write(lines: List<String>) {
        directory.mkdirs()
        val file = File(directory, FILE_NAME)
        // The daemon removes the file as it reads. If it is not running, stop
        // growing the file rather than fill the provider's storage.
        val payload = lines.joinToString(separator = "\n", postfix = "\n").toByteArray()
        // Opened per batch, not kept open: the daemon takes the file by
        // renaming it, and a held descriptor would keep writing to the old one.
        FileOutputStream(file, true).use { out ->
            out.channel.tryLock()?.use {
                if (out.channel.size() + payload.size <= MAX_FILE_BYTES) out.write(payload)
            }
        }
    }

    internal companion object {
        /** The line of a query at [time] (unix time) that returned [items] rows. */
        fun queryLine(time: Long, uid: Int, uri: String, items: Int): String = line(time, uid, "q", "", items, uri, "")

        /** The line of an open in [mode] at [time] that resolved to [path], or `""` if not known. */
        fun openLine(time: Long, uid: Int, uri: String, mode: String, path: String): String = line(time, uid, "o", mode, 0, uri, path)

        private fun line(time: Long, uid: Int, op: String, mode: String, items: Int, uri: String, path: String): String {
            if (uri.length > 4096 || path.length > 4096 || mode.length > 4) return ""
            val address = uri.substringBefore('?').substringBefore('#')
            val rest = address.removePrefix("content://")
            val authority = rest.substringBefore('/').substringAfterLast('@')
            if (!address.startsWith("content://") || (authority !in PERSONAL && authority !in STORAGE)) return ""
            val personal = authority in PERSONAL
            val keptUri = if (personal) {
                val collection = rest.substringAfter('/', "").substringBefore('/')
                if (collection in COLLECTIONS) "content://$authority/$collection" else "content://$authority"
            } else {
                address
            }
            val line = listOf(FORMAT_VERSION, time, uid, op, mode.clean(), items, keptUri.clean(), if (personal) "" else path.clean()).joinToString("\t")
            return if (line.toByteArray().size <= 8192) line else ""
        }

        private val STORAGE = setOf("media", "downloads", "com.android.providers.media.documents", "com.android.externalstorage.documents", "com.android.providers.downloads.documents", "com.android.mtp.documents", "com.google.android.apps.nbu.files.provider")
        private val PERSONAL = setOf("contacts", "com.android.contacts", "icc", "call_log", "call_log_shadow", "com.android.voicemail", "sms", "mms", "mms-sms", "com.android.calendar", "telephony")
        private val COLLECTIONS = setOf("contacts", "raw_contacts", "data", "groups", "profile", "phones", "adn", "fdn", "sdn", "calls", "voicemail", "inbox", "sent", "draft", "outbox", "conversations", "events", "calendars", "instances", "attendees", "reminders", "siminfo")

        /** Tabs and line breaks would corrupt the format; they don't occur in real paths. */
        private fun String.clean(): String = replace(SEPARATORS, " ")

        private const val FORMAT_VERSION = "1"
        private const val FILE_NAME = "events.log"
        private const val QUEUE_CAPACITY = 512
        private const val BATCH_WINDOW_MS = 2_000L
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        private val SEPARATORS = Regex("[\\t\\n\\r]")
    }
}
