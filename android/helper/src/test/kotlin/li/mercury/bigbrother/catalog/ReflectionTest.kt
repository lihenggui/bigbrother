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
import kotlin.test.Test
import kotlin.test.assertEquals

class ReflectionTest {
    @Test
    fun givenFrameworkFailuresWithPrivateMessages_whenDescribing_thenOnlyCauseTypesArePrinted() {
        val cause = NoSuchMethodException("com.example.private.hiddenMethod")
        val denied = SecurityException("denied for com.example.private", cause)
        val failure = InvocationTargetException(IllegalStateException("/data/user/0/com.example.private", denied), "wrapper message")

        assertEquals("IllegalStateException caused by SecurityException caused by NoSuchMethodException", describeFailure(failure))
    }

    @Test
    fun givenAllDeviceOverloads_whenCalling_thenPersistentDeviceOverloadWins() {
        assertEquals("android.permission.CAMERA/default:0/10", read(PersistentDevice()))
    }

    @Test
    fun givenOnlyNumericAndLegacyOverloads_whenCalling_thenNumericDeviceOverloadWins() {
        assertEquals("android.permission.CAMERA/0/10", read(NumericDevice()))
    }

    @Test
    fun givenOnlyTheLegacyOverload_whenCalling_thenNoDeviceArgumentIsAdded() {
        assertEquals("android.permission.CAMERA/10", read(LegacyDevice()))
    }

    private fun read(target: LegacyDevice): Any? = DeviceCall(target.javaClass, "read", listOf(STRING), listOf(INT)).call(target, listOf("android.permission.CAMERA"), listOf(10))

    open class LegacyDevice {
        fun read(permission: String, user: Int) = "$permission/$user"
    }

    open class NumericDevice : LegacyDevice() {
        fun read(permission: String, device: Int, user: Int) = "$permission/$device/$user"
    }

    class PersistentDevice : NumericDevice() {
        fun read(permission: String, device: String, user: Int) = "$permission/$device/$user"
    }
}
