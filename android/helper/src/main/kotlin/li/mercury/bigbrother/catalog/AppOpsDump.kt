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
import android.os.IBinder
import java.lang.reflect.InvocationTargetException
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
@SuppressLint("PrivateApi")
object AppOpsDump {
    private const val REPEAT = "--every"
    private const val END_OF_READING = "."
    private const val SERVICE_POLL_MS = 1000L

    @JvmStatic
    fun main(args: Array<String>) {
        val intervalMs = if (args.firstOrNull() == REPEAT) {
            val interval = intervalMillis(args.getOrNull(1))
            if (interval == null) {
                System.err.println("invalid --every interval")
                exitProcess(1)
            }
            interval
        } else {
            null
        }
        try {
            if (intervalMs != null) watchInput()
            val names = if (intervalMs == null) args.asList() else args.drop(2)
            val reader = AppOpsDumpReader(appOpsService(), names)
            do {
                print(reader.dump())
                if (intervalMs != null) println(END_OF_READING)
                System.out.flush()
                if (intervalMs != null) Thread.sleep(intervalMs)
            } while (intervalMs != null)
        } catch (e: Throwable) {
            System.err.println(describeFailure(e))
            exitProcess(1)
        }
    }

    internal fun intervalMillis(seconds: String?): Long? = seconds?.toLongOrNull()
        ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }
        ?.times(1000)

    private fun watchInput() {
        thread(isDaemon = true, name = "input") {
            try {
                while (System.`in`.read() >= 0) Unit
            } catch (e: Throwable) {
                System.err.println(describeFailure(e))
                exitProcess(1)
            }
            exitProcess(0)
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
        // If system_server restarts, the daemon will launch a fresh reader.
        binder.linkToDeath({ exitProcess(1) }, 0)
        return Class.forName("com.android.internal.app.IAppOpsService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }
}

internal class AppOpsDumpReader(
    private val service: Any,
    names: List<String>,
    manager: Class<*> = Class.forName("android.app.AppOpsManager"),
    serviceType: Class<*> = appOpsServiceType,
) {
    private val getPackages = serviceType.getMethod("getPackagesForOps", IntArray::class.java)
    private val codes = resolveCodes(manager, names)
    private val packages = mutableMapOf<Class<*>, PackageGetters>()
    private val entries = mutableMapOf<Class<*>, EntryGetters>()

    fun dump(): String {
        val rows = getPackages.invoke(service, codes) as List<*>? ?: return ""
        return buildString {
            for (pkg in rows) {
                pkg ?: continue
                val getters = packages.getOrPut(pkg.javaClass) { PackageGetters(pkg.javaClass) }
                val uid = getters.uid.invoke(pkg)
                val name = getters.name.invoke(pkg)
                for (entry in getters.ops.invoke(pkg) as List<*>) {
                    entry ?: continue
                    val op = entries.getOrPut(entry.javaClass) { EntryGetters(entry.javaClass) }
                    append(uid).append('\t')
                        .append(name).append('\t')
                        .append(op.name.invoke(entry)).append('\t')
                        .append(op.access.invoke(entry, ALL_FLAGS)).append('\t')
                        .append(op.reject.invoke(entry, ALL_FLAGS)).append('\n')
                }
            }
        }
    }

    private class PackageGetters(type: Class<*>) {
        val uid = type.getMethod("getUid")
        val name = type.getMethod("getPackageName")
        val ops = type.getMethod("getOps")
    }

    private class EntryGetters(type: Class<*>) {
        val name = type.getMethod("getOpStr")
        val access = type.getMethod("getLastAccessTime", INT)
        val reject = type.getMethod("getLastRejectTime", INT)
    }

    private companion object {
        /** `AppOpsManager.OP_FLAGS_ALL`: uses on the app's own behalf and on others'. */
        const val ALL_FLAGS = 31

        fun resolveCodes(manager: Class<*>, names: List<String>): IntArray {
            val toCode = manager.getMethod("strOpToOp", STRING)
            return names.mapNotNull { name ->
                try {
                    toCode.invoke(null, name) as Int
                } catch (e: InvocationTargetException) {
                    // Unknown op names may throw on older Android versions.
                    if (e.targetException !is IllegalArgumentException) throw e
                    null
                }
            }.toIntArray()
        }
    }
}
