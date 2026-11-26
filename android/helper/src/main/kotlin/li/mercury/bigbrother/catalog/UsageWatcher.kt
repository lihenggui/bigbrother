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

import android.annotation.SuppressLint
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Binder threads only mark pending work. The writer waits here between notifications. */
internal class UsageDirty(
    private val now: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { TimeUnit.NANOSECONDS.sleep(it) },
    private val emit: () -> Unit,
) {
    private val dirty = AtomicBoolean()
    private val state = Any()
    private var notified = false
    private val wake = ArrayBlockingQueue<Unit>(1)
    private var last: Long? = null

    fun mark() {
        if (dirty.compareAndSet(false, true)) wake.offer(Unit)
    }

    fun clear() {
        synchronized(state) {
            notified = false
            dirty.set(false)
        }
    }

    fun run() {
        while (true) notifyNext()
    }

    fun notifyNext() {
        wake.take()
        if (!dirty.get()) return
        last?.let { sleep(maxOf(0L, TimeUnit.SECONDS.toNanos(5) - (now() - it))) }
        val notify = synchronized(state) {
            (dirty.get() && !notified).also { if (it) notified = true }
        }
        if (notify) {
            emit()
            last = now()
        }
    }
}

@SuppressLint("PrivateApi")
internal class FrameworkUsage(private val service: Any, private val dirty: UsageDirty) : HelperUsage {
    private var reader: AppOpsDumpReader? = null
    private val watchers = mutableListOf<Pair<String, Any>>()
    private val warned = mutableSetOf<String>()

    override fun configure(ops: List<String>) {
        val next = AppOpsDumpReader(service, ops)
        // Reconfiguration replaces the watched operations instead of adding duplicate callbacks.
        for ((name, callback) in watchers) {
            runCatching { watchMethod("stop", name).invoke(service, callback) }
                .onFailure { warnOnce("stop$name", it) }
        }
        watchers.clear()
        for (name in listOf("Active", "Noted")) {
            if (next.codes.isEmpty()) break
            try {
                val stub = Class.forName("com.android.internal.app.IAppOps${name}Callback\$Stub")
                val callback = stub.getMethod("asInterface", IBinder::class.java).invoke(null, Watcher(dirty))!!
                watchMethod("start", name, IntArray::class.java).invoke(service, next.codes, callback)
                watchers.add(name to callback)
            } catch (e: Throwable) {
                warnOnce(name, e)
            }
        }
        reader = next
    }

    override fun poll(emit: (String) -> Unit) {
        dirty.clear()
        checkNotNull(reader).read(emit)
    }

    private fun watchMethod(action: String, name: String, vararg parameters: Class<*>) = appOpsServiceType.getMethod(
        "${action}Watching$name",
        *parameters,
        Class.forName("com.android.internal.app.IAppOps${name}Callback"),
    )

    private fun warnOnce(name: String, e: Throwable) {
        if (warned.add(name)) System.err.println("usage watcher $name: ${describeFailure(e)}")
    }

    private class Watcher(private val dirty: UsageDirty) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            dirty.mark()
            return true
        }
    }
}
