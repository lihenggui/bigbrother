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
    ;

    /** The modes it can be set to, the one it starts with first. */
    val modes: List<SandboxMode>
        get() = when (this) {
            LOCATION -> listOf(SandboxMode.ON, SandboxMode.COARSE, SandboxMode.OFF)
            else -> listOf(SandboxMode.ON, SandboxMode.OFF)
        }

    /** Carried out by the LSPosed hook in system_server. */
    val needsHook: Boolean get() = this == DEVICE_IDS || this == INSTALLED_APPS

    /** Carried out by mounts made as the app starts. */
    val isMounted: Boolean get() = this == STORAGE || this == CPU_INFO

    /**
     * Personal data, carried out by holding the app's app ops
     * (`bbd/src/sandbox/appops.rs`): everything neither the hook nor the
     * mounts carry out.
     */
    val needsAppOps: Boolean get() = !needsHook && !isMounted

    /**
     * The permissions an app has to ask for to get this data at all. Empty for
     * what every app can read: its effect is shown for every app.
     */
    val permissions: List<String>
        get() = when (this) {
            CONTACTS -> listOf("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS")

            MESSAGES -> listOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_CALL_LOG")

            CALENDAR -> listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR")

            LOCATION -> listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")

            CAMERA -> listOf("android.permission.CAMERA")

            MICROPHONE -> listOf("android.permission.RECORD_AUDIO")

            BODY_SENSORS -> listOf("android.permission.BODY_SENSORS", "android.permission.ACTIVITY_RECOGNITION")

            NEARBY_DEVICES -> listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.NEARBY_WIFI_DEVICES",
            )

            STORAGE, CPU_INFO, CLIPBOARD_ACCOUNTS, DEVICE_IDS, INSTALLED_APPS -> emptyList()
        }

    /** Whether an app that asks for [requested] can get this data. */
    fun concerns(requested: Set<String>): Boolean = permissions.isEmpty() || permissions.any { it in requested }

    companion object {
        fun fromId(id: String): SandboxItem? = entries.firstOrNull { it.id == id }
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
) {
    val userId: Int get() = uid / PER_USER_RANGE

    /** The mode of [item] that applies. */
    fun modeOf(item: SandboxItem): SandboxMode = items[item] ?: SandboxMode.ON

    /** In the sandbox with personal data kept from it, which takes app ops. */
    val needsAppOps: Boolean get() = enabled && SandboxItem.entries.any { it.needsAppOps && modeOf(it).isOn }

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
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
) {
    /** Whether [item] can take effect here. */
    fun supports(item: SandboxItem): Boolean = when (item) {
        SandboxItem.DEVICE_IDS -> androidIdHook
        SandboxItem.INSTALLED_APPS -> appListHook
        SandboxItem.STORAGE, SandboxItem.CPU_INFO -> gate
        else -> appOps
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
)
