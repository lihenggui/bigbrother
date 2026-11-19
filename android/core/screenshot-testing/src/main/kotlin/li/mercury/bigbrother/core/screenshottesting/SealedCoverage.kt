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

package li.mercury.bigbrother.core.screenshottesting

import android.annotation.SuppressLint

/**
 * Fails unless [samples] hold an instance of every class that can be a
 * [type], a sealed class or interface, other than [excluded]. A sealed type
 * within it counts by its own subclasses, so [excluded] names concrete
 * (leaf) subclasses: excluding a sealed type within [type] has no effect.
 *
 * A screenshot test that takes the states it captures from [samples]
 * checks them with this, so that a state added to [type] fails the tests
 * until it has a screenshot, instead of going untested.
 *
 * Kotlin lists a sealed type's subclasses in the PermittedSubclasses
 * attribute of its class file, which the JVM reads without kotlin-reflect.
 */
// Runs on the test JVM, never on a device.
@SuppressLint("NewApi")
fun <T : Any> assertSamplesCover(
    type: Class<T>,
    samples: Collection<T>,
    excluded: Set<Class<out T>> = emptySet(),
) {
    check(type.isSealed) { "${type.name} is not sealed" }
    val missing = concreteSubclasses(type)
        .filter { it !in excluded }
        .filter { subclass -> samples.none { subclass.isInstance(it) } }
    if (missing.isNotEmpty()) {
        throw AssertionError(
            "No screenshot sample of ${missing.joinToString { it.name.substringAfterLast('.') }}",
        )
    }
}

@SuppressLint("NewApi")
private fun concreteSubclasses(type: Class<*>): List<Class<*>> = type.permittedSubclasses.orEmpty().flatMap {
    if (it.isSealed) concreteSubclasses(it) else listOf(it)
}

/** The name a sample's screenshot is saved under: its class's, `NoRoot` or `Failed`. */
val Any.screenshotName: String get() = this::class.java.simpleName
