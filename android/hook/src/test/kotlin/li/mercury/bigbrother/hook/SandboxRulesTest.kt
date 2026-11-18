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

package li.mercury.bigbrother.hook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxRulesTest {
    @Test
    fun givenAndroid12Signature_whenLocated_thenCallingUidAndTargetSettingFound() {
        val types = listOf(
            "int",
            "com.android.server.pm.SettingBase",
            "com.android.server.pm.PackageSetting",
            "int",
        )
        assertEquals(FilterArguments(callingUid = 0, target = 2), locateFilterArguments(types))
    }

    @Test
    fun givenAndroid13Signature_whenLocated_thenCallingUidAndTargetStateFound() {
        val types = listOf(
            "com.android.server.pm.snapshot.PackageDataSnapshot",
            "int",
            "java.lang.Object",
            "com.android.server.pm.pkg.PackageStateInternal",
            "int",
        )
        assertEquals(FilterArguments(callingUid = 1, target = 3), locateFilterArguments(types))
    }

    @Test
    fun givenCallingSettingDeclaredAsPackage_whenLocated_thenLastPackageIsTarget() {
        val types = listOf(
            "int",
            "com.android.server.pm.PackageSetting",
            "com.android.server.pm.PackageSetting",
            "int",
        )
        assertEquals(FilterArguments(callingUid = 0, target = 2), locateFilterArguments(types))
    }

    @Test
    fun givenSignatureWithoutTargetOrUid_whenLocated_thenNull() {
        assertNull(locateFilterArguments(listOf("int", "java.lang.Object", "int")))
        assertNull(locateFilterArguments(listOf("com.android.server.pm.PackageSetting", "long")))
        assertNull(locateFilterArguments(emptyList()))
    }

    @Test
    fun givenSandboxedCaller_whenTargetIsAnotherApp_thenHidden() {
        assertTrue(hideFromSandboxed(callingUid = 10_205, targetAppId = 10_150, targetIsSystem = false))
        assertTrue(hideFromSandboxed(callingUid = 1_010_205, targetAppId = 10_150, targetIsSystem = false))
    }

    @Test
    fun givenSandboxedCaller_whenTargetCameWithTheSystem_thenVisibleWhateverItsAppId() {
        // The WebView, the media provider: system packages with app ids of their own.
        assertFalse(hideFromSandboxed(callingUid = 10_205, targetAppId = 10_150, targetIsSystem = true))
    }

    @Test
    fun givenSettingsUri_whenItReturnsAndroidId_thenRecognised() {
        assertEquals(AndroidIdQuery.TABLE, androidIdQuery(listOf("secure")))
        assertEquals(AndroidIdQuery.ROW, androidIdQuery(listOf("secure", "android_id")))
        assertNull(androidIdQuery(listOf("secure", "bluetooth_name")))
        assertNull(androidIdQuery(listOf("global", "android_id")))
        assertNull(androidIdQuery(listOf("secure", "android_id", "x")))
        assertNull(androidIdQuery(emptyList()))
    }

    @Test
    fun givenAuthority_whenItIsTheSettingsProviders_thenRecognisedWithOrWithoutUser() {
        assertTrue(isSettingsAuthority("settings"))
        assertTrue(isSettingsAuthority("0@settings"))
        assertTrue(isSettingsAuthority("10@settings"))
        assertFalse(isSettingsAuthority("media"))
        assertFalse(isSettingsAuthority("settings@evil"))
        assertFalse(isSettingsAuthority(null))
    }

    @Test
    fun givenSandboxedCaller_whenTargetIsSystemOrItself_thenVisible() {
        assertFalse(hideFromSandboxed(callingUid = 10_205, targetAppId = 1_000, targetIsSystem = true))
        assertFalse(hideFromSandboxed(callingUid = 10_205, targetAppId = 9_999, targetIsSystem = false))
        assertFalse(hideFromSandboxed(callingUid = 10_205, targetAppId = 10_205, targetIsSystem = false))
        // Another user's instance of the same app shares the app id.
        assertFalse(hideFromSandboxed(callingUid = 1_010_205, targetAppId = 10_205, targetIsSystem = false))
    }

    @Test
    fun givenSettingsCallArguments_whenAndroidIdRead_thenRecognised() {
        assertTrue(isAndroidIdRequest(listOf(null, "settings", "GET_secure", "android_id", null)))
        assertFalse(isAndroidIdRequest(listOf(null, "settings", "GET_secure", "bluetooth_name", null)))
        assertFalse(isAndroidIdRequest(listOf(null, "settings", "GET_global", "android_id", null)))
        assertFalse(isAndroidIdRequest(emptyList()))
    }

    @Test
    fun givenWellFormedList_whenParsed_thenEveryPairKept() {
        val text = "10205 0123456789abcdef\n1010150 fedcba9876543210\n"
        assertEquals(
            mapOf(10_205 to SandboxedUid("0123456789abcdef"), 1_010_150 to SandboxedUid("fedcba9876543210")),
            parseSandboxList(text),
        )
    }

    @Test
    fun givenItemsLines_whenParsed_thenOnlyTheItemsNamedApply() {
        val text = listOf(
            "10205 0123456789abcdef",
            "items 10205 installed_apps",
            "10206 fedcba9876543210",
            "items 10206 device_ids,installed_apps",
            "10207 0123456789abcdef",
            "items 10207 device_ids",
            // Items of a uid that is not listed say nothing.
            "items 10208 device_ids",
            "items abc device_ids",
        ).joinToString("\n")
        assertEquals(
            mapOf(
                10_205 to SandboxedUid("0123456789abcdef", ownAndroidId = false, hidesApps = true),
                10_206 to SandboxedUid("fedcba9876543210"),
                10_207 to SandboxedUid("0123456789abcdef", ownAndroidId = true, hidesApps = false),
            ),
            parseSandboxList(text),
        )
    }

    @Test
    fun givenBlankAndMalformedLines_whenParsed_thenOnlyValidPairsKept() {
        val text = listOf(
            "",
            "   ",
            "10205 0123456789abcdef",
            "10206",
            "10207 0123456789abcdef extra",
            "abc 0123456789abcdef",
            "-10208 0123456789abcdef",
            "10209 0123456789abcde",
            "10210 0123456789abcdeg",
            "\t10211\t0123456789ABCDEF  ",
        ).joinToString("\n")
        assertEquals(
            mapOf(10_205 to SandboxedUid("0123456789abcdef"), 10_211 to SandboxedUid("0123456789abcdef")),
            parseSandboxList(text),
        )
    }

    @Test
    fun givenSystemUid_whenParsed_thenSkipped() {
        val text = "1000 0123456789abcdef\n101000 0123456789abcdef\n0 0123456789abcdef"
        assertEquals(emptyMap(), parseSandboxList(text))
    }

    @Test
    fun givenRepeatedUid_whenParsed_thenLastLineWins() {
        val text = "10205 0123456789abcdef\n10205 fedcba9876543210"
        assertEquals(mapOf(10_205 to SandboxedUid("fedcba9876543210")), parseSandboxList(text))
    }

    @Test
    fun givenHookResults_whenStatusWritten_thenOneKeyValuePerLine() {
        assertEquals(
            "android_id=1\napp_list=0\napi=35\nlist=1\nboot=abc-1\n",
            sandboxStatusText(androidId = true, appList = false, api = 35, listReadable = true, bootId = "abc-1"),
        )
    }
}
