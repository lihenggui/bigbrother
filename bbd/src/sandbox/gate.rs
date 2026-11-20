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
//!
//! While newly installed apps are put in the sandbox (`auto`), the kernel
//! side also stops a start of an app the daemon has not dealt with yet: one
//! installed and started before the daemon got to it. That start cannot be
//! given a sandbox either. It is killed, and the main loop told, which puts
//! the app in the sandbox: its next start is held as any sandboxed one.

use std::{
    collections::{HashMap, HashSet, VecDeque},
    fs::{self, File},
    io::{self, Read as _},
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
use bb_common::{GATE_CLOSED, GateEvent, read_event};

use super::{
    Effect, Entry, Resumed, Status,
    destinations::Book,
    dirs::{self, MEDIA_RW},
    items::{Mounts, Settings},
    mounts::{self, Kernel},
    resolver::{self, Cover},
};
use crate::netctl::Target;

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
    /// Hold `pid` as a process leader. A reused ID belonging to a non-leader
    /// is the same as a missing leader for callers holding process IDs.
    pub fn open(pid: i32) -> io::Result<Self> {
        Self::open_pidfd(pid).map_err(|error| Self::normalize_open_error(pid, error))
    }

    fn normalize_open_error(pid: i32, error: io::Error) -> io::Error {
        // Kernels use either errno when a task has no thread-group leader at this ID.
        if pid > 0
            && matches!(error.raw_os_error(), Some(libc::EINVAL | libc::ENOENT))
            && Self::no_group_leader(pid)
        {
            io::Error::from_raw_os_error(libc::ESRCH)
        } else {
            error
        }
    }

    fn open_pidfd(pid: i32) -> io::Result<Self> {
        let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, 0) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: just opened, and owned by nothing else.
        Ok(Self(unsafe { OwnedFd::from_raw_fd(fd as i32) }))
    }

    fn no_group_leader(pid: i32) -> bool {
        match is_nonleader(pid) {
            Ok(nonleader) => nonleader,
            Err(e) if matches!(e.raw_os_error(), Some(libc::ENOENT | libc::ESRCH)) => {
                // The thread may have exited before its status was read.
                // Confirm that in the kernel before treating it as gone.
                matches!(Self::open_pidfd(pid), Err(e) if e.raw_os_error() == Some(libc::ESRCH))
            }
            Err(_) => false,
        }
    }

    /// A pidfd becomes readable when this process exits, including before it is reaped.
    pub fn exited(&self, timeout_ms: i32) -> io::Result<bool> {
        let mut pfd = libc::pollfd {
            fd: self.0.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };
        if unsafe { libc::poll(&mut pfd, 1, timeout_ms) } < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(pfd.revents & (libc::POLLIN | libc::POLLHUP) != 0)
    }

    /// Send `signal`. A process that has ended in the meantime gets none.
    pub fn signal(&self, signal: i32) -> io::Result<()> {
        let result = unsafe {
            libc::syscall(
                libc::SYS_pidfd_send_signal,
                self.0.as_raw_fd(),
                signal,
                std::ptr::null::<libc::siginfo_t>(),
                0,
            )
        };
        if result < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }
}

fn is_nonleader(pid: i32) -> io::Result<bool> {
    let mut status = Vec::new();
    File::open(format!("/proc/{pid}/status"))?
        .take(4096)
        .read_to_end(&mut status)?;
    // Read the IDs even when the thread's name includes non-UTF-8 bytes.
    let field = |prefix: &[u8]| {
        let value = status
            .split(|&byte| byte == b'\n')
            .find_map(|line| line.strip_prefix(prefix))?;
        std::str::from_utf8(value)
            .ok()?
            .trim_ascii()
            .parse::<i32>()
            .ok()
    };
    Ok(field(b"Pid:") == Some(pid) && field(b"Tgid:").is_some_and(|tgid| tgid > 0 && tgid != pid))
}

/// What the starts of an app are held for: its mounts, and what it finds
/// at the socket of the system's resolver.
type Hold = (Mounts, Cover);

/// Starts that are not held.
const NOT_HELD: Hold = (
    Mounts {
        storage: false,
        cpu_info: false,
    },
    Cover::None,
);

/// Whether starts are held at all for this.
fn held(hold: Hold) -> bool {
    hold.0.any() || hold.1 != Cover::None
}

/// Called with the uid of an app that was let through.
type Told = Box<dyn Fn(u32) + Send>;

/// A uid held at the gate: what the thread needs to know to mount its
/// sandbox.
#[derive(Clone)]
struct Gated {
    package: String,
    install_id: String,
    /// The packages that run under it.
    packages: Vec<String>,
    mounts: Mounts,
    /// What it finds at the socket of the system's resolver (`resolver`).
    resolver: Cover,
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
    /// Told the uid of each new app whose start was ended.
    unknown: Mutex<Option<Told>>,
}

#[derive(Default)]
struct Stats {
    admitted: u64,
    failed: u64,
    /// Starts of new apps ended.
    unknown: u64,
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
    /// Whether apps can be kept from the network on this device. Where
    /// they cannot, the resolver is left to them too.
    network: bool,
    /// The destinations apps are kept from, once the daemon listens for
    /// lookups (`names`).
    names: Option<Book>,
    /// Running processes whose resolver mounts may differ from the gate.
    resolver_unknown: HashSet<u32>,
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
            unknown: Mutex::new(None),
        });
        let (ready, started) = std::sync::mpsc::sync_channel(1);
        let thread = Thread {
            ring,
            closing: Closing {
                waiting,
                shared: shared.clone(),
            },
            keeper: Keeper::new(shared.clone(), scratch),
        };
        std::thread::Builder::new()
            .name("gate".into())
            .spawn(move || thread.run(&ready))
            .context("starting the gate thread")?;
        started
            .recv()
            .context("the gate thread ended as it started")??;
        Ok(Self {
            uids,
            shared,
            network: false,
            names: None,
            resolver_unknown: HashSet::new(),
        })
    }

    /// Give the apps whose connections are recorded, and those kept from
    /// names, the daemon's socket in place of the resolver's from now on:
    /// the daemon is listening on it (`names`). `book` tells which apps
    /// are kept from names.
    pub fn with_names(&mut self, book: Book) {
        self.names = Some(book);
    }

    /// Cover the resolver's socket from now on in the starting processes
    /// of the apps kept from the network: the packet filter is in place.
    pub fn with_network(&mut self) {
        self.network = true;
    }

    /// What a start of the app of `uid`, with `items`, is held for.
    fn held_for(&self, uid: u32, items: &Settings) -> Hold {
        let kept_from_names = |book: &Book| {
            let book = book.lock().unwrap_or_else(|e| e.into_inner());
            book.get(&uid).is_some_and(|targets| {
                targets
                    .iter()
                    .any(|target| !matches!(target, Target::Ip(..)))
            })
        };
        let cover = if self.network && items.network_refused() {
            Cover::Null
        } else if self
            .names
            .as_ref()
            .is_some_and(|book| items.network_recorded() || kept_from_names(book))
        {
            Cover::Relay
        } else {
            Cover::None
        };
        (items.mounts(), cover)
    }

    /// What the starts of `uid` are held for now.
    fn holding(&self, uid: u32) -> Hold {
        self.shared
            .apps
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .get(&uid)
            .map_or(NOT_HELD, |gated| (gated.mounts, gated.resolver))
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

    /// Have `told` called, on the gate thread, with the uid of each new app
    /// whose start was ended (see `auto`). It must not block.
    pub fn on_unknown(&self, told: impl Fn(u32) + Send + 'static) {
        *lock(&self.shared.unknown) = Some(Box::new(told));
    }

    /// From now on, hold starts of `uid` and give them `mounts`, and
    /// `resolver` at the resolver's socket. The gate thread is told first:
    /// a start it knows nothing of is let through.
    fn hold(
        &mut self,
        entry: &Entry,
        packages: &[String],
        mounts: Mounts,
        resolver: Cover,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            self.shared.open.load(Ordering::Relaxed),
            "the gate has stopped; restart the daemon"
        );
        let uid = entry.uid;
        let gated = Gated {
            package: entry.package.clone(),
            install_id: entry.install_id.clone(),
            packages: packages.to_vec(),
            mounts,
            resolver,
        };
        let mut apps = write(&self.shared.apps);
        self.uids
            .insert(&uid, &1, 0)
            .context("adding the uid to the gate")?;
        apps.insert(uid, gated);
        Ok(())
    }
}

impl Storage {
    /// Hold the starts of the app of `entry` as `held` says, or none.
    fn gate(&mut self, entry: &Entry, packages: &[String], held: Hold) -> anyhow::Result<()> {
        let (mounts, resolver) = held;
        if !self::held(held) {
            self.release(entry.uid);
            return Ok(());
        }
        if mounts.storage {
            dirs::prepare(
                Path::new(&mounts::media_of(entry.uid)),
                entry.uid,
                MEDIA_RW,
                &format!("{}-{}", entry.uid, entry.install_id),
            )
            .context("making the sandbox directory")?;
        }
        self.hold(entry, packages, mounts, resolver)
    }

    /// Bring the app of `entry` from what its starts were held for to
    /// what they are to be held for, and the processes it has to the
    /// same as far as they can be without being ended: the resolver's
    /// socket is covered in them, or uncovered (`resolver`).
    ///
    /// The gate first, either way: a process that starts in between is
    /// then as the others are about to be. Both, or neither.
    fn change(
        &mut self,
        entry: &Entry,
        packages: &[String],
        was: Hold,
        wanted: Hold,
    ) -> anyhow::Result<()> {
        self.gate(entry, packages, wanted)?;
        if was.1 == wanted.1 && !self.resolver_unknown.contains(&entry.uid) {
            return Ok(());
        }
        let Err(e) = resolver::set(entry, wanted.1)
            .context("changing what the app's processes find at the resolver's socket")
        else {
            self.resolver_unknown.remove(&entry.uid);
            return Ok(());
        };
        if let Err(undo) = self.gate(entry, packages, was) {
            eprintln!(
                "bbd: sandbox: uid {} is not held at the gate as it was: {undo:#}",
                entry.uid
            );
        }
        // The processes gone through before it failed, back as they were.
        if resolver::set(entry, was.1).is_err() {
            self.resolver_unknown.insert(entry.uid);
        } else {
            self.resolver_unknown.remove(&entry.uid);
        }
        Err(e)
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
        self.change(entry, packages, NOT_HELD, self.held_for(entry.uid, items))
    }

    fn disable(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        items: &Settings,
    ) -> anyhow::Result<()> {
        self.change(entry, packages, self.held_for(entry.uid, items), NOT_HELD)
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        before: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        let (was, wanted) = (
            self.held_for(entry.uid, before),
            self.held_for(entry.uid, after),
        );
        if was == wanted {
            return Ok(());
        }
        self.change(entry, packages, was, wanted)
    }

    fn refresh(&mut self, entry: &Entry, items: &Settings) -> anyhow::Result<()> {
        // A rule on a name added or removed changes what the app is given
        // at the resolver's socket.
        let (was, wanted) = (self.holding(entry.uid), self.held_for(entry.uid, items));
        if was == wanted && !self.resolver_unknown.contains(&entry.uid) {
            return Ok(());
        }
        let packages = self
            .shared
            .apps
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .get(&entry.uid)
            .map(|gated| gated.packages.clone());
        // Not held so far: the packages are needed for the storage mounts
        // only, which such an app has none of.
        self.change(entry, &packages.unwrap_or_default(), was, wanted)
    }

    fn resume(&mut self, apps: &[Resumed]) {
        // The directories are left alone: they were made when the app was
        // enabled, and cannot be reached before the user unlocks the device,
        // which is when the daemon usually starts.
        for app in apps {
            let (mounts, resolver) = self.held_for(app.entry.uid, &app.items);
            if !held((mounts, resolver)) {
                continue;
            }
            if let Err(e) = self.hold(&app.entry, &app.packages, mounts, resolver) {
                eprintln!(
                    "bbd: sandbox: uid {} is not held at the gate: {e:#}",
                    app.entry.uid
                );
                continue;
            }
            // In the processes that started while there was no daemon to
            // hold them, and in those that have the socket of the daemon
            // before, which nothing listens on any more.
            if resolver != Cover::None
                && let Err(e) = resolver::set(&app.entry, resolver)
            {
                eprintln!(
                    "bbd: sandbox: uid {} has the system's resolver: {e:#}",
                    app.entry.uid
                );
            }
        }
    }

    fn forget(&mut self, uid: u32) {
        self.release(uid);
        self.resolver_unknown.remove(&uid);
    }

    fn report(&self, status: &mut Status) {
        status.gated_uids.extend(
            self.shared
                .apps
                .read()
                .unwrap_or_else(|e| e.into_inner())
                .keys()
                .copied(),
        );
        let stats = lock(&self.shared.stats);
        status.gate = self.shared.open.load(Ordering::Relaxed);
        status.admitted = stats.admitted;
        status.failed = stats.failed;
        status.new_apps_stopped = stats.unknown;
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
    keeper: Keeper,
}

/// What the thread does with each process held.
struct Keeper {
    shared: Arc<Shared>,
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
    /// A new app, not in the sandbox yet: ended.
    New,
    /// The registered UID no longer belongs to the verified installation.
    Stale,
}

/// The thread cannot go on: it is in a mount namespace it could not leave.
#[derive(Debug)]
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
                if let Some(event) = read_event::<GateEvent>(&item) {
                    events.push(event);
                }
            }
            for event in events.drain(..) {
                let lost = self.keeper.admit(&event, &home, &cpuinfo).is_err();
                // Nothing waits any more: a later start is timed anew.
                let _ = self.closing.waiting.set(0, &0, 0);
                if lost {
                    return;
                }
                self.closing.shared.open.store(true, Ordering::Relaxed);
            }
        }
    }
}

impl Keeper {
    fn new(shared: Arc<Shared>, scratch: &Path) -> Self {
        Self {
            shared,
            kernel: Kernel::new(scratch),
            failures: FailureLog::default(),
        }
    }

    /// Give the stopped process of `event` its sandbox and let it go on, or
    /// kill it.
    fn admit(&mut self, event: &GateEvent, home: &File, cpuinfo: &str) -> Result<(), Lost> {
        let pid = event.pid as i32;
        let unknown = event.unknown != 0;
        // The process is stopped and cannot end by itself. Keep its event
        // until we know we have the right process or that it is gone.
        let process = loop {
            match Process::open(pid) {
                Ok(process) => break process,
                Err(e) if e.raw_os_error() == Some(libc::ESRCH) => {
                    if unknown {
                        self.tell_unknown(event.uid);
                    }
                    return Ok(());
                }
                Err(e) => {
                    self.failures.log(event.uid, &e.into());
                    // The kernel closes subsequent starts while the reader is
                    // unable to acquire a process descriptor.
                    self.shared.open.store(false, Ordering::Relaxed);
                    std::thread::sleep(Duration::from_millis(100));
                }
            }
        };
        let start = (u64::from(event.start_high) << 32) | u64::from(event.start_low);
        let hz = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
        let ticks = (u128::from(start) * hz as u128 / 1_000_000_000) as u64;
        loop {
            match process.signal(0) {
                Ok(()) => match crate::sensors::process_start(event.pid) {
                    Some(actual) if actual == ticks => break,
                    Some(_) => return Ok(()),
                    None => self.failures.log(
                        event.uid,
                        &anyhow::anyhow!("process generation unavailable"),
                    ),
                },
                Err(e) if e.raw_os_error() == Some(libc::ESRCH) => return Ok(()),
                Err(e) => self.failures.log(event.uid, &e.into()),
            }
            self.shared.open.store(false, Ordering::Relaxed);
            std::thread::sleep(Duration::from_millis(100));
        }
        let mut lost = false;
        let held = wait_stopped(pid)
            .and_then(|()| self.set_up(&process, event, home, cpuinfo, &mut lost))
            .and_then(|held| {
                if matches!(held, Held::Sandboxed | Held::NotOurs) {
                    process
                        .signal(libc::SIGCONT)
                        .context("continuing held process")?;
                }
                Ok(held)
            });
        self.finish(event, &process, held);
        if lost { Err(Lost) } else { Ok(()) }
    }

    fn finish(&mut self, event: &GateEvent, process: &Process, held: anyhow::Result<Held>) {
        let shared = &self.shared;
        match held {
            Ok(Held::Sandboxed) => {
                let held_us = monotonic_ns().saturating_sub(event.at_ns()) / 1000;
                lock(&shared.stats).admit(held_us);
                if let Some(told) = lock(&shared.admitted).as_ref() {
                    told(event.uid);
                }
            }
            Ok(Held::NotOurs) => {}
            ended => {
                let rescan = event.unknown != 0 || matches!(ended, Ok(Held::Stale));
                match ended {
                    Ok(Held::New) => lock(&shared.stats).unknown += 1,
                    Ok(_) => lock(&shared.stats).failed += 1,
                    Err(e) => {
                        lock(&shared.stats).failed += 1;
                        self.failures.log(event.uid, &e);
                    }
                }
                // Keep the descriptor until termination is confirmed or the
                // task is gone. A numeric PID cannot safely recover this case.
                while let Err(e) = process.signal(libc::SIGKILL) {
                    if e.raw_os_error() == Some(libc::ESRCH) {
                        break;
                    }
                    shared.open.store(false, Ordering::Relaxed);
                    self.failures.log(event.uid, &e.into());
                    std::thread::sleep(Duration::from_millis(100));
                }
                if rescan {
                    self.tell_unknown(event.uid);
                }
            }
        }
    }

    fn tell_unknown(&self, uid: u32) {
        if let Some(told) = lock(&self.shared.unknown).as_ref() {
            told(uid);
        }
    }

    fn set_up(
        &mut self,
        process: &Process,
        event: &GateEvent,
        home: &File,
        cpuinfo: &str,
        lost: &mut bool,
    ) -> anyhow::Result<Held> {
        let pid = event.pid as i32;
        let uid = event.uid;
        let stat = fs::read_to_string(format!("/proc/{pid}/stat")).context("reading its state")?;
        let (_, parent) = parse_stat(&stat).context("reading its state")?;
        // Only zygote starts apps. Something else that took the uid with
        // the same thread name is none of the sandbox's business; what
        // started it has to be known for that, though.
        let started_by =
            fs::read_link(format!("/proc/{parent}/exe")).context("reading what started it")?;
        let parent_status = fs::read_to_string(format!("/proc/{parent}/status"))?;
        if !is_zygote(&started_by) || real_uid(&parent_status) != Some(0) {
            return Ok(Held::NotOurs);
        }
        // A new app put in the sandbox since it was stopped is given it now.
        let Some(gated) = self
            .shared
            .apps
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .get(&uid)
            .cloned()
        else {
            return Ok(if event.unknown != 0 {
                Held::New
            } else {
                Held::NotOurs
            });
        };
        // Mounting in a namespace the process shares with zygote would
        // change what every app sees.
        use std::os::unix::fs::MetadataExt;
        let theirs =
            File::open(format!("/proc/{pid}/ns/mnt")).context("opening the app namespace")?;
        let zygote =
            File::open(format!("/proc/{parent}/ns/mnt")).context("opening the parent namespace")?;
        let identity = |file: &File| -> io::Result<(u64, u64)> {
            let stat = file.metadata()?;
            Ok((stat.dev(), stat.ino()))
        };
        let target = identity(&theirs)?;
        for protected in [&zygote, home, &File::open("/proc/1/ns/mnt")?] {
            anyhow::ensure!(
                target != identity(protected)?,
                "shared system mount namespace"
            );
        }
        let status = fs::read_to_string(format!("/proc/{pid}/status"))?;
        anyhow::ensure!(
            real_uid(&status) == Some(uid),
            "unexpected process credentials"
        );
        process.signal(0)?;
        match dirs::matches_install(
            Path::new("/data/user_de"),
            &gated.package,
            uid,
            &gated.install_id,
        ) {
            Ok(true) => {}
            Ok(false) => return Ok(Held::Stale),
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(Held::Stale),
            Err(e) => return Err(e.into()),
        }
        self.kernel.generation = format!("{}-{}", uid, gated.install_id);
        let files: &[(&str, &str)] = if gated.mounts.cpu_info {
            &[(mounts::CPUINFO, cpuinfo)]
        } else {
            &[]
        };

        if gated.resolver != Cover::None {
            // A mount on the resolver's socket must stay in this
            // namespace: in one that passes its mounts on, it would take
            // the resolver from every app.
            let mountinfo = fs::read_to_string(format!("/proc/{pid}/mountinfo"))
                .context("reading its mounts")?;
            anyhow::ensure!(
                resolver::spreads(&mountinfo) == Some(false),
                "its mounts are shared with others"
            );
        }
        enter(&theirs).context("entering its namespace")?;
        let mounted = mounts::apply(
            &mut self.kernel,
            uid,
            &gated.packages,
            files,
            gated.mounts.storage,
            gated.resolver,
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
    matches!(
        exe.to_str(),
        Some("/system/bin/app_process64" | "/system/bin/app_process32" | "/system/bin/app_process")
    )
}

fn real_uid(status: &str) -> Option<u32> {
    status
        .lines()
        .find_map(|line| line.strip_prefix("Uid:"))?
        .split_whitespace()
        .next()?
        .parse()
        .ok()
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
        assert!(!is_zygote(Path::new("/data/local/tmp/app_process64")));
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

    /// A keeper whose `on_unknown` collects the uids it is told.
    fn keeper(scratch: &Path) -> (Keeper, Arc<Shared>, Arc<Mutex<Vec<u32>>>) {
        let shared = Arc::new(Shared {
            apps: RwLock::default(),
            stats: Mutex::default(),
            open: AtomicBool::new(true),
            admitted: Mutex::new(None),
            unknown: Mutex::new(None),
        });
        let told = Arc::new(Mutex::new(Vec::new()));
        let collected = told.clone();
        *lock(&shared.unknown) = Some(Box::new(move |uid| lock(&collected).push(uid)));
        (Keeper::new(shared.clone(), scratch), shared, told)
    }

    fn new_app_start(pid: u32) -> GateEvent {
        let ticks = crate::sensors::process_start(pid).unwrap_or(0);
        let hz = unsafe { libc::sysconf(libc::_SC_CLK_TCK) } as u64;
        let start = (ticks * 1_000_000_000).div_ceil(hz);
        GateEvent {
            start_low: start as u32,
            start_high: (start >> 32) as u32,
            pid,
            uid: 1_010_230,
            at_ns_low: 0,
            at_ns_high: 0,
            unknown: 1,
        }
    }

    #[test]
    fn a_new_app_starting_is_ended_and_the_daemon_told() {
        let dir = std::env::temp_dir().join(format!("bbd-test-gate-new-{}", std::process::id()));
        let mut child = std::process::Command::new("sleep")
            .arg("30")
            .spawn()
            .unwrap();
        let process = Process::open(child.id() as i32).unwrap();
        process.signal(libc::SIGSTOP).unwrap();
        let (mut keeper, shared, told) = keeper(&dir);
        let home = File::open("/dev/null").unwrap();

        keeper.finish(&new_app_start(child.id()), &process, Ok(Held::New));
        assert!(process.exited(5000).unwrap());
        assert!(!child.wait().unwrap().success());
        assert_eq!(*lock(&told), [1_010_230]);
        let stats = lock(&shared.stats);
        assert_eq!((stats.unknown, stats.failed, stats.admitted), (1, 0, 0));
        drop(stats);

        // Killed already, by the kernel side: the daemon is told all the
        // same.
        keeper
            .admit(&new_app_start(i32::MAX as u32), &home, "")
            .unwrap();
        assert_eq!(lock(&told).len(), 2);
    }

    #[test]
    fn a_stale_gate_entry_ends_the_start_and_requests_a_scan() {
        let mut child = std::process::Command::new("sleep")
            .arg("30")
            .spawn()
            .unwrap();
        let process = Process::open(child.id() as i32).unwrap();
        process.signal(libc::SIGSTOP).unwrap();
        let (mut keeper, shared, told) = keeper(Path::new("/unused"));
        let mut event = new_app_start(child.id());
        // The kernel already held this UID for the previous installation.
        event.unknown = 0;

        keeper.finish(&event, &process, Ok(Held::Stale));

        assert!(process.exited(5000).unwrap());
        assert!(!child.wait().unwrap().success());
        assert_eq!(*lock(&told), [event.uid]);
        assert_eq!(lock(&shared.stats).failed, 1);
    }

    #[test]
    fn a_new_uid_taken_by_something_other_than_an_app_is_let_go() {
        let dir = std::env::temp_dir().join(format!("bbd-test-gate-other-{}", std::process::id()));
        let mut child = std::process::Command::new("sleep")
            .arg("30")
            .spawn()
            .unwrap();
        let held = Process::open(child.id() as i32).unwrap();
        held.signal(libc::SIGSTOP).unwrap();
        let (mut keeper, shared, told) = keeper(&dir);
        let home = File::open("/dev/null").unwrap();

        keeper.admit(&new_app_start(child.id()), &home, "").unwrap();
        // Let go, not ended: it was not started by zygote.
        assert!(lock(&told).is_empty());
        assert_eq!(lock(&shared.stats).unknown, 0);
        let state = |pid: u32| {
            fs::read_to_string(format!("/proc/{pid}/stat"))
                .ok()
                .and_then(|stat| parse_stat(&stat))
                .map(|(state, _)| state)
        };
        let deadline = Instant::now() + Duration::from_secs(5);
        while state(child.id()) == Some('T') && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_ne!(state(child.id()), Some('T'));
        held.signal(libc::SIGKILL).unwrap();
        child.wait().unwrap();
    }

    #[test]
    fn a_process_can_be_held_and_signalled_by_descriptor() {
        let mut child = std::process::Command::new("sleep")
            .arg("30")
            .spawn()
            .unwrap();
        let held = Process::open(child.id() as i32).unwrap();
        assert!(!held.exited(0).unwrap());
        held.signal(libc::SIGKILL).unwrap();
        assert!(held.exited(5000).unwrap());
        assert!(!child.wait().unwrap().success());
        assert!(held.exited(0).unwrap());
        // Gone: the signal goes nowhere, and a pid that is not there cannot
        // be opened.
        assert!(held.signal(libc::SIGKILL).is_err());
        assert!(Process::open(i32::MAX).is_err());
        assert_eq!(
            Process::open(0).err().and_then(|e| e.raw_os_error()),
            Some(libc::EINVAL)
        );
    }

    #[test]
    fn both_kernel_errors_for_a_nonleader_report_a_missing_process() {
        std::thread::scope(|scope| {
            let (send_tid, receive_tid) = std::sync::mpsc::channel();
            let (keep_alive, finish) = std::sync::mpsc::channel::<()>();
            scope.spawn(move || {
                send_tid.send(unsafe { libc::gettid() }).unwrap();
                let _ = finish.recv();
            });
            let tid = receive_tid.recv().unwrap();
            assert_ne!(tid as u32, std::process::id());
            for (errno, expected) in [
                (libc::EINVAL, libc::ESRCH),
                (libc::ENOENT, libc::ESRCH),
                (libc::EPERM, libc::EPERM),
            ] {
                let error = Process::normalize_open_error(tid, io::Error::from_raw_os_error(errno));
                assert_eq!(error.raw_os_error(), Some(expected), "input errno {errno}");
            }
            drop(keep_alive);
        });
    }

    #[test]
    fn a_leader_and_an_invalid_id_keep_the_original_open_error() {
        for pid in [std::process::id() as i32, 0, -1] {
            for errno in [libc::EINVAL, libc::ENOENT] {
                let error = Process::normalize_open_error(pid, io::Error::from_raw_os_error(errno));
                assert_eq!(error.raw_os_error(), Some(errno));
            }
        }
    }

    #[test]
    fn a_vanished_thread_is_confirmed_by_the_kernel() {
        for errno in [libc::EINVAL, libc::ENOENT] {
            let error =
                Process::normalize_open_error(i32::MAX, io::Error::from_raw_os_error(errno));
            assert_eq!(error.raw_os_error(), Some(libc::ESRCH));
        }
    }
}
