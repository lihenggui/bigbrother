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

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import li.mercury.bigbrother.core.model.BucketSize
import li.mercury.bigbrother.core.model.TimeRange
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NetworkFormatsTest {
    private fun context(locale: Locale): Context {
        val app = RuntimeEnvironment.getApplication()
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(locale) })
    }

    @Test
    fun givenAmounts_whenFormatted_thenWholeBytesAndKilobytesAndOneDecimalBeyond() {
        assertEquals("0 B", formatBytes(context(Locale.US), 0))
        assertEquals("1023 B", formatBytes(context(Locale.US), 1_023))
        assertEquals("1 KB", formatBytes(context(Locale.US), 1_024))
        assertEquals("96 KB", formatBytes(context(Locale.US), 96 * 1_024))
        assertEquals("1.1 MB", formatBytes(context(Locale.US), 1_153_434))
        assertEquals("18.2 MB", formatBytes(context(Locale.US), 19_083_878))
        assertEquals("1.0 GB", formatBytes(context(Locale.US), 1_073_741_000))
        assertEquals("2.5 GB", formatBytes(context(Locale.US), 2_684_354_560))
    }

    @Test
    fun givenAmountJustUnderAMegabyte_whenFormatted_thenNotThousandsOfKilobytes() {
        assertEquals("1.0 MB", formatBytes(context(Locale.US), 1_048_500))
    }

    @Test
    fun givenLocaleWithDecimalComma_whenFormatted_thenUsesIt() {
        assertEquals("1,1 MB", formatBytes(context(Locale.GERMANY), 1_153_434))
    }

    @Test
    fun givenArabicLocale_whenFormattingBytesAndKilobytes_thenUsesLocalDigits() {
        val context = context(Locale.forLanguageTag("ar-EG"))
        assertEquals("١٠٢٣ B", formatBytes(context, 1_023))
        assertEquals("٩٦ KB", formatBytes(context, 96 * 1_024))
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
        val context = context(Locale.US)
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        val start = LocalDateTime.of(2026, 11, 17, 23, 0).toEpochSecond(ZoneOffset.UTC)

        assertEquals("23:00–00:00", hourRange(context, start, BucketSize.HOUR.seconds, ZoneOffset.UTC))
        assertEquals("03:00–04:00", hourRange(context, start + 4 * 3_600, BucketSize.HOUR.seconds, ZoneOffset.UTC))
    }

    @Test
    fun givenRanges_whenRead_thenHoursForADayAndDaysBeyondAsFarAsRecordsGo() {
        assertEquals(BucketSize.HOUR, TimeRange.DAY.bucketSize)
        assertEquals(BucketSize.DAY, TimeRange.WEEK.bucketSize)
        assertEquals(1_000_000L - 30 * 86_400, TimeRange.ALL.since(1_000_000))
    }
}
