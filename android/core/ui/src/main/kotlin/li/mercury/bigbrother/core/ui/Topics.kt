package li.mercury.bigbrother.core.ui

import androidx.annotation.StringRes

/**
 * Name of a topic id reported by the daemon (`bbd/src/rules.rs`), or null for
 * an id this build doesn't know, in which case the raw path is shown.
 */
@StringRes
fun topicTitle(topic: String): Int? = when (topic) {
    "mac_address" -> R.string.topic_mac_address_title
    "network_interfaces" -> R.string.topic_network_interfaces_title
    "arp_table" -> R.string.topic_arp_table_title
    "network_state" -> R.string.topic_network_state_title
    "device_serial" -> R.string.topic_device_serial_title
    "soc_info" -> R.string.topic_soc_info_title
    "boot_id" -> R.string.topic_boot_id_title
    "cpu_model" -> R.string.topic_cpu_model_title
    "kernel_version" -> R.string.topic_kernel_version_title
    "other_processes" -> R.string.topic_other_processes_title
    "mounts" -> R.string.topic_mounts_title
    "kernel_modules" -> R.string.topic_kernel_modules_title
    "selinux_state" -> R.string.topic_selinux_state_title
    "block_devices" -> R.string.topic_block_devices_title
    "emulator_probe" -> R.string.topic_emulator_probe_title
    "input_devices" -> R.string.topic_input_devices_title
    "battery" -> R.string.topic_battery_title
    "root_check" -> R.string.topic_root_check_title
    "system_properties" -> R.string.topic_system_properties_title
    "installed_apps" -> R.string.topic_installed_apps_title
    "system_settings" -> R.string.topic_system_settings_title
    "shell_command" -> R.string.topic_shell_command_title
    "local_ports" -> R.string.topic_local_ports_title
    "local_network" -> R.string.topic_local_network_title
    "device_discovery" -> R.string.topic_device_discovery_title
    "process_memory" -> R.string.topic_process_memory_title
    "contacts" -> R.string.topic_contacts_title
    "call_log" -> R.string.topic_call_log_title
    "messages" -> R.string.topic_messages_title
    "calendar" -> R.string.topic_calendar_title
    "sim_info" -> R.string.topic_sim_info_title
    "clipboard" -> R.string.topic_clipboard_title
    "location" -> R.string.topic_location_title
    "wifi_info" -> R.string.topic_wifi_info_title
    "phone_identity" -> R.string.topic_phone_identity_title
    "accounts" -> R.string.topic_accounts_title
    "running_apps" -> R.string.topic_running_apps_title
    "usage_stats" -> R.string.topic_usage_stats_title
    "enabled_services" -> R.string.topic_enabled_services_title
    "sensors" -> R.string.topic_sensors_title
    "android_id" -> R.string.topic_android_id_title
    "device_name" -> R.string.topic_device_name_title
    "camera" -> R.string.topic_camera_title
    "microphone" -> R.string.topic_microphone_title
    else -> null
}
