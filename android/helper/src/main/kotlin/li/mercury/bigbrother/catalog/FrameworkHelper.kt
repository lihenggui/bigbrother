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

package li.mercury.bigbrother.catalog

import java.io.OutputStreamWriter
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val MAX_LINE = 4095

object FrameworkHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            System.err.println(describeFailure(e))
            exitProcess(1)
        }
        val stdout = OutputStreamWriter(System.out, Charsets.UTF_8).buffered()
        val stdoutLock = Any()
        val frames = HelperFrames { line ->
            synchronized(stdoutLock) {
                stdout.write(line)
                stdout.newLine()
                stdout.flush()
            }
        }
        frames.ready("catalog")
        val dirty = UsageDirty { frames.emit("usage event dirty") }
        val queries = HelperQueries(frames, BinderCatalog::read)
        val guard = AppOpsGuard(Framework, printLine = frames::guard, guardingChanged = Framework::guardingChanged)
        thread(name = "query-worker", isDaemon = true) { queries.run() }
        thread(name = "usage-events", isDaemon = true) { dirty.run() }
        thread(name = "guard-worker", isDaemon = true) { runGuard(queries, frames, guard, dirty) }
        Thread.currentThread().name = "input"
        System.`in`.bufferedReader(Charsets.UTF_8).forEachLine(queries::route)
        exitProcess(0)
    }

    private fun runGuard(queries: HelperQueries, frames: HelperFrames, guard: AppOpsGuard, dirty: UsageDirty) {
        var service = Framework.connectAppOps(waitMs = 1000)
        while (service == null) service = Framework.connectAppOps(waitMs = 1000)
        queries.usage = FrameworkUsage(service, dirty)
        frames.ready("usage")
        try {
            Framework.attach(guard, service)
            Framework.watchModes()
        } catch (e: Throwable) {
            System.err.println("initializing guard: ${describeFailure(e)}")
            return
        }
        thread(name = "check", isDaemon = true) { Framework.checkLoop() }
        frames.ready("guard")
        while (true) guard.handleLine(queries.guard.take())
    }
}

internal class HelperFrames(private val write: (String) -> Unit) {
    fun emit(line: String) {
        require(line.toByteArray(Charsets.UTF_8).size <= MAX_LINE && '\n' !in line && '\r' !in line)
        write(line)
    }

    fun ready(domain: String) = emit("ready v1 $domain")

    fun guard(line: String) {
        try {
            emit("guard $line")
        } catch (e: IllegalArgumentException) {
            System.err.println(describeFailure(e))
            emit("guard overflow")
        }
    }
}

internal interface HelperUsage {
    fun configure(ops: List<String>)
    fun poll(emit: (String) -> Unit)
}

internal class HelperQueries(
    private val frames: HelperFrames,
    private val catalog: (List<String>, (String) -> Unit) -> Unit,
) {
    private class Query(val domain: String, val id: String, val body: String)

    private val query = ArrayBlockingQueue<Query>(64)
    val guard = ArrayBlockingQueue<String>(128)

    @Volatile
    var usage: HelperUsage? = null

    fun route(line: String) {
        val domain = line.substringBefore(' ')
        val body = line.substringAfter(' ', "")
        if (line.toByteArray(Charsets.UTF_8).size > MAX_LINE) {
            if (domain == "guard") frames.emit("guard overflow")
            return
        }
        if (domain == "guard") {
            if (body.isNotEmpty() && !guard.offer(body)) frames.emit("guard overflow")
            return
        }
        if (domain != "catalog" && domain != "usage") return
        val id = body.substringBefore(' ')
        if (id.isEmpty() || id.length > 20 || id.any { it !in '0'..'9' }) return
        if (!query.offer(Query(domain, id, body.substringAfter(' ', "")))) {
            frames.emit("$domain $id err Busy")
        }
    }

    fun run() {
        while (true) answerNext()
    }

    fun answerNext() {
        val request = query.take()
        val prefix = "${request.domain} ${request.id}"
        try {
            val row: (String) -> Unit = { frames.emit("$prefix row $it") }
            val terminal = if (request.domain == "catalog") {
                require(request.body.isNotEmpty())
                catalog(request.body.split(' '), row)
                "end"
            } else {
                answerUsage(request.body, row)
            }
            frames.emit("$prefix $terminal")
        } catch (e: Throwable) {
            frames.emit("$prefix err ${describeFailure(e)}")
        }
    }

    private fun answerUsage(body: String, row: (String) -> Unit): String {
        val reader = checkNotNull(usage)
        return when {
            body.startsWith("configure ") -> {
                val ops = body.removePrefix("configure ").split(',')
                require(ops.all { it.isNotEmpty() && it.none(Char::isWhitespace) })
                reader.configure(ops)
                "ok"
            }

            body == "poll" -> {
                reader.poll(row)
                "end"
            }

            else -> throw IllegalArgumentException()
        }
    }
}
