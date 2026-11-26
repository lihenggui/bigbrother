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

import li.mercury.bigbrother.core.model.SensorSession
import java.text.NumberFormat
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensorSessionsTest {
    private fun session(sensor: String, start: Long, end: Long?, highRate: Boolean = false) = SensorSession(sensor, highRate, periodNs = 20_000_000, rateHz = 50.0, start = start, end = end)

    @Test
    fun givenSessionsOfSeveralSensors_whenGrouped_thenMostRecentSensorFirstAndSessionsMostRecentFirst() {
        val groups = groupSessions(
            listOf(
                session(ACCELEROMETER, start = 100, end = 200),
                session(GYROSCOPE, start = 300, end = 400),
                session(ACCELEROMETER, start = 500, end = 600),
            ),
        )
        assertEquals(listOf(ACCELEROMETER, GYROSCOPE), groups.map { it.sensor })
        assertEquals(listOf(500L, 100L), groups.first().sessions.map { it.start })
    }

    @Test
    fun givenSessionStillOn_whenTotalled_thenCountsUpToNow() {
        val group = groupSessions(listOf(session(ACCELEROMETER, start = 120, end = 180), session(ACCELEROMETER, start = 1200, end = null)))
            .single()
        assertEquals(60L + 300L, group.totalSeconds(now = 1500))
    }

    @Test
    fun givenOneSessionAtHighRate_whenGrouped_thenGroupIsHighRate() {
        assertTrue(groupSessions(listOf(session(GYROSCOPE, 0, 1), session(GYROSCOPE, 2, 3, highRate = true))).single().highRate)
        assertFalse(groupSessions(listOf(session(GYROSCOPE, 0, 1))).single().highRate)
    }

    @Test
    fun givenDurations_whenSplit_thenInTheUnitsTheyReadBestIn() {
        assertEquals(DurationParts(seconds = 45), durationParts(45))
        assertEquals(DurationParts(minutes = 15), durationParts(15 * 60))
        assertEquals(DurationParts(minutes = 14), durationParts(14 * 60 + 59))
        assertEquals(DurationParts(hours = 1, minutes = 12), durationParts(72 * 60))
        assertEquals(DurationParts(hours = 2), durationParts(2 * 3600))
        assertEquals(DurationParts(days = 1, hours = 3), durationParts(27 * 3600 + 10 * 60))
        assertEquals(DurationParts(seconds = 0), durationParts(-5))
    }

    @Test
    fun givenTimesShownToTheMinute_whenDurationTaken_thenTheMinutesBetweenThem() {
        // 14:02:40 to 14:17:10 reads "2:02 PM – 2:17 PM": 15 minutes.
        assertEquals(15 * 60L, shownDuration(start = at(14, 2, 40), end = at(14, 17, 10)))
        assertEquals(15 * 60L, shownDuration(start = at(14, 2, 0), end = at(14, 17, 59)))
        // Under a minute, as it was.
        assertEquals(20L, shownDuration(start = at(14, 2, 50), end = at(14, 3, 10)))
        assertEquals(0L, shownDuration(start = at(14, 3, 0), end = at(14, 2, 0)))
    }

    @Test
    fun givenRates_whenFormatted_thenWholeFromTenHertz() {
        val us = NumberFormat.getNumberInstance(Locale.US)
        val german = NumberFormat.getNumberInstance(Locale.GERMANY)
        assertEquals("50", formatRate(50.0, us))
        assertEquals("15", formatRate(1e9 / 66_667_000, us))
        assertEquals("5", formatRate(5.0, us))
        assertEquals("0.5", formatRate(0.5, us))
        assertEquals("0,5", formatRate(0.5, german))
    }

    private fun at(hour: Int, minute: Int, second: Int): Long = hour * 3600L + minute * 60L + second

    private companion object {
        const val ACCELEROMETER = "android.sensor.accelerometer"
        const val GYROSCOPE = "android.sensor.gyroscope"
    }
}
