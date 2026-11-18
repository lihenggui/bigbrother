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

//! The gate: giving a starting app its sandbox before it runs.
//!
//! The kernel side (`gate_setresuid` in bb-ebpf) stops a child of zygote as
//! it is about to take a sandboxed uid, and reports it here. zygote has by
//! then given the child a mount namespace of its own and finished mounting
//! in it; none of the app's code has run. A thread enters that namespace,
//! makes the mounts of the sandbox (`mounts`), and lets the process go on.
//! An app is held for about a millisecond.
//!
//! The thread does nothing else and never waits for the rest of the daemon:
//! an app must not be kept from starting by a database write. It is also
//! the only thread that can do this. A thread may move to another mount
//! namespace only if it shares its root and working directory with no other
//! thread, so this one gives up sharing them when it starts.
//!
//! A start that cannot be given its sandbox is killed, never let through:
//! the system starts the app again when it is next wanted. If the thread
//! itself gets stuck or ends, the kernel side kills such starts on its own
//! (`GATE_WAITING_SINCE`).

use std::{
    collections::{HashMap, VecDeque},
    fs::{self, File},
    io,
    os::{
        fd::{AsRawFd, FromRawFd, OwnedFd},
        unix::fs::PermissionsExt as _,
    },
    path::Path,
    sync::{
        Arc, Mutex, RwLock,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

use anyhow::Context as _;
use aya::maps::{Array, HashMap as BpfHashMap, MapData, RingBuf};
use bb_common::{GATE_CLOSED, GateEvent};

use super::{
    Effect, Entry, Resumed, Status,
    dirs::{self, MEDIA_RW},
    items::{Mounts, Settings},
    mounts::{self, Kernel},
};

/// How long a process is waited for to come to a stop. It stops as it leaves
/// the system call it was caught in, which takes microseconds.
const STOP_WAIT: Duration = Duration::from_secs(2);

/// Hold times kept for the 95th percentile `bbd sandbox status` reports.
const HOLDS_KEPT: usize = 256;

/// Least time between two log lines about starts that failed. An app the
/// system keeps trying to start would otherwise fill the log.
const FAILURE_LOG_INTERVAL: Duration = Duration::from_secs(60);

/// A process held by a descriptor, so that a signal meant for it cannot
/// reach another process that was given its pid in the meantime.
pub struct Process(OwnedFd);

impl Process {
    pub fn open(pid: i32) -> io::Result<Self> {
        let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, 0) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: just opened, and owned by nothing else.
        Ok(Self(unsafe { OwnedFd::from_raw_fd(fd as i32) }))
    }

    /// Send `signal`. A process that has ended in the meantime gets none.
    pub fn signal(&self, signal: i32) {
        unsafe {
            libc::syscall(
                libc::SYS_pidfd_send_signal,
                self.0.as_raw_fd(),
                signal,
                std::ptr::null::<libc::siginfo_t>(),
                0,
            )
        };
    }
}

/// Called with the uid of an app that was let through.
type Told = Box<dyn Fn(u32) + Send>;

/// A uid held at the gate: what the thread needs to know to mount its
/// sandbox.
#[derive(Clone)]
struct Gated {
    /// The packages that run under it.
    packages: Vec<String>,
    mounts: Mounts,
}

/// What the gate thread and the daemon's main loop share.
struct Shared {
    /// The uids held at the gate.
    apps: RwLock<HashMap<u32, Gated>>,
    stats: Mutex<Stats>,
    /// False once the thread has ended.
    open: AtomicBool,
    /// Told the uid of each app let through.
    admitted: Mutex<Option<Told>>,
}

#[derive(Default)]
struct Stats {
    admitted: u64,
    failed: u64,
    /// The latest hold times, in microseconds.
    holds: VecDeque<u64>,
}

impl Stats {
    fn admit(&mut self, held_us: u64) {
        self.admitted += 1;
        if self.holds.len() == HOLDS_KEPT {
            self.holds.pop_front();
        }
        self.holds.push_back(held_us);
    }

    fn hold_p95_us(&self) -> u64 {
        let mut holds: Vec<u64> = self.holds.iter().copied().collect();
        holds.sort_unstable();
        holds
            .get(holds.len().saturating_sub(1) * 95 / 100)
            .copied()
            .unwrap_or(0)
    }
}

/// The effect that replaces what an app sees of storage and the kernel: it
/// tells the kernel side which uids to stop, and the gate thread what to
/// mount for each.
pub struct Storage {
    uids: BpfHashMap<MapData, u32, u32>,
    shared: Arc<Shared>,
}

impl Storage {
    /// Start the gate thread, on the maps of the already attached
    /// `gate_setresuid`. `scratch` is a directory of the daemon's own to
    /// assemble files in.
    pub fn start(ebpf: &mut aya::Ebpf, scratch: &Path) -> anyhow::Result<Self> {
        let mut map = |name: &str| {
            ebpf.take_map(name)
                .with_context(|| format!("{name} map missing"))
        };
        let uids = BpfHashMap::try_from(map("SANDBOX_UIDS")?)?;
        let waiting: Array<MapData, u64> = Array::try_from(map("GATE_WAITING_SINCE")?)?;
        let ring = RingBuf::try_from(map("GATE")?)?;
        fs::create_dir_all(scratch)
            .and_then(|()| fs::set_permissions(scratch, fs::Permissions::from_mode(0o700)))
            .context("making the scratch directory")?;

        let shared = Arc::new(Shared {
            apps: RwLock::default(),
            stats: Mutex::default(),
            open: AtomicBool::new(true),
            admitted: Mutex::new(None),
        });
        let (ready, started) = std::sync::mpsc::sync_channel(1);
        let thread = Thread {
            ring,
            closing: Closing {
                waiting,
                shared: shared.clone(),
            },
            kernel: Kernel::new(scratch),
            failures: FailureLog::default(),
        };
        std::thread::Builder::new()
            .name("gate".into())
            .spawn(move || thread.run(&ready))
            .context("starting the gate thread")?;
        started
            .recv()
            .context("the gate thread ended as it started")??;
        Ok(Self { uids, shared })
    }

    /// Stop holding starts of `uid`, if they were held.
    fn release(&mut self, uid: u32) {
        let _ = self.uids.remove(&uid);
        write(&self.shared.apps).remove(&uid);
    }

    /// Have `told` called, on the gate thread, with the uid of each app
    /// that was let through with its sandbox. It must not block.
    pub fn on_admitted(&self, told: impl Fn(u32) + Send + 'static) {
        *lock(&self.shared.admitted) = Some(Box::new(told));
    }

    /// From now on, hold starts of `uid` and give them `mounts`. The gate
    /// thread is told first: a start it knows nothing of is let through.
    fn hold(&mut self, uid: u32, packages: &[String], mounts: Mounts) -> anyhow::Result<()> {
        anyhow::ensure!(
            self.shared.open.load(Ordering::Relaxed),
            "the gate has stopped; restart the daemon"
        );
        let gated = Gated {
            packages: packages.to_vec(),
            mounts,
        };
        write(&self.shared.apps).insert(uid, gated);
        self.uids
            .insert(&uid, &1, 0)
            .context("adding the uid to the gate")
    }
}

impl Effect for Storage {
    fn name(&self) -> &'static str {
        "storage"
    }

    fn enable(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        items: &Settings,
    ) -> anyhow::Result<()> {
        let mounts = items.mounts();
        if !mounts.any() {
            return Ok(());
        }
        if mounts.storage {
            dirs::prepare(Path::new(&mounts::media_of(entry.uid)), entry.uid, MEDIA_RW)
                .context("making the sandbox directory")?;
        }
        self.hold(entry.uid, packages, mounts)
    }

    fn disable(&mut self, entry: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        self.release(entry.uid);
        Ok(())
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        before: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        if before.mounts() == after.mounts() {
            return Ok(());
        }
        if after.mounts().any() {
            self.enable(entry, packages, after)
        } else {
            self.release(entry.uid);
            Ok(())
        }
    }

    fn resume(&mut self, apps: &[Resumed]) {
        // The directories are left alone: they were made when the app was
        // enabled, and cannot be reached before the user unlocks the device,
        // which is when the daemon usually starts.
        for app in apps.iter().filter(|app| app.items.mounts().any()) {
            if let Err(e) = self.hold(app.entry.uid, &app.packages, app.items.mounts()) {
                eprintln!(
                    "bbd: sandbox: uid {} is not held at the gate: {e:#}",
                    app.entry.uid
                );
            }
        }
    }

    fn report(&self, status: &mut Status) {
        let stats = lock(&self.shared.stats);
        status.gate = self.shared.open.load(Ordering::Relaxed);
        status.admitted = stats.admitted;
        status.failed = stats.failed;
        status.hold_p95_us = stats.hold_p95_us();
    }
}

/// Stands in for [`Storage`] on a kernel where the gate cannot be attached:
/// nothing can be put in the sandbox there.
pub struct Unavailable;

impl Effect for Unavailable {
    fn name(&self) -> &'static str {
        "storage"
    }

    fn enable(&mut self, _: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        anyhow::ensure!(
            !items.mounts().any(),
            "this kernel cannot hold starting apps; storage and CPU information cannot be sandboxed"
        );
        Ok(())
    }

    fn disable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        Ok(())
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        _: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        self.enable(entry, packages, after)
    }

    fn resume(&mut self, apps: &[Resumed]) {
        let mounted = apps.iter().filter(|app| app.items.mounts().any()).count();
        if mounted > 0 {
            eprintln!(
                "bbd: warning: {mounted} sandboxed apps see the real storage: the gate is not available"
            );
        }
    }
}

// A thread that panicked while holding one of these cannot have left what
// it guards half changed: each is changed in a single call.
fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

fn write<T>(lock: &RwLock<T>) -> std::sync::RwLockWriteGuard<'_, T> {
    lock.write().unwrap_or_else(|e| e.into_inner())
}

// ---- the thread ------------------------------------------------------------

struct Thread {
    ring: RingBuf<MapData>,
    closing: Closing,
    kernel: Kernel,
    failures: FailureLog,
}

/// Closes the gate when the thread ends, however it ends: the kernel side
/// then kills the starts it would have held for nobody.
struct Closing {
    waiting: Array<MapData, u64>,
    shared: Arc<Shared>,
}

impl Drop for Closing {
    fn drop(&mut self) {
        self.shared.open.store(false, Ordering::Relaxed);
        let _ = self.waiting.set(0, &GATE_CLOSED, 0);
        eprintln!("bbd: sandbox: the gate has stopped; sandboxed apps cannot start");
    }
}

/// How a held process turned out.
enum Held {
    /// It was given the sandbox.
    Sandboxed,
    /// Not an app starting, or not sandboxed any more: let go as it is.
    NotOurs,
}

/// The thread cannot go on: it is in a mount namespace it could not leave.
struct Lost;

impl Thread {
    fn run(mut self, ready: &std::sync::mpsc::SyncSender<anyhow::Result<()>>) {
        let home = match own_namespace() {
            Ok(home) => home,
            Err(e) => {
                let _ = ready.send(Err(e));
                return;
            }
        };
        // The same for every app and for as long as the device is up.
        let cpuinfo = match fs::read_to_string(mounts::CPUINFO) {
            Ok(real) => mounts::cpuinfo_template(&real),
            Err(e) => {
                let _ = ready.send(Err(e).context("reading /proc/cpuinfo"));
                return;
            }
        };
        let _ = ready.send(Ok(()));

        let mut events = Vec::new();
        loop {
            let mut poll = libc::pollfd {
                fd: self.ring.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            };
            // No timeout: with nothing starting, the thread does not run.
            if unsafe { libc::poll(&mut poll, 1, -1) } < 0
                && io::Error::last_os_error().kind() != io::ErrorKind::Interrupted
            {
                return;
            }
            while let Some(item) = self.ring.next() {
                if item.len() >= size_of::<GateEvent>() {
                    // SAFETY: long enough, and any bytes are a GateEvent.
                    events.push(unsafe { item.as_ptr().cast::<GateEvent>().read_unaligned() });
                }
            }
            for event in events.drain(..) {
                let lost = self.admit(&event, &home, &cpuinfo).is_err();
                // Nothing waits any more: a later start is timed anew.
                let _ = self.closing.waiting.set(0, &0, 0);
                if lost {
                    return;
                }
            }
        }
    }

    /// Give the stopped process of `event` its sandbox and let it go on, or
    /// kill it.
    fn admit(&mut self, event: &GateEvent, home: &File, cpuinfo: &str) -> Result<(), Lost> {
        let pid = event.pid as i32;
        // The process is stopped and cannot end by itself; if something
        // killed it, there is nothing left to do.
        let Ok(process) = Process::open(pid) else {
            return Ok(());
        };
        let mut lost = false;
        let held = self
            .set_up(pid, event.uid, home, cpuinfo, &mut lost)
            // Let go only once it has stopped: the stop takes effect as the
            // process leaves the system call, and a process told to continue
            // before that stays stopped for good.
            .and_then(|held| wait_stopped(pid).map(|()| held));
        let shared = &self.closing.shared;
        match held {
            Ok(held) => {
                process.signal(libc::SIGCONT);
                if matches!(held, Held::Sandboxed) {
                    let held_us = monotonic_ns().saturating_sub(event.at_ns()) / 1000;
                    lock(&shared.stats).admit(held_us);
                    if let Some(told) = lock(&shared.admitted).as_ref() {
                        told(event.uid);
                    }
                }
            }
            Err(e) => {
                process.signal(libc::SIGKILL);
                lock(&shared.stats).failed += 1;
                self.failures.log(event.uid, &e);
            }
        }
        if lost { Err(Lost) } else { Ok(()) }
    }

    fn set_up(
        &mut self,
        pid: i32,
        uid: u32,
        home: &File,
        cpuinfo: &str,
        lost: &mut bool,
    ) -> anyhow::Result<Held> {
        let stat = fs::read_to_string(format!("/proc/{pid}/stat")).context("reading its state")?;
        let (_, parent) = parse_stat(&stat).context("reading its state")?;
        // Only zygote starts apps. Something else that took the uid with
        // the same thread name is none of the sandbox's business; what
        // started it has to be known for that, though.
        let started_by =
            fs::read_link(format!("/proc/{parent}/exe")).context("reading what started it")?;
        if !is_zygote(&started_by) {
            return Ok(Held::NotOurs);
        }
        let Some(gated) = self
            .closing
            .shared
            .apps
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .get(&uid)
            .cloned()
        else {
            return Ok(Held::NotOurs);
        };
        // Mounting in a namespace the process shares with zygote would
        // change what every app sees.
        let namespace = |pid: i32| fs::read_link(format!("/proc/{pid}/ns/mnt"));
        anyhow::ensure!(
            namespace(pid).context("reading its namespace")?
                != namespace(parent).context("reading zygote's namespace")?,
            "it shares zygote's mount namespace"
        );

        let files: &[(&str, &str)] = if gated.mounts.cpu_info {
            &[(mounts::CPUINFO, cpuinfo)]
        } else {
            &[]
        };

        let theirs = File::open(format!("/proc/{pid}/ns/mnt")).context("opening its namespace")?;
        enter(&theirs).context("entering its namespace")?;
        let mounted = mounts::apply(
            &mut self.kernel,
            uid,
            &gated.packages,
            files,
            gated.mounts.storage,
        );
        if let Err(e) = enter(home) {
            *lost = true;
            return Err(e).context("returning from its namespace");
        }
        mounted.map(|()| Held::Sandboxed)
    }
}

/// Whether a program is zygote, which is `app_process` under one of its
/// names (`app_process64`, `app_process32`).
fn is_zygote(exe: &Path) -> bool {
    exe.file_name()
        .is_some_and(|name| name.to_string_lossy().starts_with("app_process"))
}

/// Prepare the calling thread to visit other mount namespaces, and return
/// its own to come back to.
fn own_namespace() -> anyhow::Result<File> {
    // Until now the root and working directory were shared with the
    // daemon's other threads, which rules out changing namespace.
    if unsafe { libc::unshare(libc::CLONE_FS) } != 0 {
        return Err(io::Error::last_os_error()).context("unshare(CLONE_FS)");
    }
    // The daemon runs at low priority so as not to compete with apps. This
    // thread is what a starting app waits for; it must not wait behind them.
    // Best effort: without it the gate is slower, not wrong.
    unsafe { libc::setpriority(libc::PRIO_PROCESS, libc::gettid() as libc::id_t, -10) };
    File::open("/proc/thread-self/ns/mnt").context("opening the daemon's mount namespace")
}

fn enter(namespace: &File) -> io::Result<()> {
    if unsafe { libc::setns(namespace.as_raw_fd(), libc::CLONE_NEWNS) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// The state and the parent of a process, from its `/proc/<pid>/stat`:
/// `<pid> (<name>) <state> <parent> ...`. The name is the process's to
/// choose, and may contain spaces and parentheses; the fields are counted
/// from its last closing one.
fn parse_stat(stat: &str) -> Option<(char, i32)> {
    let mut fields = stat.rsplit_once(") ")?.1.split(' ');
    let state = fields.next()?.chars().next()?;
    Some((state, fields.next()?.parse().ok()?))
}

/// Wait until the process has stopped.
fn wait_stopped(pid: i32) -> anyhow::Result<()> {
    let deadline = Instant::now() + STOP_WAIT;
    let mut waits = 0u32;
    loop {
        let state = fs::read_to_string(format!("/proc/{pid}/stat"))
            .ok()
            .and_then(|stat| parse_stat(&stat))
            .map(|(state, _)| state);
        match state {
            Some('T') => return Ok(()),
            None | Some('Z' | 'X') => anyhow::bail!("it ended while held"),
            Some(_) if Instant::now() > deadline => anyhow::bail!("it did not stop"),
            Some(_) => {}
        }
        // It stops within microseconds; only a process that does not is
        // waited for at length, and then without spinning.
        waits += 1;
        std::thread::sleep(Duration::from_micros(if waits < 40 { 50 } else { 1000 }));
    }
}

fn monotonic_ns() -> u64 {
    let mut now = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut now) };
    now.tv_sec as u64 * 1_000_000_000 + now.tv_nsec as u64
}

/// Logs starts that failed, at most one line per interval.
#[derive(Default)]
struct FailureLog {
    logged: Option<Instant>,
    unlogged: u32,
}

impl FailureLog {
    fn log(&mut self, uid: u32, e: &anyhow::Error) {
        if self
            .logged
            .is_some_and(|at| at.elapsed() < FAILURE_LOG_INTERVAL)
        {
            self.unlogged += 1;
            return;
        }
        let more = match self.unlogged {
            0 => String::new(),
            n => format!(" ({n} more since the last)"),
        };
        eprintln!("bbd: sandbox: a start of uid {uid} was stopped: {e:#}{more}");
        self.logged = Some(Instant::now());
        self.unlogged = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_state_and_parent_are_read_past_any_process_name() {
        assert_eq!(
            parse_stat("4321 (main) T 612 612 0 0 -1 4194624"),
            Some(('T', 612))
        );
        // A name is whatever the process set.
        assert_eq!(
            parse_stat("4321 (a) R 1 (b c) S 99 0) R 612 612 0"),
            Some(('R', 612))
        );
        assert_eq!(parse_stat("4321 (usap64) S 700 700"), Some(('S', 700)));
        assert_eq!(parse_stat(""), None);
        assert_eq!(parse_stat("4321 (main"), None);
        assert_eq!(parse_stat("4321 (main) T"), None);
        assert_eq!(parse_stat("4321 (main) T x"), None);
    }

    #[test]
    fn only_app_process_counts_as_zygote() {
        assert!(is_zygote(Path::new("/system/bin/app_process64")));
        assert!(is_zygote(Path::new("/system/bin/app_process32")));
        assert!(!is_zygote(Path::new("/system/bin/sh")));
        assert!(!is_zygote(Path::new("/system/bin/run-as")));
        assert!(!is_zygote(Path::new("/")));
    }

    #[test]
    fn the_hold_time_reported_is_the_95th_percentile_of_the_latest() {
        let mut stats = Stats::default();
        assert_eq!(stats.hold_p95_us(), 0);
        for held in 1..=100 {
            stats.admit(held);
        }
        assert_eq!((stats.admitted, stats.hold_p95_us()), (100, 95));
        // Older ones drop out.
        for _ in 0..HOLDS_KEPT {
            stats.admit(7);
        }
        assert_eq!(stats.hold_p95_us(), 7);
        assert_eq!(stats.admitted, 100 + HOLDS_KEPT as u64);
    }

    #[test]
    fn a_process_can_be_held_and_signalled_by_descriptor() {
        let mut child = std::process::Command::new("sleep")
            .arg("30")
            .spawn()
            .unwrap();
        let held = Process::open(child.id() as i32).unwrap();
        held.signal(libc::SIGKILL);
        assert!(!child.wait().unwrap().success());
        // Gone: the signal goes nowhere, and a pid that is not there cannot
        // be opened.
        held.signal(libc::SIGKILL);
        assert!(Process::open(i32::MAX).is_err());
    }
}
