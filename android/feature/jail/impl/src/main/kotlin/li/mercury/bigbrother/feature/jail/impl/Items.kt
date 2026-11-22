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

package li.mercury.bigbrother.feature.jail.impl

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import li.mercury.bigbrother.core.model.SandboxHookState
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxPart
import li.mercury.bigbrother.core.model.SandboxStatus

/** The items by what they keep from the app, as the global settings list them. */
internal enum class ItemGroup(@StringRes val title: Int, val items: List<SandboxItem>) {
    FILES(R.string.jail_group_files, listOf(SandboxItem.STORAGE)),
    PERSONAL(
        R.string.jail_group_personal,
        listOf(
            SandboxItem.CONTACTS,
            SandboxItem.MESSAGES,
            SandboxItem.CALENDAR,
            SandboxItem.CLIPBOARD_ACCOUNTS,
            SandboxItem.NOTIFICATION_CONTENT,
            SandboxItem.SCREEN_CONTENT,
        ),
    ),
    SENSORS(
        R.string.jail_group_sensors,
        listOf(SandboxItem.LOCATION, SandboxItem.CAMERA, SandboxItem.MICROPHONE, SandboxItem.BODY_SENSORS),
    ),
    DEVICE(
        R.string.jail_group_device,
        listOf(SandboxItem.DEVICE_IDS, SandboxItem.INSTALLED_APPS, SandboxItem.NEARBY_DEVICES, SandboxItem.CPU_INFO),
    ),
}

/**
 * Every isolation item in the order the groups list them: files first, then
 * personal data. The network is not one of them: it has a section of its own.
 */
internal val ITEM_ORDER: List<SandboxItem> = ItemGroup.entries.flatMap { it.items }

@get:StringRes
internal val SandboxItem.title: Int
    get() = when (this) {
        SandboxItem.STORAGE -> R.string.jail_item_storage
        SandboxItem.CPU_INFO -> R.string.jail_item_cpu_info
        SandboxItem.CONTACTS -> R.string.jail_item_contacts
        SandboxItem.MESSAGES -> R.string.jail_item_messages
        SandboxItem.CALENDAR -> R.string.jail_item_calendar
        SandboxItem.CLIPBOARD_ACCOUNTS -> R.string.jail_item_clipboard_accounts
        SandboxItem.LOCATION -> R.string.jail_item_location
        SandboxItem.CAMERA -> R.string.jail_item_camera
        SandboxItem.MICROPHONE -> R.string.jail_item_microphone
        SandboxItem.BODY_SENSORS -> R.string.jail_item_body_sensors
        SandboxItem.DEVICE_IDS -> R.string.jail_item_device_ids
        SandboxItem.INSTALLED_APPS -> R.string.jail_item_installed_apps
        SandboxItem.NEARBY_DEVICES -> R.string.jail_item_nearby_devices
        SandboxItem.NOTIFICATION_CONTENT -> R.string.jail_item_notification_content
        SandboxItem.SCREEN_CONTENT -> R.string.jail_item_screen_content
        SandboxItem.NETWORK -> R.string.jail_network_access
        SandboxItem.NETWORK_RECORD -> R.string.jail_network_record
    }

internal val SandboxItem.icon: ImageVector
    get() = when (this) {
        SandboxItem.STORAGE -> Icons.Outlined.Folder
        SandboxItem.CPU_INFO -> Icons.Outlined.Memory
        SandboxItem.CONTACTS -> Icons.Outlined.Contacts
        SandboxItem.MESSAGES -> Icons.Outlined.Sms
        SandboxItem.CALENDAR -> Icons.Outlined.CalendarMonth
        SandboxItem.CLIPBOARD_ACCOUNTS -> Icons.Outlined.ContentPaste
        SandboxItem.LOCATION -> Icons.Outlined.LocationOn
        SandboxItem.CAMERA -> Icons.Outlined.PhotoCamera
        SandboxItem.MICROPHONE -> Icons.Outlined.Mic
        SandboxItem.BODY_SENSORS -> Icons.AutoMirrored.Outlined.DirectionsRun
        SandboxItem.DEVICE_IDS -> Icons.Outlined.Fingerprint
        SandboxItem.INSTALLED_APPS -> Icons.Outlined.Apps
        SandboxItem.NEARBY_DEVICES -> Icons.Outlined.DevicesOther
        SandboxItem.NOTIFICATION_CONTENT -> Icons.Outlined.Notifications
        SandboxItem.SCREEN_CONTENT -> Icons.Outlined.ScreenshotMonitor
        SandboxItem.NETWORK -> Icons.Outlined.Public
        SandboxItem.NETWORK_RECORD -> Icons.Outlined.FiberManualRecord
    }

/** What the app gets with the item on, in a few words. */
@get:StringRes
private val SandboxItem.onLabel: Int
    get() = when (this) {
        SandboxItem.STORAGE -> R.string.jail_item_storage_on
        SandboxItem.CPU_INFO -> R.string.jail_item_cpu_info_on
        SandboxItem.CONTACTS -> R.string.jail_item_contacts_on
        SandboxItem.MESSAGES -> R.string.jail_item_messages_on
        SandboxItem.CALENDAR -> R.string.jail_item_calendar_on
        SandboxItem.CLIPBOARD_ACCOUNTS -> R.string.jail_item_clipboard_accounts_on
        SandboxItem.LOCATION -> R.string.jail_item_location_on
        SandboxItem.CAMERA -> R.string.jail_item_camera_on
        SandboxItem.MICROPHONE -> R.string.jail_item_microphone_on
        SandboxItem.BODY_SENSORS -> R.string.jail_item_body_sensors_on
        SandboxItem.DEVICE_IDS -> R.string.jail_item_device_ids_on
        SandboxItem.INSTALLED_APPS -> R.string.jail_item_installed_apps_on
        SandboxItem.NEARBY_DEVICES -> R.string.jail_item_nearby_devices_on
        SandboxItem.NOTIFICATION_CONTENT -> R.string.jail_item_notification_content_on
        SandboxItem.SCREEN_CONTENT -> R.string.jail_item_screen_content_on
        SandboxItem.NETWORK -> R.string.jail_network_blocked
        SandboxItem.NETWORK_RECORD -> R.string.jail_summary_recording
    }

@get:StringRes
private val SandboxItem.onDetail: Int
    get() = when (this) {
        SandboxItem.STORAGE -> R.string.jail_item_storage_on_detail
        SandboxItem.CPU_INFO -> R.string.jail_item_cpu_info_on_detail
        SandboxItem.CONTACTS -> R.string.jail_item_contacts_on_detail
        SandboxItem.MESSAGES -> R.string.jail_item_messages_on_detail
        SandboxItem.CALENDAR -> R.string.jail_item_calendar_on_detail
        SandboxItem.CLIPBOARD_ACCOUNTS -> R.string.jail_item_clipboard_accounts_on_detail
        SandboxItem.LOCATION -> R.string.jail_item_location_on_detail
        SandboxItem.CAMERA -> R.string.jail_item_camera_on_detail
        SandboxItem.MICROPHONE -> R.string.jail_item_microphone_on_detail
        SandboxItem.BODY_SENSORS -> R.string.jail_item_body_sensors_on_detail
        SandboxItem.DEVICE_IDS -> R.string.jail_item_device_ids_on_detail
        SandboxItem.INSTALLED_APPS -> R.string.jail_item_installed_apps_on_detail
        SandboxItem.NEARBY_DEVICES -> R.string.jail_item_nearby_devices_on_detail
        SandboxItem.NOTIFICATION_CONTENT -> R.string.jail_item_notification_content_on_detail
        SandboxItem.SCREEN_CONTENT -> R.string.jail_item_screen_content_on_detail
        SandboxItem.NETWORK -> R.string.jail_network_blocked_detail
        SandboxItem.NETWORK_RECORD -> R.string.jail_network_record_summary
    }

/** What the app gets from [item] in [mode], in a few words. */
@Composable
internal fun modeLabel(item: SandboxItem, mode: SandboxMode): String = stringResource(
    when (mode) {
        SandboxMode.ON -> item.onLabel
        SandboxMode.COARSE -> R.string.jail_mode_coarse
        SandboxMode.FOREGROUND -> R.string.jail_mode_foreground
        SandboxMode.OFF -> if (item == SandboxItem.NETWORK) R.string.jail_network_allowed else R.string.jail_mode_off
    },
)

/** A missing mount or packet filter changes what the whole item can do. */
@StringRes
internal fun notInEffect(item: SandboxItem, unmet: Set<SandboxPart>, hookHealth: Map<SandboxPart, SandboxHookState>): Int = when {
    SandboxPart.PACKET_FILTER in unmet -> R.string.jail_not_in_effect_packet_filter

    item == SandboxItem.NETWORK -> R.string.jail_not_in_effect_resolver

    SandboxPart.MOUNTS in unmet -> R.string.jail_not_in_effect_mounts

    unmet.any { it in HOOK_PARTS } -> when (hookFailure(unmet, hookHealth)) {
        SandboxHookState.NOT_LOADED -> R.string.jail_not_in_effect_hook
        SandboxHookState.INCOMPATIBLE -> R.string.jail_hook_incompatible
        SandboxHookState.DEGRADED -> R.string.jail_hook_degraded
        else -> R.string.jail_hook_unavailable
    }

    SandboxPart.APP_OPS_UNKNOWN in unmet -> R.string.jail_app_ops_unknown

    else -> R.string.jail_not_in_effect_app_ops
}

private val HOOK_PARTS = setOf(SandboxPart.ANDROID_ID_HOOK, SandboxPart.APP_LIST_HOOK, SandboxPart.URI_GRANTS_HOOK, SandboxPart.AD_ID_HOOK)

internal fun SandboxStatus.unmetHooks(): Set<SandboxPart> = (unmet(SandboxItem.DEVICE_IDS) + unmet(SandboxItem.INSTALLED_APPS) + unmet(SandboxItem.STORAGE)).intersect(HOOK_PARTS)

internal fun hookFailure(unmet: Set<SandboxPart>, health: Map<SandboxPart, SandboxHookState>): SandboxHookState? {
    val states = unmet.filter { it in HOOK_PARTS }.map { health[it] }
    return when {
        SandboxHookState.DEGRADED in states -> SandboxHookState.DEGRADED
        SandboxHookState.INCOMPATIBLE in states -> SandboxHookState.INCOMPATIBLE
        states.isNotEmpty() && states.all { it == SandboxHookState.NOT_LOADED } -> SandboxHookState.NOT_LOADED
        else -> null
    }
}

/** What [mode] of [item] means for the app. */
@Composable
internal fun modeDetail(item: SandboxItem, mode: SandboxMode): String = stringResource(
    when (mode) {
        SandboxMode.ON -> item.onDetail
        SandboxMode.COARSE -> R.string.jail_mode_coarse_detail
        SandboxMode.FOREGROUND -> R.string.jail_mode_foreground_detail
        SandboxMode.OFF -> if (item == SandboxItem.NETWORK) R.string.jail_network_allowed_detail else R.string.jail_mode_off_detail
    },
)
