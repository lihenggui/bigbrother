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
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.OpState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppOpsGuardProtocolTest {
    @Test
    fun givenReleaseLine_whenParsed_thenCommandForUid() {
        assertEquals(AppOpsGuardProtocol.Command.Release(10205), AppOpsGuardProtocol.parseCommand("release 10205"))
        assertEquals(null, AppOpsGuardProtocol.parseCommand("release 10205 extra"))
    }

    @Test
    fun givenSetOrVerifyLine_whenParsed_thenTargetsByOp() {
        val targets = mapOf("android:camera" to 1, "android:read_contacts" to 2)
        assertEquals(Command.Set(10123, targets), AppOpsGuardProtocol.parseCommand("set 10123 android:camera=1,android:read_contacts=2"))
        assertEquals(Command.Verify(10123, targets), AppOpsGuardProtocol.parseCommand("verify 10123 android:camera=1,android:read_contacts=2"))
        assertEquals(Command.Set(10123, mapOf("android:camera" to 1)), AppOpsGuardProtocol.parseCommand("  set\t10123 android:camera=1 \n"))
    }

    @Test
    fun givenApplyLine_whenParsed_thenTargetsAndResets() {
        assertEquals(
            Command.Apply(10123, mapOf("android:camera" to 2), listOf("android:read_contacts" to null, "android:read_sms" to 4)),
            AppOpsGuardProtocol.parseCommand("apply 10123 android:camera=2 android:read_contacts=default,android:read_sms=4"),
        )
        assertEquals(Command.Apply(10123, mapOf("android:camera" to 1), emptyList()), AppOpsGuardProtocol.parseCommand("apply 10123 android:camera=1 -"))
        assertEquals(Command.Apply(10123, emptyMap(), listOf("android:camera" to 0)), AppOpsGuardProtocol.parseCommand("apply 10123 - android:camera=0"))
    }

    @Test
    fun givenRestoreLine_whenParsed_thenModesInOrder() {
        assertEquals(
            Command.Restore(10123, listOf("android:camera" to 4, "android:read_contacts" to null)),
            AppOpsGuardProtocol.parseCommand("restore 10123 android:camera=4,android:read_contacts=default"),
        )
        assertEquals(Command.Restore(10123, emptyList()), AppOpsGuardProtocol.parseCommand("restore 10123"))
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
            "set 10123 android:camera=default",
            "set 10123 android:camera=1 more",
            "apply 10123 android:camera=1",
            "apply 10123 android:camera=3 -",
            "apply 10123 - android:camera=x",
            "unset 10123",
            "restore 10123 android:camera",
            "restore 10123 android:camera=",
            "restore 10123 android:camera=ignored",
            "restore 10123 =1",
            "restore 10123 android:camera=1,",
            "restore 10123 android:camera=-1",
            "restore 10123 android:camera=1 more",
        ).forEach { assertNull(AppOpsGuardProtocol.parseCommand(it), it) }
    }

    @Test
    fun givenModes_whenEncodedAndDecoded_thenSameModes() {
        val modes = listOf("android:camera" to 4, "android:read_sms" to 3, "android:read_contacts" to null)
        val text = AppOpsGuardProtocol.encodeModes(modes)

        assertEquals("android:camera=4,android:read_sms=3,android:read_contacts=default", text)
        assertEquals(modes, AppOpsGuardProtocol.decodeModes(text))
    }

    @Test
    fun givenOpsWithAndWithoutUidMode_whenSaved_thenNoUidModeIsDefault() {
        val states = listOf(
            OpState("android:camera", uidMode = 4, defaultMode = 0),
            OpState("android:read_contacts", uidMode = null, defaultMode = 0),
        )

        assertEquals(
            "saved 10123 android:camera=4,android:read_contacts=default",
            AppOpsGuardProtocol.savedLine(10123, AppOpsGuardProtocol.savedModes(states)),
        )
        assertEquals("saved 10123", AppOpsGuardProtocol.savedLine(10123, emptyList()))
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
}
