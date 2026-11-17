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

package li.mercury.bigbrother.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SensorNamesTest {
    @Test
    fun givenPlatformSensorType_whenNamed_thenHasItsOwnName() {
        assertEquals(R.string.sensor_accelerometer, sensorNameRes("android.sensor.accelerometer"))
        assertEquals(R.string.sensor_magnetic_field, sensorNameRes("android.sensor.magnetic_field"))
        assertEquals(R.string.sensor_pressure, sensorNameRes("android.sensor.pressure"))
    }

    @Test
    fun givenOtherSensorType_whenNamed_thenNoOwnName() {
        assertNull(sensorNameRes("com.google.sensor.binned_brightness"))
        assertNull(sensorNameRes("android.sensor.something_new"))
        // The last part alone is not a platform type.
        assertNull(sensorNameRes("accelerometer"))
    }

    @Test
    fun givenUnknownSensorType_whenMadeReadable_thenLastPartInWords() {
        assertEquals("Binned brightness", readableSensorType("com.google.sensor.binned_brightness"))
        assertEquals("Something new", readableSensorType("android.sensor.something_new"))
        assertEquals("Lux", readableSensorType("lux"))
    }

    @Test
    fun givenNumberOrNothing_whenMadeReadable_thenNull() {
        assertNull(readableSensorType("65537"))
        assertNull(readableSensorType(""))
    }
}
