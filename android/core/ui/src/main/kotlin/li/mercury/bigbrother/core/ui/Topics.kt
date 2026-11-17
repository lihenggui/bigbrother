package li.mercury.bigbrother.core.ui

import androidx.annotation.StringRes

/** What the app shows for a topic id: its name and its category of private data. */
private class TopicInfo(@StringRes val title: Int, val category: DataCategory)

/**
 * Every topic id the daemon reports (`bbd/src/rules.rs`), in one table so a
 * new id gets its name and its category together.
 */
private val TOPICS: Map<String, TopicInfo> = mapOf(
    "mac_address" to TopicInfo(R.string.topic_mac_address_title, DataCategory.DEVICE_IDS),
    "network_interfaces" to TopicInfo(R.string.topic_network_interfaces_title, DataCategory.NETWORK),
    "arp_table" to TopicInfo(R.string.topic_arp_table_title, DataCategory.NETWORK),
    "network_state" to TopicInfo(R.string.topic_network_state_title, DataCategory.NETWORK),
    "device_serial" to TopicInfo(R.string.topic_device_serial_title, DataCategory.DEVICE_IDS),
    "soc_info" to TopicInfo(R.string.topic_soc_info_title, DataCategory.DEVICE_PROFILE),
    "boot_id" to TopicInfo(R.string.topic_boot_id_title, DataCategory.DEVICE_IDS),
    "cpu_model" to TopicInfo(R.string.topic_cpu_model_title, DataCategory.DEVICE_PROFILE),
    "kernel_version" to TopicInfo(R.string.topic_kernel_version_title, DataCategory.DEVICE_PROFILE),
    "other_processes" to TopicInfo(R.string.topic_other_processes_title, DataCategory.APP_ACTIVITY),
    "mounts" to TopicInfo(R.string.topic_mounts_title, DataCategory.ENVIRONMENT),
    "kernel_modules" to TopicInfo(R.string.topic_kernel_modules_title, DataCategory.ENVIRONMENT),
    "selinux_state" to TopicInfo(R.string.topic_selinux_state_title, DataCategory.ENVIRONMENT),
    "block_devices" to TopicInfo(R.string.topic_block_devices_title, DataCategory.DEVICE_PROFILE),
    "emulator_probe" to TopicInfo(R.string.topic_emulator_probe_title, DataCategory.ENVIRONMENT),
    "input_devices" to TopicInfo(R.string.topic_input_devices_title, DataCategory.DEVICE_PROFILE),
    "battery" to TopicInfo(R.string.topic_battery_title, DataCategory.DEVICE_PROFILE),
    "root_check" to TopicInfo(R.string.topic_root_check_title, DataCategory.ENVIRONMENT),
    "app_probe" to TopicInfo(R.string.topic_app_probe_title, DataCategory.ENVIRONMENT),
    "system_properties" to TopicInfo(R.string.topic_system_properties_title, DataCategory.DEVICE_PROFILE),
    "installed_apps" to TopicInfo(R.string.topic_installed_apps_title, DataCategory.APP_ACTIVITY),
    "system_settings" to TopicInfo(R.string.topic_system_settings_title, DataCategory.DEVICE_PROFILE),
    "shell_command" to TopicInfo(R.string.topic_shell_command_title, DataCategory.ENVIRONMENT),
    "local_ports" to TopicInfo(R.string.topic_local_ports_title, DataCategory.NETWORK),
    "local_network" to TopicInfo(R.string.topic_local_network_title, DataCategory.NETWORK),
    "device_discovery" to TopicInfo(R.string.topic_device_discovery_title, DataCategory.NETWORK),
    "process_memory" to TopicInfo(R.string.topic_process_memory_title, DataCategory.APP_ACTIVITY),
    "contacts" to TopicInfo(R.string.topic_contacts_title, DataCategory.CONTACTS),
    "call_log" to TopicInfo(R.string.topic_call_log_title, DataCategory.MESSAGES),
    "messages" to TopicInfo(R.string.topic_messages_title, DataCategory.MESSAGES),
    "calendar" to TopicInfo(R.string.topic_calendar_title, DataCategory.CALENDAR),
    "sim_info" to TopicInfo(R.string.topic_sim_info_title, DataCategory.DEVICE_IDS),
    "clipboard" to TopicInfo(R.string.topic_clipboard_title, DataCategory.PERSONAL_INFO),
    "location" to TopicInfo(R.string.topic_location_title, DataCategory.LOCATION),
    "wifi_info" to TopicInfo(R.string.topic_wifi_info_title, DataCategory.LOCATION),
    "phone_identity" to TopicInfo(R.string.topic_phone_identity_title, DataCategory.DEVICE_IDS),
    "accounts" to TopicInfo(R.string.topic_accounts_title, DataCategory.PERSONAL_INFO),
    "running_apps" to TopicInfo(R.string.topic_running_apps_title, DataCategory.APP_ACTIVITY),
    "usage_stats" to TopicInfo(R.string.topic_usage_stats_title, DataCategory.APP_ACTIVITY),
    "enabled_services" to TopicInfo(R.string.topic_enabled_services_title, DataCategory.APP_ACTIVITY),
    "sensors" to TopicInfo(R.string.topic_sensors_title, DataCategory.SENSORS),
    "android_id" to TopicInfo(R.string.topic_android_id_title, DataCategory.DEVICE_IDS),
    "device_name" to TopicInfo(R.string.topic_device_name_title, DataCategory.PERSONAL_INFO),
    "camera" to TopicInfo(R.string.topic_camera_title, DataCategory.SENSORS),
    "microphone" to TopicInfo(R.string.topic_microphone_title, DataCategory.SENSORS),
)

/** Name of a topic id, or null for an id this build doesn't know, in which case the raw path is shown. */
@StringRes
fun topicTitle(topic: String): Int? = TOPICS[topic]?.title

/** Category of a topic id, or null for an id this build doesn't know. */
fun topicCategory(topic: String): DataCategory? = TOPICS[topic]?.category
