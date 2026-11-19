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

import android.os.SystemClock
import org.junit.rules.ExternalResource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Makes it [SHOWN_TIME] today, whenever the screenshots are taken.
 *
 * The screens read the real clock (`System.currentTimeMillis()`,
 * `LocalDate.now()`), which a test cannot set, and show times of day, "today"
 * and "2 hours ago". What a test can set is the time zone: this rule picks
 * the offset from UTC at which it is [SHOWN_TIME] now, so that records
 * placed at fixed times of today ([todayAt]) are always today, always in
 * the past, and always show the same times. The previous time zone is
 * restored afterwards.
 *
 * Apply it before the compose rule: it also sets Robolectric's clock, which
 * the framework reads (`DateUtils.isToday`), to the real time, which the
 * app reads. Robolectric resets that clock after each test.
 */
class DemoClockRule : ExternalResource() {
    private var previous: TimeZone? = null

    override fun before() {
        val now = System.currentTimeMillis()
        previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId(now)))
        // With a paused looper, Robolectric's wall clock starts at its
        // uptime, in 1970; it can only move forward.
        SystemClock.setCurrentTimeMillis(now)
    }

    override fun after() {
        TimeZone.setDefault(previous)
    }

    /** [hour]:[minute] today, as Unix time. */
    fun todayAt(hour: Int, minute: Int): Long = LocalDate.now().atTime(hour, minute).atZone(ZoneId.systemDefault()).toEpochSecond()

    /**
     * A fixed date and time, as Unix time: shown the same in any time zone
     * the rule picks. For what a screen shows with its date: a time of
     * [todayAt] shows the day the test runs.
     */
    fun at(dateTime: LocalDateTime): Long = dateTime.atZone(ZoneId.systemDefault()).toEpochSecond()

    private companion object {
        /** The time of day the screenshots show as now. */
        val SHOWN_TIME: LocalTime = LocalTime.of(15, 0)

        const val SECONDS_PER_DAY = 86_400L
        const val SECONDS_PER_MINUTE = 60L

        /**
         * A custom zone (`GMT+05:17`) in which it is between [SHOWN_TIME]
         * and a minute after at [nowMillis]. Offsets are whole minutes, so
         * the offset is rounded up: earlier than [SHOWN_TIME], the latest
         * record of the day could be in the future.
         */
        fun zoneId(nowMillis: Long): String {
            val utcSecondOfDay = Math.floorMod(nowMillis / 1000, SECONDS_PER_DAY)
            // Within ±12 hours: any time of day is reachable from UTC by one.
            var offset = SHOWN_TIME.toSecondOfDay() - utcSecondOfDay
            if (offset >= SECONDS_PER_DAY / 2) offset -= SECONDS_PER_DAY
            if (offset < -SECONDS_PER_DAY / 2) offset += SECONDS_PER_DAY
            val minutes = Math.floorDiv(offset + SECONDS_PER_MINUTE - 1, SECONDS_PER_MINUTE)
            val sign = if (minutes < 0) "-" else "+"
            return String.format(Locale.ROOT, "GMT%s%02d:%02d", sign, abs(minutes) / 60, abs(minutes) % 60)
        }
    }
}
