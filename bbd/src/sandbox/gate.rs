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
    fs,
    os::unix::fs::PermissionsExt as _,
    path::Path,
    sync::{
        Arc, Mutex, RwLock,
        atomic::{AtomicBool, Ordering},
    },
};

use anyhow::Context as _;
use aya::maps::{Array, HashMap as BpfHashMap, MapData, MapError, RingBuf};

use super::{
    Effect, Entry, Resumed, Status, auto,
    destinations::Book,
    dirs::{self, MEDIA_RW},
    items::{Mounts, Settings},
    mounts,
    resolver::{self, Cover},
    target::Target,
};
use crate::{
    log::{Limited, Policy, SANDBOX_INTERVAL},
    sync::{lock, read, write},
};

mod admission;

/// Hold times kept for the 95th percentile `bbd sandbox status` reports.
const HOLDS_KEPT: usize = 256;

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

/// What the gate thread and the daemon's main loop share. Each update
/// takes a single call, so the guards can be recovered after a panic.
struct Shared {
    /// The uids held at the gate.
    apps: RwLock<HashMap<u32, Gated>>,
    auto: Arc<RwLock<auto::Admission>>,
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
    release_errors: Limited,
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
            auto: Arc::default(),
            stats: Mutex::default(),
            open: AtomicBool::new(true),
            admitted: Mutex::new(None),
            unknown: Mutex::new(None),
        });
        admission::start(ring, waiting, &shared, scratch)?;
        Ok(Self {
            uids,
            release_errors: Limited::new(SANDBOX_INTERVAL, Policy::Shared),
            shared,
            network: false,
            names: None,
            resolver_unknown: HashSet::new(),
        })
    }

    pub(super) fn auto_admission(&self) -> Arc<RwLock<auto::Admission>> {
        self.shared.auto.clone()
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
            let book = lock(book);
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
        read(&self.shared.apps)
            .get(&uid)
            .map_or(NOT_HELD, |gated| (gated.mounts, gated.resolver))
    }

    /// Stop holding starts of `uid`, if they were held.
    fn release(&mut self, uid: u32) {
        if let Err(e) = self.uids.remove(&uid)
            && !missing_gate_key(&e)
        {
            self.release_errors.log(format_args!(
                "bbd: sandbox: cannot remove uid {uid} from the gate: {e:?}"
            ));
        }
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

fn missing_gate_key(error: &MapError) -> bool {
    // Aya's hash-map delete passes the kernel's ENOENT through as a syscall error.
    matches!(error, MapError::KeyNotFound)
        || matches!(error, MapError::SyscallError(e) if e.io_error.raw_os_error() == Some(libc::ENOENT))
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
        let packages = read(&self.shared.apps)
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
        status
            .gated_uids
            .extend(read(&self.shared.apps).keys().copied());
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

#[cfg(test)]
mod tests {
    use std::io;

    use super::*;

    #[test]
    fn only_a_missing_gate_key_is_ignored() {
        let delete_error = |errno| {
            MapError::from(aya::sys::SyscallError {
                call: "bpf_map_delete_elem",
                io_error: io::Error::from_raw_os_error(errno),
            })
        };
        assert!(missing_gate_key(&MapError::KeyNotFound));
        assert!(missing_gate_key(&delete_error(libc::ENOENT)));
        assert!(!missing_gate_key(&delete_error(libc::EPERM)));
        assert!(!missing_gate_key(&delete_error(libc::EINVAL)));
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
}
