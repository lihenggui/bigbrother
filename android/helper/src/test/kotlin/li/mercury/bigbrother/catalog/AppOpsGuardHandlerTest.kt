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

import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.FLAG_USER_FIXED
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_ALLOWED
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_DEFAULT
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_IGNORED
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.ALL_FILES_OP_NAME
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.COARSE
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.CONTACTS
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.FINE
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.PACKAGE
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.UID
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.USAGE_OP_NAME
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppOpsGuardHandlerTest {
    @Test
    fun givenLostRepliesAndTwoListeners_whenAnotherHelperRecovers_thenBothOriginalServicesReturn() {
        val access = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
        val services = setOf("com.example.reader/com.example.reader.First", "com.example.reader/com.example.reader.Second")
        for (delayed in listOf(false, true)) {
            val android = FakeAndroid()
            android.pmGrant(FakeAndroid.CAMERA)
            val originalCamera = android.mode("android:camera")
            android.setServices(access, services)
            val daemon = DaemonDouble(android)

            daemon.enterWithInterruptedReply("android:camera=1,$access=revoked", delayed)
            assertEquals(MODE_IGNORED, android.mode("android:camera"))
            assertTrue(android.services(access).isEmpty())
            daemon.restartHelper()
            daemon.recoverInterruptedChange()

            assertEquals(originalCamera, android.mode("android:camera"))
            assertEquals(services, android.services(access))
        }
    }

    @Test
    fun givenServiceRegrantedAfterHelperRestart_whenLeavingJail_thenBothServicesAreRestored() {
        checkServiceRegrantAfterRestart { daemon, _ -> daemon.disable() }
    }

    @Test
    fun givenServiceRegrantedAfterHelperRestart_whenTurningItsItemOff_thenBothServicesAreRestored() {
        checkServiceRegrantAfterRestart { daemon, item -> daemon.set(item, "off") }
    }

    private fun checkServiceRegrantAfterRestart(restore: (DaemonDouble, String) -> Unit) {
        val accesses = mapOf(
            "notification_content" to "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "screen_content" to "android.permission.BIND_ACCESSIBILITY_SERVICE",
        )
        val first = "$PACKAGE/$PACKAGE.First"
        val second = "$PACKAGE/$PACKAGE.Second"
        for ((item, access) in accesses) {
            for (checked in listOf(true, false)) {
                val android = FakeAndroid()
                android.setServices(access, setOf(first))
                val daemon = DaemonDouble(android)
                daemon.set(item, "on")
                daemon.enable()
                assertTrue(android.services(access).isEmpty())
                daemon.restartHelper()
                android.setServices(access, setOf(second))
                if (checked) {
                    daemon.check()
                    assertTrue(android.services(access).isEmpty())
                }

                restore(daemon, item)

                assertEquals(setOf(first, second), android.services(access), "$item, checked=$checked")
            }
        }
    }

    @Test
    fun givenAnotherAccessibilityServiceChangesAfterReading_whenHolding_thenTheOtherChangesAreKept() {
        val jailed = "$PACKAGE/$PACKAGE.Service"
        val old = "com.example.reader/com.example.reader.Old"
        val new = "com.example.reader/com.example.reader.New"
        var enabled = listOf(old, jailed)
        var changePending = true

        updateAccessibilityServices(
            setOf(jailed),
            given = false,
            read = {
                enabled.also {
                    if (changePending) {
                        enabled = listOf(jailed, new)
                        changePending = false
                    }
                }
            },
            write = { enabled = it },
        )

        assertEquals(listOf(new), enabled)
    }

    @Test
    fun givenAnotherAccessibilityWriterUsesAnEarlierList_whenRestoring_thenBothChangesAreKept() {
        val jailed = "$PACKAGE/$PACKAGE.Service"
        val reader = "com.example.reader/com.example.reader.Service"
        var enabled = emptyList<String>()
        var changePending = true

        updateAccessibilityServices(
            setOf(jailed),
            given = true,
            read = { enabled },
            write = {
                enabled = if (changePending) listOf(reader) else it
                changePending = false
            },
        )

        assertEquals(listOf(reader, jailed), enabled)
    }

    @Test
    fun givenARegrantWhileHeld_whenChecked_thenItIsKeptUntilTheDaemonPersistsIt() {
        val android = FakeAndroid()
        val handler = AppOpsGuardHandler(android, supervised = true)
        handler.handle(AppOpsGuardProtocol.parseCommand("verify $UID $FINE=revoked")!!)
        android.pmGrant(FINE)
        handler.checkAll()

        assertTrue(android.isGranted(FINE))
        val found = android.printed.last { it.startsWith("reverted $UID $FINE=") }
        handler.handle(AppOpsGuardProtocol.parseCommand(found.replaceFirst("reverted", "persisted"))!!)
        handler.checkAll()

        assertFalse(android.isGranted(FINE))
    }

    @Test
    fun givenPreviousSuccess_whenTheNextReadThrows_thenOnlyUnknownIsReported() {
        val android = FakeAndroid()
        val handler = AppOpsGuardHandler(android, supervised = true)
        handler.handle(AppOpsGuardProtocol.parseCommand("verify $UID android:camera=1")!!)
        assertTrue("unheld $UID -" in android.printed)
        android.printed.clear()
        android.uidModesUnavailable = true

        handler.checkAll()

        assertEquals(listOf("checking $UID", "unknown-readback $UID"), android.printed)
    }

    @Test
    fun givenAnUndoAtItsRetryLimit_whenReadBack_thenItPerformsNoWrites() {
        val android = FakeAndroid()
        android.requestsAllFiles = true
        android.userSetsMode(ALL_FILES_OP_NAME, MODE_IGNORED)
        val handler = AppOpsGuardHandler(android)
        val touched = android.allFilesChanges
        val command = AppOpsGuardProtocol.parseCommand("check-undo $UID $ALL_FILES_OP_NAME=0")!!

        assertFailsWith<IllegalStateException> { handler.handle(command, transaction = true) }

        assertEquals(touched, android.allFilesChanges)
        assertEquals(MODE_IGNORED, android.mode(ALL_FILES_OP_NAME))
    }

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
    fun givenUsageAccessNotMappedBackToItsOp_whenNotRequestedAndHeld_thenItIsNeverWritten() {
        val android = FakeAndroid()
        val handler = AppOpsGuardHandler(android)

        handler.handle(AppOpsGuardProtocol.parseCommand("set $UID $USAGE_OP_NAME=1,android:read_contacts=1")!!)
        repeat(3) { handler.checkAll() }

        assertEquals(0, android.helperWrites.getOrDefault(USAGE_OP_NAME, 0))
        assertEquals(MODE_DEFAULT, android.mode(USAGE_OP_NAME))
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
    fun givenAnotherProfileMayBeRunning_whenHoldingAllFiles_thenTheOpIsUnheldUntilItIsSafeToWrite() {
        for (running in listOf(true, null)) {
            val android = FakeAndroid()
            android.requestsAllFiles = true
            android.userSetsMode(ALL_FILES_OP_NAME, MODE_ALLOWED)
            android.otherProfileRunning = running
            val handler = AppOpsGuardHandler(android)

            handler.send("set $UID $ALL_FILES_OP_NAME=1,android:camera=1")
            repeat(10) { handler.checkAll() }

            assertEquals(0, android.helperWrites.getOrDefault(ALL_FILES_OP_NAME, 0))
            assertEquals(MODE_ALLOWED, android.mode(ALL_FILES_OP_NAME))
            assertEquals(MODE_IGNORED, android.mode("android:camera"))
            assertEquals("unheld $UID $ALL_FILES_OP_NAME", android.printed.last { it.startsWith("unheld ") })

            android.otherProfileRunning = false
            handler.checkAll()

            assertEquals(1, android.helperWrites[ALL_FILES_OP_NAME])
            assertEquals(MODE_IGNORED, android.mode(ALL_FILES_OP_NAME))
            assertEquals("unheld $UID -", android.printed.last { it.startsWith("unheld ") })
        }
    }

    @Test
    fun givenAnotherProfileIsRunning_whenRestoringAllFiles_thenTheSavedModeStaysRetryable() {
        val android = FakeAndroid()
        android.requestsAllFiles = true
        val handler = AppOpsGuardHandler(android)
        handler.send("set $UID $ALL_FILES_OP_NAME=1")
        android.otherProfileRunning = true

        assertFailsWith<IllegalStateException> { handler.send("restore $UID $ALL_FILES_OP_NAME=default") }

        assertEquals(1, android.helperWrites[ALL_FILES_OP_NAME])
        assertEquals(MODE_IGNORED, android.mode(ALL_FILES_OP_NAME))
        assertEquals("unheld $UID -", android.printed.last { it.startsWith("unheld ") })
        assertTrue(handler.isGuarded(UID))

        android.otherProfileRunning = false
        handler.send("restore $UID $ALL_FILES_OP_NAME=default")

        assertEquals(2, android.helperWrites[ALL_FILES_OP_NAME])
        assertEquals(MODE_DEFAULT, android.mode(ALL_FILES_OP_NAME))
        assertEquals("restored $UID", android.printed.last())
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

    @Test
    fun givenMatchingOpModes_whenSetAppliedVerifiedAndReleased_thenNoModeIsWritten() {
        val android = FakeAndroid()
        android.requestsAllFiles = true
        android.userSetsMode(ALL_FILES_OP_NAME, MODE_IGNORED)
        val handler = AppOpsGuardHandler(android)
        val targets = "$ALL_FILES_OP_NAME=1,android:read_contacts=1"

        handler.send("set $UID $targets")
        handler.send("apply $UID $targets -")
        handler.send("verify $UID $targets")
        handler.send("release $UID")
        handler.send("restore $UID $ALL_FILES_OP_NAME=1")

        assertTrue(android.helperWrites.isEmpty(), android.helperWrites.toString())
        assertEquals("restored $UID", android.printed.last())
    }

    @Test
    fun givenAnIndependentWifiScanSwitch_whenLocationMovesToForegroundAndOff_thenItsOriginalModeReturns() {
        val android = FakeAndroid(wifiScanSharesCoarseLocation = false)
        val daemon = DaemonDouble(android)
        val scan = "android:wifi_scan"
        android.userSetsMode(scan, MODE_IGNORED)

        daemon.enable()
        assertEquals(MODE_IGNORED, android.mode(scan))
        daemon.set("location", "foreground")
        assertEquals(AppOpsGuardProtocol.MODE_FOREGROUND, android.mode(scan))
        daemon.set("location", "off")
        assertEquals(MODE_IGNORED, android.mode(scan))
    }

    @Test
    fun givenSharedSwitchTargets_whenSetAndRestored_thenEachModeIsWrittenOnce() {
        val android = FakeAndroid()
        android.pmGrant(COARSE)
        val handler = AppOpsGuardHandler(android)
        val targets = "android:coarse_location=1,android:wifi_scan=1"

        handler.send("set $UID $targets")
        handler.send("release $UID")

        assertEquals(mapOf("android:coarse_location" to 2), android.helperWrites)
    }

    @Test
    fun givenContactsOpRepeatedlyReset_whenChecked_thenEveryResetIsReapplied() {
        val android = FakeAndroid()
        val op = "android:read_contacts"
        android.pmGrant(CONTACTS)
        android.resetOpsAfterWrite[op] = MODE_ALLOWED
        val handler = AppOpsGuardHandler(android) { 0L }
        handler.send("set $UID $op=1")

        repeat(20) {
            android.settleExternalChanges()
            assertEquals(MODE_ALLOWED, android.mode(op))
            handler.checkAll()
            assertEquals(MODE_IGNORED, android.mode(op))
        }

        assertEquals(21, android.helperWrites[op])
        assertEquals(listOf("unheld $UID -"), android.printed.filter { it.startsWith("unheld ") })
    }

    @Test
    fun givenRevokedPermissionWithRepeatedlyClearedFlags_whenChecked_thenFlagRepairsLeaveTheRevocationBudgetAvailable() {
        val android = FakeAndroid()
        val handler = AppOpsGuardHandler(android) { 0L }
        handler.send("set $UID $FINE=revoked")

        repeat(20) {
            android.settingsRevoke(FINE)
            handler.checkAll()
            assertTrue(android.hasFlag(FINE, FLAG_USER_FIXED))
        }
        assertEquals(0, android.revocations.getOrDefault(FINE, 0))

        android.settingsGrant(FINE)
        handler.checkAll()
        assertEquals(1, android.revocations[FINE])
        assertFalse(android.isGranted(FINE))
        assertEquals(listOf("unheld $UID -"), android.printed.filter { it.startsWith("unheld ") })
    }

    @Test
    fun givenAllFilesAccessRepeatedlyReset_whenCheckedAndTargetsRepeated_thenWritesStopAndTheOpIsUnheld() {
        val android = FakeAndroid()
        android.requestsAllFiles = true
        android.resetOpsAfterWrite[ALL_FILES_OP_NAME] = MODE_DEFAULT
        val handler = AppOpsGuardHandler(android)
        val targets = "$ALL_FILES_OP_NAME=1,android:read_contacts=1"

        handler.send("set $UID $targets")
        repeat(20) {
            android.settleExternalChanges()
            handler.checkAll()
        }
        handler.send("set $UID $targets")
        handler.send("apply $UID $targets -")
        handler.send("verify $UID $targets")

        assertEquals(4, android.helperWrites[ALL_FILES_OP_NAME])
        assertEquals(8, android.allFilesChanges)
        assertEquals(MODE_IGNORED, android.mode("android:read_contacts"))
        assertEquals("unheld $UID $ALL_FILES_OP_NAME", android.printed.last { it.startsWith("unheld ") })
    }

    @Test
    fun givenPreciseLocationRepeatedlyRegranted_whenChecked_thenRevocationsStopAndTheChoiceIsRestored() {
        val android = FakeAndroid()
        android.settingsGrant(FINE)
        val handler = AppOpsGuardHandler(android)
        handler.send("set $UID $FINE=revoked")
        android.pmGrant(FINE)
        android.regrantAfterRevoke += FINE

        repeat(20) { handler.checkAll() }
        handler.send("verify $UID $FINE=unheld")

        assertEquals(4, android.revocations[FINE])
        assertEquals("unheld $UID $FINE", android.printed.last { it.startsWith("unheld ") })
        assertTrue(android.isGranted(FINE))

        android.settingsRevoke(FINE)
        handler.checkAll()
        handler.send("restore $UID $FINE=granted")
        assertFalse(android.isGranted(FINE))
    }

    @Test
    fun givenSpecialAccessRepeatedlyReenabled_whenChecked_thenUnbindsStopAndOtherAccessIsStillHeld() {
        for (access in AppOpsGuardProtocol.SPECIAL_ACCESS) {
            val other = AppOpsGuardProtocol.SPECIAL_ACCESS.single { it != access }
            val service = setOf("$PACKAGE/$PACKAGE.Service")
            val android = FakeAndroid()
            android.setServices(access, service)
            android.setServices(other, service)
            val handler = AppOpsGuardHandler(android)
            handler.send("set $UID $access=revoked,$other=revoked")
            android.setServices(access, service)
            android.regrantAfterRevoke += access

            repeat(20) { handler.checkAll() }

            assertEquals(4, android.helperWrites[access])
            assertEquals(9, android.effectiveChanges[access])
            assertEquals(1, android.helperWrites[other])
            assertEquals("unheld $UID $access", android.printed.last { it.startsWith("unheld ") })
        }
    }

    @Test
    fun givenUpdateThatDropsAllFilesPermission_whenRestoredOrReleased_thenTheSavedModeIsNotRewritten() {
        for (restore in listOf("restore $UID $ALL_FILES_OP_NAME=0", "release $UID")) {
            val android = FakeAndroid()
            android.requestsAllFiles = true
            android.userSetsMode(ALL_FILES_OP_NAME, MODE_ALLOWED)
            val handler = AppOpsGuardHandler(android)
            handler.send("set $UID $ALL_FILES_OP_NAME=1")
            android.requestsAllFiles = false

            handler.send(restore)

            assertEquals(1, android.helperWrites[ALL_FILES_OP_NAME])
            assertEquals(MODE_DEFAULT, android.mode(ALL_FILES_OP_NAME))
            assertEquals("restored $UID", android.printed.last())
            assertFalse(handler.isGuarded(UID))
        }
    }

    @Test
    fun givenUnknownAllFilesMetadata_whenSetAppliedAndVerified_thenOnlyThatOpIsUnheldAndNeverWritten() {
        for (serviceAvailable in listOf(true, false)) {
            val android = FakeAndroid()
            android.requestsAllFiles = true
            android.pmGrant(CONTACTS)
            android.permissionServiceAvailable = serviceAvailable
            android.packageInfoAvailable = false
            val handler = AppOpsGuardHandler(android)
            val targets = "$ALL_FILES_OP_NAME=1,android:read_contacts=1"

            handler.send("set $UID $targets")
            handler.send("apply $UID $targets -")
            handler.send("verify $UID $targets")
            repeat(20) { handler.checkAll() }

            assertEquals(0, android.helperWrites.getOrDefault(ALL_FILES_OP_NAME, 0))
            assertEquals(1, android.helperWrites["android:read_contacts"])
            assertEquals(listOf("unheld $UID $ALL_FILES_OP_NAME"), android.printed.filter { it.startsWith("unheld ") }.distinct())

            handler.send("restore $UID $ALL_FILES_OP_NAME=default")
            assertEquals(0, android.helperWrites.getOrDefault(ALL_FILES_OP_NAME, 0))
            assertEquals("restored $UID", android.printed.last())
        }
    }

    @Test
    fun givenMetadataMissingAtRestore_whenAllFilesModeDiffers_thenTheHelperKeepsTheRestoreRetryable() {
        val android = FakeAndroid()
        android.requestsAllFiles = true
        val handler = AppOpsGuardHandler(android)
        handler.send("set $UID $ALL_FILES_OP_NAME=1")
        android.packageInfoAvailable = false

        assertFailsWith<IllegalStateException> { handler.send("release $UID") }
        assertEquals(1, android.helperWrites[ALL_FILES_OP_NAME])
        assertEquals("unheld $UID $ALL_FILES_OP_NAME", android.printed.last())
        assertTrue(handler.isGuarded(UID))

        android.packageInfoAvailable = true
        handler.send("release $UID")
        assertEquals(2, android.helperWrites[ALL_FILES_OP_NAME])
        assertEquals(MODE_DEFAULT, android.mode(ALL_FILES_OP_NAME))
        assertEquals("restored $UID", android.printed.last())
    }

    @Test
    fun givenAContestedOp_whenTargetsChangeOrTheGuardIsForgotten_thenItCanBeHeldAgain() {
        val targets = "$ALL_FILES_OP_NAME=1"
        for (retry in listOf("set", "apply", "verify", "release", "new installation")) {
            val android = FakeAndroid()
            android.requestsAllFiles = true
            android.resetOpsAfterWrite[ALL_FILES_OP_NAME] = MODE_DEFAULT
            val handler = AppOpsGuardHandler(android)
            handler.send("set $UID $targets")
            repeat(4) {
                android.settleExternalChanges()
                handler.checkAll()
            }
            assertEquals(4, android.helperWrites[ALL_FILES_OP_NAME])
            android.resetOpsAfterWrite.clear()

            when (retry) {
                "set", "verify" -> handler.send("$retry $UID $targets,android:read_contacts=1")

                "apply" -> handler.send("apply $UID $targets,android:read_contacts=1 -")

                "release" -> {
                    handler.send("release $UID")
                    handler.send("set $UID $targets")
                }

                else -> {
                    android.uninstall()
                    android.install()
                    // A bound command for a new installation forgets the old guard.
                    handler.forget(UID)
                    handler.send("verify $UID $targets")
                }
            }

            assertEquals(5, android.helperWrites[ALL_FILES_OP_NAME], retry)
            assertEquals(MODE_IGNORED, android.mode(ALL_FILES_OP_NAME), retry)
            assertEquals("unheld $UID -", android.printed.last { it.startsWith("unheld ") }, retry)
        }
    }

    @Test
    fun givenIntermittentThenRepeatedOpResets_whenTimePasses_thenSpacedResetsAreToleratedAndSuspensionSticks() {
        val android = FakeAndroid()
        android.requestsAllFiles = true
        android.resetOpsAfterWrite[ALL_FILES_OP_NAME] = MODE_DEFAULT
        var now = 0L
        val handler = AppOpsGuardHandler(android) { now }
        handler.send("set $UID $ALL_FILES_OP_NAME=1")

        repeat(6) {
            now += TimeUnit.MINUTES.toNanos(10)
            android.settleExternalChanges()
            handler.checkAll()
        }
        assertEquals(7, android.helperWrites[ALL_FILES_OP_NAME])

        repeat(4) {
            android.settleExternalChanges()
            handler.checkAll()
        }
        assertEquals(9, android.helperWrites[ALL_FILES_OP_NAME])

        now += TimeUnit.HOURS.toNanos(1)
        handler.checkAll()
        assertEquals(9, android.helperWrites[ALL_FILES_OP_NAME])
        assertEquals("unheld $UID $ALL_FILES_OP_NAME", android.printed.last { it.startsWith("unheld ") })
    }

    private fun AppOpsGuardHandler.send(line: String) = handle(checkNotNull(AppOpsGuardProtocol.parseCommand(line)))
}
