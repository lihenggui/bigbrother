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

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

internal class TestHookChain(
    private val arguments: List<Any?> = emptyList(),
    private val original: () -> Any?,
) : XposedInterface.Chain {
    var originalCalls = 0
        private set
    var argumentReads = 0
        private set

    override fun getExecutable(): Executable = error("no executable in test")
    override fun getThisObject(): Any? = null
    override fun getArgs(): List<Any?> {
        argumentReads++
        return arguments
    }

    override fun getArg(index: Int): Any? = args[index]

    override fun proceed(): Any? {
        originalCalls++
        return original()
    }

    override fun proceed(args: Array<out Any?>): Any? = error("unexpected proceed with args")
    override fun proceedWith(thisObject: Any): Any? = error("unexpected proceed with receiver")
    override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? = error("unexpected proceed with args and receiver")
}
