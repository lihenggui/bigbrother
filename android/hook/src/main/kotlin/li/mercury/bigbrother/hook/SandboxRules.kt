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

/*
 * The decisions behind the sandbox hooks, kept free of framework classes so
 * that they can be tested on the JVM. SandboxHook.kt applies them inside
 * system_server.
 */

/** Android multi-user layout: uid = user * 100000 + app id. */
internal const val PER_USER_RANGE = 100_000

/** The first app id given to an installed app; lower ones belong to the system. */
internal const val FIRST_APPLICATION_UID = 10_000

/**
 * What the hook answers differently for a sandboxed uid: its own
 * [androidId], if [ownAndroidId]; no installed app but itself and the
 * system's, if [hidesApps]; and, if [isolatesStorage], no file grant but
 * those of the moment ([strengthOfTheMoment]).
 */
internal data class SandboxedUid(
    val androidId: String,
    val ownAndroidId: Boolean = true,
    val hidesApps: Boolean = true,
    val isolatesStorage: Boolean = false,
)

/**
 * Parses the sandbox list the daemon writes (keep in sync with
 * `bbd/src/sandbox/identity.rs`): one `<uid> <android id>` pair per line,
 * separated by white space, the id being 16 hexadecimal digits, and a line
 * `items <uid> <item>[,<item>]` naming the hook's items that are on for the
 * uid (`device_ids`, `installed_apps`, `storage`). A uid without such a
 * line has `device_ids` and `installed_apps`, as before there was storage.
 *
 * Lines that do not have this shape are skipped rather than failing the
 * whole list, and so are uids of system components, which the daemon never
 * sandboxes: hiding apps from them would break the system. If a uid appears
 * twice, the last line wins.
 */
internal fun parseSandboxList(text: String): Map<Int, SandboxedUid> {
    val ids = mutableMapOf<Int, String>()
    val items = mutableMapOf<Int, Set<String>>()
    for (line in text.lineSequence()) {
        val fields = line.trim().split(WHITE_SPACE)
        val uidField = when {
            fields.size == 2 -> fields[0]
            fields.size == 3 && fields[0] == ITEMS -> fields[1]
            else -> continue
        }
        val uid = uidField.toIntOrNull() ?: continue
        if (uid < 0 || uid % PER_USER_RANGE < FIRST_APPLICATION_UID) continue
        if (fields.size == 3) {
            items[uid] = fields[2].split(',').toSet()
            continue
        }
        if (!ANDROID_ID.matches(fields[1])) continue
        ids[uid] = fields[1].lowercase()
    }
    return ids.mapValues { (uid, androidId) ->
        val named = items[uid] ?: return@mapValues SandboxedUid(androidId)
        SandboxedUid(
            androidId,
            ownAndroidId = DEVICE_IDS in named,
            hidesApps = INSTALLED_APPS in named,
            isolatesStorage = STORAGE in named,
        )
    }
}

/** The word that starts a line of items in the sandbox list. */
private const val ITEMS = "items"

/** The items of the sandbox the hook carries out (`bbd/src/sandbox/items.rs`). */
private const val DEVICE_IDS = "device_ids"
private const val INSTALLED_APPS = "installed_apps"
private const val STORAGE = "storage"

/** `Intent.FLAG_GRANT_READ_URI_PERMISSION` and `FLAG_GRANT_WRITE_URI_PERMISSION`. */
private const val READ_AND_WRITE = 0x1 or 0x2

/**
 * Strengths `UriPermission.getStrength` answers with (its `STRENGTH_*`,
 * compiled into the callers and so not to be read at run time).
 */
internal const val STRENGTH_NONE = 0
internal const val STRENGTH_OWNED = 1
internal const val STRENGTH_GLOBAL = 2

/**
 * How strong a grant to an app whose storage is isolated is for [modeFlags],
 * given the modes it was granted for the moment: [ownedModeFlags], held for
 * as long as what it was granted through (the activity the user picked a
 * file for), and [globalModeFlags], until the device restarts. The modes it
 * keeps across restarts (persisted) do not count: those reach real files
 * the app may have been let pick before its storage was isolated, and the
 * app itself can take them again at any time to make them look new. The
 * moment's grants are made by the system as the user picks a file, which
 * the app cannot do on its own.
 *
 * Mirrors `UriPermission.getStrength` without its persistable modes.
 */
internal fun strengthOfTheMoment(modeFlags: Int, ownedModeFlags: Int, globalModeFlags: Int): Int {
    val wanted = modeFlags and READ_AND_WRITE
    return when {
        globalModeFlags and wanted == wanted -> STRENGTH_GLOBAL
        ownedModeFlags and wanted == wanted -> STRENGTH_OWNED
        else -> STRENGTH_NONE
    }
}

/**
 * Whether the arguments of a `ContentProvider.Transport.call` are a read of
 * `Settings.Secure.ANDROID_ID`. The settings client asks for it with the
 * method `GET_secure` and the setting name as the argument; the other
 * arguments (caller, authority, extras) vary between releases, so the two
 * values are looked for anywhere in the list.
 */
internal fun isAndroidIdRequest(args: List<Any?>): Boolean = "GET_secure" in args && "android_id" in args

/**
 * Whether a content URI's authority is the settings provider's. A caller may
 * name the user in it (`0@settings`).
 */
internal fun isSettingsAuthority(authority: String?): Boolean = authority?.substringAfterLast('@') == "settings"

/** What a query of the settings provider that returns ANDROID_ID asks for. */
internal enum class AndroidIdQuery {
    /** That one setting: `content://settings/secure/android_id`. */
    ROW,

    /** The secure table as a whole, which has it among its rows. */
    TABLE,
}

/**
 * Whether a query of the settings provider with this path returns
 * ANDROID_ID, and how. This is the other way to the value, past
 * `Settings.Secure.getString`.
 */
internal fun androidIdQuery(pathSegments: List<String>): AndroidIdQuery? = when {
    pathSegments.firstOrNull() != "secure" -> null
    pathSegments.size == 1 -> AndroidIdQuery.TABLE
    pathSegments.size == 2 && pathSegments[1] == "android_id" -> AndroidIdQuery.ROW
    else -> null
}

/** Where the two arguments `shouldFilterApplication` is judged by are, as indexes into its parameters. */
internal data class FilterArguments(val callingUid: Int, val target: Int)

/**
 * Finds the calling uid and the target package in a `shouldFilterApplication`
 * signature, given the declared parameter types as class names (`int` for
 * the primitive). Returns null if the signature does not have both.
 *
 * Known shapes:
 * - Android 12 / 12L: `(int callingUid, SettingBase callingSetting,
 *   PackageSetting targetPkgSetting, int userId)`
 * - Android 13 to 17: `(PackageDataSnapshot snapshot, int callingUid,
 *   Object callingSetting, PackageStateInternal targetPkgSetting, int userId)`
 *
 * The calling uid is the first `int`; the user id comes later. The target is
 * the last parameter declared as a package, matched by simple name so that a
 * package moved between Java packages is still found. It has to be the last
 * one and has to go by the declared type: the calling setting comes first and
 * is itself a `PackageSetting` at run time, so the first match, or a match on
 * run-time types, is the caller.
 */
internal fun locateFilterArguments(parameterTypes: List<String>): FilterArguments? {
    val callingUid = parameterTypes.indexOfFirst { it == "int" }
    val target = parameterTypes.indexOfLast { it.substringAfterLast('.') in PACKAGE_TYPES }
    if (callingUid < 0 || target < 0) return null
    return FilterArguments(callingUid, target)
}

/**
 * Whether a sandboxed app calling as [callingUid] must not see the package
 * with app id [targetAppId]. The apps the user installed, other than itself,
 * are hidden. What came with the system stays visible, whatever its app id:
 * the WebView, the media and contacts providers, the file picker and Google
 * Play services are packages of the system image with app ids of their own,
 * and the same check decides whether an app may reach them at all. Packages
 * sharing the caller's uid stay visible too.
 */
internal fun hideFromSandboxed(callingUid: Int, targetAppId: Int, targetIsSystem: Boolean): Boolean = !targetIsSystem && targetAppId >= FIRST_APPLICATION_UID && targetAppId != callingUid % PER_USER_RANGE

/**
 * The permission Google Play services checks before it gives an app that
 * targets Android 13 or later its advertising ID; to an app without it, it
 * gives one of zeros, as to every app once the user deleted the ID.
 */
internal const val AD_ID_PERMISSION = "com.google.android.gms.permission.AD_ID"

/** Where a check by package of [AD_ID_PERMISSION] names the package and the user. */
internal data class PackageCheck(val packageName: String, val userId: Int)

/**
 * The package and user a `PermissionManagerService.checkPermission` is
 * asked about, if it asks about [AD_ID_PERMISSION]; null for any other
 * permission.
 *
 * Known shapes:
 * - Android 12 / 12L: `(String permName, String pkgName, int userId)`
 * - Android 13: `(String packageName, String permissionName, int userId)`
 * - Android 14: `(String packageName, String permissionName, int deviceId, int userId)`
 * - Android 15 to 17: `(String packageName, String permissionName,
 *   String persistentDeviceId, int userId)`
 *
 * The package is the first string that is not the permission, the device
 * coming after it; the user is the last `int`.
 */
internal fun adIdCheckByPackage(args: List<Any?>): PackageCheck? {
    if (AD_ID_PERMISSION !in args) return null
    val packageName = args.firstOrNull { it is String && it != AD_ID_PERMISSION } as String? ?: return null
    val userId = args.lastOrNull { it is Int } as Int? ?: return null
    return PackageCheck(packageName, userId)
}

/**
 * The uid a `PermissionManagerService.checkUidPermission` is asked about,
 * if it asks about [AD_ID_PERMISSION]; null for any other permission.
 *
 * Known shapes:
 * - Android 12 / 12L: `(String permName, int uid)`
 * - Android 13: `(int uid, String permissionName)`
 * - Android 14 to 17: `(int uid, String permissionName, int deviceId)`
 *
 * The uid is the first `int`.
 */
internal fun adIdCheckByUid(args: List<Any?>): Int? {
    if (AD_ID_PERMISSION !in args) return null
    return args.firstOrNull { it is Int } as Int?
}

/** The first release whose apps Play services asks for [AD_ID_PERMISSION]. */
private const val AD_ID_PERMISSION_SINCE = 33

/**
 * Whether Play services gives a uid whose packages target these releases
 * its advertising ID without asking for [AD_ID_PERMISSION]: it does for a
 * package that targets one before Android 13, and the uid gets the ID if
 * any of its packages does.
 */
internal fun playServicesGivesIdUnasked(targetSdks: List<Int>): Boolean = targetSdks.any { it < AD_ID_PERMISSION_SINCE }

/** What gives the identifier a service in [ID_SERVICES] gives. */
internal enum class IdServiceKind {
    /**
     * Google Play services' advertising ID, which an app that targets
     * Android 13 or later is held from by [AD_ID_PERMISSION] instead: it
     * then gets an ID of zeros rather than none.
     */
    PLAY_SERVICES,

    /** Any other: Android's own advertising ID, or a vendor's (OAID). */
    OTHER,
}

/**
 * A service that gives an advertising or device identifier, as an app
 * binds it: in [packageName] (any package if null), with [action] or by
 * [className]. Either is enough, and neither names another service.
 */
private data class IdService(
    val packageName: String?,
    val action: String?,
    val className: String?,
    val kind: IdServiceKind = IdServiceKind.OTHER,
)

/**
 * The services that give advertising and device identifiers. Those of
 * the vendors are those the identifier SDKs (MSA and its open
 * reimplementations) bind; the package alone is not enough, as the same
 * package serves accounts, push messages and the like.
 */
private val ID_SERVICES = listOf(
    IdService(
        "com.google.android.gms",
        "com.google.android.gms.ads.identifier.service.START",
        "com.google.android.gms.ads.identifier.service.AdvertisingIdService",
        IdServiceKind.PLAY_SERVICES,
    ),
    // Android's AdServices (`AdIdManager`), from Android 13.
    IdService(null, "android.adservices.ADID_SERVICE", "com.android.adservices.adid.AdIdService"),
    // Huawei (HMS), in com.huawei.hwid and its variants.
    IdService(null, "com.uodis.opendevice.OPENIDS_SERVICE", null),
    // OPPO, OnePlus and realme.
    IdService("com.heytap.openid", "action.com.heytap.openid.OPEN_ID_SERVICE", "com.heytap.openid.IdentifyService"),
    IdService("com.coloros.mcs", "action.com.oplus.stdid.ID_SERVICE", "com.oplus.stdid.IdentifyService"),
    IdService("com.samsung.android.deviceidservice", null, "com.samsung.android.deviceidservice.DeviceIdService"),
    // Lenovo and Motorola.
    IdService("com.zui.deviceidservice", null, "com.zui.deviceidservice.DeviceidService"),
    IdService("com.asus.msa.SupplementaryDID", "com.asus.msa.action.ACCESS_DID", "com.asus.msa.SupplementaryDID.SupplementaryDIDService"),
    IdService("com.coolpad.deviceidsupport", null, "com.coolpad.deviceidsupport.DeviceIdService"),
    IdService("com.qiku.id", "qiku.service.action.id", null),
    IdService("com.android.creator", "android.service.action.msa", null),
    IdService("com.mdid.msa", "com.bun.msa.action.bindto.service", "com.mdid.msa.service.MsaIdService"),
)

/**
 * The providers that give vendors' device identifiers, by authority: vivo,
 * Meizu, nubia, and Xiaomi's Security Center, which the system's
 * `com.android.id.impl.IdProviderImpl` queries in the app's own process.
 */
private val ID_PROVIDERS = setOf("com.vivo.vms.IdProvider", "com.meizu.flyme.openidsdk", "cn.nubia.identity", "com.miui.idprovider")

/**
 * Which identifier service an app binding a service reaches, or null if
 * none: [packageName] is the package of the intent's component, or the
 * package the intent is limited to (a service is bound with one or the
 * other), [className] the component's class.
 */
internal fun idServiceBound(packageName: String?, className: String?, action: String?): IdServiceKind? = ID_SERVICES.firstOrNull { service ->
    (service.packageName == null || service.packageName == packageName) &&
        ((service.action != null && service.action == action) || (service.className != null && service.className == className))
}?.kind

/**
 * Whether the provider an app asks for by [name] (an authority, which may
 * name the user, `0@authority`) gives a device identifier.
 */
internal fun isIdProvider(name: String): Boolean = name.substringAfterLast('@') in ID_PROVIDERS

/**
 * The self-check the daemon reads (keep in sync with
 * `bbd/src/sandbox/identity.rs`): which hooks attached, on which release,
 * whether the sandbox list could be read, and in which boot this was written.
 * A status left by an earlier boot, in which the hook may have been there
 * and no longer is, is told by its [bootId].
 */
internal fun sandboxStatusText(
    androidId: Boolean,
    appList: Boolean,
    uriGrants: Boolean,
    adId: Boolean,
    api: Int,
    listReadable: Boolean,
    bootId: String,
): String = buildString {
    append("android_id=").append(if (androidId) 1 else 0).append('\n')
    append("app_list=").append(if (appList) 1 else 0).append('\n')
    append("uri_grants=").append(if (uriGrants) 1 else 0).append('\n')
    append("ad_id=").append(if (adId) 1 else 0).append('\n')
    append("api=").append(api).append('\n')
    append("list=").append(if (listReadable) 1 else 0).append('\n')
    append("boot=").append(bootId).append('\n')
}

private val WHITE_SPACE = Regex("\\s+")
private val ANDROID_ID = Regex("[0-9a-fA-F]{16}")
private val PACKAGE_TYPES = setOf("PackageSetting", "PackageStateInternal")
