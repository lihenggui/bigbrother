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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AppOpsDumpTest {
    @Test
    fun givenMissingOrInvalidSeconds_whenValidatingRepeatInterval_thenItIsRejected() {
        for (seconds in listOf(null, "0", "-1", "com.example.private", Long.MAX_VALUE.toString())) {
            assertNull(AppOpsDump.intervalMillis(seconds))
        }
    }

    @Test
    fun givenPositiveSeconds_whenValidatingRepeatInterval_thenMillisecondsAreReturned() {
        assertEquals(30_000L, AppOpsDump.intervalMillis("30"))
        assertEquals(1_000L, AppOpsDump.intervalMillis("1"))
    }

    @Test
    fun givenKnownAndUnknownOps_whenTakingRepeatedReadings_thenCodesAreReusedAndRowsHaveTheExpectedFormat() {
        OpNames.lookups = 0
        val service = TestService()
        val reader = AppOpsDumpReader(service, listOf("android:camera", "unknown"), OpNames::class.java, TestService::class.java)

        val expected = "10100\tcom.example.camera\tandroid:camera\t12345\t-1\n"
        assertEquals(expected, reader.dump())
        assertEquals(expected, reader.dump())
        assertEquals(2, OpNames.lookups)
        assertEquals(listOf(26), service.codes[0].toList())
        assertSame(service.codes[0], service.codes[1])
    }

    class OpNames {
        companion object {
            var lookups = 0

            @JvmStatic
            fun strOpToOp(name: String): Int {
                lookups++
                require(name == "android:camera")
                return 26
            }
        }
    }

    class TestService {
        val codes = mutableListOf<IntArray>()

        fun getPackagesForOps(codes: IntArray): List<TestPackage> {
            this.codes += codes
            return listOf(TestPackage())
        }
    }

    class TestPackage {
        fun getUid() = 10_100

        fun getPackageName() = "com.example.camera"

        fun getOps() = listOf(TestOp())
    }

    class TestOp {
        fun getOpStr() = "android:camera"

        fun getLastAccessTime(flags: Int): Long {
            assertEquals(31, flags)
            return 12345L
        }

        fun getLastRejectTime(flags: Int): Long {
            assertEquals(31, flags)
            return -1L
        }
    }
}
