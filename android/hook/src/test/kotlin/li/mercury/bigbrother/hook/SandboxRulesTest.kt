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
    fun givenIsolatedStorage_whenAGrantIsWeighed_thenOnlyTheGrantsOfTheMomentCount() {
        val read = 0x1
        val readWrite = 0x3
        // Kept from before, persisted only: nothing.
        assertEquals(STRENGTH_NONE, strengthOfTheMoment(read, ownedModeFlags = 0, globalModeFlags = 0))
        // Picked by the user just now, through an activity, or until restart.
        assertEquals(STRENGTH_OWNED, strengthOfTheMoment(read, ownedModeFlags = read, globalModeFlags = 0))
        assertEquals(STRENGTH_GLOBAL, strengthOfTheMoment(read, ownedModeFlags = read, globalModeFlags = read))
        // Asked to write as well, with only reading granted.
        assertEquals(STRENGTH_NONE, strengthOfTheMoment(readWrite, ownedModeFlags = read, globalModeFlags = 0))
        // The persistable flag in the request is not a mode of its own.
        assertEquals(STRENGTH_OWNED, strengthOfTheMoment(read or 0x40, ownedModeFlags = read, globalModeFlags = 0))
    }

    @Test
    fun givenEveryListOfTheCases_whenParsed_thenTheAppsTheDaemonListed() {
        val cases = protocolCases("sandbox-list.txt", types = setOf("line", "read", "bad"), keys = setOf("app"))
        for (case in cases.ofType("line") + cases.ofType("read") + cases.ofType("bad")) {
            val apps = case.all("app").associate { app ->
                val parts = app.split('/')
                parts[0].toInt() to SandboxedUid(
                    parts[1],
                    ownAndroidId = "device_ids" in parts,
                    hidesApps = "installed_apps" in parts,
                    isolatesStorage = "storage" in parts,
                )
            }
            assertEquals(apps, parseSandboxList(case.line), case.line)
        }
    }

    @Test
    fun givenEveryStatusOfTheCases_whenWritten_thenTheTextTheDaemonReads() {
        val cases = protocolCases(
            "sandbox-status.txt",
            types = setOf("line", "read"),
            keys = setOf("android_id", "app_list", "uri_grants", "ad_id", "api", "list", "boot", "in_place", "now"),
        )
        for (case in cases.ofType("line")) {
            val text = sandboxStatusText(
                androidId = case.get("android_id") == "1",
                appList = case.get("app_list") == "1",
                uriGrants = case.get("uri_grants") == "1",
                adId = case.get("ad_id") == "1",
                api = case.get("api")!!.toInt(),
                listReadable = case.get("list") == "1",
                bootId = case.get("boot")!!,
            )
            assertEquals(case.line, text)
        }
    }

    @Test
    fun givenEveryKnownShapeOfACheckByPackage_whenTheAdIdIsChecked_thenThePackageAndUserAreFound() {
        val expected = PackageCheck("com.example", 10)
        // Android 12, 13, 14, and 15 on.
        assertEquals(expected, adIdCheckByPackage(listOf(AD_ID_PERMISSION, "com.example", 10)))
        assertEquals(expected, adIdCheckByPackage(listOf("com.example", AD_ID_PERMISSION, 10)))
        assertEquals(expected, adIdCheckByPackage(listOf("com.example", AD_ID_PERMISSION, 0, 10)))
        assertEquals(expected, adIdCheckByPackage(listOf("com.example", AD_ID_PERMISSION, "default:0", 10)))
    }

    @Test
    fun givenEveryKnownShapeOfACheckByUid_whenTheAdIdIsChecked_thenTheUidIsFound() {
        // Android 12, 13, and 14 on.
        assertEquals(10123, adIdCheckByUid(listOf(AD_ID_PERMISSION, 10123)))
        assertEquals(10123, adIdCheckByUid(listOf(10123, AD_ID_PERMISSION)))
        assertEquals(10123, adIdCheckByUid(listOf(10123, AD_ID_PERMISSION, 0)))
    }

    @Test
    fun givenAnotherPermission_whenChecked_thenItIsNotLookedAt() {
        assertNull(adIdCheckByPackage(listOf("com.example", "android.permission.CAMERA", "default:0", 0)))
        assertNull(adIdCheckByUid(listOf(10123, "android.permission.CAMERA", 0)))
    }
}
