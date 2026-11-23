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

package li.mercury.bigbrother.feature.appdetail.impl

import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.feature.appdetail.impl.RecordDetail.App
import li.mercury.bigbrother.feature.appdetail.impl.RecordDetail.Plain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordDetailTest {
    @Test
    fun givenServiceCallAboutAnApp_whenDetailed_thenOnlyTheAppIsKept() {
        assertEquals(
            App("com.devadvance.rootcloak"),
            recordDetail("binder:android.content.pm.IPackageManager/getPackageInfo#com.devadvance.rootcloak"),
        )
        assertNull(recordDetail("binder:android.content.IClipboard/getPrimaryClip"))
        assertEquals(
            App("eu.chainfire.supersu"),
            recordDetail("binder:android.content.pm.IPackageManager/getPackageInfo#!eu.chainfire.supersu"),
        )
    }

    @Test
    fun givenCommandOrAddress_whenDetailed_thenKeptAsIs() {
        assertEquals(Plain("which su"), recordDetail("exec:which su"))
        assertEquals(Plain("127.0.0.1:8080"), recordDetail("net://127.0.0.1:8080"))
    }

    @Test
    fun givenRecordTheTitleNames_whenDetailed_thenNoDetail() {
        assertNull(recordDetail("/proc/version"))
        assertNull(recordDetail("content://sms"))
        assertNull(recordDetail("setting:secure/android_id"))
        assertNull(recordDetail("appop:android:read_clipboard"))
    }

    @Test
    fun givenCallsAboutTheSameApp_whenDetailed_thenAppListedOnce() {
        assertEquals(
            listOf(App("com.a")),
            recordDetails(
                listOf(
                    "binder:android.content.pm.IPackageManager/getPackageInfo#com.a",
                    "binder:android.content.pm.IPackageManager/getApplicationInfo#com.a",
                    "binder:android.content.pm.IPackageManager/getInstalledPackages",
                ),
                titled = true,
            ),
        )
    }

    @Test
    fun givenUntitledRow_whenDetailed_thenEachRecordListedOnceAsIs() {
        assertEquals(
            listOf(Plain("/sys/x"), Plain("/sys/y")),
            recordDetails(listOf("/sys/x", "/sys/y", "/sys/x"), titled = false),
        )
    }

    @Test
    fun givenSensorTurnedOn_whenDetailed_thenNotTakenForAnApp() {
        assertNull(recordDetail("binder:android.gui.SensorEventConnection/enableDisable@android.sensor.gyroscope:fast"))
        assertNull(recordDetail("binder:android.gui.SensorEventConnection/enableDisable"))
    }

    @Test
    fun givenSensorRecords_whenAppsAskedCounted_thenNone() {
        val calls = listOf(
            "binder:android.gui.SensorEventConnection/enableDisable@android.sensor.accelerometer",
            "binder:android.gui.SensorEventConnection/enableDisable@android.sensor.gyroscope:fast",
        ).map {
            Access(Category.SERVICE, it, Action.CALL, viaProvider = false, items = 0, allowed = 1, denied = 0, lastSeen = 0, topic = "sensors")
        }
        assertEquals(0, appsAsked(TopicGroup("sensors", calls, DataCategory.SENSORS, RecordSource.APP)))
    }
}
