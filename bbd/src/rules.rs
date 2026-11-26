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

//! Decides which opens of kernel pseudo-files (/proc, /sys, /dev) are worth
//! recording, and what each one means.
//!
//! Most of what apps read there is routine and says nothing about the user:
//! CPU load, memory statistics, scheduler knobs. Only reads that reveal
//! something about the device's identity, its network, its environment or the
//! user's other apps are kept. Each kept path gets a *topic*: a stable id the
//! app turns into an explanation a non-expert can read ("the device's network
//! hardware address, which uniquely identifies it").
//!
//! An app looking at itself (/proc/self, its own pids) is never recorded.

use crate::{record::Category, topic};

fn under(path: &str, prefix: &str) -> bool {
    path.strip_prefix(prefix)
        .is_some_and(|rest| rest.is_empty() || rest.starts_with('/'))
}

/// True if `path` has the consecutive components `a/b` anywhere in it.
/// Matching on components rather than a fixed prefix matters because sysfs
/// has two names for most things: an open is recorded under the real device
/// path (`/sys/devices/platform/.../net/wlan0/address`), a failed probe under
/// whatever the app typed (`/sys/class/net/wlan0/address`).
fn has_component(path: &str, name: &str) -> bool {
    path.split('/').any(|c| c == name)
}

fn file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

/// Parts of /sys that hold nothing of interest and are dropped wholesale, in
/// the kernel already (`bb_common::filter::sysfs_entry_matters`). Excluding
/// them here as well keeps the two sides in agreement: without this, a path
/// such as `/sys/kernel/.../net/...` would match a rule below but never
/// arrive.
const SYS_EXCLUDED: &[&str] = &[
    "/sys/devices/system",
    "/sys/kernel",
    "/sys/module",
    "/sys/power",
    "/sys/firmware",
];

/// Topic of a path under /sys, if it is privacy-relevant.
fn sys_topic(path: &str) -> Option<&'static str> {
    if SYS_EXCLUDED.iter().any(|prefix| under(path, prefix)) {
        return None;
    }
    if under(path, "/sys/fs") {
        // Only the files that tell how SELinux is set up. The rest of
        // selinuxfs is how a process works with SELinux: zygote opens
        // `context` in every app it starts, after it has taken the app's uid.
        return matches!(
            path,
            "/sys/fs/selinux/enforce" | "/sys/fs/selinux/policy" | "/sys/fs/selinux/status"
        )
        .then_some(topic::SELINUX_STATE);
    }
    let name = file_name(path);
    if has_component(path, "net") {
        // .../net/<interface>/address
        return Some(if name == "address" {
            topic::MAC_ADDRESS
        } else {
            topic::NETWORK_INTERFACES
        });
    }
    if matches!(name, "serial_number" | "serial" | "cid" | "iSerial") {
        return Some(topic::DEVICE_SERIAL);
    }
    if under(path, "/sys/devices/soc0") {
        return Some(topic::SOC_INFO);
    }
    if has_component(path, "power_supply") {
        return Some(topic::BATTERY);
    }
    if has_component(path, "block") {
        return Some(topic::BLOCK_DEVICES);
    }
    if has_component(path, "input") {
        return Some(topic::INPUT_DEVICES);
    }
    None
}

/// Topic of a system-wide path under /proc (not a process directory).
///
/// `/proc/sys/kernel/random/boot_id` has none: the system's own libraries
/// read it in every app process, to name the shared memory device, so a
/// read of it does not tell an app that asks from one that does not.
fn proc_topic(path: &str) -> Option<&'static str> {
    match path {
        "/proc/net/arp" => return Some(topic::ARP_TABLE),
        "/proc/cpuinfo" => return Some(topic::CPU_MODEL),
        "/proc/version" => return Some(topic::KERNEL_VERSION),
        "/proc/mounts" | "/proc/filesystems" => return Some(topic::MOUNTS),
        "/proc/modules" | "/proc/kallsyms" => return Some(topic::KERNEL_MODULES),
        "/proc/partitions" => return Some(topic::BLOCK_DEVICES),
        "/proc/sys/kernel/osrelease" | "/proc/sys/kernel/version" | "/proc/sys/kernel/ostype" => {
            return Some(topic::KERNEL_VERSION);
        }
        _ => {}
    }
    if under(path, "/proc/net") {
        return Some(topic::NETWORK_STATE);
    }
    if under(path, "/proc/bus/input") {
        return Some(topic::INPUT_DEVICES);
    }
    None
}

/// Topic of a path under /dev. Most of /dev is functional device nodes
/// (binder, GPU, null...); only a few entries say anything.
fn dev_topic(path: &str) -> Option<&'static str> {
    if under(path, "/dev/block") {
        return Some(topic::BLOCK_DEVICES);
    }
    if under(path, "/dev/input") {
        return Some(topic::INPUT_DEVICES);
    }
    const EMULATOR_NODES: &[&str] = &[
        "/dev/qemu",
        "/dev/goldfish",
        "/dev/socket/qemud",
        "/dev/socket/genyd",
        "/dev/socket/baseband_genyd",
        "/dev/vboxguest",
        "/dev/vboxuser",
    ];
    // Prefix match: "/dev/qemu" also covers "/dev/qemu_pipe".
    EMULATOR_NODES
        .iter()
        .any(|node| path.starts_with(node))
        .then_some(topic::EMULATOR_PROBE)
}

/// Prefix of a stored attempt to get at another process directly.
const PROCESS_SCHEME: &str = "process:";

/// What to store for a `ProcessEvent` of the given kind.
pub fn process_subject(how: u32) -> Option<&'static str> {
    match how {
        bb_common::PROCESS_TRACE => Some("process:ptrace"),
        bb_common::PROCESS_READ => Some("process:read-memory"),
        bb_common::PROCESS_WRITE => Some("process:write-memory"),
        _ => None,
    }
}

/// What a stored record means, for everything but user files. `None` for
/// storage paths, provider URIs and anything recorded before a topic existed
/// for it.
pub fn topic_of(path: &str) -> Option<&'static str> {
    if path.starts_with(crate::commands::SCHEME) {
        crate::commands::topic_of(path)
    } else if path.starts_with(crate::binder::SCHEME)
        || path.starts_with(crate::binder::SETTING_SCHEME)
    {
        crate::binder::topic_of(path)
    } else if path.starts_with(crate::appops::SCHEME) {
        crate::appops::topic_of(path)
    } else if path.starts_with(crate::net::SCHEME) {
        crate::net::topic_of(path)
    } else if path.starts_with(crate::net::NETLINK_SCHEME) {
        crate::net::netlink_topic_of(path)
    } else if path.starts_with(PROCESS_SCHEME) {
        Some(topic::PROCESS_MEMORY)
    } else if path.starts_with("content://") {
        crate::personal::topic_of(path)
    } else if under(path, "/sys") {
        sys_topic(path)
    } else if under(path, "/dev") {
        dev_topic(path)
    } else if under(path, "/proc/[pid]") {
        Some(topic::OTHER_PROCESSES)
    } else if under(path, "/proc") {
        proc_topic(path)
    } else {
        None
    }
}

/// Classify a normalized absolute path. `caller_uid` is the app doing the
/// open; `uid_of_pid` reports which uid owns a pid (None if it's gone).
///
/// Returns the category and the path to store, or `None` if the access is
/// not worth recording. For other processes' /proc entries the pid is
/// replaced with `[pid]` so that repeated probing aggregates into one row
/// instead of one per pid.
pub fn classify(
    path: &str,
    caller_uid: u32,
    uid_of_pid: impl Fn(u32) -> Option<u32>,
) -> Option<(Category, String)> {
    if under(path, "/sys") {
        return sys_topic(path).map(|_| (Category::Device, path.to_string()));
    }
    if under(path, "/dev") {
        return dev_topic(path).map(|_| (Category::Device, path.to_string()));
    }

    let rest = path.strip_prefix("/proc/")?;
    let (first, tail) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, ""),
    };
    let is_pid = first.bytes().all(|b| b.is_ascii_digit());
    if is_pid || matches!(first, "self" | "thread-self") {
        // /proc/mounts and /proc/net are symlinks to the caller's own
        // self/mounts and self/net, so a read of the mount table or the
        // network state shows up under a process directory. Record it as
        // the system-wide entry it is.
        if under(tail, "/net") {
            let global = format!("/proc{tail}");
            return proc_topic(&global).map(|_| (Category::System, global));
        }
        if matches!(tail, "/mounts" | "/mountinfo") {
            return Some((Category::System, "/proc/mounts".to_string()));
        }
    }
    match first {
        "" | "self" | "thread-self" => None,
        _ if is_pid => {
            // Another process's entry. Other processes of the same app (same
            // uid) count as the app looking at itself. A pid that no longer
            // exists can't be attributed, so it is skipped rather than guessed.
            let owner = uid_of_pid(first.parse().ok()?)?;
            (owner != caller_uid).then(|| (Category::Process, format!("/proc/[pid]{tail}")))
        }
        _ => proc_topic(path).map(|_| (Category::System, path.to_string())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ME: u32 = 10_100;

    /// pid 500 is ours, 600 belongs to another app, 1 to root; others are gone.
    fn owner(pid: u32) -> Option<u32> {
        match pid {
            500 => Some(ME),
            600 => Some(10_200),
            1 => Some(0),
            _ => None,
        }
    }

    fn c(path: &str) -> Option<(Category, String)> {
        classify(path, ME, owner)
    }

    #[test]
    fn identifiers_are_recorded_under_either_sysfs_name() {
        for (path, want) in [
            ("/sys/class/net/wlan0/address", topic::MAC_ADDRESS),
            (
                "/sys/devices/platform/soc/1c00000.pcie/pci0000:00/0000:00:00.0/0000:01:00.0/net/wlan0/address",
                topic::MAC_ADDRESS,
            ),
            ("/sys/class/net", topic::NETWORK_INTERFACES),
            ("/sys/class/net/tun0/operstate", topic::NETWORK_INTERFACES),
            ("/sys/devices/soc0/serial_number", topic::DEVICE_SERIAL),
            ("/sys/devices/soc0/soc_id", topic::SOC_INFO),
            ("/sys/block/mmcblk0/device/cid", topic::DEVICE_SERIAL),
            ("/sys/block/sda/size", topic::BLOCK_DEVICES),
            ("/sys/class/power_supply/battery/capacity", topic::BATTERY),
            (
                "/sys/devices/platform/soc/soc:battery/power_supply/battery/health",
                topic::BATTERY,
            ),
            ("/sys/fs/selinux/enforce", topic::SELINUX_STATE),
            ("/sys/fs/selinux/policy", topic::SELINUX_STATE),
            ("/sys/fs/selinux/status", topic::SELINUX_STATE),
            ("/sys/class/input/event2/device/name", topic::INPUT_DEVICES),
        ] {
            assert_eq!(
                c(path),
                Some((Category::Device, path.to_string())),
                "{path}"
            );
            assert_eq!(topic_of(path), Some(want), "{path}");
        }
    }

    #[test]
    fn routine_system_reads_are_not_recorded() {
        for path in [
            // CPU, memory and scheduler state: performance tuning, not privacy.
            "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq",
            "/sys/devices/system/cpu/cpu3/cpuidle/state0/time",
            "/sys/devices/system/cpu/online",
            "/sys/kernel/mm/transparent_hugepage/enabled",
            "/sys/kernel/tracing/trace_marker",
            "/sys/fs/cgroup/uid_0/cgroup.procs",
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys",
            "/proc/meminfo",
            "/proc/stat",
            "/proc/loadavg",
            "/proc/uptime",
            "/proc/sys/vm/overcommit_memory",
            "/dev/null",
            "/dev/binder",
            "/dev/alarm",
            "/dev/kgsl-3d0",
        ] {
            assert_eq!(c(path), None, "{path}");
            assert_eq!(topic_of(path), None, "{path}");
        }
    }

    #[test]
    fn what_the_system_reads_in_every_starting_app_is_not_recorded() {
        for path in [
            "/sys/fs/selinux/context",
            "/sys/fs/selinux/policy_capabilities/memfd_class",
            "/sys/fs/selinux",
            "/proc/sys/kernel/random/boot_id",
        ] {
            assert_eq!(c(path), None, "{path}");
            assert_eq!(topic_of(path), None, "{path}");
        }
    }

    #[test]
    fn system_wide_proc_entries_are_recorded_with_a_topic() {
        for (path, want) in [
            ("/proc/net/arp", topic::ARP_TABLE),
            ("/proc/net/route", topic::NETWORK_STATE),
            ("/proc/net/unix", topic::NETWORK_STATE),
            ("/proc/cpuinfo", topic::CPU_MODEL),
            ("/proc/version", topic::KERNEL_VERSION),
            ("/proc/mounts", topic::MOUNTS),
            ("/proc/modules", topic::KERNEL_MODULES),
            ("/proc/partitions", topic::BLOCK_DEVICES),
            ("/proc/bus/input/devices", topic::INPUT_DEVICES),
        ] {
            assert_eq!(
                c(path),
                Some((Category::System, path.to_string())),
                "{path}"
            );
            assert_eq!(topic_of(path), Some(want), "{path}");
        }
    }

    #[test]
    fn mounts_and_net_are_recorded_through_the_process_directory() {
        // What the kernel reports for an open of /proc/mounts and /proc/net/arp.
        assert_eq!(
            c("/proc/500/mounts"),
            Some((Category::System, "/proc/mounts".into()))
        );
        assert_eq!(
            c("/proc/500/mountinfo"),
            Some((Category::System, "/proc/mounts".into()))
        );
        assert_eq!(
            c("/proc/500/net/arp"),
            Some((Category::System, "/proc/net/arp".into()))
        );
        assert_eq!(
            c("/proc/self/net/route"),
            Some((Category::System, "/proc/net/route".into()))
        );
        assert_eq!(
            c("/proc/self/mounts"),
            Some((Category::System, "/proc/mounts".into()))
        );
        assert_eq!(
            c("/proc/thread-self/net/route"),
            Some((Category::System, "/proc/net/route".into()))
        );
        for path in ["/proc/thread-self/mounts", "/proc/thread-self/mountinfo"] {
            assert_eq!(c(path), Some((Category::System, "/proc/mounts".into())));
        }
        assert_eq!(topic_of("/proc/mounts"), Some(topic::MOUNTS));

        use bb_common::filter::own_proc_entry_matters;
        for (below_pid, kept) in [
            ("net", true),
            ("mounts", true),
            ("mountinfo", true),
            ("maps", false),
            ("status", false),
        ] {
            let (component, _) = head_components(&format!("/x/{below_pid}"));
            assert_eq!(own_proc_entry_matters(&component), kept, "{below_pid}");
        }
    }

    #[test]
    fn excluded_parts_of_sysfs_match_no_rule() {
        // These contain a component a rule would otherwise match.
        for path in [
            "/sys/kernel/tracing/events/net/netif_rx/enable",
            "/sys/module/block/parameters/events_dfl_poll_msecs",
            "/sys/devices/system/cpu/cpu0/power_supply",
            "/sys/fs/cgroup/net",
            "/sys/firmware/devicetree/base/serial",
        ] {
            assert_eq!(c(path), None, "{path}");
        }
    }

    #[test]
    fn self_inspection_is_ignored() {
        for p in [
            "/proc/self/maps",
            "/proc/thread-self/stat",
            "/proc/500/status",
            "/proc/500/task/501/stat",
            "/proc/self",
            "/proc/",
        ] {
            assert_eq!(c(p), None, "{p}");
        }
    }

    #[test]
    fn other_processes_are_flagged_with_pid_masked() {
        assert_eq!(
            c("/proc/600/cmdline"),
            Some((Category::Process, "/proc/[pid]/cmdline".into()))
        );
        assert_eq!(
            c("/proc/1/status"),
            Some((Category::Process, "/proc/[pid]/status".into()))
        );
        assert_eq!(
            c("/proc/600"),
            Some((Category::Process, "/proc/[pid]".into()))
        );
        assert_eq!(
            topic_of("/proc/[pid]/cmdline"),
            Some(topic::OTHER_PROCESSES)
        );
        assert_eq!(topic_of("/proc/[pid]"), Some(topic::OTHER_PROCESSES));
        // Exited process: can't tell whose it was.
        assert_eq!(c("/proc/999/cmdline"), None);
    }

    #[test]
    fn informative_device_nodes_are_recorded_with_a_topic() {
        for (path, want) in [
            ("/dev/block/sda", topic::BLOCK_DEVICES),
            ("/dev/input/event0", topic::INPUT_DEVICES),
            ("/dev/qemu_pipe", topic::EMULATOR_PROBE),
            ("/dev/socket/qemud", topic::EMULATOR_PROBE),
        ] {
            assert_eq!(
                c(path),
                Some((Category::Device, path.to_string())),
                "{path}"
            );
            assert_eq!(topic_of(path), Some(want), "{path}");
        }
    }

    /// The first two components below the pseudo-filesystem's root, as the
    /// eBPF side sees them: truncated and NUL padded.
    fn head_components(path: &str) -> ([u8; 8], [u8; 8]) {
        use bb_common::filter::COMPONENT_CHARS;
        let mut parts = path.split('/').filter(|c| !c.is_empty()).skip(1);
        let pack = |part: Option<&str>| {
            let mut out = [0u8; 8];
            let bytes = part.unwrap_or("").as_bytes();
            let len = bytes.len().min(COMPONENT_CHARS);
            out[..len].copy_from_slice(&bytes[..len]);
            out
        };
        (pack(parts.next()), pack(parts.next()))
    }

    /// The kernel-side filter must never drop a path these rules record.
    #[test]
    fn kernel_filter_passes_everything_recorded() {
        use bb_common::filter::{proc_entry_matters, sysfs_entry_matters};
        for path in [
            "/sys/class/net/wlan0/address",
            "/sys/devices/platform/soc/1c00000.pcie/pci0000:00/0000:00:00.0/0000:01:00.0/net/wlan0/address",
            "/sys/devices/virtual/net/tun0/operstate",
            "/sys/class/net",
            "/sys/devices/soc0/serial_number",
            "/sys/devices/soc0/soc_id",
            "/sys/block/mmcblk0/device/cid",
            "/sys/block/sda/size",
            "/sys/class/power_supply/battery/capacity",
            "/sys/devices/platform/soc/soc:battery/power_supply/battery/health",
            "/sys/fs/selinux/enforce",
            "/sys/class/input/event2/device/name",
        ] {
            assert!(c(path).is_some(), "{path} should be recorded");
            let (top, second) = head_components(path);
            assert!(
                sysfs_entry_matters(&top, &second),
                "{path} dropped in kernel"
            );
        }
        for path in [
            "/proc/net/arp",
            "/proc/net/route",
            "/proc/cpuinfo",
            "/proc/version",
            "/proc/sys/kernel/osrelease",
            "/proc/mounts",
            "/proc/filesystems",
            "/proc/modules",
            "/proc/kallsyms",
            "/proc/partitions",
            "/proc/bus/input/devices",
        ] {
            assert!(c(path).is_some(), "{path} should be recorded");
            let (top, _) = head_components(path);
            assert!(proc_entry_matters(&top), "{path} dropped in kernel");
        }
        // And it does drop the common noise.
        for path in [
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
            "/sys/kernel/mm/transparent_hugepage/enabled",
            "/sys/fs/cgroup/cgroup.procs",
            "/sys/module/lowmemorykiller/parameters/minfree",
            "/sys/firmware/devicetree/base/model",
        ] {
            let (top, second) = head_components(path);
            assert!(
                !sysfs_entry_matters(&top, &second),
                "{path} not dropped in kernel"
            );
        }
        for path in [
            "/proc/meminfo",
            "/proc/stat",
            "/proc/loadavg",
            "/proc/uptime",
            "/proc/vmstat",
        ] {
            let (top, _) = head_components(path);
            assert!(!proc_entry_matters(&top), "{path} not dropped in kernel");
        }
    }

    #[test]
    fn direct_access_to_another_process_is_about_its_memory() {
        for how in [
            bb_common::PROCESS_TRACE,
            bb_common::PROCESS_READ,
            bb_common::PROCESS_WRITE,
        ] {
            let subject = process_subject(how).unwrap();
            assert_eq!(topic_of(subject), Some(topic::PROCESS_MEMORY), "{subject}");
        }
        assert_eq!(process_subject(9), None);
    }

    #[test]
    fn paths_elsewhere_have_no_topic() {
        for p in [
            "/data/app/x/base.apk",
            "/storage/emulated/0/DCIM/a.jpg",
            "content://media/external/images/media",
            "/procfoo",
            "/",
        ] {
            assert_eq!(c(p), None, "{p}");
            assert_eq!(topic_of(p), None, "{p}");
        }
    }
}
