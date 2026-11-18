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

import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.Command
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.FLAG_USER_FIXED
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.FLAG_USER_SET
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.OpState
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.PermissionState
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.Targets
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.Values
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppOpsGuardProtocolTest {
    private val fine = "android.permission.ACCESS_FINE_LOCATION"

    private fun ops(vararg ops: Pair<String, Int>) = Targets(mapOf(*ops), emptySet())

    private fun opValues(vararg ops: Pair<String, Int?>) = Values(listOf(*ops), emptyList())

    @Test
    fun givenReleaseLine_whenParsed_thenCommandForUid() {
        assertEquals(AppOpsGuardProtocol.Command.Release(10205), AppOpsGuardProtocol.parseCommand("release 10205"))
        assertEquals(null, AppOpsGuardProtocol.parseCommand("release 10205 extra"))
    }

    @Test
    fun givenSetOrVerifyLine_whenParsed_thenTargetsByOp() {
        val targets = ops("android:camera" to 1, "android:read_contacts" to 1)
        assertEquals(Command.Set(10123, targets), AppOpsGuardProtocol.parseCommand("set 10123 android:camera=1,android:read_contacts=1"))
        assertEquals(Command.Verify(10123, targets), AppOpsGuardProtocol.parseCommand("verify 10123 android:camera=1,android:read_contacts=1"))
        assertEquals(Command.Set(10123, ops("android:camera" to 1)), AppOpsGuardProtocol.parseCommand("  set\t10123 android:camera=1 \n"))
    }

    @Test
    fun givenOpsInTheForeground_whenParsed_thenHeldAtModeFour() {
        assertEquals(
            Command.Set(10123, ops("android:camera" to AppOpsGuardProtocol.MODE_FOREGROUND, "android:read_contacts" to 1)),
            AppOpsGuardProtocol.parseCommand("set 10123 android:camera=4,android:read_contacts=1"),
        )
        assertEquals(
            Command.Apply(10123, ops("android:coarse_location" to 4, "android:fine_location" to 4), opValues("android:wifi_scan" to null)),
            AppOpsGuardProtocol.parseCommand("apply 10123 android:coarse_location=4,android:fine_location=4 android:wifi_scan=default"),
        )
        assertEquals(Command.Verify(10123, ops("android:record_audio" to 4)), AppOpsGuardProtocol.parseCommand("verify 10123 android:record_audio=4"))
    }

    @Test
    fun givenOpsHeldInTheForeground_whenChecked_thenOnlyThoseWithAnotherModeAreSetAgain() {
        val states = listOf(
            OpState("android:camera", uidMode = 4, defaultMode = 0),
            // The system set it back to allowed as the permission changed.
            OpState("android:record_audio", uidMode = 0, defaultMode = 0),
            OpState("android:fine_location", uidMode = null, defaultMode = 0),
        )
        val targets = mapOf("android:camera" to 4, "android:record_audio" to 4, "android:fine_location" to 4)

        assertEquals(listOf("android:record_audio", "android:fine_location"), AppOpsGuardProtocol.opsToSetAgain(states, targets))
    }

    @Test
    fun givenPermissionRevokedInTargets_whenParsed_thenHeldApartFromOps() {
        assertEquals(
            Command.Set(10123, Targets(mapOf("android:camera" to 1), setOf(fine))),
            AppOpsGuardProtocol.parseCommand("set 10123 android:camera=1,$fine=revoked"),
        )
        assertEquals(
            Command.Verify(10123, Targets(emptyMap(), setOf(fine))),
            AppOpsGuardProtocol.parseCommand("verify 10123 $fine=revoked"),
        )
    }

    @Test
    fun givenApplyLine_whenParsed_thenTargetsAndResets() {
        assertEquals(
            Command.Apply(10123, ops("android:camera" to 1), opValues("android:read_contacts" to null, "android:read_sms" to 4)),
            AppOpsGuardProtocol.parseCommand("apply 10123 android:camera=1 android:read_contacts=default,android:read_sms=4"),
        )
        assertEquals(Command.Apply(10123, ops("android:camera" to 1), Values.NONE), AppOpsGuardProtocol.parseCommand("apply 10123 android:camera=1 -"))
        assertEquals(Command.Apply(10123, Targets.NONE, opValues("android:camera" to 0)), AppOpsGuardProtocol.parseCommand("apply 10123 - android:camera=0"))
        // Location made precise again: its ops held, the permission given back.
        assertEquals(
            Command.Apply(
                10123,
                ops("android:fine_location" to 1),
                Values(emptyList(), listOf(fine to PermissionState(granted = true, flags = FLAG_USER_SET))),
            ),
            AppOpsGuardProtocol.parseCommand("apply 10123 android:fine_location=1 $fine=granted+user_set"),
        )
    }

    @Test
    fun givenRestoreLine_whenParsed_thenModesAndStatesInOrder() {
        assertEquals(
            Command.Restore(10123, opValues("android:camera" to 4, "android:read_contacts" to null)),
            AppOpsGuardProtocol.parseCommand("restore 10123 android:camera=4,android:read_contacts=default"),
        )
        assertEquals(Command.Restore(10123, Values.NONE), AppOpsGuardProtocol.parseCommand("restore 10123"))
        assertEquals(
            Command.Restore(
                10123,
                Values(listOf("android:camera" to 4), listOf(fine to PermissionState(granted = false, flags = FLAG_USER_SET or FLAG_USER_FIXED))),
            ),
            AppOpsGuardProtocol.parseCommand("restore 10123 android:camera=4,$fine=denied+user_set+user_fixed"),
        )
        assertEquals(
            Command.Restore(10123, Values(emptyList(), listOf(fine to null))),
            AppOpsGuardProtocol.parseCommand("restore 10123 $fine=default"),
        )
    }

    @Test
    fun givenMalformedLine_whenParsed_thenNull() {
        listOf(
            "",
            "set",
            "set abc",
            "set -1",
            "set 10123",
            "verify 10123",
            "set 10123 extra",
            "verify 10123 extra",
            "set 10123 android:camera=0",
            "set 10123 android:camera=2",
            "set 10123 android:camera=default",
            "set 10123 android:camera=1 more",
            "set 10123 $fine=granted",
            "set 10123 $fine=1",
            "set 10123 android:camera=revoked",
            "apply 10123 android:camera=1",
            "apply 10123 android:camera=3 -",
            "apply 10123 - android:camera=x",
            "apply 10123 - $fine=revoked",
            "unset 10123",
            "restore 10123 android:camera",
            "restore 10123 android:camera=",
            "restore 10123 android:camera=ignored",
            "restore 10123 =1",
            "restore 10123 android:camera=1,",
            "restore 10123 android:camera=-1",
            "restore 10123 android:camera=1 more",
            "restore 10123 $fine=granted+user_whatever",
            "restore 10123 $fine=maybe",
            "restore 10123 $fine=4",
        ).forEach { assertNull(AppOpsGuardProtocol.parseCommand(it), it) }
    }

    @Test
    fun givenModesAndStates_whenEncodedAndDecoded_thenSameValues() {
        val values = Values(
            listOf("android:camera" to 4, "android:read_sms" to 3, "android:read_contacts" to null),
            listOf(fine to PermissionState(granted = true, flags = 0), "android.permission.CAMERA" to null),
        )
        val text = AppOpsGuardProtocol.encodeValues(values)

        assertEquals(
            "android:camera=4,android:read_sms=3,android:read_contacts=default,$fine=granted,android.permission.CAMERA=default",
            text,
        )
        assertEquals(values, AppOpsGuardProtocol.decodeValues(text))
    }

    @Test
    fun givenModesSavedWithTheirGrants_whenEncodedAndDecoded_thenGrantsKeptByOp() {
        val values = Values(
            listOf("android:read_contacts" to 1, "android:camera" to null, "android:read_clipboard" to 1),
            listOf(fine to PermissionState(granted = true, flags = 0)),
            mapOf("android:read_contacts" to 1, "android:camera" to 4),
        )
        val text = AppOpsGuardProtocol.encodeValues(values)

        assertEquals("android:read_contacts=1/1,android:camera=default/4,android:read_clipboard=1,$fine=granted", text)
        assertEquals(values, AppOpsGuardProtocol.decodeValues(text))
        assertEquals(
            Command.Restore(10123, Values(listOf("android:read_contacts" to 1), emptyList(), mapOf("android:read_contacts" to 1))),
            AppOpsGuardProtocol.parseCommand("restore 10123 android:read_contacts=1/1"),
        )
    }

    @Test
    fun givenModesSavedBeforeGrantsWere_whenDecoded_thenModesWithoutGrants() {
        assertEquals(
            Values(listOf("android:read_contacts" to 1, "android:camera" to null), emptyList()),
            AppOpsGuardProtocol.decodeValues("android:read_contacts=1,android:camera=default"),
        )
    }

    @Test
    fun givenMalformedGrant_whenDecoded_thenNull() {
        listOf(
            "android:camera=1/",
            "android:camera=1/x",
            "android:camera=1/-1",
            "android:camera=/1",
            "android:camera=1/1/1",
        ).forEach { assertNull(AppOpsGuardProtocol.decodeValues(it), it) }
        assertNull(AppOpsGuardProtocol.parseCommand("set 10123 android:camera=1/1"))
    }

    @Test
    fun givenPermissionGrants_whenTheirOpsMode_thenAllowedForegroundOrIgnored() {
        assertEquals(AppOpsGuardProtocol.MODE_ALLOWED, AppOpsGuardProtocol.grantMode(granted = true, background = null))
        assertEquals(AppOpsGuardProtocol.MODE_ALLOWED, AppOpsGuardProtocol.grantMode(granted = true, background = true))
        assertEquals(AppOpsGuardProtocol.MODE_FOREGROUND, AppOpsGuardProtocol.grantMode(granted = true, background = false))
        assertEquals(AppOpsGuardProtocol.MODE_IGNORED, AppOpsGuardProtocol.grantMode(granted = false, background = null))
        assertEquals(AppOpsGuardProtocol.MODE_IGNORED, AppOpsGuardProtocol.grantMode(granted = false, background = true))
    }

    @Test
    fun givenPermissionGrantedWhileHeld_whenRestored_thenSystemsModeForTheGrant() {
        val ignored = AppOpsGuardProtocol.MODE_IGNORED
        // Contacts not granted when saved, granted since.
        assertEquals(AppOpsGuardProtocol.MODE_ALLOWED, AppOpsGuardProtocol.modeToRestore(ignored, ignored, AppOpsGuardProtocol.MODE_ALLOWED))
        // The camera granted while in use, the background camera not being one's to have.
        assertEquals(AppOpsGuardProtocol.MODE_FOREGROUND, AppOpsGuardProtocol.modeToRestore(ignored, ignored, AppOpsGuardProtocol.MODE_FOREGROUND))
        // Location while in use when saved, all the time since.
        assertEquals(AppOpsGuardProtocol.MODE_ALLOWED, AppOpsGuardProtocol.modeToRestore(4, 4, AppOpsGuardProtocol.MODE_ALLOWED))
    }

    @Test
    fun givenPermissionRevokedWhileHeld_whenRestored_thenIgnored() {
        assertEquals(AppOpsGuardProtocol.MODE_IGNORED, AppOpsGuardProtocol.modeToRestore(null, AppOpsGuardProtocol.MODE_ALLOWED, AppOpsGuardProtocol.MODE_IGNORED))
    }

    @Test
    fun givenGrantAsWhenSaved_whenRestored_thenSavedMode() {
        val allowed = AppOpsGuardProtocol.MODE_ALLOWED
        // Ignored by the user's choice with the permission granted.
        assertEquals(AppOpsGuardProtocol.MODE_IGNORED, AppOpsGuardProtocol.modeToRestore(AppOpsGuardProtocol.MODE_IGNORED, allowed, allowed))
        assertNull(AppOpsGuardProtocol.modeToRestore(null, 4, 4))
        assertEquals(1, AppOpsGuardProtocol.modeToRestore(1, 1, 1))
    }

    @Test
    fun givenGrantNotKnown_whenRestored_thenSavedMode() {
        // Saved before grants were, or with the permissions out of reach.
        assertEquals(1, AppOpsGuardProtocol.modeToRestore(1, null, AppOpsGuardProtocol.MODE_ALLOWED))
        assertEquals(1, AppOpsGuardProtocol.modeToRestore(1, 1, null))
        assertNull(AppOpsGuardProtocol.modeToRestore(null, null, null))
    }

    @Test
    fun givenEveryPermissionState_whenEncoded_thenReadBackTheSame() {
        for (granted in listOf(true, false)) {
            for (flags in 0..AppOpsGuardProtocol.USER_FLAGS) {
                val state = PermissionState(granted, flags)
                assertEquals(state, AppOpsGuardProtocol.decodeState(AppOpsGuardProtocol.encodeState(state)))
            }
        }
        assertEquals("denied+user_set+user_fixed", AppOpsGuardProtocol.encodeState(PermissionState(granted = false, flags = 3)))
    }

    @Test
    fun givenOpsWithAndWithoutUidMode_whenSaved_thenNoUidModeIsDefault() {
        val states = listOf(
            OpState("android:camera", uidMode = 4, defaultMode = 0),
            OpState("android:read_contacts", uidMode = null, defaultMode = 0),
        )

        assertEquals(
            "saved 10123 android:camera=4,android:read_contacts=default",
            AppOpsGuardProtocol.savedLine(10123, Values(AppOpsGuardProtocol.savedModes(states), emptyList())),
        )
        assertEquals("saved 10123", AppOpsGuardProtocol.savedLine(10123, Values.NONE))
        assertEquals(
            "saved 10123 android:camera=4,$fine=granted+user_set",
            AppOpsGuardProtocol.savedLine(10123, Values(listOf("android:camera" to 4), listOf(fine to PermissionState(true, FLAG_USER_SET)))),
        )
    }

    @Test
    fun givenPermissionsTakenAnew_whenApplied_thenTheirStatesAnswered() {
        assertEquals("applied 10123", AppOpsGuardProtocol.appliedLine(10123, emptyList()))
        assertEquals(
            "applied 10123 $fine=denied+user_set",
            AppOpsGuardProtocol.appliedLine(10123, listOf(fine to PermissionState(granted = false, flags = FLAG_USER_SET))),
        )
    }

    @Test
    fun givenPermissionStates_whenChecked_thenHeldOnlyRevokedAndFixed() {
        assertTrue(AppOpsGuardProtocol.isHeld(granted = false, flags = FLAG_USER_FIXED))
        assertTrue(AppOpsGuardProtocol.isHeld(granted = false, flags = FLAG_USER_FIXED or FLAG_USER_SET or 0x40))
        assertFalse(AppOpsGuardProtocol.isHeld(granted = true, flags = FLAG_USER_FIXED))
        // Revoked, but the app may ask again: the user would be asked.
        assertFalse(AppOpsGuardProtocol.isHeld(granted = false, flags = FLAG_USER_SET))
    }

    @Test
    fun givenPermissionStates_whenCheckedAfterPuttingBack_thenTheUserFlagsAndGrantMatch() {
        val granted = PermissionState(granted = true, flags = FLAG_USER_SET)
        assertTrue(AppOpsGuardProtocol.isPutBack(granted, granted = true, flags = FLAG_USER_SET))
        // Flags that are not the user's are the system's, and left to it.
        assertTrue(AppOpsGuardProtocol.isPutBack(granted, granted = true, flags = FLAG_USER_SET or 0x40))
        assertFalse(AppOpsGuardProtocol.isPutBack(granted, granted = false, flags = FLAG_USER_SET))
        assertFalse(AppOpsGuardProtocol.isPutBack(granted, granted = true, flags = FLAG_USER_SET or FLAG_USER_FIXED))
        // With no state known, the app only has to be able to ask again.
        assertTrue(AppOpsGuardProtocol.isPutBack(null, granted = false, flags = FLAG_USER_SET))
        assertFalse(AppOpsGuardProtocol.isPutBack(null, granted = false, flags = FLAG_USER_FIXED))
    }

    @Test
    fun givenCurrentModes_whenChecked_thenOnlyHeldOpsWithoutTheirModeAreSetAgain() {
        val states = listOf(
            OpState("android:camera", uidMode = 1, defaultMode = 0),
            OpState("android:read_contacts", uidMode = 0, defaultMode = 0),
            OpState("android:record_audio", uidMode = 4, defaultMode = 0),
            OpState("android:read_sms", uidMode = null, defaultMode = 0),
            OpState("android:get_usage_stats", uidMode = null, defaultMode = 1),
            OpState("android:wifi_scan", uidMode = null, defaultMode = null),
            OpState("android:read_calendar", uidMode = 1, defaultMode = 0),
            OpState("android:fine_location", uidMode = 0, defaultMode = 0),
        )
        val targets = mapOf(
            "android:camera" to 1,
            "android:read_contacts" to 1,
            "android:record_audio" to 1,
            "android:read_sms" to 1,
            "android:get_usage_stats" to 1,
            "android:wifi_scan" to 1,
            "android:read_calendar" to 2,
        )

        assertEquals(
            listOf("android:read_contacts", "android:record_audio", "android:read_sms", "android:wifi_scan", "android:read_calendar"),
            AppOpsGuardProtocol.opsToSetAgain(states, targets),
        )
    }

    @Test
    fun givenEveryOpIgnored_whenChecked_thenNothingToSetAgain() {
        val states = AppOpsGuardProtocol.GUARDED_OPS.map { OpState(it, uidMode = 1, defaultMode = 0) }
        val targets = AppOpsGuardProtocol.GUARDED_OPS.associateWith { 1 }

        assertEquals(emptyList(), AppOpsGuardProtocol.opsToSetAgain(states, targets))
    }

    @Test
    fun givenUnheldOps_whenReported_thenNamedOrDash() {
        assertEquals(
            "unheld 10100 android:camera,android:read_sms",
            AppOpsGuardProtocol.unheldLine(10100, listOf("android:camera", "android:read_sms")),
        )
        assertEquals("unheld 10100 -", AppOpsGuardProtocol.unheldLine(10100, emptyList()))
    }

    @Test
    fun givenPermissionHeldByTheGuard_whenRestoringItsOp_thenGrantNotCompared() {
        // Location made coarse: the precise permission is revoked by the guard, not the user.
        assertFalse(AppOpsGuardProtocol.comparesGrant(fine, setOf(fine)))
        assertTrue(AppOpsGuardProtocol.comparesGrant("android.permission.READ_CONTACTS", setOf(fine)))
        assertFalse(AppOpsGuardProtocol.comparesGrant(null, emptySet()))
    }

    @Test
    fun givenPermissionTakenAnewByApply_whenItsOpIsSetBack_thenGrantStillCompared() {
        // Location on, then coarse: the precise permission is a target of the
        // `apply` and taken only after the location ops are set back.
        val command = AppOpsGuardProtocol.parseCommand(
            "apply 10123 $fine=revoked android:coarse_location=1/1,android:fine_location=1/1",
        ) as Command.Apply
        val held = AppOpsGuardProtocol.heldWhileSettingBack(emptySet(), command.resets)

        assertTrue(AppOpsGuardProtocol.comparesGrant(fine, held))
    }

    @Test
    fun givenPermissionHeldOrGivenBack_whenItsOpIsSetBack_thenGrantNotCompared() {
        val camera = "android.permission.CAMERA"
        val resets = Values(listOf("android:camera" to 4), listOf(camera to null))
        val held = AppOpsGuardProtocol.heldWhileSettingBack(setOf(fine), resets)

        assertEquals(setOf(fine, camera), held)
        assertFalse(AppOpsGuardProtocol.comparesGrant(fine, held))
        assertFalse(AppOpsGuardProtocol.comparesGrant(camera, held))
    }

    @Test
    fun givenHeldPermissionFoundGrantedAgain_whenReverted_thenItsStateIsReported() {
        val found = PermissionState(granted = true, flags = FLAG_USER_SET)
        assertEquals(found, AppOpsGuardProtocol.regranted(found))
        assertNull(AppOpsGuardProtocol.regranted(PermissionState(granted = false, flags = 0)))
        assertNull(AppOpsGuardProtocol.regranted(null))
        assertEquals("reverted 10123 $fine=granted+user_set", AppOpsGuardProtocol.revertedLine(10123, fine, found))
        assertEquals("reverted 10123 $fine", AppOpsGuardProtocol.revertedLine(10123, fine))
        assertEquals("reverted 10123 android:camera", AppOpsGuardProtocol.revertedLine(10123, "android:camera"))
    }

    @Test
    fun givenPermissionGrantedAgainWhileHeld_whenGivenBack_thenAsFoundWhateverTheDaemonSent() {
        val before = PermissionState(granted = false, flags = FLAG_USER_SET)
        val found = PermissionState(granted = true, flags = FLAG_USER_SET)
        assertEquals(found, AppOpsGuardProtocol.stateToGiveBack(sent = before, kept = found, regranted = true))
        assertEquals(found, AppOpsGuardProtocol.stateToGiveBack(sent = null, kept = found, regranted = true))
    }

    @Test
    fun givenPermissionNotGrantedAgain_whenGivenBack_thenAsTheDaemonSentOrAsKept() {
        val before = PermissionState(granted = false, flags = FLAG_USER_SET)
        val kept = PermissionState(granted = true, flags = 0)
        assertEquals(before, AppOpsGuardProtocol.stateToGiveBack(sent = before, kept = kept, regranted = false))
        assertEquals(kept, AppOpsGuardProtocol.stateToGiveBack(sent = null, kept = kept, regranted = false))
        assertEquals(before, AppOpsGuardProtocol.stateToGiveBack(sent = before, kept = null, regranted = true))
        assertNull(AppOpsGuardProtocol.stateToGiveBack(sent = null, kept = null, regranted = false))
    }
}
