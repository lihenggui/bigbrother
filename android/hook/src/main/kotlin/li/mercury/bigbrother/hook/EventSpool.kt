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

    fun addQuery(uid: Int, uri: String, items: Int) = add(uid, "q", "", items, uri, "")

    fun addOpen(uid: Int, uri: String, mode: String, path: String) =
        add(uid, "o", mode, 0, uri, path)

    private fun add(uid: Int, op: String, mode: String, items: Int, uri: String, path: String) {
        val time = System.currentTimeMillis() / 1000
        val line = listOf(FORMAT_VERSION, time, uid, op, mode.clean(), items, uri.clean(), path.clean())
            .joinToString("\t")
        // If the writer can't keep up, dropping records beats stalling the provider.
        queue.offer(line)
    }

    /** Tabs and line breaks would corrupt the format; they don't occur in real paths. */
    private fun String.clean(): String = replace(SEPARATORS, " ")

    private fun writeLoop() {
        val batch = ArrayList<String>()
        while (true) {
            try {
                batch.add(queue.take())
                // Let more records arrive so one write covers a burst.
                TimeUnit.MILLISECONDS.sleep(BATCH_WINDOW_MS)
                queue.drainTo(batch)
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
        if (file.length() > MAX_FILE_BYTES) return
        // Opened per batch, not kept open: the daemon takes the file by
        // renaming it, and a held descriptor would keep writing to the old one.
        FileOutputStream(file, true).use { out ->
            out.write(lines.joinToString(separator = "\n", postfix = "\n").toByteArray())
        }
    }

    private companion object {
        const val FORMAT_VERSION = "1"
        const val FILE_NAME = "events.log"
        const val QUEUE_CAPACITY = 10_000
        const val BATCH_WINDOW_MS = 2_000L
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
        val SEPARATORS = Regex("[\\t\\n\\r]")
    }
}
