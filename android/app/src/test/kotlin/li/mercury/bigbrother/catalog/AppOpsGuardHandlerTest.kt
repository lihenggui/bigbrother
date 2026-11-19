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

import li.mercury.bigbrother.catalog.FakeAndroid.Companion.COARSE
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.FINE
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.UID
import kotlin.test.Test
import kotlin.test.assertEquals

class AppOpsGuardHandlerTest {
    @Test
    fun givenGrantTheSystemWillNotTakeBack_whenCheckedAgainAndAgain_thenItsStateIsReportedOnce() {
        val android = FakeAndroid()
        android.pmGrant(COARSE)
        val handler = AppOpsGuardHandler(android)
        handler.handle(AppOpsGuardProtocol.parseCommand("set $UID $FINE=revoked")!!)
        android.pmGrant(FINE)
        android.refuseToRevoke = true

        repeat(3) { handler.checkAll() }

        assertEquals(listOf("reverted $UID $FINE=granted"), android.printed.filter { it.startsWith("reverted $UID $FINE=") })
    }
}
