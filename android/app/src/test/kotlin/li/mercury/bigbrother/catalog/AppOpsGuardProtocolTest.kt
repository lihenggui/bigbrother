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
    fun givenSetOrVerifyLine_whenParsed_thenCommandForUid() {
        assertEquals(Command.Set(10123), AppOpsGuardProtocol.parseCommand("set 10123"))
        assertEquals(Command.Verify(10123), AppOpsGuardProtocol.parseCommand("verify 10123"))
        assertEquals(Command.Set(10123), AppOpsGuardProtocol.parseCommand("  set\t10123 \n"))
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
            "set 10123 extra",
            "verify 10123 extra",
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
    fun givenCurrentModes_whenChecked_thenOnlyOpsNotIgnoredAreSetAgain() {
        val states = listOf(
            OpState("android:camera", uidMode = 1, defaultMode = 0),
            OpState("android:read_contacts", uidMode = 0, defaultMode = 0),
            OpState("android:record_audio", uidMode = 4, defaultMode = 0),
            OpState("android:read_sms", uidMode = null, defaultMode = 0),
            OpState("android:get_usage_stats", uidMode = null, defaultMode = 1),
            OpState("android:wifi_scan", uidMode = null, defaultMode = null),
        )

        assertEquals(
            listOf("android:read_contacts", "android:record_audio", "android:read_sms", "android:wifi_scan"),
            AppOpsGuardProtocol.opsToSetAgain(states),
        )
    }

    @Test
    fun givenEveryOpIgnored_whenChecked_thenNothingToSetAgain() {
        val states = AppOpsGuardProtocol.GUARDED_OPS.map { OpState(it, uidMode = 1, defaultMode = 0) }

        assertEquals(emptyList(), AppOpsGuardProtocol.opsToSetAgain(states))
    }
}
