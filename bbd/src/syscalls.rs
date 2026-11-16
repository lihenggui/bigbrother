//! Which kernel functions the syscall probes attach to.
//!
//! Each syscall has an entry function named `<arch prefix><name>`. The eBPF
//! side has one entry program per argument layout; this table says which
//! syscalls use which layout. Not every kernel has every function (32-bit
//! support is optional), so the table is filtered through /proc/kallsyms.

use std::{collections::HashSet, fs};

/// (eBPF entry program, syscall entry functions it handles).
type Table = &'static [(&'static str, &'static [&'static str])];

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

/// The function every Android kernel must have; without it nothing works.
#[cfg(target_arch = "aarch64")]
pub const REQUIRED: &str = "__arm64_sys_openat";
#[cfg(target_arch = "x86_64")]
pub const REQUIRED: &str = "__x64_sys_openat";

pub const ENTRY_PROGRAMS: [&str; 6] = [
    "enter_open",
    "enter_openat",
    "enter_openat2",
    "enter_probe",
    "enter_probe_at",
    "enter_watch",
];

/// Names of all function symbols in the running kernel.
pub fn kernel_symbols() -> anyhow::Result<HashSet<String>> {
    Ok(parse_kallsyms(&fs::read_to_string("/proc/kallsyms")?))
}

fn parse_kallsyms(text: &str) -> HashSet<String> {
    // "<address> <type> <name> [module]"
    text.lines()
        .filter_map(|line| line.split_ascii_whitespace().nth(2))
        .map(str::to_string)
        .collect()
}

/// (entry program, function) pairs to attach, limited to functions that exist.
pub fn attach_plan(symbols: &HashSet<String>) -> Vec<(&'static str, &'static str)> {
    TABLE
        .iter()
        .flat_map(|(program, functions)| functions.iter().map(move |f| (*program, *f)))
        .filter(|(_, function)| symbols.contains(*function))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plan_skips_missing_functions() {
        let symbols = parse_kallsyms(&format!(
            "0000000000000000 T {REQUIRED}\n\
             0000000000000000 t some_static_fn\n\
             0000000000000000 T {} [some_module]\n",
            TABLE[2].1[0]
        ));
        let plan = attach_plan(&symbols);
        assert_eq!(
            plan,
            [("enter_openat", REQUIRED), ("enter_openat2", TABLE[2].1[0])]
        );
    }

    #[test]
    fn every_table_entry_names_a_real_program() {
        for (program, functions) in TABLE {
            assert!(ENTRY_PROGRAMS.contains(program), "{program}");
            assert!(!functions.is_empty());
        }
        assert!(TABLE.iter().any(|(_, fs)| fs.contains(&REQUIRED)));
    }
}
