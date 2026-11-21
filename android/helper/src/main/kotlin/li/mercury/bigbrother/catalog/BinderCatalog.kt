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

import java.lang.reflect.Modifier

/**
 * Prints the transaction codes of system service interfaces, for the daemon.
 *
 * The daemon sees binder calls in the kernel as an interface name and a
 * number. Which method a number stands for differs between Android versions
 * and between manufacturers, who add methods of their own, so it is not
 * something to ship a table of: it is read here from the device's own
 * framework, where every interface's `Stub` class has a `TRANSACTION_<method>`
 * constant per method.
 *
 * Not part of the app. The daemon runs it as root, outside any app process:
 *
 *     CLASSPATH=<this apk> app_process /system/bin \
 *         li.mercury.bigbrother.catalog.BinderCatalog <interface>...
 *
 * Output, one line per method, tab separated: `<interface> <code> <method>`.
 * An interface this device doesn't have is skipped.
 *
 * A few old interfaces are written by hand and have no `Stub`; their codes
 * are constants of the interface itself, named `<NAME>_TRANSACTION`, and are
 * printed under that name.
 */
object BinderCatalog {
    private const val PREFIX = "TRANSACTION_"
    private const val SUFFIX = "_TRANSACTION"

    @JvmStatic
    fun main(args: Array<String>) {
        val out = StringBuilder()
        for (name in args) {
            try {
                val loader = ClassLoader.getSystemClassLoader()
                val holder = try {
                    Class.forName("$name\$Stub", false, loader)
                } catch (_: ClassNotFoundException) {
                    Class.forName(name, false, loader)
                }
                for (field in holder.declaredFields) {
                    if (!Modifier.isStatic(field.modifiers) || field.type != Int::class.javaPrimitiveType) continue
                    val method = when {
                        field.name.startsWith(PREFIX) -> field.name.removePrefix(PREFIX)
                        field.name.endsWith(SUFFIX) -> field.name.removeSuffix(SUFFIX)
                        else -> continue
                    }
                    field.isAccessible = true
                    out.append(name).append('\t')
                        .append(field.getInt(null)).append('\t')
                        .append(method).append('\n')
                }
            } catch (_: Throwable) {
                // Missing here, or laid out differently: not covered.
            }
        }
        print(out)
        System.out.flush()
    }
}
