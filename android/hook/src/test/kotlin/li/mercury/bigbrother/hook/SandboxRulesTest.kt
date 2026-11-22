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
        assertEquals(FilterArguments(callingUid = 0, target = 2), locateFilterArguments(HookSignature("boolean", types)))
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
        assertEquals(FilterArguments(callingUid = 1, target = 3), locateFilterArguments(HookSignature("boolean", types)))
    }

    @Test
    fun givenCallingSettingDeclaredAsPackage_whenLocated_thenLastPackageIsTarget() {
        val types = listOf(
            "int",
            "com.android.server.pm.PackageSetting",
            "com.android.server.pm.PackageSetting",
            "int",
        )
        assertEquals(FilterArguments(callingUid = 0, target = 2), locateFilterArguments(HookSignature("boolean", types)))
    }

    @Test
    fun givenLeadingIntegerOrWrongReturnType_whenLocatingFilterArguments_thenRejectsSignature() {
        val known = listOf("com.android.server.pm.snapshot.PackageDataSnapshot", "int", "java.lang.Object", "com.android.server.pm.pkg.PackageStateInternal", "int")
        assertNull(locateFilterArguments(HookSignature("boolean", listOf("int") + known)))
        assertNull(locateFilterArguments(HookSignature("int", known)))
    }

    @Test
    fun givenUnknownSettingsOverloads_whenValidatingSignatures_thenRejectsThem() {
        val call = HookSignature("android.os.Bundle", listOf("android.content.AttributionSource", "java.lang.String", "java.lang.String", "java.lang.String", "android.os.Bundle"))
        val query = HookSignature("android.database.Cursor", listOf("android.content.AttributionSource", "android.net.Uri", "java.lang.String[]", "android.os.Bundle", "android.os.ICancellationSignal"))
        assertTrue(isSettingsCall(call))
        assertTrue(isSettingsQuery(query))
        assertFalse(isSettingsCall(call.copy(returns = "java.lang.Object")))
        assertFalse(isSettingsCall(call.copy(parameters = call.parameters + "int")))
        assertFalse(isSettingsQuery(query.copy(parameters = listOf("android.net.Uri") + query.parameters)))
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
        assertTrue(isAndroidIdRequest("settings", "GET_secure", "android_id"))
        assertFalse(isAndroidIdRequest("settings", "GET_secure", "bluetooth_name"))
        assertFalse(isAndroidIdRequest("settings", "GET_global", "android_id"))
        assertFalse(isAndroidIdRequest("other", "GET_secure", "android_id"))
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
            keys = setOf("android_id", "app_list", "uri_grants", "ad_id", "api", "list", "boot", "ack", "now"),
        )
        for (case in cases.ofType("line")) {
            val text = sandboxStatusText(
                androidId = writtenState(case.line, "android_id"),
                appList = writtenState(case.line, "app_list"),
                uriGrants = writtenState(case.line, "uri_grants"),
                adId = writtenState(case.line, "ad_id"),
                api = case.get("api")!!.toInt(),
                listReadable = case.get("list") == "1",
                bootId = case.get("boot")!!,
            )
            assertEquals(case.line, text)
        }
    }

    private fun writtenState(text: String, key: String): HookState = HookState.entries.single { "${key}_health=${it.id}" in text.lineSequence() }

    @Test
    fun givenKnownPermissionChecks_whenLocatingArguments_thenFindsSubjectAndPermission() {
        assertEquals(PermissionArguments(1, 0, 2), locatePackagePermission(HookSignature("int", listOf("java.lang.String", "java.lang.String", "int"))))
        assertEquals(PermissionArguments(1, 0, 3), locatePackagePermission(HookSignature("int", listOf("java.lang.String", "java.lang.String", "int", "int"))))
        assertEquals(PermissionArguments(1, 0, 3), locatePackagePermission(HookSignature("int", listOf("java.lang.String", "java.lang.String", "java.lang.String", "int"))))
        assertEquals(PermissionArguments(1, 0), locateUidPermission(HookSignature("int", listOf("int", "java.lang.String", "int"))))
    }

    @Test
    fun givenUnknownIdentifierOverloads_whenValidatingSignatures_thenRejectsThem() {
        val provider = HookSignature("android.app.ContentProviderHolder", listOf("android.app.IApplicationThread", "java.lang.String", "java.lang.String", "int", "boolean"))
        val bind = HookSignature("int", listOf("android.app.IApplicationThread", "android.os.IBinder", "android.content.Intent", "java.lang.String", "android.app.IServiceConnection", "int", "java.lang.String", "java.lang.String", "int"))
        assertTrue(isIdentifierProvider(provider))
        assertTrue(isIdentifierBind(bind))
        assertFalse(isIdentifierProvider(provider.copy(returns = "int")))
        assertFalse(isIdentifierBind(bind.copy(parameters = bind.parameters + "android.content.Intent")))
        assertNull(locateUidPermission(HookSignature("int", listOf("int", "int", "java.lang.String", "int"))))
        assertNull(locatePackagePermission(HookSignature("boolean", listOf("java.lang.String", "java.lang.String", "int"))))
    }

    @Test
    fun givenTheWaysToAnIdService_whenBound_thenEachIsFound() {
        // Play services, by its action and by its class.
        assertEquals(IdServiceKind.PLAY_SERVICES, idServiceBound("com.google.android.gms", null, "com.google.android.gms.ads.identifier.service.START"))
        assertEquals(
            IdServiceKind.PLAY_SERVICES,
            idServiceBound("com.google.android.gms", "com.google.android.gms.ads.identifier.service.AdvertisingIdService", null),
        )
        // AdServices and Huawei in whichever package serves them.
        assertEquals(IdServiceKind.OTHER, idServiceBound("com.google.android.adservices.api", null, "android.adservices.ADID_SERVICE"))
        assertEquals(IdServiceKind.OTHER, idServiceBound("com.android.adservices.api", "com.android.adservices.adid.AdIdService", null))
        assertEquals(IdServiceKind.OTHER, idServiceBound("com.huawei.hwid", null, "com.uodis.opendevice.OPENIDS_SERVICE"))
        assertEquals(IdServiceKind.OTHER, idServiceBound("com.samsung.android.deviceidservice", "com.samsung.android.deviceidservice.DeviceIdService", null))
        assertEquals(IdServiceKind.OTHER, idServiceBound("com.android.creator", null, "android.service.action.msa"))
    }

    @Test
    fun givenAnotherServiceOfTheSamePackages_whenBound_thenItIsNotAnIdService() {
        assertNull(idServiceBound("com.google.android.gms", "com.google.android.gms.chimera.GmsBoundBrokerService", "com.google.android.gms.measurement.START"))
        assertNull(idServiceBound("com.huawei.hwid", null, "com.huawei.push.action.MESSAGING_EVENT"))
        assertNull(idServiceBound("com.example", null, null))
        // An action that names an identifier service only in its package.
        assertNull(idServiceBound("com.example", null, "android.service.action.msa"))
    }

    @Test
    fun givenThePackagesTargets_whenAsked_thenPlayServicesGivesTheIdUnaskedBeforeAndroid13() {
        assertTrue(playServicesGivesIdUnasked(listOf(32)))
        assertTrue(playServicesGivesIdUnasked(listOf(35, 30)))
        assertFalse(playServicesGivesIdUnasked(listOf(33, 36)))
        assertFalse(playServicesGivesIdUnasked(emptyList()))
    }

    @Test
    fun givenAProviderName_whenAsked_thenTheIdProvidersAreKnownWithOrWithoutTheUser() {
        assertTrue(isIdProvider("com.vivo.vms.IdProvider"))
        assertTrue(isIdProvider("0@com.meizu.flyme.openidsdk"))
        assertTrue(isIdProvider("com.miui.idprovider"))
        assertFalse(isIdProvider("settings"))
        assertFalse(isIdProvider("com.example.provider"))
    }
}
