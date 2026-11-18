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

    private val cases = protocolCases(
        "appops.txt",
        types = setOf("command", "command-read", "command-bad", "answer", "answer-bad"),
        keys = setOf(
            "set", "verify", "release", "restore", "apply", "saved", "restored", "applied", "failed", "verified", "reverted", "unheld",
            "uid", "hold", "revoke", "op", "permission", "name", "not_held",
            "kind", "library", "app", "unknown", "request", "package",
        ),
    )

    /** An op's uid mode, `null` for none, and its grant's mode if given: `<op>/<mode>[/<grant>]`. */
    private fun opValue(word: String): Triple<String, Int?, Int?> {
        val parts = word.split('/')
        return Triple(parts[0], parts[1].takeUnless { it == "none" }?.toInt(), parts.getOrNull(2)?.toInt())
    }

    /**
     * A permission's state, `null` if not known:
     * `<permission>/<granted|denied|unknown>[/<flag>]...[/service:<package>:<class>]...`.
     */
    private fun permissionValue(word: String): Pair<String, PermissionState?> {
        val parts = word.split('/')
        if (parts[1] == "unknown") return parts[0] to null
        val (services, flagWords) = parts.drop(2).partition { it.startsWith("service:") }
        val flags = flagWords.fold(0) { flags, flag ->
            flags or when (flag) {
                "user_set" -> FLAG_USER_SET
                "user_fixed" -> FLAG_USER_FIXED
                else -> error("no flag $flag")
            }
        }
        val named = services.map { it.removePrefix("service:").replaceFirst(':', '/') }.toSet()
        return parts[0] to PermissionState(granted = parts[1] == "granted", flags = flags, services = named)
    }

    private fun ProtocolCase.uid(): Int = get("uid")!!.toInt()

    private fun ProtocolCase.targets(): Targets = Targets(
        all("hold").associate { it.substringBefore('/') to it.substringAfter('/').toInt() },
        all("revoke").toSet(),
    )

    private fun ProtocolCase.values(): Values {
        val ops = all("op").map(::opValue)
        return Values(
            ops.map { (name, mode, _) -> name to mode },
            all("permission").map(::permissionValue),
            ops.mapNotNull { (name, _, grant) -> grant?.let { name to it } }.toMap(),
        )
    }

    @Test
    fun givenEveryCommandOfTheCases_whenParsed_thenWhatTheDaemonMeant() {
        for (case in cases.ofType("command") + cases.ofType("command-read")) {
            val uid = case.uid()
            val expected = when (case.first) {
                "set" -> Command.Set(uid, case.targets())
                "verify" -> Command.Verify(uid, case.targets())
                "release" -> Command.Release(uid)
                "restore" -> Command.Restore(uid, case.values())
                "apply" -> Command.Apply(uid, case.targets(), case.values())
                "kind" -> Command.Kind(uid, case.get("request")!!.toLong(), case.get("package")!!)
                else -> error("no command ${case.first}")
            }
            assertEquals(expected, AppOpsGuardProtocol.parseCommand(case.line), case.line)
        }
    }

    @Test
    fun givenEveryValueListOfTheCommands_whenEncoded_thenAsTheDaemonWroteIt() {
        for (case in cases.ofType("command").filter { it.first == "restore" || it.first == "apply" }) {
            val values = case.values()
            if (values.isEmpty()) continue
            assertEquals(case.line.split(' ').last(), AppOpsGuardProtocol.encodeValues(values), case.line)
        }
    }

    @Test
    fun givenEveryMalformedCommandOfTheCases_whenParsed_thenNull() {
        for (case in cases.ofType("command-bad")) {
            assertNull(AppOpsGuardProtocol.parseCommand(case.line), case.line)
        }
    }

    @Test
    fun givenEveryAnswerOfTheCases_whenWritten_thenTheLineTheDaemonReads() {
        for (case in cases.ofType("answer")) {
            val uid = case.uid()
            val line = when (case.first) {
                "saved" -> AppOpsGuardProtocol.savedLine(uid, case.values())

                "restored" -> AppOpsGuardProtocol.restoredLine(uid)

                "applied" -> AppOpsGuardProtocol.appliedLine(uid, case.values().permissions.map { (name, state) -> name to state!! })

                "failed" -> AppOpsGuardProtocol.failedLine(uid)

                "verified" -> AppOpsGuardProtocol.verifiedLine(uid)

                "reverted" -> case.get("name")?.let { AppOpsGuardProtocol.revertedLine(uid, it) }
                    ?: case.values().permissions.single().let { (name, state) -> AppOpsGuardProtocol.revertedLine(uid, name, state!!) }

                "unheld" -> AppOpsGuardProtocol.unheldLine(uid, case.all("not_held"))

                "library", "app", "unknown" -> AppOpsGuardProtocol.kindLine(
                    uid,
                    case.get("request")!!.toLong(),
                    when (case.first) {
                        "library" -> true
                        "app" -> false
                        else -> null
                    },
                )

                else -> error("no answer ${case.first}")
            }
            assertEquals(case.line, line)
        }
    }

    @Test
    fun givenPermissionSyncOverwritesRestoredMode_whenReadBack_thenTheLatestGrantIsApplied() {
        var grant = AppOpsGuardProtocol.MODE_ALLOWED
        var actual = AppOpsGuardProtocol.MODE_IGNORED
        val writes = mutableListOf<Int>()
        assertTrue(
            AppOpsGuardProtocol.restoreUidMode(
                desiredMode = { grant },
                writeMode = { mode ->
                    writes += mode
                    if (writes.size == 1) {
                        // Android reapplied the previous mode and the user
                        // changed the current grant before it was read back.
                        grant = AppOpsGuardProtocol.MODE_FOREGROUND
                    } else {
                        actual = mode
                    }
                    true
                },
                readMode = { actual },
            ),
        )
        assertEquals(listOf(AppOpsGuardProtocol.MODE_ALLOWED, AppOpsGuardProtocol.MODE_FOREGROUND), writes)
    }

    @Test
    fun givenPermissionSyncKeepsOverwritingMode_whenRestored_thenFailure() {
        var writes = 0
        assertFalse(
            AppOpsGuardProtocol.restoreUidMode(
                desiredMode = { AppOpsGuardProtocol.MODE_ALLOWED },
                writeMode = {
                    writes++
                    true
                },
                readMode = { AppOpsGuardProtocol.MODE_IGNORED },
            ),
        )
        assertEquals(2, writes)
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
    fun givenHeldPermissionFoundGrantedAgain_whenChecked_thenItsStateIsReported() {
        val found = PermissionState(granted = true, flags = FLAG_USER_SET)
        assertEquals(found, AppOpsGuardProtocol.chosenWhileHeld(found, wasHeld = false))
        assertEquals(found, AppOpsGuardProtocol.chosenWhileHeld(found, wasHeld = true))
        assertNull(AppOpsGuardProtocol.chosenWhileHeld(null, wasHeld = true))
        assertEquals("reverted 10123 $fine=granted+user_set", AppOpsGuardProtocol.revertedLine(10123, fine, found))
        assertEquals("reverted 10123 $fine", AppOpsGuardProtocol.revertedLine(10123, fine))
        assertEquals("reverted 10123 android:camera", AppOpsGuardProtocol.revertedLine(10123, "android:camera"))
    }

    @Test
    fun givenAccessGivenAgainToOneService_whenKept_thenTheOthersThatHadItKeepIt() {
        val both = PermissionState.ofServices(setOf("a/a.A", "a/a.B"))
        val one = PermissionState.ofServices(setOf("a/a.A"))
        assertEquals(both, AppOpsGuardProtocol.regrantedToKeep(both, one))
        assertEquals(
            PermissionState.ofServices(setOf("a/a.A", "a/a.B", "a/a.C")),
            AppOpsGuardProtocol.regrantedToKeep(both, PermissionState.ofServices(setOf("a/a.C"))),
        )
        // Nothing kept, or a permission: what was found.
        assertEquals(one, AppOpsGuardProtocol.regrantedToKeep(null, one))
        val granted = PermissionState(granted = true, flags = FLAG_USER_SET)
        assertEquals(granted, AppOpsGuardProtocol.regrantedToKeep(PermissionState(granted = true, flags = 0), granted))
    }

    @Test
    fun givenHeldPermissionNoLongerFixed_whenChecked_thenTheUsersChoiceOnceItWasHeld() {
        // Revoked by the user in the settings, which may ask again.
        val revoked = PermissionState(granted = false, flags = FLAG_USER_SET)
        assertEquals(revoked, AppOpsGuardProtocol.chosenWhileHeld(revoked, wasHeld = true))
        // Not held yet: the flag is this program's to set.
        assertNull(AppOpsGuardProtocol.chosenWhileHeld(revoked, wasHeld = false))
        // As this program holds it.
        assertNull(AppOpsGuardProtocol.chosenWhileHeld(PermissionState(granted = false, flags = FLAG_USER_FIXED), wasHeld = true))
    }

    @Test
    fun givenPermissionChangedWhileHeld_whenGivenBack_thenAsFoundWhateverTheDaemonSent() {
        val before = PermissionState(granted = false, flags = FLAG_USER_SET)
        val found = PermissionState(granted = true, flags = FLAG_USER_SET)
        assertEquals(found, AppOpsGuardProtocol.stateToGiveBack(sent = before, kept = found, chosen = true))
        assertEquals(found, AppOpsGuardProtocol.stateToGiveBack(sent = null, kept = found, chosen = true))
    }

    @Test
    fun givenPermissionNotChangedWhileHeld_whenGivenBack_thenAsTheDaemonSentOrAsKept() {
        val before = PermissionState(granted = false, flags = FLAG_USER_SET)
        val kept = PermissionState(granted = true, flags = 0)
        assertEquals(before, AppOpsGuardProtocol.stateToGiveBack(sent = before, kept = kept, chosen = false))
        assertEquals(kept, AppOpsGuardProtocol.stateToGiveBack(sent = null, kept = kept, chosen = false))
        assertEquals(before, AppOpsGuardProtocol.stateToGiveBack(sent = before, kept = null, chosen = true))
        assertNull(AppOpsGuardProtocol.stateToGiveBack(sent = null, kept = null, chosen = false))
    }
}
