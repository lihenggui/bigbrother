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

import android.os.IBinder
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Prints the system's record of when apps last used, and were last refused,
 * a set of permissions ("app ops"), for the daemon.
 *
 * Not part of the app. The daemon runs it as root, outside any app process:
 *
 *     CLASSPATH=<this apk> app_process /system/bin \
 *         li.mercury.bigbrother.catalog.AppOpsDump [--every <seconds>] <op name>...
 *
 * Op names are the public ones, such as `android:camera`. Output, one line
 * per app and op, tab separated:
 * `<uid> <package> <op name> <last use> <last refusal>`, the times in
 * milliseconds since the epoch, or -1 if it never happened.
 *
 * With `--every` it keeps running and prints a reading per interval, each
 * followed by a line holding a single `.`, until its input is closed: the
 * daemon holds the other end, so this process ends with it.
 *
 * Everything used here is internal to the framework and reached by
 * reflection, so that a device where it differs prints nothing instead of
 * failing to load.
 */
object AppOpsDump {
    /** `AppOpsManager.OP_FLAGS_ALL`: uses on the app's own behalf and on others'. */
    private const val ALL_FLAGS = 31

    private const val REPEAT = "--every"
    private const val END_OF_READING = "."

    /** How often to look again for a system service not registered yet. */
    private const val SERVICE_POLL_MS = 1000L

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args.firstOrNull() != REPEAT) {
                print(dump(appOpsService(), args))
                System.out.flush()
                return
            }
            val intervalMs = args[1].toLong() * 1000
            val names = args.copyOfRange(2, args.size)
            thread(isDaemon = true, name = "input") {
                while (System.`in`.read() >= 0) Unit
                exitProcess(0)
            }
            val service = appOpsService()
            while (true) {
                print(dump(service, names))
                println(END_OF_READING)
                System.out.flush()
                Thread.sleep(intervalMs)
            }
        } catch (e: Throwable) {
            System.err.println(e)
            exitProcess(1)
        }
    }

    /**
     * The system's app ops service. The daemon starts this program while the
     * system is still booting, before the service is registered, so it waits
     * for it rather than fail: the daemon would otherwise only try again much
     * later.
     */
    private fun appOpsService(): Any {
        val getService = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
        var binder = getService.invoke(null, "appops") as IBinder?
        while (binder == null) {
            Thread.sleep(SERVICE_POLL_MS)
            binder = getService.invoke(null, "appops") as IBinder?
        }
        return Class.forName("com.android.internal.app.IAppOpsService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    private fun dump(service: Any, names: Array<String>): String {
        val manager = Class.forName("android.app.AppOpsManager")
        val toCode = manager.getMethod("strOpToOp", String::class.java)
        // A name this Android version doesn't know is left out.
        val codes = names.mapNotNull { name ->
            try {
                toCode.invoke(null, name) as Int
            } catch (_: Throwable) {
                null
            }
        }.toIntArray()

        val packages = service.javaClass
            .getMethod("getPackagesForOps", IntArray::class.java)
            .invoke(service, codes) as List<*>? ?: return ""

        val out = StringBuilder()
        for (pkg in packages) {
            pkg ?: continue
            val uid = pkg.call("getUid")
            val name = pkg.call("getPackageName")
            for (op in pkg.call("getOps") as List<*>) {
                op ?: continue
                out.append(uid).append('\t')
                    .append(name).append('\t')
                    .append(op.call("getOpStr")).append('\t')
                    .append(op.time("getLastAccessTime")).append('\t')
                    .append(op.time("getLastRejectTime")).append('\n')
            }
        }
        return out.toString()
    }

    private fun Any.call(method: String): Any? = javaClass.getMethod(method).invoke(this)

    private fun Any.time(method: String): Long = javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(this, ALL_FLAGS) as Long
}
