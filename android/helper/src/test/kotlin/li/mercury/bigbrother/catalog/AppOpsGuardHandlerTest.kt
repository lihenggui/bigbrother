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

import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_DEFAULT
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_IGNORED
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.ALL_FILES_OP_NAME
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.COARSE
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.FINE
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.PACKAGE
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.UID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppOpsGuardHandlerTest {
    @Test
    fun givenOriginalState_whenCapturedThenRestoredByANewHelper_thenAppOpsAndPermissionFlagsMatch() {
        val android = FakeAndroid()
        android.pmGrant(COARSE)
        android.settingsGrant(FINE)
        val originalFlags = android.flags(PACKAGE, FINE, UID)
        val op = android.ops.first { it.name == "android:coarse_location" }
        val handler = AppOpsGuardHandler(android)
        val targets = "${op.name}=1,$FINE=revoked"

        handler.handle(AppOpsGuardProtocol.parseCommand("capture $UID 7 $targets")!!)
        val saved = android.printed.single().removePrefix("captured $UID 7 ").split(',')
            .filter { it.substringBefore('=') in setOf(op.name, FINE) }.joinToString(",")
        assertFalse(handler.isGuarded(UID))
        assertTrue(android.isGranted(FINE))
        assertEquals(originalFlags, android.flags(PACKAGE, FINE, UID))
        assertEquals(4, android.uidModes(UID, intArrayOf(op.switchCode))[op.switchCode])

        handler.handle(AppOpsGuardProtocol.parseCommand("set $UID $targets")!!)
        assertFalse(android.isGranted(FINE))
        assertEquals(1, android.uidModes(UID, intArrayOf(op.switchCode))[op.switchCode])

        val restarted = AppOpsGuardHandler(android)
        restarted.handle(AppOpsGuardProtocol.parseCommand("restore $UID $saved")!!)
        assertTrue(android.isGranted(FINE))
        assertEquals(originalFlags, android.flags(PACKAGE, FINE, UID))
        assertEquals(4, android.uidModes(UID, intArrayOf(op.switchCode))[op.switchCode])
    }

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

    @Test
    fun givenAllFilesAccessNotRequested_whenHeldAndCheckedAgain_thenItsModeIsLeftAndNothingIsUnheld() {
        val android = FakeAndroid()
        val handler = AppOpsGuardHandler(android)

        handler.handle(AppOpsGuardProtocol.parseCommand("set $UID $ALL_FILES_OP_NAME=1,android:read_contacts=1")!!)
        repeat(3) { handler.checkAll() }

        assertEquals(0, android.allFilesChanges)
        assertEquals(MODE_DEFAULT, android.mode(ALL_FILES_OP_NAME))
        assertEquals(MODE_IGNORED, android.mode("android:read_contacts"))
        assertEquals(listOf("unheld $UID -"), android.printed.filter { it.startsWith("unheld ") })
    }

    @Test
    fun givenAllFilesAccessNotRequested_whenOnlyItIsHeld_thenTheSetSucceeds() {
        val android = FakeAndroid()
        val handler = AppOpsGuardHandler(android)

        handler.handle(AppOpsGuardProtocol.parseCommand("set $UID $ALL_FILES_OP_NAME=1")!!)

        assertTrue(handler.isGuarded(UID))
        assertTrue(android.printed.any { it.startsWith("saved $UID") })
        assertEquals(0, android.allFilesChanges)
    }

    @Test
    fun givenAllFilesAccessRequested_whenHeld_thenItIsIgnored() {
        val android = FakeAndroid()
        android.requestsAllFiles = true
        val handler = AppOpsGuardHandler(android)

        handler.handle(AppOpsGuardProtocol.parseCommand("set $UID $ALL_FILES_OP_NAME=1")!!)

        assertEquals(MODE_IGNORED, android.mode(ALL_FILES_OP_NAME))
        assertEquals(listOf("unheld $UID -"), android.printed.filter { it.startsWith("unheld ") })
    }

    @Test
    fun givenUpdateThatRequestsAllFilesAccess_whenChecked_thenItIsHeld() {
        val android = FakeAndroid()
        val handler = AppOpsGuardHandler(android)
        handler.handle(AppOpsGuardProtocol.parseCommand("set $UID $ALL_FILES_OP_NAME=1")!!)

        android.requestsAllFiles = true
        handler.checkAll()

        assertEquals(MODE_IGNORED, android.mode(ALL_FILES_OP_NAME))
    }
}
