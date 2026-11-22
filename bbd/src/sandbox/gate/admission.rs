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

//! Give stopped processes their sandbox before allowing them to run.

use std::{
    fs::{self, File},
    io,
    os::{fd::AsRawFd, unix::fs::MetadataExt as _},
    path::Path,
    sync::{Arc, atomic::Ordering},
    time::{Duration, Instant},
};

use anyhow::Context as _;
use aya::maps::{Array, MapData, RingBuf};
use bb_common::{GATE_CLOSED, GATE_NEW, GATE_RECORD, GateEvent, read_event};

use super::{Gated, Resolver, Shared, recording_only};
use crate::{
    clock::clock_ns,
    log::{Limited, Policy, SANDBOX_INTERVAL},
    proc::{Process, parse_stat, process_start, real_uid},
    sandbox::{
        dirs,
        mounts::{self, Kernel},
        resolver::{self, Cover},
    },
    sync::{lock, read},
};

/// How long a process is waited for to come to a stop. It stops as it leaves
/// the system call it was caught in, which takes microseconds.
const STOP_WAIT: Duration = Duration::from_secs(2);

pub(super) fn start(
    ring: RingBuf<MapData>,
    waiting: Array<MapData, u64>,
    shared: &Arc<Shared>,
    scratch: &Path,
) -> anyhow::Result<()> {
    let (ready, started) = std::sync::mpsc::sync_channel(1);
    let thread = Thread {
        ring,
        closing: Closing {
            waiting,
            reset_errors: Limited::new(SANDBOX_INTERVAL, Policy::Shared),
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
    Ok(())
}

struct Thread {
    ring: RingBuf<MapData>,
    closing: Closing,
    keeper: Keeper,
}

/// What the thread does with each process held.
struct Keeper {
    shared: Arc<Shared>,
    kernel: Kernel,
    failures: Limited,
}

/// Closes the gate when the thread ends, however it ends: the kernel side
/// then kills the starts it would have held for nobody.
struct Closing {
    waiting: Array<MapData, u64>,
    reset_errors: Limited,
    shared: Arc<Shared>,
}

impl Drop for Closing {
    fn drop(&mut self) {
        self.shared.open.store(false, Ordering::Relaxed);
        if let Err(e) = self.waiting.set(0, &GATE_CLOSED, 0) {
            eprintln!("bbd: sandbox: cannot mark the gate closed in the kernel: {e:?}");
        }
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
                if event.kind == GATE_RECORD {
                    if self.keeper.record(&event, &home, &cpuinfo).is_err() {
                        return;
                    }
                    continue;
                }
                let lost = self.keeper.admit(&event, &home, &cpuinfo).is_err();
                // Nothing waits any more: a later start is timed anew.
                if let Err(e) = self.closing.waiting.set(0, &0, 0) {
                    self.closing.reset_errors.log(format_args!(
                        "bbd: sandbox: cannot reset the gate wait in the kernel: {e:?}"
                    ));
                }
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
            failures: Limited::new(SANDBOX_INTERVAL, Policy::Shared),
        }
    }

    /// Give the stopped process of `event` its sandbox and let it go on, or
    /// kill it.
    fn admit(&mut self, event: &GateEvent, home: &File, cpuinfo: &str) -> Result<(), Lost> {
        let pid = event.pid as i32;
        let unknown = event.kind == GATE_NEW;
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
                    log_start(&mut self.failures, event.uid, &e.into());
                    // The kernel closes subsequent starts while the reader is
                    // unable to acquire a process descriptor.
                    self.shared.open.store(false, Ordering::Relaxed);
                    std::thread::sleep(Duration::from_millis(100));
                }
            }
        };
        let ticks = start_ticks(event);
        loop {
            match process.signal(0) {
                Ok(()) => match process_start(event.pid) {
                    Some(actual) if actual == ticks => break,
                    Some(_) => return Ok(()),
                    None => log_start(
                        &mut self.failures,
                        event.uid,
                        &anyhow::anyhow!("process generation unavailable"),
                    ),
                },
                Err(e) if e.raw_os_error() == Some(libc::ESRCH) => return Ok(()),
                Err(e) => log_start(&mut self.failures, event.uid, &e.into()),
            }
            self.shared.open.store(false, Ordering::Relaxed);
            std::thread::sleep(Duration::from_millis(100));
        }
        let mut lost = false;
        let held = match wait_stopped(pid) {
            Ok(()) => self.set_up(&process, event, home, cpuinfo, &mut lost),
            // An undelivered auto-isolation stop cannot end a start unless
            // a required mount policy has since been installed.
            Err(_)
                if unknown
                    && read(&self.shared.apps)
                        .get(&event.uid)
                        .is_none_or(|app| recording_only((app.mounts, app.resolver))) =>
            {
                Ok(Held::NotOurs)
            }
            Err(e) => Err(e),
        };
        self.finish(event, &process, held);
        if lost { Err(Lost) } else { Ok(()) }
    }

    fn record(&mut self, event: &GateEvent, home: &File, cpuinfo: &str) -> Result<(), Lost> {
        if !self.shared.relay.alive() {
            return Ok(());
        }
        if let Ok(process) = Process::open(event.pid as i32)
            && process_start(event.pid) == Some(start_ticks(event))
            && process.signal(0).is_ok()
        {
            let mut lost = false;
            let _ = self.set_up(&process, event, home, cpuinfo, &mut lost);
            if lost {
                return Err(Lost);
            }
        }
        Ok(())
    }

    fn finish(&mut self, event: &GateEvent, process: &Process, held: anyhow::Result<Held>) {
        let held = match held {
            Ok(Held::New) if !read(&self.shared.auto).ends_queued_start(event.uid) => {
                Ok(Held::NotOurs)
            }
            Ok(Held::Stale) if read(&self.shared.auto).releases(event.uid) => Ok(Held::NotOurs),
            other => other,
        };
        let held = held.and_then(|held| {
            if matches!(held, Held::Sandboxed | Held::NotOurs) {
                process
                    .signal(libc::SIGCONT)
                    .context("continuing held process")?;
            }
            Ok(held)
        });
        let shared = &self.shared;
        match held {
            Ok(Held::Sandboxed) => {
                let held_us =
                    (clock_ns(libc::CLOCK_MONOTONIC) as u64).saturating_sub(event.at_ns()) / 1000;
                lock(&shared.stats).admit(held_us);
                if let Some(told) = lock(&shared.admitted).as_ref() {
                    told(event.uid);
                }
            }
            Ok(Held::NotOurs) => {}
            ended => {
                let rescan = event.kind == GATE_NEW || matches!(ended, Ok(Held::Stale));
                match ended {
                    Ok(Held::New) => lock(&shared.stats).unknown += 1,
                    Ok(_) => lock(&shared.stats).failed += 1,
                    Err(e) => {
                        lock(&shared.stats).failed += 1;
                        log_start(&mut self.failures, event.uid, &e);
                    }
                }
                // Keep the descriptor until termination is confirmed or the
                // task is gone. A numeric PID cannot safely recover this case.
                while let Err(e) = process.signal(libc::SIGKILL) {
                    if e.raw_os_error() == Some(libc::ESRCH) {
                        break;
                    }
                    shared.open.store(false, Ordering::Relaxed);
                    log_start(&mut self.failures, event.uid, &e.into());
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
        let gated = read(&self.shared.apps).get(&uid).cloned();
        // A queued hold may arrive after only recording is left. The
        // optional cover must not make that start depend on mount setup.
        if gated.as_ref().is_some_and(|app| {
            (event.kind == GATE_RECORD) != recording_only((app.mounts, app.resolver))
        }) {
            return Ok(Held::NotOurs);
        }
        let Some(parent) = check_started_by_zygote(pid)? else {
            return Ok(Held::NotOurs);
        };
        // A new app put in the sandbox since it was stopped is given it now.
        let Some(gated) = gated else {
            return Ok(if event.kind == GATE_NEW {
                Held::New
            } else {
                Held::NotOurs
            });
        };
        let result = (|| {
            // Mounting in a namespace shared with zygote would affect other apps.
            let theirs =
                File::open(format!("/proc/{pid}/ns/mnt")).context("opening the app namespace")?;
            let zygote = File::open(format!("/proc/{parent}/ns/mnt"))
                .context("opening the parent namespace")?;
            check_namespace(&theirs, &zygote, home)?;
            check_credentials(process, pid, uid)?;
            match dirs::matches_install(
                Path::new("/data/user_de"),
                &gated.package,
                uid,
                &gated.install_id,
            ) {
                Ok(true) => {}
                Ok(false) => return Ok((Held::Stale, false)),
                Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok((Held::Stale, false)),
                Err(e) => return Err(e.into()),
            }
            let covered = self.mount_for(event, &gated, &theirs, home, cpuinfo, lost)?;
            Ok((Held::Sandboxed, covered))
        })();
        if let Some(failed) = gated.relay_failed {
            failed.store(!matches!(result, Ok((_, true))), Ordering::Relaxed);
        }
        result.map(|(held, _)| held)
    }

    fn mount_for(
        &mut self,
        event: &GateEvent,
        gated: &Gated,
        theirs: &File,
        home: &File,
        cpuinfo: &str,
        lost: &mut bool,
    ) -> anyhow::Result<bool> {
        let pid = event.pid as i32;
        let uid = event.uid;
        self.kernel.generation = format!("{}-{}", uid, gated.install_id);
        let files: &[(&str, &str)] = if gated.mounts.cpu_info {
            &[(mounts::CPUINFO, cpuinfo)]
        } else {
            &[]
        };

        let recording = gated.resolver == Resolver::Record;
        let required = if recording {
            Cover::None
        } else if gated.resolver == Resolver::Names && !self.shared.relay.alive() {
            Cover::Null
        } else {
            gated.resolver.cover()
        };
        let safe = || -> anyhow::Result<()> {
            // A resolver cover must stay in this namespace.
            let mountinfo = fs::read_to_string(format!("/proc/{pid}/mountinfo"))
                .context("reading its mounts")?;
            anyhow::ensure!(
                resolver::spreads(&mountinfo) == Some(false),
                "its mounts are shared with others"
            );
            Ok(())
        };
        if required != Cover::None {
            safe()?;
        }
        let may_record = recording && self.shared.relay.alive() && safe().is_ok();
        if event.kind == GATE_RECORD && !may_record {
            return Ok(false);
        }
        enter(theirs).context("entering its namespace")?;
        let mounted = mounts::apply(
            &mut self.kernel,
            uid,
            &gated.packages,
            files,
            gated.mounts.storage,
            required,
        )
        .and_then(|()| {
            if !may_record {
                return Ok(required == Cover::Relay);
            }
            let apps = read(&self.shared.apps);
            let registered = apps.get(&uid).and_then(|app| app.relay_failed.as_ref());
            if !registered
                .zip(gated.relay_failed.as_ref())
                .is_some_and(|(current, expected)| Arc::ptr_eq(current, expected))
            {
                return Ok(false);
            }
            let serving = self.shared.relay.socket();
            serving.map_or(Ok(false), |id| {
                mounts::apply_resolver(Cover::Relay, true, |_| resolver::record_here(id))
            })
        });
        if let Err(e) = enter(home) {
            *lost = true;
            return Err(e).context("returning from its namespace");
        }
        mounted
    }
}

fn start_ticks(event: &GateEvent) -> u64 {
    let start = (u64::from(event.start_high) << 32) | u64::from(event.start_low);
    let hz = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
    (u128::from(start) * hz as u128 / 1_000_000_000) as u64
}

fn check_started_by_zygote(pid: i32) -> anyhow::Result<Option<i32>> {
    let stat = fs::read_to_string(format!("/proc/{pid}/stat")).context("reading its state")?;
    let (_, parent) = parse_stat(&stat)
        .and_then(|stat| stat.state_and_parent())
        .context("reading its state")?;
    // Only zygote starts apps. Something else that took the uid with
    // the same thread name is none of the sandbox's business; what
    // started it has to be known for that, though.
    let started_by =
        fs::read_link(format!("/proc/{parent}/exe")).context("reading what started it")?;
    let parent_status = fs::read_to_string(format!("/proc/{parent}/status"))?;
    Ok((is_zygote(&started_by) && real_uid(parent_status.as_bytes()) == Some(0)).then_some(parent))
}

fn check_namespace(theirs: &File, zygote: &File, home: &File) -> anyhow::Result<()> {
    let identity = |file: &File| -> io::Result<(u64, u64)> {
        let stat = file.metadata()?;
        Ok((stat.dev(), stat.ino()))
    };
    let target = identity(theirs)?;
    for protected in [zygote, home, &File::open("/proc/1/ns/mnt")?] {
        anyhow::ensure!(
            target != identity(protected)?,
            "shared system mount namespace"
        );
    }
    Ok(())
}

fn check_credentials(process: &Process, pid: i32, uid: u32) -> anyhow::Result<()> {
    let status = fs::read_to_string(format!("/proc/{pid}/status"))?;
    anyhow::ensure!(
        real_uid(status.as_bytes()) == Some(uid),
        "unexpected process credentials"
    );
    process.signal(0)?;
    Ok(())
}

/// Whether a program is zygote, which is `app_process` under one of its
/// names (`app_process64`, `app_process32`).
fn is_zygote(exe: &Path) -> bool {
    matches!(
        exe.to_str(),
        Some("/system/bin/app_process64" | "/system/bin/app_process32" | "/system/bin/app_process")
    )
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

/// Wait until the process has stopped.
fn wait_stopped(pid: i32) -> anyhow::Result<()> {
    let deadline = Instant::now() + STOP_WAIT;
    let mut waits = 0u32;
    loop {
        let state = fs::read_to_string(format!("/proc/{pid}/stat"))
            .ok()
            .and_then(|stat| parse_stat(&stat)?.state_and_parent())
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

fn log_start(log: &mut Limited, uid: u32, e: &anyhow::Error) {
    log.log(format_args!(
        "bbd: sandbox: a start of uid {uid} was stopped: {e:#}"
    ));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sandbox::{Sandbox, tests::Fixture};
    use crate::sync::write;

    use std::sync::{Mutex, RwLock, atomic::AtomicBool};

    #[test]
    fn only_app_process_counts_as_zygote() {
        assert!(is_zygote(Path::new("/system/bin/app_process64")));
        assert!(is_zygote(Path::new("/system/bin/app_process32")));
        assert!(!is_zygote(Path::new("/data/local/tmp/app_process64")));
        assert!(!is_zygote(Path::new("/system/bin/sh")));
        assert!(!is_zygote(Path::new("/system/bin/run-as")));
        assert!(!is_zygote(Path::new("/")));
    }

    /// A keeper whose `on_unknown` collects the uids it is told.
    fn keeper(scratch: &Path) -> (Keeper, Arc<Shared>, Arc<Mutex<Vec<u32>>>) {
        let shared = Arc::new(Shared {
            apps: RwLock::default(),
            auto: Arc::default(),
            stats: Mutex::default(),
            open: AtomicBool::new(true),
            relay: Default::default(),
            admitted: Mutex::new(None),
            unknown: Mutex::new(None),
        });
        let told = Arc::new(Mutex::new(Vec::new()));
        let collected = told.clone();
        *lock(&shared.unknown) = Some(Box::new(move |uid| lock(&collected).push(uid)));
        (Keeper::new(shared.clone(), scratch), shared, told)
    }

    fn new_app_start(pid: u32) -> GateEvent {
        let ticks = process_start(pid).unwrap_or(0);
        let hz = unsafe { libc::sysconf(libc::_SC_CLK_TCK) } as u64;
        let start = (ticks * 1_000_000_000).div_ceil(hz);
        GateEvent {
            start_low: start as u32,
            start_high: (start >> 32) as u32,
            pid,
            uid: 1_010_230,
            at_ns_low: 0,
            at_ns_high: 0,
            kind: GATE_NEW,
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

        let mut fixture = Fixture::new("gate-new");
        let mut sandbox = fixture.sandbox();
        sandbox
            .set_auto(&fixture.writer, &mut fixture.packages, true)
            .unwrap();
        *write(&shared.auto) = sandbox.auto_admission();

        keeper.finish(&new_app_start(child.id()), &process, Ok(Held::New));
        assert!(process.exited(5000).unwrap());
        assert!(!child.wait().unwrap().success());
        assert_eq!(*lock(&told), [1_010_230]);
        let stats = lock(&shared.stats);
        assert_eq!((stats.unknown, stats.failed, stats.admitted), (1, 0, 0));
        drop(stats);

        // The child can be gone by the time the thread opens its pidfd.
        // Recheck the package list even then.
        keeper
            .admit(&new_app_start(i32::MAX as u32), &home, "")
            .unwrap();
        assert_eq!(lock(&told).len(), 2);
    }

    #[test]
    fn a_queued_new_start_is_resumed_after_auto_isolation_releases_it() {
        let mut fixture = Fixture::new("gate-released");
        let user_data = fixture.dir.join("user_de");
        fs::create_dir_all(user_data.join("0")).unwrap();
        let mut sandbox = Sandbox::load(&fixture.writer, Vec::new(), Some(&user_data)).unwrap();
        sandbox
            .set_auto(&fixture.writer, &mut fixture.packages, true)
            .unwrap();
        fs::write(
            fixture.dir.join("packages.list"),
            "com.example.new 10230 0 /d default none 0 1 1 @null",
        )
        .unwrap();
        let mut child = std::process::Command::new("sleep")
            .arg("30")
            .spawn()
            .unwrap();
        let process = Process::open(child.id() as i32).unwrap();
        process.signal(libc::SIGSTOP).unwrap();
        wait_stopped(child.id() as i32).unwrap();
        let (mut keeper, shared, told) = keeper(&fixture.dir);
        let event = new_app_start(child.id());
        *write(&shared.auto) = sandbox.auto_admission();
        assert!(read(&shared.auto).ends_queued_start(event.uid));

        // No directory is available for the observed profile, so the scan
        // releases it even if this start is still queued for disposition.
        sandbox.auto_started(&fixture.writer, &mut fixture.packages, [event.uid].into());
        *write(&shared.auto) = sandbox.auto_admission();
        keeper.finish(&event, &process, Ok(Held::New));

        let stopped = || {
            fs::read_to_string(format!("/proc/{}/stat", child.id()))
                .ok()
                .and_then(|stat| parse_stat(&stat)?.state_and_parent())
                .is_some_and(|(state, _)| state == 'T')
        };
        let deadline = Instant::now() + Duration::from_secs(2);
        while stopped() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(1));
        }
        let resumed = !stopped() && !process.exited(0).unwrap();
        if child.try_wait().unwrap().is_none() {
            process.signal(libc::SIGKILL).unwrap();
            child.wait().unwrap();
        }
        assert!(resumed);
        assert!(lock(&told).is_empty());
        assert_eq!(lock(&shared.stats).unknown, 0);
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
        event.kind = bb_common::GATE_REQUIRED;

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
                .and_then(|stat| parse_stat(&stat)?.state_and_parent())
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
}
