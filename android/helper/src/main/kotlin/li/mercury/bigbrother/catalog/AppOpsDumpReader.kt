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

import java.lang.reflect.InvocationTargetException

internal class AppOpsDumpReader(
    private val service: Any,
    names: List<String>,
    manager: Class<*> = Class.forName("android.app.AppOpsManager"),
    serviceType: Class<*> = appOpsServiceType,
) {
    private val getPackages = serviceType.getMethod("getPackagesForOps", IntArray::class.java)
    val codes = resolveCodes(manager, names)
    private val packages = mutableMapOf<Class<*>, PackageGetters>()
    private val entries = mutableMapOf<Class<*>, EntryGetters>()

    fun read(emit: (String) -> Unit) {
        val rows = getPackages.invoke(service, codes) as List<*>? ?: return
        for (pkg in rows) {
            pkg ?: continue
            val getters = packages.getOrPut(pkg.javaClass) { PackageGetters(pkg.javaClass) }
            val uid = getters.uid.invoke(pkg)
            val name = getters.name.invoke(pkg)
            for (entry in getters.ops.invoke(pkg) as List<*>) {
                entry ?: continue
                val op = entries.getOrPut(entry.javaClass) { EntryGetters(entry.javaClass) }
                emit(
                    buildString {
                        append(uid).append('\t')
                            .append(name).append('\t')
                            .append(op.name.invoke(entry)).append('\t')
                            .append(op.access.invoke(entry, ALL_FLAGS)).append('\t')
                            .append(op.reject.invoke(entry, ALL_FLAGS))
                    },
                )
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
