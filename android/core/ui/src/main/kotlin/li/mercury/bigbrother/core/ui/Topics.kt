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

package li.mercury.bigbrother.core.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.ui.graphics.vector.ImageVector

/** What the app shows for a topic id: its name, its category of private data and its icon. */
private class TopicInfo(@StringRes val title: Int, val category: DataCategory, val icon: ImageVector)

/**
 * Every topic id the daemon reports (`bbd/src/rules.rs`), in one table so a
 * new id gets its name and its category together.
 */
private val TOPICS: Map<String, TopicInfo> = mapOf(
    "mac_address" to TopicInfo(R.string.topic_mac_address_title, DataCategory.DEVICE_IDS, Icons.Outlined.Router),
    "network_interfaces" to TopicInfo(R.string.topic_network_interfaces_title, DataCategory.NETWORK, Icons.Outlined.SettingsEthernet),
    "arp_table" to TopicInfo(R.string.topic_arp_table_title, DataCategory.NETWORK, Icons.Outlined.Lan),
    "network_state" to TopicInfo(R.string.topic_network_state_title, DataCategory.NETWORK, Icons.Outlined.NetworkCheck),
    "device_serial" to TopicInfo(R.string.topic_device_serial_title, DataCategory.DEVICE_IDS, Icons.Outlined.Tag),
    "soc_info" to TopicInfo(R.string.topic_soc_info_title, DataCategory.DEVICE_PROFILE, Icons.Outlined.DeveloperBoard),
    "boot_id" to TopicInfo(R.string.topic_boot_id_title, DataCategory.DEVICE_IDS, Icons.Outlined.RestartAlt),
    "cpu_model" to TopicInfo(R.string.topic_cpu_model_title, DataCategory.DEVICE_PROFILE, Icons.Outlined.Memory),
    "kernel_version" to TopicInfo(R.string.topic_kernel_version_title, DataCategory.DEVICE_PROFILE, Icons.Outlined.Terminal),
    "other_processes" to TopicInfo(R.string.topic_other_processes_title, DataCategory.APP_ACTIVITY, Icons.AutoMirrored.Outlined.ManageSearch),
    "mounts" to TopicInfo(R.string.topic_mounts_title, DataCategory.ENVIRONMENT, Icons.Outlined.Storage),
    "kernel_modules" to TopicInfo(R.string.topic_kernel_modules_title, DataCategory.ENVIRONMENT, Icons.Outlined.Extension),
    "selinux_state" to TopicInfo(R.string.topic_selinux_state_title, DataCategory.ENVIRONMENT, Icons.Outlined.Policy),
    "block_devices" to TopicInfo(R.string.topic_block_devices_title, DataCategory.DEVICE_PROFILE, Icons.Outlined.SdStorage),
    "emulator_probe" to TopicInfo(R.string.topic_emulator_probe_title, DataCategory.ENVIRONMENT, Icons.Outlined.PhoneAndroid),
    "input_devices" to TopicInfo(R.string.topic_input_devices_title, DataCategory.DEVICE_PROFILE, Icons.Outlined.Keyboard),
    "battery" to TopicInfo(R.string.topic_battery_title, DataCategory.DEVICE_PROFILE, Icons.Outlined.BatteryStd),
    "root_check" to TopicInfo(R.string.topic_root_check_title, DataCategory.ENVIRONMENT, Icons.Outlined.AdminPanelSettings),
    "app_probe" to TopicInfo(R.string.topic_app_probe_title, DataCategory.ENVIRONMENT, Icons.Outlined.TravelExplore),
    "system_properties" to TopicInfo(R.string.topic_system_properties_title, DataCategory.DEVICE_PROFILE, Icons.Outlined.Tune),
    "installed_apps" to TopicInfo(R.string.topic_installed_apps_title, DataCategory.APP_ACTIVITY, Icons.Outlined.Apps),
    "system_settings" to TopicInfo(R.string.topic_system_settings_title, DataCategory.DEVICE_PROFILE, Icons.Outlined.Settings),
    "shell_command" to TopicInfo(R.string.topic_shell_command_title, DataCategory.ENVIRONMENT, Icons.Outlined.Terminal),
    "local_ports" to TopicInfo(R.string.topic_local_ports_title, DataCategory.NETWORK, Icons.Outlined.Dns),
    "local_network" to TopicInfo(R.string.topic_local_network_title, DataCategory.NETWORK, Icons.Outlined.Lan),
    "device_discovery" to TopicInfo(R.string.topic_device_discovery_title, DataCategory.NETWORK, Icons.Outlined.Radar),
    "process_memory" to TopicInfo(R.string.topic_process_memory_title, DataCategory.APP_ACTIVITY, Icons.Outlined.Memory),
    "contacts" to TopicInfo(R.string.topic_contacts_title, DataCategory.CONTACTS, Icons.Outlined.Contacts),
    "call_log" to TopicInfo(R.string.topic_call_log_title, DataCategory.MESSAGES, Icons.Outlined.Call),
    "messages" to TopicInfo(R.string.topic_messages_title, DataCategory.MESSAGES, Icons.Outlined.Sms),
    "calendar" to TopicInfo(R.string.topic_calendar_title, DataCategory.CALENDAR, Icons.Outlined.CalendarMonth),
    "sim_info" to TopicInfo(R.string.topic_sim_info_title, DataCategory.DEVICE_IDS, Icons.Outlined.SimCard),
    "clipboard" to TopicInfo(R.string.topic_clipboard_title, DataCategory.PERSONAL_INFO, Icons.Outlined.ContentPaste),
    "location" to TopicInfo(R.string.topic_location_title, DataCategory.LOCATION, Icons.Outlined.MyLocation),
    "wifi_info" to TopicInfo(R.string.topic_wifi_info_title, DataCategory.LOCATION, Icons.Outlined.WifiFind),
    "phone_identity" to TopicInfo(R.string.topic_phone_identity_title, DataCategory.DEVICE_IDS, Icons.Outlined.Badge),
    "accounts" to TopicInfo(R.string.topic_accounts_title, DataCategory.PERSONAL_INFO, Icons.Outlined.AccountCircle),
    "running_apps" to TopicInfo(R.string.topic_running_apps_title, DataCategory.APP_ACTIVITY, Icons.Outlined.ViewCarousel),
    "usage_stats" to TopicInfo(R.string.topic_usage_stats_title, DataCategory.APP_ACTIVITY, Icons.Outlined.QueryStats),
    "enabled_services" to TopicInfo(R.string.topic_enabled_services_title, DataCategory.APP_ACTIVITY, Icons.Outlined.Accessibility),
    "sensors" to TopicInfo(R.string.topic_sensors_title, DataCategory.SENSORS, Icons.Outlined.Sensors),
    "android_id" to TopicInfo(R.string.topic_android_id_title, DataCategory.DEVICE_IDS, Icons.Outlined.Fingerprint),
    "device_name" to TopicInfo(R.string.topic_device_name_title, DataCategory.PERSONAL_INFO, Icons.Outlined.Smartphone),
    "camera" to TopicInfo(R.string.topic_camera_title, DataCategory.SENSORS, Icons.Outlined.PhotoCamera),
    "microphone" to TopicInfo(R.string.topic_microphone_title, DataCategory.SENSORS, Icons.Outlined.Mic),
)

/** Name of a topic id, or null for an id this build doesn't know, in which case the raw path is shown. */
@StringRes
fun topicTitle(topic: String): Int? = TOPICS[topic]?.title

/** Category of a topic id, or null for an id this build doesn't know. */
fun topicCategory(topic: String): DataCategory? = TOPICS[topic]?.category

/** Icon of a topic id; for one this build doesn't know, or none, the device fingerprint's, its fallback category. */
fun topicIcon(topic: String?): ImageVector = topic?.let { TOPICS[it]?.icon } ?: DataCategory.DEVICE_PROFILE.icon
