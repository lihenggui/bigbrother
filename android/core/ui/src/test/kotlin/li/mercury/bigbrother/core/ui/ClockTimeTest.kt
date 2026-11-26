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

import android.content.res.Configuration
import android.provider.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ClockTimeTest {
    @Test
    fun givenClockPreference_whenFormattingWithSeconds_thenUsesBothHourCycles() {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.US) }
        val context = app.createConfigurationContext(config)
        val time = LocalDateTime.of(2026, 1, 2, 15, 4, 5).atZone(ZoneId.systemDefault()).toInstant()

        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        val twelveHourTime = formatClockTime(context, time, includeSeconds = true)
        assertEquals("3:04:05 PM", twelveHourTime.replace(Regex("""[\s\p{Zs}]+"""), " "))

        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        assertEquals("15:04:05", formatClockTime(context, time, includeSeconds = true))
        assertEquals("15:04", formatClockTime(context, time))
    }

    @Test
    fun givenUsLocale_whenFormattingHourBucket_thenKeepsDayPeriodOrMinutes() {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.US) }
        val context = app.createConfigurationContext(config)
        val morning = LocalDateTime.of(2026, 1, 2, 3, 0).toInstant(ZoneOffset.UTC)
        val afternoon = LocalDateTime.of(2026, 1, 2, 15, 0).toInstant(ZoneOffset.UTC)

        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        val am = formatClockTime(context, morning, zone = ZoneOffset.UTC, hourOnlyIn12HourCycle = true)
        val pm = formatClockTime(context, afternoon, zone = ZoneOffset.UTC, hourOnlyIn12HourCycle = true)
        assertEquals("3 AM", am.replace(Regex("""[\s\p{Zs}]+"""), " "))
        assertEquals("3 PM", pm.replace(Regex("""[\s\p{Zs}]+"""), " "))

        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        assertEquals("03:00", formatClockTime(context, morning, zone = ZoneOffset.UTC, hourOnlyIn12HourCycle = true))
        assertEquals("15:00", formatClockTime(context, afternoon, zone = ZoneOffset.UTC, hourOnlyIn12HourCycle = true))
    }

    @Test
    fun givenChineseLocaleAndTwelveHourPreference_whenFormattingHourBucket_thenUsesLocalizedHour() {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val context = app.createConfigurationContext(config)
        val time = LocalDateTime.of(2026, 1, 2, 15, 0).toInstant(ZoneOffset.UTC)
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")

        assertEquals("下午3时", formatClockTime(context, time, zone = ZoneOffset.UTC, hourOnlyIn12HourCycle = true))
    }

    @Test
    fun givenExplicitZone_whenFormattingClockTime_thenUsesThatOffset() {
        val context = RuntimeEnvironment.getApplication()
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        val instant = LocalDateTime.of(2026, 1, 2, 15, 4).toInstant(ZoneOffset.UTC)

        assertEquals("20:34", formatClockTime(context, instant, zone = ZoneOffset.ofHoursMinutes(5, 30)))
    }

    @Test
    fun givenChineseLocaleAndTwelveHourPreference_whenFormattingWithSeconds_thenDayPeriodComesFirst() {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val context = app.createConfigurationContext(config)
        val time = LocalDateTime.of(2026, 1, 2, 15, 4, 5).atZone(ZoneId.systemDefault()).toInstant()
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")

        val formatted = formatClockTime(context, time, includeSeconds = true)

        assertTrue(formatted.startsWith("下午"), formatted)
        assertContains(formatted, "3:04:05")
    }
}
