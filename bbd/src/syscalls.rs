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

use std::{
    collections::HashSet,
    fs,
    io::{self, BufRead, BufReader},
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
}

/// The file probes. Unlike the others these are required.
pub const FILES: Feature = Feature {
    name: "files",
    table: TABLE,
    with_return: true,
    offsets: &[],
};

pub const FEATURES: &[Feature] = &[
    Feature {
        name: "exec",
        table: EXEC_TABLE,
        with_return: true,
        offsets: &[],
    },
    Feature {
        name: "network",
        table: NETWORK_TABLE,
        with_return: false,
        offsets: &[],
    },
    Feature {
        name: "process",
        table: PROCESS_TABLE,
        with_return: true,
        offsets: &[],
    },
    Feature {
        name: "netlink",
        table: NETLINK_TABLE,
        with_return: true,
        offsets: &[bb_common::OFF_SOCK_PROTOCOL, bb_common::OFF_SKB_DATA],
    },
];

/// Not a syscall but the security hook every netlink message passes, which
/// has the same name on every architecture.
const NETLINK_TABLE: Table = &[("enter_netlink", &["security_netlink_send"])];

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
    std::iter::once(&FILES)
        .chain(FEATURES)
        .flat_map(|feature| feature.table.iter())
        .flat_map(|(_, functions)| functions.iter().copied())
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

/// (entry program, function) pairs to attach, limited to functions that exist.
pub fn attach_plan(
    table: Table,
    symbols: &HashSet<&'static str>,
) -> Vec<(&'static str, &'static str)> {
    table
        .iter()
        .flat_map(|(program, functions)| functions.iter().map(move |f| (*program, *f)))
        .filter(|(_, function)| symbols.contains(function))
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
        let plan = attach_plan(FILES.table, &symbols);
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
        for (program, functions) in TABLE {
            assert!(program.starts_with("enter_"), "{program}");
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
            .map(|f| (f.name, attach_plan(f.table, &symbols).len()))
            .collect();
        assert_eq!(
            plans,
            [("exec", 0), ("network", 1), ("process", 0), ("netlink", 0)]
        );
    }
}
