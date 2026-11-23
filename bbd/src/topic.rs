// Copyright (C) 2026 Mercury Li
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License version 3 as
// published by the Free Software Foundation.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

//! Topic ids. The Android app has a title and an explanation for each
//! (`core/ui/.../Topics.kt`); keep the two lists in sync.

/// Hardware (MAC) address of a network interface.
pub const MAC_ADDRESS: &str = "mac_address";
/// Which network interfaces exist and their state (reveals VPN use).
pub const NETWORK_INTERFACES: &str = "network_interfaces";
/// The ARP table: other devices on the local network, incl. the router.
pub const ARP_TABLE: &str = "arp_table";
/// Routing, sockets and other network state under /proc/net.
pub const NETWORK_STATE: &str = "network_state";
/// Serial numbers of the chip or storage.
pub const DEVICE_SERIAL: &str = "device_serial";
/// Chip model and revision.
pub const SOC_INFO: &str = "soc_info";
/// Processor model and features.
pub const CPU_MODEL: &str = "cpu_model";
/// Kernel version and build.
pub const KERNEL_VERSION: &str = "kernel_version";
/// Another app's or the system's processes.
pub const OTHER_PROCESSES: &str = "other_processes";
/// Mounted file systems (used to detect root and tampering).
pub const MOUNTS: &str = "mounts";
/// Loaded kernel modules and symbols (used to detect root).
pub const KERNEL_MODULES: &str = "kernel_modules";
/// SELinux state.
pub const SELINUX_STATE: &str = "selinux_state";
/// Raw storage devices and partitions.
pub const BLOCK_DEVICES: &str = "block_devices";
/// Device nodes that only exist on emulators and virtual machines.
pub const EMULATOR_PROBE: &str = "emulator_probe";
/// Input devices (touch screen, keys).
pub const INPUT_DEVICES: &str = "input_devices";
/// Battery level, health and charging state.
pub const BATTERY: &str = "battery";

// Commands (`commands.rs`).
/// Looked for the tools and apps that only a rooted device has.
pub const ROOT_CHECK: &str = "root_check";
/// System properties: model, build, serial number and many more.
pub const SYSTEM_PROPERTIES: &str = "system_properties";
/// Which apps are installed.
pub const INSTALLED_APPS: &str = "installed_apps";
/// Asked about apps that are not installed: going down a list of apps
/// to find out which of them the device has (`binder.rs`).
pub const APP_PROBE: &str = "app_probe";
/// System settings, among them the device's Android ID.
pub const SYSTEM_SETTINGS: &str = "system_settings";
/// Any other system program.
pub const SHELL_COMMAND: &str = "shell_command";

/// Tracing another app's process or reading its memory.
pub const PROCESS_MEMORY: &str = "process_memory";

// Personal data behind system content providers (`personal.rs`).
pub const CONTACTS: &str = "contacts";
pub const CALL_LOG: &str = "call_log";
/// SMS and MMS.
pub const MESSAGES: &str = "messages";
pub const CALENDAR: &str = "calendar";
/// SIM cards, subscriptions and carrier settings.
pub const SIM_INFO: &str = "sim_info";

// Calls to system services (`binder.rs`).
pub const CLIPBOARD: &str = "clipboard";
/// GPS, and the cell towers in range.
pub const LOCATION: &str = "location";
/// Wi-Fi networks in range, the one connected to, the saved ones.
pub const WIFI_INFO: &str = "wifi_info";
/// IMEI, subscriber id, SIM serial number, phone number.
pub const PHONE_IDENTITY: &str = "phone_identity";
/// Accounts signed in on the device.
pub const ACCOUNTS: &str = "accounts";
/// Apps and tasks currently running.
pub const RUNNING_APPS: &str = "running_apps";
/// Which apps were used and when.
pub const USAGE_STATS: &str = "usage_stats";
/// Enabled keyboards and accessibility services.
pub const ENABLED_SERVICES: &str = "enabled_services";
/// Motion, position and body sensors.
pub const SENSORS: &str = "sensors";
/// The identifier Android gives each app for this device.
pub const ANDROID_ID: &str = "android_id";
/// The name the user gave the device.
pub const DEVICE_NAME: &str = "device_name";

// The system's permission usage records (`appops.rs`).
pub const CAMERA: &str = "camera";
pub const MICROPHONE: &str = "microphone";

// Network destinations and netlink requests (`net.rs`).
/// Ports on the device itself: other apps and services running on it.
pub const LOCAL_PORTS: &str = "local_ports";
/// Other devices on the local network.
pub const LOCAL_NETWORK: &str = "local_network";
/// Multicast and broadcast, used to discover devices on the network.
pub const DEVICE_DISCOVERY: &str = "device_discovery";
