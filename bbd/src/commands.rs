//! Which programs started by apps are recorded, and what running one reveals.
//!
//! An app can start any program the system ships. Some of those answer the
//! questions the app is not supposed to ask directly: `getprop` prints the
//! serial number, `pm list packages` the installed apps, `ip neigh` the
//! devices on the network, and merely trying to run `su` tells whether the
//! device is rooted. An app running its own code is not recorded.
//!
//! A recorded command is stored as `exec:<name> <arguments>`, the name
//! without its directory: an app looking for `su` tries a dozen directories,
//! and that is one finding, not twelve.

use crate::store::Mode;
use std::collections::HashMap;

use crate::rules::topic;

/// Prefix of a stored command.
pub const SCHEME: &str = "exec:";

/// Longest command line stored, in characters.
const SUBJECT_CHARS: usize = 200;

/// Where apps' own code and data live. A program started from here is part
/// of the app.
const APP_DIRECTORIES: &[&str] = &[
    "/data/app/",
    "/data/data/",
    "/data/user/",
    "/data/user_de/",
    "/mnt/expand/",
];

/// System programs that run under an app's uid without the app asking for
/// anything: those that load its own code, and the crash reporter the C
/// library starts when the app crashes.
const ROUTINE: &[&str] = &[
    "linker",
    "linker64",
    "dex2oat",
    "dex2oat32",
    "dex2oat64",
    "crash_dump32",
    "crash_dump64",
];

/// Programs that only exist on a rooted device. Looking for one is a root
/// check wherever the app looks, its own directories included.
const ROOT_TOOLS: &[&str] = &[
    "su",
    "magisk",
    "magiskhide",
    "magiskpolicy",
    "resetprop",
    "busybox",
    "ksud",
    "apd",
];

const SHELLS: &[&str] = &["sh", "bash", "ash"];
/// Multi-call binaries: `toybox getprop` is `getprop`.
const MULTI_CALL: &[&str] = &["toybox", "toolbox"];

fn base_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

/// What to store for an exec of `path` with the arguments `args` (without
/// `argv[0]`), or `None` if it is not worth recording.
pub fn subject(path: &str, args: &[String]) -> Option<String> {
    let name = base_name(path);
    if name.is_empty() {
        return None;
    }
    if !ROOT_TOOLS.contains(&name) {
        // A relative path is resolved against the app's own directory.
        let own = !path.starts_with('/') || APP_DIRECTORIES.iter().any(|d| path.starts_with(d));
        if own || ROUTINE.contains(&name) {
            return None;
        }
    }
    let line = std::iter::once(name)
        .chain(args.iter().map(String::as_str))
        .collect::<Vec<_>>()
        .join(" ");
    // One line, whatever the arguments contain.
    let line: String = line
        .chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .take(SUBJECT_CHARS)
        .collect();
    Some(format!("{SCHEME}{}", line.trim_end()))
}

/// What a stored command reveals. `None` if `subject` is not a command.
pub fn topic_of(subject: &str) -> Option<&'static str> {
    let line = subject.strip_prefix(SCHEME)?;
    let mut words: Vec<&str> = line.split_ascii_whitespace().collect();
    // `sh -c "getprop ro.serialno"` and `toybox getprop` are `getprop`.
    loop {
        match words.as_slice() {
            [shell, "-c", ..] if SHELLS.contains(shell) => {
                words.drain(..2);
            }
            [multi, _, ..] if MULTI_CALL.contains(multi) => {
                words.remove(0);
            }
            _ => break,
        }
    }
    let Some((&program, args)) = words.split_first() else {
        return Some(topic::SHELL_COMMAND);
    };
    let has = |wanted: &[&str]| args.iter().any(|a| wanted.contains(&base_name(a)));
    Some(match base_name(program) {
        name if ROOT_TOOLS.contains(&name) => topic::ROOT_CHECK,
        "which" | "type" | "command" if has(ROOT_TOOLS) => topic::ROOT_CHECK,
        "getprop" => topic::SYSTEM_PROPERTIES,
        "pm" => topic::INSTALLED_APPS,
        "cmd" if args.first() == Some(&"package") => topic::INSTALLED_APPS,
        "settings" => topic::SYSTEM_SETTINGS,
        "ps" | "top" => topic::OTHER_PROCESSES,
        "arp" => topic::ARP_TABLE,
        "ip" if has(&["neigh", "neighbor", "neighbour"]) => topic::ARP_TABLE,
        "ip" | "ifconfig" | "netcfg" => topic::NETWORK_INTERFACES,
        "netstat" | "ss" | "ping" | "ping6" => topic::NETWORK_STATE,
        "uname" => topic::KERNEL_VERSION,
        "mount" | "df" => topic::MOUNTS,
        "lsmod" => topic::KERNEL_MODULES,
        "getenforce" => topic::SELINUX_STATE,
        _ => topic::SHELL_COMMAND,
    })
}

/// Turns the execs of one `PATH` search into one outcome.
///
/// A program started by name is looked for in each directory of `PATH` in
/// turn, as one exec per directory, all but the last of which fail: "not
/// found", or "permission denied" for a directory the app may not search.
/// Counted one by one, running `getprop` once would read as five failures
/// and a success, and looking for `su` as ten attempts. Failures are
/// therefore held back: if the same process then starts the same command,
/// they were the search and are forgotten; if it doesn't, the command could
/// not be run, once.
#[derive(Default)]
pub struct Searches {
    /// (pid, command) -> (uid to record under, origin bits, time of the
    /// first failure).
    not_found: HashMap<(u32, String), (u32, Mode, i64)>,
}

/// A command that was looked for and could not be run: (uid, origin bits
/// such as [`crate::store::MODE_ISOLATED`], command, time).
pub type NotFound = (u32, Mode, String, i64);

impl Searches {
    /// Take in the result of one exec: the syscall's return value, or `None`
    /// if it never arrived (which is how a successful exec from a secondary
    /// thread looks). Returns `Some(true)` for a command that started, to be
    /// recorded now; a failure is reported later, by [`Self::finished`].
    pub fn outcome(
        &mut self,
        pid: u32,
        uid: u32,
        origin: Mode,
        command: &str,
        ts: i64,
        ret: Option<i32>,
    ) -> Option<bool> {
        let key = (pid, command.to_string());
        match ret {
            Some(ret) if ret < 0 => {
                self.not_found.entry(key).or_insert((uid, origin, ts));
                None
            }
            _ => {
                self.not_found.remove(&key);
                Some(true)
            }
        }
    }

    /// The searches that started at or before `cutoff` and ran nothing.
    pub fn finished(&mut self, cutoff: i64) -> Vec<NotFound> {
        let mut done = Vec::new();
        self.not_found.retain(|(_, command), (uid, origin, ts)| {
            if *ts > cutoff {
                return true;
            }
            done.push((*uid, *origin, command.clone(), *ts));
            false
        });
        done
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_path_search_counts_once() {
        let mut searches = Searches::default();
        let missing = Some(-libc::ENOENT);
        // getprop: not in the first two directories, then started.
        assert_eq!(
            searches.outcome(7, 10100, 0, "exec:getprop", 100, missing),
            None
        );
        assert_eq!(
            searches.outcome(7, 10100, 0, "exec:getprop", 100, missing),
            None
        );
        assert_eq!(
            searches.outcome(7, 10100, 0, "exec:getprop", 100, Some(0)),
            Some(true)
        );
        assert!(searches.finished(i64::MAX).is_empty());

        // su: nowhere. One finding, once the search is over.
        for _ in 0..10 {
            assert_eq!(searches.outcome(8, 10100, 0, "exec:su", 200, missing), None);
        }
        assert!(searches.finished(199).is_empty());
        assert_eq!(
            searches.finished(200),
            [(10100, 0, "exec:su".to_string(), 200)]
        );
        assert!(searches.finished(i64::MAX).is_empty());

        // A directory that may not be searched, or a program that may not
        // be run, is part of the same search.
        assert_eq!(searches.outcome(9, 10100, 0, "exec:su", 300, missing), None);
        assert_eq!(
            searches.outcome(9, 10100, 0, "exec:su", 300, Some(-libc::EACCES)),
            None
        );
        assert_eq!(searches.finished(i64::MAX).len(), 1);
        // No result at all is taken for a success.
        assert_eq!(
            searches.outcome(9, 10100, 0, "exec:sh", 300, None),
            Some(true)
        );
    }

    fn s(path: &str, args: &[&str]) -> Option<String> {
        let args: Vec<String> = args.iter().map(|a| a.to_string()).collect();
        subject(path, &args)
    }

    #[test]
    fn system_programs_are_recorded_without_their_directory() {
        assert_eq!(
            s("/system/bin/getprop", &["ro.serialno"]).as_deref(),
            Some("exec:getprop ro.serialno")
        );
        assert_eq!(s("/system/bin/sh", &[]).as_deref(), Some("exec:sh"));
        // The same command found in another directory is the same record.
        assert_eq!(s("/system/xbin/su", &[]), s("/sbin/su", &[]));
    }

    #[test]
    fn an_apps_own_code_is_not_recorded() {
        for path in [
            "/data/app/~~x==/com.a-y==/lib/arm64/libhelper.so",
            "/data/data/com.a/files/tool",
            "/data/user/0/com.a/cache/bin",
            "./tool",
            "tool",
            "/system/bin/linker64",
            "/apex/com.android.art/bin/dex2oat64",
            "/apex/com.android.runtime/bin/crash_dump64",
            "",
            "/system/bin/",
        ] {
            assert_eq!(s(path, &["x"]), None, "{path}");
        }
        // Looking for su is a root check even there.
        assert_eq!(s("/data/data/com.a/su", &[]).as_deref(), Some("exec:su"));
        assert_eq!(s("su", &[]).as_deref(), Some("exec:su"));
    }

    #[test]
    fn command_lines_are_bounded_and_single_line() {
        let long = "x".repeat(500);
        let stored = s("/system/bin/echo", &["a\nb\tc", &long]).unwrap();
        assert!(stored.starts_with("exec:echo a b c xxx"));
        assert_eq!(stored.chars().count(), SCHEME.len() + SUBJECT_CHARS);
    }

    #[test]
    fn topic_follows_the_program_even_through_a_shell() {
        for (command, want) in [
            ("exec:su", topic::ROOT_CHECK),
            ("exec:su -c id", topic::ROOT_CHECK),
            ("exec:which su", topic::ROOT_CHECK),
            ("exec:sh -c which su", topic::ROOT_CHECK),
            ("exec:busybox", topic::ROOT_CHECK),
            ("exec:getprop ro.serialno", topic::SYSTEM_PROPERTIES),
            (
                "exec:sh -c getprop ro.build.fingerprint",
                topic::SYSTEM_PROPERTIES,
            ),
            ("exec:toybox getprop", topic::SYSTEM_PROPERTIES),
            ("exec:sh -c /system/bin/getprop", topic::SYSTEM_PROPERTIES),
            ("exec:pm list packages", topic::INSTALLED_APPS),
            ("exec:cmd package list packages", topic::INSTALLED_APPS),
            ("exec:cmd wifi status", topic::SHELL_COMMAND),
            (
                "exec:settings get secure android_id",
                topic::SYSTEM_SETTINGS,
            ),
            ("exec:ps -A", topic::OTHER_PROCESSES),
            ("exec:ip neigh show", topic::ARP_TABLE),
            ("exec:ip addr", topic::NETWORK_INTERFACES),
            ("exec:ping -c 1 192.168.1.1", topic::NETWORK_STATE),
            ("exec:uname -a", topic::KERNEL_VERSION),
            ("exec:mount", topic::MOUNTS),
            ("exec:cat /proc/cpuinfo", topic::SHELL_COMMAND),
            ("exec:sh", topic::SHELL_COMMAND),
            ("exec:sh -c", topic::SHELL_COMMAND),
            ("exec:which ls", topic::SHELL_COMMAND),
        ] {
            assert_eq!(topic_of(command), Some(want), "{command}");
        }
        assert_eq!(topic_of("/proc/cpuinfo"), None);
    }

    #[test]
    fn every_recorded_command_has_a_topic() {
        for (path, args) in [
            ("/system/bin/logcat", &["-d"][..]),
            ("/vendor/bin/thing", &[]),
            ("/system/bin/sh", &["-c", ""]),
        ] {
            assert!(topic_of(&s(path, args).unwrap()).is_some(), "{path}");
        }
    }
}
