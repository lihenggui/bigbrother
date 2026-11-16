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
    else -> null
}
