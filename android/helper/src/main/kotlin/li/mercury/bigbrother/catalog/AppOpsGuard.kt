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

import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.Command
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Keeps a set of permissions ("app ops") ignored for given apps, for the
 * daemon's sandbox: an app whose ops are ignored is given no data, while its
 * permissions still show as granted. Ops can be kept in the foreground
 * instead, where the app gets the data only while it is in use. It also
 * keeps runtime permissions revoked, for what no op can do: the precise
 * location permission, whose loss gives an app the system's approximate
 * location. And it keeps from an app what the system hands its services
 * itself, past any op: the notifications, given to notification listeners,
 * and the screen, given to accessibility services
 * ([AppOpsGuardProtocol.SPECIAL_ACCESS]). These go by the permission the
 * system binds such services with, and are taken and given back as a
 * permission is ([SpecialAccess]).
 *
 * The daemon runs it as root, outside any app process,
 * and keeps it running:
 *
 *     CLASSPATH=<this apk> app_process /system/bin \
 *         li.mercury.bigbrother.catalog.AppOpsGuard
 *
 * It reads one command per line and answers each, one line per item. What a
 * uid is held to (`<targets>`) is `<op>=<mode>` for each op, with the mode
 * ignored (1) or foreground (4), and `<permission>=revoked` for each runtime
 * permission, joined by commas:
 *
 * - `set <uid> <targets>`: prints `saved <uid> <values>`, the uid's current
 *   modes for the guarded ops and the states of the permissions in
 *   `targets`, then sets the ops listed to the modes given, revokes the
 *   permissions, and keeps them so.
 * - `apply <uid> <targets> <resets>`: for a guarded uid, sets what `resets`
 *   names (`<values>`, or `-`) back to the values given and leaves it to
 *   the system, then holds the uid to `targets` (or `-`) instead of what it
 *   was held to. Prints `applied <uid>`, followed by the states of the
 *   permissions it was not holding before, if any.
 * - `restore <uid> <values>`: sets what is named back, stops holding the
 *   uid and prints `restored <uid>`. Without values, the uid modes of every
 *   guarded op are removed: what there is to go back to when the daemon
 *   lost what `set` saved.
 * - `release <uid>`: undoes a `set` whose answer the daemon did not get:
 *   sets the modes and states saved here back and prints `restored <uid>`.
 * - `verify <uid> <targets>`: holds the uid to the targets and sets again
 *   each op that no longer has its mode, and each permission no longer
 *   revoked, printing `reverted <uid> <name>` for each, then `verified
 *   <uid>`. A uid not guarded yet is guarded from then on, so that the
 *   daemon can resume after this program was restarted without saving what
 *   it set itself; its permissions count as held already, but for those
 *   written `<permission>=unheld`, which the daemon last heard were not.
 *   A permission found changed by the user, granted again
 *   or, once held, no longer fixed (as the settings leave one the user
 *   revokes), is printed as `reverted <uid> <permission>=<state>` with the
 *   state it was found in: from then on the state to give back, here and
 *   in what the daemon keeps. Every other command looks for such a change
 *   first, and prints the same line for a permission it holds revoked
 *   still.
 * - `kind <uid> <request> <package>`: prints `library <uid> <request>` for
 *   a static shared library or SDK library, `app <uid> <request>` for
 *   another package of the uid, and `unknown <uid> <request>` if it cannot
 *   tell. This command reads from the package manager without changing
 *   the uid's permissions or its installation cookie.
 *
 * In `<values>` an op has a mode, the framework's number for it, or
 * `default` for an op that had no uid mode, which `restore` removes again.
 * `set` follows the mode of an op of a runtime permission with `/` and the
 * mode the system gives the op for the permission's grant at that time
 * (`android:read_contacts=1/1`: ignored, as the permission was not
 * granted). An op given back with one is set to the system's mode for the
 * grant as it is then if that is another, so that a permission granted or
 * revoked in the meantime takes effect; with the same, or without one, it
 * is set to the mode saved, which may be the user's own.
 *
 * A permission has a state, `granted` or `denied` followed by `+user_set`
 * and `+user_fixed` for the user's flags it had, or `default` where the
 * daemon does not know it: then the state saved here is put back, and
 * where there is none the permission only loses its `user_fixed` flag. A
 * special access is `granted` followed by `+service:<package>:<class>` for
 * each service that had it, or `denied`; with none known, it stays taken.
 *
 * Before the answer to `set`, `apply` and `verify`, and whenever a check
 * finds it changed, it prints `unheld <uid> <name>,...` (`-` for none):
 * what the uid is held to that the system did not keep even after it was
 * set again, ops at their mode and permissions revoked. The helper limits
 * retries for all-files mode changes, permission revocations, and disabling
 * special access. A restriction suspended this way is reported as unheld
 * until the targets or the installation change, or the uid is released.
 * All-files access with unreadable request metadata is also unheld. Ops this
 * device does not have are not named.
 *
 * A command that fails is answered with `failed <uid>`, and the reason goes
 * to the error output. So is one the system took none of: a `set` after
 * which the service accepted none of the attempted modes must not pass for
 * one that worked. Op names are the public ones, such as `android:camera`, and
 * permissions go by their full names.
 *
 * The modes are the uid's, not its packages': the content providers check
 * the uid mode. The system sets uid modes itself whenever permissions
 * change, so the guarded uids are checked whenever the system reports a
 * change to a guarded op or to a permission, and every minute in case those
 * reports do not arrive. What is found changed then is printed as
 * `reverted` lines too.
 *
 * A permission is revoked for every package of the uid that requests it,
 * and marked as the user's choice not to be asked again
 * (`FLAG_PERMISSION_USER_FIXED`), as the system's own settings do when the
 * user turns precise location off for good. The system keeps one state for
 * the packages of a uid, and ends the uid's processes when it revokes a
 * permission they had.
 *
 * It ends when its input is closed: the daemon holds the other end.
 *
 * Everything used here is internal to the framework and reached by
 * reflection, so that a device where it differs fails a command instead of
 * failing to load. The system's own error messages name packages, so only
 * their types are reported.
 */
object AppOpsGuard {
    /** Held by each command and each check, so that they run one at a time and their output lines stay whole. */
    private val lock = Any()

    private val handler = AppOpsGuardHandler(Framework)

    private var journal: File? = null

    internal fun restoreItem(name: String, change: () -> Unit) {
        val file = journal
        if (file != null && file.exists() && name in file.readLines()) return
        change()
        if (file != null) {
            FileOutputStream(file, true).use { out ->
                out.write("$name\n".toByteArray())
                out.fd.sync()
            }
        }
    }

    private val owners = mutableMapOf<Int, Installation>()

    internal fun requireOwner(uid: Int) {
        check(owners[uid]?.matches(uid) == true) { "installation changed" }
    }

    @Volatile
    internal var inputClosed = false
        private set

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 2 && args[0] == "--recover") journal = File(args[1])
        val lines = readInput()
        try {
            Framework.connect()
            thread(isDaemon = true, name = "check") { Framework.checkLoop(lock, handler) }
        } catch (e: Throwable) {
            System.err.println(describeFailure(e))
            exitProcess(1)
        }
        while (true) {
            val line = lines.take() as? Line.Text ?: break
            handleLine(line.text)
        }
        exitProcess(0)
    }

    private fun readInput(): LinkedBlockingQueue<Line> {
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
        return lines
    }

    private fun handleLine(line: String) {
        if (line.startsWith("kind ")) {
            val query = AppOpsGuardProtocol.parseCommand(line) as? Command.Kind ?: return
            synchronized(lock) { answerKind(query) }
            return
        }
        val bound = AppOpsGuardProtocol.parseBound(line) ?: return
        val command = AppOpsGuardProtocol.parseCommand(bound.commandLine)
        if (command == null) {
            System.err.println("invalid helper command")
            return
        }
        val owner = Installation(bound.packageName, bound.token)
        synchronized(lock) { handleBound(owner, command) }
    }

    private fun handleBound(owner: Installation, command: Command) {
        val uid = command.uid
        try {
            val current = owner.isCurrent(uid)
            if (!current && journal != null && command is Command.Restore) {
                emit("retired $uid")
                return
            }
            check(current) { "installation changed" }
            if (owners.put(uid, owner) != owner) handler.forget(uid)
            handler.handle(command)
            if (!handler.isGuarded(uid)) owners.remove(uid)
        } catch (e: Throwable) {
            System.err.println("bound $uid: ${describeFailure(e)}")
            emit(
                when (command) {
                    is Command.Capture -> AppOpsGuardProtocol.captureFailedLine(uid, command.request)
                    else -> AppOpsGuardProtocol.failedLine(uid)
                },
            )
        }
    }

    /**
     * Answer `kind`, apart from the commands, `unknown` where it cannot be
     * told: never taken for a command's answer.
     */
    private fun answerKind(command: Command.Kind) {
        val library = try {
            isLibrary(command.packageName, command.uid)
        } catch (e: Throwable) {
            System.err.println("kind ${command.uid}: ${describeFailure(e)}")
            null
        }
        emit(AppOpsGuardProtocol.kindLine(command.uid, command.request, library))
    }

    internal fun emit(line: String) {
        synchronized(lock) {
            println(line)
            System.out.flush()
        }
    }

    private sealed interface Line {
        class Text(val text: String) : Line

        data object End : Line
    }
}
