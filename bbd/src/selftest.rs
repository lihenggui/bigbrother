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

//! Whether the attached probes fire.
//!
//! A kprobe can attach to a function and never run: on the android13-5.15
//! kernels `security_file_open` is inlined into its callers, and a probe on
//! the symbol that is left attaches without error and sees nothing. So
//! after attaching, the daemon makes each kind of call once and looks for
//! the events.
//!
//! The calls are made by a child process that takes [`UID`], a uid the
//! kernel side traces and that no app has, and does one thing per check
//! ([`CHECKS`]). Its events are told apart by that uid and counted, never
//! recorded ([`Seen::claim`]). The child makes only raw system calls that
//! return at once, not through the C library, which may take another way
//! (bionic's `connect` asks netd first), and is killed if it has not ended
//! within [`CHILD_TIMEOUT`]; its events are in the ring buffer by the time
//! it has ended, as the programs run inside the calls. Events the kernel
//! side had to drop meanwhile may have been the child's, so silence then
//! decides nothing.
//!
//! `bbd selftest` runs the same checks on probes of its own and says which
//! features fire.

use std::{
    ffi::CStr,
    time::{Duration, Instant},
};

use anyhow::Context as _;
use aya::maps::RingBuf;
use aya::maps::{MapData, PerCpuArray};
use bb_common::{
    EV_CONNECT, EV_EXEC, EV_FILE, EV_NETLINK, EV_PATH, EV_PROCESS, EventHeader, OP_PROBE,
    PathEvent, read_event,
};

use crate::{syscalls::Feature, trace};

/// The uid the child takes: app id 70000 of user 0. The kernel side traces
/// every uid whose app id is 10000 or more (`should_trace`); Android gives
/// apps 10000 to 19999, SDK sandboxes and cache gids 20000 to 29999,
/// external storage and shared gids 30000 to 59999, `nobody` 65534 and
/// isolated processes 90000 to 99999. Nothing has 70000.
pub const UID: u32 = 70_000;

// Above the ranges Android hands out below the isolated processes', and so
// traced (from 10000 up), and below those.
const _: () = assert!(
    UID % crate::packages::PER_USER_RANGE >= 60_000
        && UID % crate::packages::PER_USER_RANGE < crate::packages::FIRST_ISOLATED_APP_ID
);

/// How long the child may take; it makes a handful of system calls that
/// do not wait for anything.
const CHILD_TIMEOUT: Duration = Duration::from_secs(1);
/// How often the child is looked for in that time.
const CHILD_POLL: Duration = Duration::from_millis(1);

/// One call the child makes, and the event that shows its probe fired.
struct Check {
    feature: &'static str,
    event: u32,
    /// How the name of the kernel function the call goes through ends,
    /// to find it among the functions the feature is attached to.
    function: &'static str,
}

/// In the order the child makes them; bit `i` of its exit status is set
/// when it could not make the call of `CHECKS[i]`.
const CHECKS: &[Check] = &[
    // Opening /proc/version reaches the file_open hook.
    Check {
        feature: "files",
        event: EV_FILE,
        function: "file_open",
    },
    // faccessat on it: the entry probe and `sys_ret`, which reports it. Only
    // an `EV_PATH` of a stat-style call counts (see `Seen::claim`): a failed
    // open is reported as one too.
    Check {
        feature: "files",
        event: EV_PATH,
        function: "sys_faccessat",
    },
    // execve of "/", which fails.
    Check {
        feature: "exec",
        event: EV_EXEC,
        function: "sys_execve",
    },
    // connect of a UDP socket to the loopback address, which sends nothing.
    Check {
        feature: "network",
        event: EV_CONNECT,
        function: "sys_connect",
    },
    // process_vm_readv of nothing from the parent.
    Check {
        feature: "process",
        event: EV_PROCESS,
        function: "sys_process_vm_readv",
    },
    // A request for the network interfaces.
    Check {
        feature: "netlink",
        event: EV_NETLINK,
        function: "netlink_send",
    },
];

/// The child's exit status when it could not take [`UID`].
const NO_UID: i32 = 255;

/// The kinds of event a child's calls produced.
#[derive(Default, Clone, Copy, Debug, PartialEq, Eq)]
pub struct Seen {
    /// The child's process id; 0 before there is one.
    child: u32,
    kinds: u32,
}

impl Seen {
    /// For the events of the child with this process id.
    pub fn of(child: u32) -> Self {
        Self { child, kinds: 0 }
    }

    /// Takes an event of [`UID`] and returns true; returns false for any
    /// other uid's event, to be handled as usual. Only the child's events
    /// are counted. The others of the uid, such as those of the child of a
    /// `bbd selftest` run next to the daemon, are not recorded either: no
    /// app has the uid.
    pub fn claim(&mut self, bytes: &[u8]) -> bool {
        let Some(hdr) = read_event::<EventHeader>(bytes) else {
            return false;
        };
        if hdr.uid != UID {
            return false;
        }
        if hdr.pid != self.child || self.child == 0 || hdr.kind >= 32 {
            return true;
        }
        // A failed open is an `EV_PATH` as well; only a stat-style call's
        // shows the faccessat probe fired.
        if hdr.kind == EV_PATH && read_event::<PathEvent>(bytes).is_none_or(|ev| ev.op != OP_PROBE)
        {
            return true;
        }
        self.kinds |= 1 << hdr.kind;
        true
    }

    fn has(self, kind: u32) -> bool {
        kind < 32 && self.kinds & (1 << kind) != 0
    }
}

/// Which of [`CHECKS`] the child made: bit `i` for `CHECKS[i]`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Made(u32);

impl Made {
    const NONE: Self = Self(0);
    const ALL: Self = Self((1 << CHECKS.len()) - 1);
}

/// What a feature's probes did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum State {
    /// Every check of it produced its event.
    Fires,
    /// Attached, but this call through this function produced nothing.
    Silent(&'static str),
    /// Not attached (`attach_feature` says why).
    NotAttached,
    /// Attached, and the call through the function ending in this could
    /// not be made: the child did not get to it, or no such function is
    /// among the attached ones on this kernel.
    Unchecked(&'static str),
}

impl State {
    /// What `coverage_<feature>` says. A check that could not be made
    /// leaves the attach to decide, as before there were checks.
    pub fn covered(self) -> bool {
        matches!(self, Self::Fires | Self::Unchecked(_))
    }
}

/// (program, kernel function) pairs a feature is attached to.
pub type Plan = Vec<(&'static str, &'static str)>;

/// What each feature's probes did, given what they are attached to, what
/// the child made and what arrived from it. A feature without checks is
/// judged by its attach alone. When the kernel side dropped events while
/// the child ran (`lost`), a missing event may have been one of them: the
/// check then counts as not made.
pub fn judge(
    features: &[(&'static Feature, anyhow::Result<Plan>)],
    made: Made,
    seen: Seen,
    lost: bool,
) -> Vec<(&'static str, State)> {
    features
        .iter()
        .map(|(feature, attached)| {
            let Ok(plan) = attached else {
                return (feature.name, State::NotAttached);
            };
            let mut state = State::Fires;
            for (i, check) in CHECKS.iter().enumerate() {
                if check.feature != feature.name {
                    continue;
                }
                match check.through(plan) {
                    Some(function) if made.0 & (1 << i) != 0 && !seen.has(check.event) && !lost => {
                        // Silence outweighs a check that could not be made.
                        return (feature.name, State::Silent(function));
                    }
                    Some(_) if made.0 & (1 << i) != 0 && seen.has(check.event) => {}
                    _ if state == State::Fires => state = State::Unchecked(check.function),
                    _ => {}
                }
            }
            (feature.name, state)
        })
        .collect()
}

impl Check {
    /// The function of `plan` this check's call goes through, if attached.
    fn through(&self, plan: &Plan) -> Option<&'static str> {
        plan.iter()
            .map(|(_, function)| *function)
            .find(|function| function.ends_with(self.function))
    }
}

/// The attached functions the checks of `feature` go through.
fn checked(feature: &str, plan: &Plan) -> Vec<&'static str> {
    CHECKS
        .iter()
        .filter(|check| check.feature == feature)
        .filter_map(|check| check.through(plan))
        .collect()
}

/// Make the calls in a child process and wait for it to end. Returns which
/// calls it made, and the child's process id.
pub fn exercise() -> (Made, u32) {
    // Everything the child uses is made before the fork: in the child of a
    // process that may have other threads, only system calls are safe.
    let calls = Calls::new();
    // SAFETY: the child only makes system calls and exits (`Calls::make`).
    let pid = unsafe { libc::fork() };
    if pid < 0 {
        return (Made::NONE, 0);
    }
    if pid == 0 {
        // SAFETY: in the child, which never returns from this.
        unsafe { calls.make() }
    }
    let started = Instant::now();
    loop {
        let mut status = 0;
        // SAFETY: plain system call on our own child.
        let reaped = unsafe { libc::waitpid(pid, &mut status, libc::WNOHANG) };
        if reaped == pid {
            return (made(status), pid as u32);
        }
        if reaped < 0 {
            return (Made::NONE, pid as u32);
        }
        if started.elapsed() >= CHILD_TIMEOUT {
            // SAFETY: as above; the child is reaped right after.
            unsafe {
                libc::kill(pid, libc::SIGKILL);
                libc::waitpid(pid, &mut status, 0);
            }
            return (Made::NONE, pid as u32);
        }
        std::thread::sleep(CHILD_POLL);
    }
}

/// Which calls the child made, from its wait status.
fn made(status: i32) -> Made {
    if !libc::WIFEXITED(status) {
        return Made::NONE;
    }
    match libc::WEXITSTATUS(status) {
        NO_UID => Made::NONE,
        missed => Made(Made::ALL.0 & !(missed as u32)),
    }
}

/// `struct nlmsghdr` and `struct rtgenmsg`: a dump of the interfaces.
#[repr(C)]
struct LinkRequest {
    len: u32,
    kind: u16,
    flags: u16,
    seq: u32,
    pid: u32,
    family: u8,
    _pad: [u8; 3],
}

const RTM_GETLINK: u16 = 18;
const NLM_F_REQUEST: u16 = 0x1;
const NLM_F_DUMP: u16 = 0x300;

/// What the child's calls take, made before the fork.
struct Calls {
    parent: libc::pid_t,
    loopback: libc::sockaddr_in,
    link_request: LinkRequest,
}

const PROC_VERSION: &CStr = c"/proc/version";
const ROOT_DIR: &CStr = c"/";

impl Calls {
    fn new() -> Self {
        // SAFETY: sockaddr_in is plain data; all zeros is a valid value.
        let mut loopback: libc::sockaddr_in = unsafe { std::mem::zeroed() };
        loopback.sin_family = libc::AF_INET as libc::sa_family_t;
        // The discard port: nothing is sent to it, connecting UDP sends
        // nothing.
        loopback.sin_port = 9u16.to_be();
        loopback.sin_addr.s_addr = u32::from(std::net::Ipv4Addr::LOCALHOST).to_be();
        Self {
            // SAFETY: plain system call.
            parent: unsafe { libc::getpid() },
            loopback,
            link_request: LinkRequest {
                len: size_of::<LinkRequest>() as u32,
                kind: RTM_GETLINK,
                flags: NLM_F_REQUEST | NLM_F_DUMP,
                seq: 1,
                pid: 0,
                family: libc::AF_UNSPEC as u8,
                _pad: [0; 3],
            },
        }
    }

    /// The child: take [`UID`], make each call of [`CHECKS`] and exit with
    /// the ones it could not make.
    ///
    /// # Safety
    ///
    /// Only in the child of `fork`. Makes system calls only: no allocation,
    /// no lock, no output.
    unsafe fn make(&self) -> ! {
        unsafe {
            let uid = UID as libc::uid_t;
            if libc::setgroups(0, std::ptr::null()) != 0
                || libc::setresgid(uid, uid, uid) != 0
                || libc::setresuid(uid, uid, uid) != 0
            {
                libc::_exit(NO_UID);
            }
            let mut missed = 0;

            let fd = libc::syscall(
                libc::SYS_openat,
                libc::AT_FDCWD,
                PROC_VERSION.as_ptr(),
                libc::O_RDONLY | libc::O_CLOEXEC,
            );
            if fd < 0 {
                missed |= 1 << 0;
            } else {
                libc::close(fd as i32);
            }

            libc::syscall(
                libc::SYS_faccessat,
                libc::AT_FDCWD,
                PROC_VERSION.as_ptr(),
                libc::R_OK,
            );

            let argv = [ROOT_DIR.as_ptr(), std::ptr::null()];
            let envp: [*const libc::c_char; 1] = [std::ptr::null()];
            libc::syscall(
                libc::SYS_execve,
                ROOT_DIR.as_ptr(),
                argv.as_ptr(),
                envp.as_ptr(),
            );

            let udp = libc::syscall(
                libc::SYS_socket,
                libc::AF_INET,
                libc::SOCK_DGRAM | libc::SOCK_CLOEXEC,
                0,
            );
            if udp < 0 {
                missed |= 1 << 3;
            } else {
                libc::syscall(
                    libc::SYS_connect,
                    udp,
                    &raw const self.loopback,
                    size_of::<libc::sockaddr_in>(),
                );
                libc::close(udp as i32);
            }

            libc::syscall(
                libc::SYS_process_vm_readv,
                self.parent,
                std::ptr::null::<libc::iovec>(),
                0,
                std::ptr::null::<libc::iovec>(),
                0,
                0,
            );

            let netlink = libc::syscall(
                libc::SYS_socket,
                libc::AF_NETLINK,
                libc::SOCK_RAW | libc::SOCK_CLOEXEC,
                libc::NETLINK_ROUTE,
            );
            if netlink < 0 {
                missed |= 1 << 5;
            } else {
                libc::syscall(
                    libc::SYS_sendto,
                    netlink,
                    &raw const self.link_request,
                    size_of::<LinkRequest>(),
                    0,
                    std::ptr::null::<libc::sockaddr>(),
                    0,
                );
                libc::close(netlink as i32);
            }

            libc::_exit(missed)
        }
    }
}

/// `bbd selftest`: attach the probes, make the calls and print what fired.
/// Returns whether every feature fires.
pub fn command() -> anyhow::Result<bool> {
    let started = Instant::now();
    let mut probes = trace::load_probes()?;
    let mut ring = RingBuf::try_from(
        probes
            .ebpf
            .take_map("EVENTS")
            .context("EVENTS map missing")?,
    )?;
    let dropped = PerCpuArray::try_from(
        probes
            .ebpf
            .take_map("DROPPED")
            .context("DROPPED map missing")?,
    )?;
    // What came before the child is not its.
    while ring.next().is_some() {}
    let dropped_before = dropped_events(&dropped);
    let (made, child) = exercise();
    let mut seen = Seen::of(child);
    while let Some(item) = ring.next() {
        seen.claim(&item);
    }
    let lost = dropped_events(&dropped) > dropped_before;
    let states = judge(&probes.features, made, seen, lost);
    println!("kernel {}", kernel_release());
    for ((feature, attached), (_, state)) in probes.features.iter().zip(&states) {
        match (state, attached) {
            (State::Fires, Ok(plan)) => println!(
                "ok {}: fires ({})",
                feature.name,
                checked(feature.name, plan).join(", ")
            ),
            (State::Silent(function), _) => println!(
                "FAIL {}: attached to {function} but did not fire",
                feature.name
            ),
            (State::Unchecked(function), _) => println!(
                "FAIL {}: attached, but the call through *{function} could not be made",
                feature.name
            ),
            (_, Err(e)) => println!("FAIL {}: not attached: {e:#}", feature.name),
            (State::NotAttached, Ok(_)) => unreachable!("judged from the attach"),
        }
    }
    let firing = states.iter().filter(|(_, s)| *s == State::Fires).count();
    let passed = firing == states.len();
    println!(
        "selftest {}: {firing} of {} features fire ({} ms)",
        if passed { "PASS" } else { "FAIL" },
        states.len(),
        started.elapsed().as_millis()
    );
    Ok(passed)
}

/// Events the kernel side has dropped so far, over every CPU.
pub fn dropped_events(map: &PerCpuArray<MapData, u64>) -> u64 {
    map.get(&0, 0)
        .map_or(0, |per_cpu| per_cpu.iter().sum::<u64>())
}

fn kernel_release() -> String {
    // SAFETY: utsname is plain data, filled in by uname.
    let mut name: libc::utsname = unsafe { std::mem::zeroed() };
    if unsafe { libc::uname(&mut name) } != 0 {
        return "unknown".into();
    }
    // SAFETY: uname NUL-terminates the field.
    unsafe { CStr::from_ptr(name.release.as_ptr()) }
        .to_string_lossy()
        .into_owned()
}

#[cfg(test)]
pub(crate) fn event_bytes<T: bb_common::EventData>(value: &T) -> &[u8] {
    // SAFETY: EventData guarantees an initialized, padding-free layout.
    unsafe { std::slice::from_raw_parts((value as *const T).cast::<u8>(), size_of::<T>()) }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::syscalls;

    const CHILD: u32 = 4321;

    fn header(kind: u32, uid: u32, pid: u32) -> EventHeader {
        EventHeader {
            kind,
            uid,
            pid,
            tid: pid,
            boot_ns_low: 0,
            boot_ns_high: 0,
        }
    }

    /// An event of `kind` from `pid` as the ring buffer holds it; an
    /// `EV_PATH` of operation `op`.
    fn event(kind: u32, uid: u32, pid: u32, op: u32) -> Vec<u8> {
        let hdr = header(kind, uid, pid);
        if kind != EV_PATH {
            return event_bytes(&hdr).to_vec();
        }
        // SAFETY: PathEvent is plain data; all zeros is a valid value.
        let mut ev: PathEvent = unsafe { std::mem::zeroed() };
        ev.hdr = hdr;
        ev.op = op;
        event_bytes(&ev).to_vec()
    }

    fn seen(kinds: &[u32]) -> Seen {
        let mut seen = Seen::of(CHILD);
        for &kind in kinds {
            assert!(seen.claim(&event(kind, UID, CHILD, OP_PROBE)));
        }
        seen
    }

    fn feature(name: &str) -> &'static Feature {
        syscalls::FEATURES
            .iter()
            .chain([&syscalls::FILES])
            .find(|f| f.name == name)
            .unwrap()
    }

    fn files_attached() -> (&'static Feature, anyhow::Result<Plan>) {
        (
            feature("files"),
            Ok(vec![
                ("file_open", "selinux_file_open"),
                ("enter_openat", "__x64_sys_openat"),
                ("enter_probe_at", "__x64_sys_faccessat"),
                ("enter_probe_at", "__x64_sys_faccessat2"),
            ]),
        )
    }

    fn netlink_attached() -> (&'static Feature, anyhow::Result<Plan>) {
        (
            feature("netlink"),
            Ok(vec![("enter_netlink", "security_netlink_send")]),
        )
    }

    #[test]
    fn events_of_other_uids_are_left_to_be_recorded() {
        let mut seen = Seen::of(CHILD);
        assert!(!seen.claim(&event(EV_FILE, 10_123, CHILD, 0)));
        assert!(!seen.claim(&event(EV_FILE, 0, CHILD, 0)));
        assert_eq!(seen, Seen::of(CHILD));
    }

    #[test]
    fn only_the_childs_events_are_counted_and_none_of_the_uid_recorded() {
        let mut seen = Seen::of(CHILD);
        assert!(seen.claim(&event(EV_FILE, UID, CHILD + 1, 0)));
        assert!(!seen.has(EV_FILE));
        assert!(seen.claim(&event(EV_FILE, UID, CHILD, 0)));
        assert!(seen.has(EV_FILE));
        // Before and after the check.
        assert!(Seen::default().claim(&event(EV_FILE, UID, CHILD, 0)));
    }

    #[test]
    fn a_failed_open_does_not_stand_for_the_faccessat_probe() {
        use bb_common::OP_OPEN;
        let mut seen = Seen::of(CHILD);
        assert!(seen.claim(&event(EV_PATH, UID, CHILD, OP_OPEN)));
        assert!(!seen.has(EV_PATH));
        let states = judge(&[files_attached()], Made::ALL, seen, false);
        assert_eq!(states, [("files", State::Silent("selinux_file_open"))]);
        assert!(seen.claim(&event(EV_PATH, UID, CHILD, OP_PROBE)));
        assert!(seen.has(EV_PATH));
    }

    #[test]
    fn silence_while_events_were_dropped_leaves_the_attach_to_decide() {
        let states = judge(
            &[files_attached(), netlink_attached()],
            Made::ALL,
            seen(&[EV_FILE, EV_PATH]),
            true,
        );
        assert_eq!(
            states,
            [
                ("files", State::Fires),
                ("netlink", State::Unchecked("netlink_send")),
            ]
        );
        assert!(State::Unchecked("netlink_send").covered());
    }

    #[test]
    fn a_feature_whose_calls_produced_their_events_fires() {
        let states = judge(
            &[files_attached(), netlink_attached()],
            Made::ALL,
            seen(&[EV_FILE, EV_PATH, EV_NETLINK]),
            false,
        );
        assert_eq!(states, [("files", State::Fires), ("netlink", State::Fires)]);
        assert!(State::Fires.covered());
    }

    #[test]
    fn a_probe_that_attached_and_did_not_fire_is_not_covered() {
        let states = judge(
            &[files_attached(), netlink_attached()],
            Made::ALL,
            seen(&[EV_PATH]),
            false,
        );
        assert_eq!(
            states,
            [
                ("files", State::Silent("selinux_file_open")),
                ("netlink", State::Silent("security_netlink_send")),
            ]
        );
        assert!(!State::Silent("selinux_file_open").covered());
    }

    #[test]
    fn the_silent_function_is_the_one_the_call_goes_through() {
        let states = judge(&[files_attached()], Made::ALL, seen(&[EV_FILE]), false);
        assert_eq!(states, [("files", State::Silent("__x64_sys_faccessat"))]);
    }

    #[test]
    fn a_feature_that_did_not_attach_is_not_covered() {
        let failed = (feature("exec"), Err(anyhow::anyhow!("verifier rejected")));
        let states = judge(&[failed], Made::ALL, seen(&[EV_EXEC]), false);
        assert_eq!(states, [("exec", State::NotAttached)]);
        assert!(!State::NotAttached.covered());
    }

    #[test]
    fn a_call_the_child_did_not_make_leaves_the_attach_to_decide() {
        let states = judge(
            &[files_attached(), netlink_attached()],
            Made::NONE,
            Seen::default(),
            false,
        );
        assert_eq!(
            states,
            [
                ("files", State::Unchecked("file_open")),
                ("netlink", State::Unchecked("netlink_send")),
            ]
        );
        assert!(State::Unchecked("file_open").covered());
    }

    #[test]
    fn silence_outweighs_a_call_that_was_not_made() {
        // The open failed, so the file_open hook is unchecked; faccessat
        // was made and nothing arrived.
        let made = Made(Made::ALL.0 & !1);
        let states = judge(&[files_attached()], made, Seen::default(), false);
        assert_eq!(states, [("files", State::Silent("__x64_sys_faccessat"))]);
    }

    #[test]
    fn a_call_through_a_function_not_attached_is_not_judged() {
        // A kernel with faccessat2 only: the child's faccessat reaches no
        // probe.
        let files = (
            feature("files"),
            Ok(vec![
                ("file_open", "selinux_file_open"),
                ("enter_probe_at", "__x64_sys_faccessat2"),
            ]),
        );
        let states = judge(&[files], Made::ALL, seen(&[EV_FILE]), false);
        assert_eq!(states, [("files", State::Unchecked("sys_faccessat"))]);
    }

    #[test]
    fn the_child_reports_the_calls_it_could_not_make() {
        let exited = |code: i32| code << 8;
        assert_eq!(made(exited(0)), Made::ALL);
        assert_eq!(made(exited(1 << 3)), Made(Made::ALL.0 & !(1 << 3)));
        assert_eq!(made(exited(NO_UID)), Made::NONE);
        // Killed by a signal.
        assert_eq!(made(libc::SIGKILL), Made::NONE);
    }

    #[test]
    fn every_check_belongs_to_a_feature_with_probes() {
        for check in CHECKS {
            feature(check.feature);
        }
        assert!(CHECKS.len() < 8, "the exit status holds one bit per check");
    }
}
