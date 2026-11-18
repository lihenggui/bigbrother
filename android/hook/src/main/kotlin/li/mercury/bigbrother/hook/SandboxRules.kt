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
    api: Int,
    listReadable: Boolean,
    bootId: String,
): String = buildString {
    append("android_id=").append(if (androidId) 1 else 0).append('\n')
    append("app_list=").append(if (appList) 1 else 0).append('\n')
    append("uri_grants=").append(if (uriGrants) 1 else 0).append('\n')
    append("api=").append(api).append('\n')
    append("list=").append(if (listReadable) 1 else 0).append('\n')
    append("boot=").append(bootId).append('\n')
}

private val WHITE_SPACE = Regex("\\s+")
private val ANDROID_ID = Regex("[0-9a-fA-F]{16}")
private val PACKAGE_TYPES = setOf("PackageSetting", "PackageStateInternal")
