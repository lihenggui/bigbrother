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

package li.mercury.bigbrother.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubjectsTest {
    @Test
    fun givenClipboardCallsAndPermissionRecords_whenRead_thenEachHasItsDirection() {
        assertEquals(ClipboardUse.READ, clipboardUse("binder:android.content.IClipboard/getPrimaryClip"))
        assertEquals(ClipboardUse.LISTEN, clipboardUse("binder:android.content.IClipboard/addPrimaryClipChangedListener"))
        assertEquals(ClipboardUse.WRITE, clipboardUse("binder:android.content.IClipboard/setPrimaryClip"))
        assertEquals(ClipboardUse.WRITE, clipboardUse("binder:android.content.IClipboard/setPrimaryClipAsPackage"))
        assertEquals(ClipboardUse.WRITE, clipboardUse("binder:android.content.IClipboard/clearPrimaryClip"))
        assertEquals(ClipboardUse.READ, clipboardUse("appop:android:read_clipboard"))
        assertEquals(ClipboardUse.WRITE, clipboardUse("appop:android:write_clipboard"))
    }

    @Test
    fun givenOtherRecords_whenReadForClipboard_thenNone() {
        assertNull(clipboardUse("binder:android.content.IClipboard/hasPrimaryClip"))
        assertNull(clipboardUse("appop:android:camera"))
        assertNull(clipboardUse("/proc/cpuinfo"))
    }

    @Test
    fun givenClipboardRecords_whenCounted_thenSummedByDirectionWithRefusals() {
        val counts = clipboardCounts(
            listOf(
                SubjectCount("binder:android.content.IClipboard/getPrimaryClip", allowed = 3, denied = 0),
                SubjectCount("appop:android:read_clipboard", allowed = 2, denied = 1),
                SubjectCount("binder:android.content.IClipboard/addPrimaryClipChangedListener", allowed = 1, denied = 0),
                SubjectCount("binder:android.content.IClipboard/setPrimaryClip", allowed = 1, denied = 0),
                SubjectCount("binder:android.content.IClipboard/clearPrimaryClip", allowed = 1, denied = 0),
            ),
        )
        assertEquals(ClipboardCounts(read = 5, readDenied = 1, listened = 1, written = 2), counts)
    }

    @Test
    fun givenNoClipboardRecord_whenCounted_thenNull() {
        assertNull(clipboardCounts(listOf(SubjectCount("binder:android.content.IClipboard/hasClipboardText", 2, 0))))
        assertNull(clipboardCounts(emptyList()))
    }

    @Test
    fun givenSensorTurnedOn_whenRead_thenTypeAndRate() {
        assertEquals(
            SensorUse("android.sensor.accelerometer", highRate = false),
            sensorUse("binder:android.gui.SensorEventConnection/enableDisable@android.sensor.accelerometer"),
        )
        assertEquals(
            SensorUse("android.sensor.gyroscope", highRate = true),
            sensorUse("binder:android.gui.SensorEventConnection/enableDisable@android.sensor.gyroscope:fast"),
        )
        assertEquals(SensorUse("65537", highRate = false), sensorUse("binder:android.gui.SensorEventConnection/enableDisable@65537"))
        // Turned on, but which sensor could not be told.
        assertEquals(SensorUse("", highRate = false), sensorUse("binder:android.gui.SensorEventConnection/enableDisable"))
    }

    @Test
    fun givenOtherRecords_whenReadForSensor_thenNone() {
        assertNull(sensorUse("binder:android.gui.SensorServer/createSensorEventConnection"))
        assertNull(sensorUse("binder:android.gui.SensorEventConnection/enableDisableX"))
        assertNull(sensorUse("appop:android:body_sensors"))
        assertNull(sensorUse("binder:android.content.pm.IPackageManager/getPackageInfo#com.example.a"))
    }

    @Test
    fun givenSensorRecords_whenSummed_thenEachSensorOnceMostUsedFirstAndHighRateKept() {
        val used = sensorsUsed(
            listOf(
                SubjectCount("binder:android.gui.SensorServer/createSensorEventConnection", allowed = 5, denied = 0),
                SubjectCount("binder:android.gui.SensorEventConnection/enableDisable@android.sensor.gyroscope", 1, 0),
                SubjectCount("binder:android.gui.SensorEventConnection/enableDisable@android.sensor.gyroscope:fast", 1, 0),
                SubjectCount("binder:android.gui.SensorEventConnection/enableDisable", 4, 0),
                SubjectCount("binder:android.gui.SensorEventConnection/enableDisable@android.sensor.accelerometer", 3, 0),
            ),
        )
        assertEquals(
            SensorsUsed(
                sensors = listOf(
                    SensorUse("android.sensor.accelerometer", highRate = false),
                    SensorUse("android.sensor.gyroscope", highRate = true),
                    SensorUse("", highRate = false),
                ),
                times = 9,
            ),
            used,
        )
    }

    @Test
    fun givenOnlyConnectionsOpened_whenSummed_thenNull() {
        assertNull(sensorsUsed(listOf(SubjectCount("binder:android.gui.SensorServer/createSensorEventConnection", 2, 0))))
    }
}
