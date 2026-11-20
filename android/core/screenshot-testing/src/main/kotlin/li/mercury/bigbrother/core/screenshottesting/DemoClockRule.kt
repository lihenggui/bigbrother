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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Places the demo records on today's date and gives elapsed-time labels a
 * fixed reference at [SHOWN_TIME].
 *
 * The time zone puts the real clock near [SHOWN_TIME], so records placed
 * at fixed times of day are already in the past. Use [shownClock] for
 * elapsed times, which otherwise depend on how long the capture takes.
 *
 * Apply it before the compose rule. It aligns Robolectric's wall clock
 * with the host for the framework's date checks (`DateUtils.isToday`).
 */
class DemoClockRule : ExternalResource() {
    private var previous: TimeZone? = null
    lateinit var shownClock: Clock
        private set

    override fun before() {
        val now = System.currentTimeMillis()
        previous = TimeZone.getDefault()
        val timeZone = TimeZone.getTimeZone(zoneId(now))
        TimeZone.setDefault(timeZone)
        val zone = timeZone.toZoneId()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        shownClock = Clock.fixed(today.atTime(SHOWN_TIME).atZone(zone).toInstant(), zone)
        // With a paused looper, Robolectric's wall clock starts at its
        // uptime, in 1970; it can only move forward.
        SystemClock.setCurrentTimeMillis(now)
    }

    override fun after() {
        TimeZone.setDefault(previous)
    }

    /** [hour]:[minute] today, as Unix time. */
    fun todayAt(hour: Int, minute: Int): Long = at(LocalDate.now(shownClock).atTime(hour, minute))

    /**
     * A fixed date and time, as Unix time: shown the same in any time zone
     * the rule picks. For what a screen shows with its date: a time of
     * [todayAt] shows the day the test runs.
     */
    fun at(dateTime: LocalDateTime): Long = dateTime.atZone(shownClock.zone).toEpochSecond()

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
