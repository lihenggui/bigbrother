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

package li.mercury.bigbrother.core.model

/**
 * A kind of data the sandbox can keep from an app (`bbd/src/sandbox/items.rs`),
 * by the name the daemon gives it.
 */
enum class SandboxItem(val id: String) {
    STORAGE("storage"),
    CPU_INFO("cpu_info"),
    CONTACTS("contacts"),
    MESSAGES("messages"),
    CALENDAR("calendar"),
    CLIPBOARD_ACCOUNTS("clipboard_accounts"),
    LOCATION("location"),
    CAMERA("camera"),
    MICROPHONE("microphone"),
    BODY_SENSORS("body_sensors"),
    DEVICE_IDS("device_ids"),
    INSTALLED_APPS("installed_apps"),
    NEARBY_DEVICES("nearby_devices"),
    NOTIFICATION_CONTENT("notification_content"),
    SCREEN_CONTENT("screen_content"),

    /** On, the app is kept from the network: from anything but the device itself. */
    NETWORK("network"),

    /** On, the destinations the app connects to are recorded. Set per app only. */
    NETWORK_RECORD("network_record"),
    ;

    /** The mode it has unless set otherwise: the network is left to the app, and not recorded. */
    val default: SandboxMode
        get() = when (this) {
            NETWORK, NETWORK_RECORD -> SandboxMode.OFF
            else -> SandboxMode.ON
        }

    /** The modes it can be set to, the one it starts with first. */
    val modes: List<SandboxMode>
        get() = when (this) {
            LOCATION -> listOf(SandboxMode.ON, SandboxMode.COARSE, SandboxMode.FOREGROUND, SandboxMode.OFF)
            CAMERA, MICROPHONE -> listOf(SandboxMode.ON, SandboxMode.FOREGROUND, SandboxMode.OFF)
            NETWORK, NETWORK_RECORD -> listOf(SandboxMode.OFF, SandboxMode.ON)
            else -> listOf(SandboxMode.ON, SandboxMode.OFF)
        }

    /**
     * What carries it out (`Settings::unmet` in `bbd/src/sandbox/items.rs`):
     * it is in effect only with all of them.
     */
    val parts: Set<SandboxPart>
        get() = when (this) {
            STORAGE -> setOf(SandboxPart.MOUNTS, SandboxPart.APP_OPS, SandboxPart.URI_GRANTS_HOOK)

            CPU_INFO -> setOf(SandboxPart.MOUNTS)

            DEVICE_IDS -> setOf(SandboxPart.APP_OPS, SandboxPart.ANDROID_ID_HOOK, SandboxPart.AD_ID_HOOK)

            INSTALLED_APPS -> setOf(SandboxPart.APP_OPS, SandboxPart.APP_LIST_HOOK)

            // The mount is the one over the resolver's socket: without it,
            // connections fail but name lookups still go through.
            NETWORK -> setOf(SandboxPart.PACKET_FILTER, SandboxPart.MOUNTS)

            NETWORK_RECORD -> emptySet()

            else -> setOf(SandboxPart.APP_OPS)
        }

    /**
     * The permissions an app has to name to get this data at all: ask for
     * them, or, for the notifications and the screen, bind a service with
     * them. Empty for what every app can read: its effect is shown for
     * every app.
     */
    val permissions: List<String>
        get() = when (this) {
            CONTACTS -> listOf("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS")

            MESSAGES -> listOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_CALL_LOG")

            CALENDAR -> listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR")

            LOCATION -> listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")

            CAMERA -> listOf("android.permission.CAMERA")

            MICROPHONE -> listOf("android.permission.RECORD_AUDIO")

            BODY_SENSORS -> listOf(
                "android.permission.BODY_SENSORS",
                "android.permission.ACTIVITY_RECOGNITION",
                // What Android 16 splits the body sensors permission into.
                "android.permission.health.READ_HEART_RATE",
                "android.permission.health.READ_SKIN_TEMPERATURE",
                "android.permission.health.READ_OXYGEN_SATURATION",
            )

            NEARBY_DEVICES -> listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.NEARBY_WIFI_DEVICES",
            )

            NOTIFICATION_CONTENT -> listOf("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")

            SCREEN_CONTENT -> listOf("android.permission.BIND_ACCESSIBILITY_SERVICE")

            NETWORK, NETWORK_RECORD -> listOf("android.permission.INTERNET")

            STORAGE, CPU_INFO, CLIPBOARD_ACCOUNTS, DEVICE_IDS, INSTALLED_APPS -> emptyList()
        }

    /** Whether an app whose manifest names [requested] can get this data. */
    fun concerns(requested: Set<String>): Boolean = permissions.isEmpty() || permissions.any { it in requested }

    companion object {
        fun fromId(id: String): SandboxItem? = entries.firstOrNull { it.id == id }
    }
}

/** What carries out an item, by the name the daemon gives it. */
enum class SandboxPart(val id: String) {
    /** Mounts made as the app starts. */
    MOUNTS("mounts"),

    /** The app's app ops, held by the daemon. */
    APP_OPS("app_ops"),

    /** The LSPosed hook in system_server, answering ANDROID_ID. */
    ANDROID_ID_HOOK("android_id_hook"),

    /** The LSPosed hook in system_server, hiding the installed apps. */
    APP_LIST_HOOK("app_list_hook"),

    /**
     * The LSPosed hook in system_server, holding the app to the file grants
     * of the moment rather than those it kept from before.
     */
    URI_GRANTS_HOOK("uri_grants_hook"),

    /** The LSPosed hook in system_server, withholding the advertising ID. */
    AD_ID_HOOK("ad_id_hook"),

    /** The kernel's packet filter, refusing the app's connections. */
    PACKET_FILTER("packet_filter"),
    ;

    companion object {
        fun fromId(id: String): SandboxPart? = entries.firstOrNull { it.id == id }
    }
}

/** Health reported by system_server for each sandbox hook part. */
enum class SandboxHookState(val id: String) {
    NOT_LOADED("not_loaded"),
    INCOMPATIBLE("incompatible"),
    DEGRADED("degraded"),
    WORKING("working"),
    ;

    companion object {
        fun fromId(id: String): SandboxHookState? = entries.firstOrNull { it.id == id }
    }
}

/** What an item does to the app. */
enum class SandboxMode(val id: String) {
    /** The app gets what the system gives it. */
    OFF("off"),

    /** An empty, made-up or separate answer, with the permission shown as granted. */
    ON("on"),

    /** For location: an approximate one only. */
    COARSE("coarse"),

    /** For location, camera and microphone: only while the app is in use. */
    FOREGROUND("foreground"),
    ;

    /** Whether the item keeps anything from the app. */
    val isOn: Boolean get() = this != OFF

    companion object {
        fun fromId(id: String): SandboxMode? = entries.firstOrNull { it.id == id }
    }
}

/** An app in the sandbox, or one that was and kept its settings. */
data class SandboxedApp(
    val uid: Int,
    val packageName: String,
    val enabled: Boolean,
    /** When it was first put in the sandbox, in seconds since the epoch. */
    val created: Long,
    /** The items it has a mode of its own for. */
    val settings: Map<SandboxItem, SandboxMode>,
    /** What applies to it: its own settings over the global ones. */
    val items: Map<SandboxItem, SandboxMode>,
    /**
     * For an app in the sandbox, the items that are on but not in effect,
     * each with the parts not in place, as the daemon found them.
     */
    val unmet: Map<SandboxItem, Set<SandboxPart>> = emptyMap(),
    /** How many destinations are blocked for it. */
    val networkRules: Int = 0,
    /** Since when its connections are recorded, in seconds since the epoch; null if they are not. */
    val recordingSince: Long? = null,
    /** Whether the daemon is still checking this app's Android restrictions. */
    val appOpsChecking: Boolean = false,
    val hookHealth: Map<SandboxPart, SandboxHookState> = emptyMap(),
) {
    val userId: Int get() = userIdOf(uid)

    /** The mode of [item] that applies. */
    fun modeOf(item: SandboxItem): SandboxMode = items[item] ?: item.default

    /** In the sandbox with an item on that takes app ops. */
    val needsAppOps: Boolean get() = enabled && SandboxItem.entries.any { SandboxPart.APP_OPS in it.parts && modeOf(it).isOn }
}

/** Which parts of the sandbox work on this device. */
data class SandboxStatus(
    /** Starting apps can be given their mounts (storage, processor information). */
    val gate: Boolean,
    /**
     * App ops are held. The daemon runs what holds them only while an app
     * needs them, and reports false otherwise: see [relevantTo].
     */
    val appOps: Boolean,
    /** The hook answers ANDROID_ID. */
    val androidIdHook: Boolean,
    /** The hook hides the installed apps. */
    val appListHook: Boolean,
    /** The hook holds apps to the file grants of the moment. */
    val uriGrantsHook: Boolean,
    /** The hook withholds the advertising ID. */
    val adIdHook: Boolean,
    /** Newly installed apps not isolated yet, with isolating new apps on. */
    val newAppsPending: Int = 0,
    /** Whether those are kept from starting until they are isolated. */
    val newAppsHeld: Boolean = false,
    /** The packet filter can keep apps from the network. */
    val network: Boolean = false,
    /** The destinations apps connect to can be recorded. */
    val netRecord: Boolean = false,
    /** The names apps look up can be read: destinations have their domain names. */
    val dnsNames: Boolean = false,
    /** How much data goes to each destination can be counted. */
    val traffic: Boolean = false,
    /** Newly installed apps allowed to start after automatic isolation failed. */
    val newAppsFailed: Int = 0,
    val hookHealth: Map<SandboxPart, SandboxHookState> = emptyMap(),
) {
    /** The parts of [item] not in place on this device, for every app. */
    fun unmet(item: SandboxItem): Set<SandboxPart> = item.parts.filterTo(mutableSetOf()) { part ->
        when (part) {
            SandboxPart.MOUNTS -> !gate
            SandboxPart.APP_OPS -> !appOps
            SandboxPart.ANDROID_ID_HOOK -> !androidIdHook
            SandboxPart.APP_LIST_HOOK -> !appListHook
            SandboxPart.URI_GRANTS_HOOK -> !uriGrantsHook
            SandboxPart.AD_ID_HOOK -> !adIdHook
            SandboxPart.PACKET_FILTER -> !network
        }
    }

    /**
     * This status as it bears on [apps]. App ops not held is a fault only
     * while one of them needs them: otherwise the daemon has no reason to
     * hold any, and nothing goes without.
     */
    fun relevantTo(apps: List<SandboxedApp>): SandboxStatus = if (apps.any { it.needsAppOps }) this else copy(appOps = true)
}

/** An app installed for the current user. */
class InstalledApp(
    val packageName: String,
    val uid: Int,
    val info: AppInfo,
) {
    val userId: Int get() = userIdOf(uid)
}
