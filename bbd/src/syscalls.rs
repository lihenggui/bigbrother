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

//! Which kernel functions the syscall probes attach to.
//!
//! Each syscall has an entry function named `<arch prefix><name>`. The eBPF
//! side has one entry program per argument layout; these tables say which
//! syscalls use which layout. Not every kernel has every function (32-bit
//! support is optional), so they are filtered through /proc/kallsyms.
//!
//! Probes are grouped into [`Feature`]s. The file probes ([`FILES`]) are
//! required: the daemon does not start without them. Each of the others is
//! attached if it can be and reported as missing if not.
//!
//! Two probes are on security hooks rather than syscalls. A `security_*`
//! function is a small wrapper that link-time optimization can inline into
//! its only caller: on the android13-5.15 kernels it is, and a probe on what
//! is left of it attaches but never fires. SELinux's own hook functions are
//! called through the table of security modules, never inlined, and GKI
//! kernels build SELinux in, so the probes go there. The wrapper is the
//! fallback for a kernel without them, or where SELinux is not running
//! (`selinux=0`, another `lsm=` list): its functions are then never called.

use std::{
    collections::HashSet,
    fs,
    io::{self, BufRead, BufReader},
    path::Path,
};

/// (eBPF entry program, syscall entry functions it handles).
pub type Table = &'static [(&'static str, &'static [&'static str])];

/// A kind of activity recorded by probes that the daemon can run without.
pub struct Feature {
    /// Stable id, reported by `bbd stats` as `coverage_<name>`.
    pub name: &'static str,
    pub table: Table,
    /// Whether the shared return program is attached as well, to report the
    /// call's outcome.
    pub with_return: bool,
    /// Kernel struct offsets (`bb_common::OFF_*`) its programs read.
    pub offsets: &'static [usize],
    /// The functions of each entry are the same call reached at different
    /// places, best first: only the first one the kernel has is attached,
    /// as attaching more would report each call more than once.
    pub alternatives: bool,
}

/// The file probes. Unlike the others these are required.
pub const FILES: Feature = Feature {
    name: "files",
    table: TABLE,
    with_return: true,
    offsets: &[],
    alternatives: false,
};

pub const FEATURES: &[Feature] = &[
    Feature {
        name: "exec",
        table: EXEC_TABLE,
        with_return: true,
        offsets: &[],
        alternatives: false,
    },
    Feature {
        name: "network",
        table: NETWORK_TABLE,
        with_return: false,
        offsets: &[],
        alternatives: false,
    },
    Feature {
        name: "process",
        table: PROCESS_TABLE,
        with_return: true,
        offsets: &[],
        alternatives: false,
    },
    Feature {
        name: "netlink",
        table: NETLINK_TABLE,
        with_return: true,
        offsets: &[bb_common::OFF_SOCK_PROTOCOL, bb_common::OFF_SKB_DATA],
        alternatives: true,
    },
];

/// The sandbox's gate (see `sandbox::gate`): not a recorder, and attached
/// apart from the others, once the sandbox is ready to answer it.
pub const GATE: Feature = Feature {
    name: "sandbox",
    table: GATE_TABLE,
    with_return: false,
    offsets: &[],
    alternatives: false,
};

/// `setresuid`, which zygote's child calls to become the app. On arm64,
/// 32-bit processes reach the same function through `setresuid32`; the
/// second is the 16-bit call of the old 32-bit table.
#[cfg(target_arch = "aarch64")]
const GATE_TABLE: Table = &[(
    "gate_setresuid",
    &["__arm64_sys_setresuid", "__arm64_sys_setresuid16"],
)];
#[cfg(target_arch = "x86_64")]
const GATE_TABLE: Table = &[("gate_setresuid", &["__x64_sys_setresuid"])];

/// Not a syscall but the security hook every netlink message passes, which
/// has the same names on every architecture. Alternatives, best first (see
/// the module documentation).
const NETLINK_TABLE: Table = &[(
    "enter_netlink",
    &["selinux_netlink_send", "security_netlink_send"],
)];

/// The hook `file_open` attaches to, best first (see the module
/// documentation).
const FILE_OPEN_HOOKS: &[&str] = &["selinux_file_open", "security_file_open"];

/// The function `file_open` attaches to: the first of [`FILE_OPEN_HOOKS`]
/// the kernel has and calls. When kallsyms lists none of them, the last is
/// attached by name all the same: the probe is required, and the kernel's
/// own lookup of the name may still find it.
pub fn file_open_hook(symbols: &HashSet<&'static str>, selinux: bool) -> &'static str {
    FILE_OPEN_HOOKS
        .iter()
        .copied()
        .find(|f| usable(f, symbols, selinux))
        .unwrap_or(FILE_OPEN_HOOKS[FILE_OPEN_HOOKS.len() - 1])
}

/// Whether SELinux is running, and so its hook functions are called. Its
/// filesystem is mounted only then.
pub fn selinux_running() -> bool {
    Path::new("/sys/fs/selinux/enforce").exists()
}

/// Whether a probe on `function` can fire: the kernel has it and, for one of
/// SELinux's hook functions, SELinux is running.
fn usable(function: &str, symbols: &HashSet<&'static str>, selinux: bool) -> bool {
    symbols.contains(function) && (selinux || !function.starts_with("selinux_"))
}

/// arm64. 32-bit processes use a separate syscall table whose entries are
/// either shared with the 64-bit one (listed once here), 32-bit-only
/// (`__arm64_sys_access`...), or have a compat variant (`__arm64_compat_sys_*`).
#[cfg(target_arch = "aarch64")]
const TABLE: Table = &[
    ("enter_open", &["__arm64_compat_sys_open"]),
    (
        "enter_openat",
        &["__arm64_sys_openat", "__arm64_compat_sys_openat"],
    ),
    ("enter_openat2", &["__arm64_sys_openat2"]),
    (
        "enter_probe",
        &[
            "__arm64_sys_access",
            "__arm64_sys_stat64",
            "__arm64_sys_lstat64",
            "__arm64_sys_readlink",
        ],
    ),
    (
        "enter_probe_at",
        &[
            "__arm64_sys_newfstatat",
            "__arm64_sys_fstatat64",
            "__arm64_sys_statx",
            "__arm64_sys_faccessat",
            "__arm64_sys_faccessat2",
            "__arm64_sys_readlinkat",
        ],
    ),
    ("enter_watch", &["__arm64_sys_inotify_add_watch"]),
];

#[cfg(target_arch = "aarch64")]
const EXEC_TABLE: Table = &[
    (
        "enter_execve",
        &["__arm64_sys_execve", "__arm64_compat_sys_execve"],
    ),
    (
        "enter_execveat",
        &["__arm64_sys_execveat", "__arm64_compat_sys_execveat"],
    ),
];

/// 32-bit processes use the same functions for connect and sendto.
#[cfg(target_arch = "aarch64")]
const NETWORK_TABLE: Table = &[
    ("enter_connect", &["__arm64_sys_connect"]),
    ("enter_sendto", &["__arm64_sys_sendto"]),
    (
        "enter_sendmsg",
        &[
            "__arm64_sys_sendmsg",
            "__arm64_compat_sys_sendmsg",
            "__arm64_sys_sendmmsg",
            "__arm64_compat_sys_sendmmsg",
        ],
    ),
];

#[cfg(target_arch = "aarch64")]
const PROCESS_TABLE: Table = &[
    (
        "enter_ptrace",
        &["__arm64_sys_ptrace", "__arm64_compat_sys_ptrace"],
    ),
    (
        "enter_vm_read",
        &[
            "__arm64_sys_process_vm_readv",
            "__arm64_compat_sys_process_vm_readv",
        ],
    ),
    (
        "enter_vm_write",
        &[
            "__arm64_sys_process_vm_writev",
            "__arm64_compat_sys_process_vm_writev",
        ],
    ),
];

/// x86_64 (emulators, host testing).
#[cfg(target_arch = "x86_64")]
const TABLE: Table = &[
    ("enter_open", &["__x64_sys_open"]),
    ("enter_openat", &["__x64_sys_openat"]),
    ("enter_openat2", &["__x64_sys_openat2"]),
    (
        "enter_probe",
        &[
            "__x64_sys_access",
            "__x64_sys_newstat",
            "__x64_sys_newlstat",
            "__x64_sys_readlink",
        ],
    ),
    (
        "enter_probe_at",
        &[
            "__x64_sys_newfstatat",
            "__x64_sys_statx",
            "__x64_sys_faccessat",
            "__x64_sys_faccessat2",
            "__x64_sys_readlinkat",
        ],
    ),
    ("enter_watch", &["__x64_sys_inotify_add_watch"]),
];

#[cfg(target_arch = "x86_64")]
const EXEC_TABLE: Table = &[
    ("enter_execve", &["__x64_sys_execve"]),
    ("enter_execveat", &["__x64_sys_execveat"]),
];

#[cfg(target_arch = "x86_64")]
const NETWORK_TABLE: Table = &[
    ("enter_connect", &["__x64_sys_connect"]),
    ("enter_sendto", &["__x64_sys_sendto"]),
    (
        "enter_sendmsg",
        &["__x64_sys_sendmsg", "__x64_sys_sendmmsg"],
    ),
];

#[cfg(target_arch = "x86_64")]
const PROCESS_TABLE: Table = &[
    ("enter_ptrace", &["__x64_sys_ptrace"]),
    ("enter_vm_read", &["__x64_sys_process_vm_readv"]),
    ("enter_vm_write", &["__x64_sys_process_vm_writev"]),
];

/// The function every Android kernel must have; without it nothing works.
#[cfg(target_arch = "aarch64")]
pub const REQUIRED: &str = "__arm64_sys_openat";
#[cfg(target_arch = "x86_64")]
pub const REQUIRED: &str = "__x64_sys_openat";

/// The functions named in the tables that the running kernel has.
///
/// /proc/kallsyms lists every symbol of the kernel, a few hundred thousand
/// of them; only the few dozen the probes attach to are kept.
pub fn kernel_symbols() -> anyhow::Result<HashSet<&'static str>> {
    let file = fs::File::open("/proc/kallsyms")?;
    Ok(parse_kallsyms(BufReader::with_capacity(64 * 1024, file))?)
}

/// Every function named in a table.
fn wanted_symbols() -> HashSet<&'static str> {
    [&FILES, &GATE]
        .into_iter()
        .chain(FEATURES)
        .flat_map(|feature| feature.table.iter())
        .flat_map(|(_, functions)| functions.iter().copied())
        .chain(FILE_OPEN_HOOKS.iter().copied())
        .collect()
}

fn parse_kallsyms(mut text: impl BufRead) -> io::Result<HashSet<&'static str>> {
    let wanted = wanted_symbols();
    let mut found = HashSet::new();
    let mut line = String::new();
    while text.read_line(&mut line)? > 0 {
        // "<address> <type> <name> [module]"
        if let Some(name) = line.split_ascii_whitespace().nth(2)
            && let Some(known) = wanted.get(name)
        {
            found.insert(*known);
        }
        line.clear();
    }
    Ok(found)
}

/// (entry program, function) pairs to attach, limited to functions that
/// exist and are called (`selinux` tells whether SELinux's are), and to the
/// first of them for a feature of alternatives.
pub fn attach_plan(
    feature: &Feature,
    symbols: &HashSet<&'static str>,
    selinux: bool,
) -> Vec<(&'static str, &'static str)> {
    let take = if feature.alternatives { 1 } else { usize::MAX };
    feature
        .table
        .iter()
        .flat_map(|(program, functions)| {
            functions
                .iter()
                .filter(|f| usable(f, symbols, selinux))
                .take(take)
                .map(move |f| (*program, *f))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plan_skips_missing_functions() {
        let symbols = parse_kallsyms(
            format!(
                "0000000000000000 T {REQUIRED}\n\
                 0000000000000000 t some_static_fn\n\
                 0000000000000000 T {} [some_module]\n",
                TABLE[2].1[0]
            )
            .as_bytes(),
        )
        .unwrap();
        let plan = attach_plan(&FILES, &symbols, true);
        assert_eq!(
            plan,
            [("enter_openat", REQUIRED), ("enter_openat2", TABLE[2].1[0])]
        );
    }

    #[test]
    fn only_the_functions_probes_attach_to_are_kept() {
        let symbols = parse_kallsyms(
            format!(
                "0000000000000000 T {REQUIRED}\n\
                 0000000000000000 t some_static_fn\n\
                 0000000000000000 T security_netlink_send\n\
                 0000000000000000 d some_variable\t[some_module]\n"
            )
            .as_bytes(),
        )
        .unwrap();
        assert_eq!(symbols, HashSet::from([REQUIRED, "security_netlink_send"]));
    }

    #[test]
    fn every_table_entry_names_a_real_program() {
        for (program, functions) in TABLE.iter().chain(GATE.table) {
            assert!(
                program.starts_with("enter_") || *program == "gate_setresuid",
                "{program}"
            );
            assert!(!functions.is_empty());
        }
        assert!(TABLE.iter().any(|(_, fs)| fs.contains(&REQUIRED)));
    }

    #[test]
    fn features_attach_what_the_kernel_has() {
        let symbols =
            parse_kallsyms(format!("0 T {}\n", NETWORK_TABLE[0].1[0]).as_bytes()).unwrap();
        let plans: Vec<_> = FEATURES
            .iter()
            .map(|f| (f.name, attach_plan(f, &symbols, true).len()))
            .collect();
        assert_eq!(
            plans,
            [("exec", 0), ("network", 1), ("process", 0), ("netlink", 0)]
        );
    }

    fn netlink() -> &'static Feature {
        FEATURES.iter().find(|f| f.name == "netlink").unwrap()
    }

    #[test]
    fn security_hooks_attach_to_selinux_before_the_wrapper() {
        let both = parse_kallsyms(
            "0 t security_file_open\n\
             0 t selinux_file_open\n\
             0 t security_netlink_send\n\
             0 t selinux_netlink_send\n"
                .as_bytes(),
        )
        .unwrap();
        assert_eq!(file_open_hook(&both, true), "selinux_file_open");
        // One function only: each would report every request.
        assert_eq!(
            attach_plan(netlink(), &both, true),
            [("enter_netlink", "selinux_netlink_send")]
        );
    }

    #[test]
    fn security_hooks_use_the_wrapper_when_selinux_is_not_running() {
        let both = parse_kallsyms(
            "0 t security_file_open\n\
             0 t selinux_file_open\n\
             0 t security_netlink_send\n\
             0 t selinux_netlink_send\n"
                .as_bytes(),
        )
        .unwrap();
        assert_eq!(file_open_hook(&both, false), "security_file_open");
        assert_eq!(
            attach_plan(netlink(), &both, false),
            [("enter_netlink", "security_netlink_send")]
        );
    }

    #[test]
    fn security_hooks_fall_back_to_the_wrapper() {
        let wrappers = parse_kallsyms(
            "0 T security_file_open\n\
             0 T security_netlink_send\n"
                .as_bytes(),
        )
        .unwrap();
        assert_eq!(file_open_hook(&wrappers, true), "security_file_open");
        assert_eq!(
            attach_plan(netlink(), &wrappers, true),
            [("enter_netlink", "security_netlink_send")]
        );
        // The file probe is required, so it is tried even when kallsyms
        // lists neither.
        assert_eq!(file_open_hook(&HashSet::new(), true), "security_file_open");
    }
}
