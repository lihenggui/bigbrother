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
import java.lang.reflect.Method

/** `Context.DEVICE_ID_DEFAULT`. */
private const val DEVICE_ID_DEFAULT = 0

/**
 * `VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT`: "default:" and
 * [DEVICE_ID_DEFAULT]. A constant of the framework, so it is the same in
 * every app that was built with it.
 */
private const val PERSISTENT_DEVICE_ID_DEFAULT = "default:0"

internal val STRING = String::class.java
internal val INT = Int::class.javaPrimitiveType!!
internal val LONG = Long::class.javaPrimitiveType!!
internal val BOOLEAN = Boolean::class.javaPrimitiveType!!

// The service's implementing class may be a framework-private Binder proxy.
internal val appOpsServiceType by lazy { Class.forName("com.android.internal.app.IAppOpsService") }

/**
 * A method of the permission service called for the default device:
 * with the parameters [before], the device in whichever form this
 * version takes one, and [after].
 */
internal class DeviceCall(type: Class<*>, name: String, before: List<Class<*>>, after: List<Class<*>>) {
    private val method: Method
    private val device: List<Any>

    init {
        val forms: List<List<Pair<Class<*>, Any>>> = listOf(
            // A persistent device id, from Android 14 QPR3 on.
            listOf(STRING to PERSISTENT_DEVICE_ID_DEFAULT),
            // A device id, in Android 14 QPR2.
            listOf(INT to DEVICE_ID_DEFAULT),
            // None, before.
            emptyList(),
        )
        val (method, device) = forms.firstNotNullOfOrNull { form ->
            val types = before + form.map { (parameter, _) -> parameter } + after
            type.optionalMethod(name, *types.toTypedArray())?.let { found -> found to form.map { (_, value) -> value } }
        } ?: throw NoSuchMethodException(name)
        this.method = method
        this.device = device
    }

    fun call(target: Any, before: List<Any?>, after: List<Any?>): Any? = method.call(target, *(before + device + after).toTypedArray())
}

/** Call the method and let its original exception reach the caller. */
internal fun Method.call(target: Any, vararg args: Any?): Any? = try {
    invoke(target, *args)
} catch (e: InvocationTargetException) {
    throw e.targetException ?: e
}

/** Exception types only. Messages can contain package names and paths. */
internal fun describeFailure(e: Throwable): String = generateSequence(e) { it.cause }
    .take(8)
    .filterNot { it is InvocationTargetException && it.cause != null }
    .joinToString(" caused by ") { it.javaClass.simpleName.ifEmpty { "Throwable" } }
    .ifEmpty { e.javaClass.simpleName }

internal fun Class<*>.optionalMethod(name: String, vararg types: Class<*>) = try {
    getMethod(name, *types)
} catch (_: Throwable) {
    null
}

internal fun Method.invokeOrNull(vararg args: Any?): Any? = try {
    invoke(null, *args)
} catch (_: Throwable) {
    null
}
