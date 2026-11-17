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
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.Command
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.GUARDED_OPS
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_DEFAULT
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_IGNORED
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.OpState
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Keeps a set of permissions ("app ops") ignored for given apps, for the
 * daemon's sandbox: an app whose ops are ignored is given no data, while its
 * permissions still show as granted.
 *
 * Not part of the app. The daemon runs it as root, outside any app process,
 * and keeps it running:
 *
 *     CLASSPATH=<this apk> app_process /system/bin \
 *         li.mercury.bigbrother.catalog.AppOpsGuard
 *
 * It reads one command per line and answers each, one line per item:
 *
 * - `set <uid>`: prints `saved <uid> <op>=<mode>,...`, the uid's current
 *   modes for the guarded ops, then sets every one to ignored and keeps it
 *   so.
 * - `restore <uid> <op>=<mode>,...`: sets the modes back, stops keeping
 *   them ignored and prints `restored <uid>`. Without modes, the uid modes
 *   of every guarded op are removed: what there is to go back to when the
 *   daemon lost what `set` saved.
 * - `release <uid>`: undoes a `set` whose answer the daemon did not get:
 *   sets the modes saved here back and prints `restored <uid>`.
 * - `verify <uid>`: sets each guarded op that is no longer ignored again,
 *   printing `reverted <uid> <op>` for each, then `verified <uid>`. A uid not
 *   guarded yet is guarded from then on, so that the daemon can resume after
 *   this program was restarted without saving modes it set itself.
 *
 * A command that fails is answered with `failed <uid>`, and the reason goes
 * to the error output. So is one the system took none of: a `set` after
 * which no op is ignored must not pass for one that worked. Op names are the public ones, such as
 * `android:camera`; a mode is the framework's number for it, or `default`
 * for an op that had no uid mode, which `restore` removes again.
 *
 * The modes are the uid's, not its packages': the content providers check
 * the uid mode. The system sets uid modes itself whenever permissions
 * change, so the guarded uids are checked whenever the system reports a
 * change to a guarded op, and every minute in case those reports do not
 * arrive. Ops found changed then are printed as `reverted` lines too.
 *
 * It ends when its input is closed: the daemon holds the other end.
 *
 * Everything used here is internal to the framework and reached by
 * reflection, so that a device where it differs fails a command instead of
 * failing to load.
 */
@SuppressLint("PrivateApi")
object AppOpsGuard {
    /** How often to look again for a system service not registered yet. */
    private const val SERVICE_POLL_MS = 1000L

    /** How often every guarded uid is checked without being asked. */
    private const val CHECK_EVERY_MS = 60_000L

    /**
     * How long a check prompted by a reported change waits, and the least
     * time between two such checks: the system reports each op separately,
     * so changes come in bursts, and this program's own changes are
     * reported too.
     */
    private const val SETTLE_MS = 1000L

    /** An op this device knows, as the service names it. */
    private class Op(val name: String, val code: Int, val switchCode: Int, val defaultMode: Int?)

    /** Held by each command and each check, so that they run one at a time and their output lines stay whole. */
    private val lock = Any()

    /** The guarded uids, with the modes saved for each when known. */
    private val guarded = mutableMapOf<Int, List<Pair<String, Int?>>?>()

    private val changes = LinkedBlockingQueue<Unit>()

    @Volatile
    private var inputClosed = false

    private lateinit var service: Any

    /**
     * Methods are looked up on the public interface rather than on the
     * service's own class, a proxy class private to the framework.
     */
    private val serviceType by lazy { Class.forName("com.android.internal.app.IAppOpsService") }

    private lateinit var ops: List<Op>

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = LinkedBlockingQueue<Line>()
        thread(isDaemon = true, name = "input") {
            try {
                System.`in`.bufferedReader().forEachLine { lines.put(Line.Text(it)) }
            } catch (e: Throwable) {
                System.err.println("reading input: $e")
            }
            inputClosed = true
            lines.put(Line.End)
        }
        try {
            val binder = appOpsBinder() ?: exitProcess(0)
            service = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            // A system server that restarted is another one: the daemon
            // starts this program again, for it.
            binder.linkToDeath({ exitProcess(1) }, 0)
            ops = resolve(GUARDED_OPS)
            watchModes()
            thread(isDaemon = true, name = "check") { checkLoop() }
        } catch (e: Throwable) {
            System.err.println(e)
            exitProcess(1)
        }
        while (true) {
            val line = lines.take() as? Line.Text ?: break
            val command = AppOpsGuardProtocol.parseCommand(line.text)
            if (command == null) {
                System.err.println("not a command: ${line.text.take(80)}")
                continue
            }
            synchronized(lock) {
                try {
                    handle(command)
                } catch (e: Throwable) {
                    System.err.println("${line.text.trim().substringBefore(' ')} ${command.uid}: $e")
                    emit(AppOpsGuardProtocol.failedLine(command.uid))
                }
            }
        }
        exitProcess(0)
    }

    private sealed interface Line {
        class Text(val text: String) : Line

        data object End : Line
    }

    /**
     * The system's app ops service, or `null` if the input closed first. The
     * daemon starts this program while the system is still booting, before
     * the service is registered, so it waits for it rather than fail.
     */
    private fun appOpsBinder(): IBinder? {
        val getService = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
        var binder = getService.invoke(null, "appops") as IBinder?
        while (binder == null) {
            if (inputClosed) return null
            Thread.sleep(SERVICE_POLL_MS)
            binder = getService.invoke(null, "appops") as IBinder?
        }
        return binder
    }

    private fun resolve(names: List<String>): List<Op> {
        val manager = Class.forName("android.app.AppOpsManager")
        val toCode = manager.getMethod("strOpToOp", String::class.java)
        val toSwitch = manager.optionalMethod("opToSwitch", Int::class.javaPrimitiveType!!)
        val toDefault = manager.optionalMethod("opToDefaultMode", Int::class.javaPrimitiveType!!)
        return names.mapNotNull { name ->
            // A name this Android version doesn't know is left out.
            val code = try {
                toCode.invoke(null, name) as Int
            } catch (_: Throwable) {
                return@mapNotNull null
            }
            // The service keeps uid modes under the op that switches a group
            // of ops together, and reports them under it.
            val switchCode = toSwitch?.invokeOrNull(code) as Int? ?: code
            Op(name, code, switchCode, toDefault?.invokeOrNull(switchCode) as Int?)
        }
    }

    private fun handle(command: Command) {
        val uid = command.uid
        when (command) {
            is Command.Set -> {
                // Guarded since a `verify`, by a daemon carrying on: what the
                // ops are now is this program's own doing, not what to go
                // back to, and the daemon has that already.
                if (uid in guarded && guarded[uid] == null) error("already guarded, with nothing saved")
                // Asked again, the modes saved the first time are the ones to
                // go back to: the current ones are this program's own.
                val saved = guarded[uid] ?: AppOpsGuardProtocol.savedModes(states(uid))
                val taken = ops.count { setUidMode(it.name, it.code, uid, MODE_IGNORED) }
                if (ops.isNotEmpty() && taken == 0) error("the system took none of the modes")
                guarded[uid] = saved
                emit(AppOpsGuardProtocol.savedLine(uid, saved))
            }

            is Command.Restore -> {
                // Nothing saved: every guarded op goes back to having no uid
                // mode, which is how the system has them before it is asked.
                val modes = command.modes.ifEmpty { ops.map { it.name to null } }
                var taken = 0
                for ((name, mode) in modes) {
                    val op = ops.find { it.name == name } ?: resolve(listOf(name)).firstOrNull()
                    if (op == null) {
                        System.err.println("restore $uid: no op $name here")
                        continue
                    }
                    // Setting an op's default mode removes the uid mode.
                    if (setUidMode(name, op.code, uid, mode ?: op.defaultMode ?: MODE_DEFAULT)) taken++
                }
                // Still guarded if it fails: the daemon then keeps the app
                // sandboxed, and its ops must stay ignored.
                if (modes.isNotEmpty() && taken == 0) error("the system took none of the modes")
                guarded.remove(uid)
                emit(AppOpsGuardProtocol.restoredLine(uid))
            }

            is Command.Release -> {
                // The daemon gave up on a `set` it got no answer to, and does
                // not have what was saved: put that back from here.
                val saved = guarded.remove(uid)
                for ((name, mode) in saved.orEmpty()) {
                    val op = ops.find { it.name == name } ?: continue
                    setUidMode(name, op.code, uid, mode ?: op.defaultMode ?: MODE_DEFAULT)
                }
                emit(AppOpsGuardProtocol.restoredLine(uid))
            }

            is Command.Verify -> {
                if (uid !in guarded) guarded[uid] = null
                verify(uid)
                emit(AppOpsGuardProtocol.verifiedLine(uid))
            }
        }
    }

    /** Set the guarded ops of [uid] that are no longer ignored again. */
    private fun verify(uid: Int) {
        for (name in AppOpsGuardProtocol.opsToSetAgain(states(uid))) {
            val op = ops.first { it.name == name }
            if (setUidMode(name, op.code, uid, MODE_IGNORED)) {
                emit(AppOpsGuardProtocol.revertedLine(uid, name))
            }
        }
    }

    /** The guarded ops' uid modes for [uid], as the service reports them. */
    private fun states(uid: Int): List<OpState> {
        val switchCodes = ops.map { it.switchCode }.distinct().toIntArray()
        // Only ops with a uid mode of their own are listed, under their
        // switch op; `null` if there are none.
        val packages = serviceType
            .getMethod("getUidOps", Int::class.javaPrimitiveType, IntArray::class.java)
            .invoke(service, uid, switchCodes) as List<*>?
        val modes = mutableMapOf<Int, Int>()
        for (pkg in packages.orEmpty()) {
            for (entry in pkg?.call("getOps") as List<*>? ?: continue) {
                entry ?: continue
                modes[entry.call("getOp") as Int] = entry.call("getMode") as Int
            }
        }
        return ops.map { OpState(it.name, modes[it.switchCode], it.defaultMode) }
    }

    /** Whether the mode was set; a failure is reported and the rest carries on. */
    private fun setUidMode(name: String, code: Int, uid: Int, mode: Int): Boolean = try {
        serviceType
            .getMethod("setUidMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(service, code, uid, mode)
        true
    } catch (e: Throwable) {
        System.err.println("setting $name for $uid: ${e.cause ?: e}")
        false
    }

    /**
     * Ask the service to report changes to the guarded ops. Its callback
     * interface is internal, so a plain [Binder] stands in for an
     * implementation of it: every call it receives is taken as "something
     * changed", whatever its arguments. If the service will not take it, the
     * checks every minute remain.
     */
    private fun watchModes() {
        try {
            val callbackType = Class.forName("com.android.internal.app.IAppOpsCallback")
            val asInterface = Class.forName("com.android.internal.app.IAppOpsCallback\$Stub")
                .getMethod("asInterface", IBinder::class.java)
            val startWatching = serviceType
                .getMethod("startWatchingMode", Int::class.javaPrimitiveType, String::class.java, callbackType)
            // One callback per op: the service files a callback under the op
            // it was first registered for.
            for (code in ops.map { it.switchCode }.distinct()) {
                startWatching.invoke(service, code, null, asInterface.invoke(null, ModeWatcher()))
            }
        } catch (e: Throwable) {
            System.err.println("not watching mode changes, checking every minute only: ${e.cause ?: e}")
        }
    }

    private class ModeWatcher : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            changes.offer(Unit)
            return true
        }
    }

    /** Check every guarded uid after a reported change, and every minute. */
    private fun checkLoop() {
        while (true) {
            if (changes.poll(CHECK_EVERY_MS, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(SETTLE_MS)
                changes.clear()
            }
            synchronized(lock) {
                for (uid in guarded.keys.toList()) {
                    try {
                        verify(uid)
                    } catch (e: Throwable) {
                        System.err.println("checking $uid: $e")
                    }
                }
            }
        }
    }

    private fun emit(line: String) {
        synchronized(lock) {
            println(line)
            System.out.flush()
        }
    }

    private fun Class<*>.optionalMethod(name: String, vararg types: Class<*>) = try {
        getMethod(name, *types)
    } catch (_: Throwable) {
        null
    }

    private fun java.lang.reflect.Method.invokeOrNull(vararg args: Any?): Any? = try {
        invoke(null, *args)
    } catch (_: Throwable) {
        null
    }

    private fun Any.call(method: String): Any? = javaClass.getMethod(method).invoke(this)
}
