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

package li.mercury.bigbrother.feature.jail.impl.network

import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.TimeRange
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkFormatsTest {
    @Test
    fun givenAmounts_whenFormatted_thenWholeBytesAndKilobytesAndOneDecimalBeyond() {
        assertEquals("0 B", formatBytes(0, Locale.US))
        assertEquals("1023 B", formatBytes(1_023, Locale.US))
        assertEquals("1 KB", formatBytes(1_024, Locale.US))
        assertEquals("96 KB", formatBytes(96 * 1_024, Locale.US))
        assertEquals("1.1 MB", formatBytes(1_153_434, Locale.US))
        assertEquals("18.2 MB", formatBytes(19_083_878, Locale.US))
        assertEquals("1.0 GB", formatBytes(1_073_741_000, Locale.US))
        assertEquals("2.5 GB", formatBytes(2_684_354_560, Locale.US))
    }

    @Test
    fun givenAmountJustUnderAMegabyte_whenFormatted_thenNotThousandsOfKilobytes() {
        assertEquals("1.0 MB", formatBytes(1_048_500, Locale.US))
    }

    @Test
    fun givenLocaleWithDecimalComma_whenFormatted_thenUsesIt() {
        assertEquals("1,1 MB", formatBytes(1_153_434, Locale.GERMANY))
    }

    @Test
    fun givenCommonPorts_whenNamed_thenServiceOrNothing() {
        assertEquals("HTTPS", portService(443))
        assertEquals("DNS", portService(53))
        assertNull(portService(8080))
        assertNull(portService(5223))
    }

    @Test
    fun givenHourBeforeMidnight_whenLabelled_thenEndsAtMidnight() {
        val start = LocalDateTime.of(2026, 11, 17, 23, 0).toEpochSecond(ZoneOffset.UTC)

        assertEquals("23:00–00:00", hourRange(start, BucketSize.HOUR.seconds, ZoneOffset.UTC))
        assertEquals("03:00–04:00", hourRange(start + 4 * 3_600, BucketSize.HOUR.seconds, ZoneOffset.UTC))
    }

    @Test
    fun givenRanges_whenRead_thenHoursForADayAndDaysBeyondAsFarAsRecordsGo() {
        assertEquals(BucketSize.HOUR, TimeRange.DAY.bucketSize)
        assertEquals(BucketSize.DAY, TimeRange.WEEK.bucketSize)
        assertEquals(1_000_000L - 30 * 86_400, TimeRange.ALL.since(1_000_000))
    }
}
