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
    sync::{Arc, atomic::Ordering, mpsc},
    time::{Duration, Instant},
};

use anyhow::Context as _;
use bb_common::{GATE_NEW, GATE_RECORD, GateEvent};
use tokio::sync::mpsc as async_mpsc;

use super::{Gated, Resolver, Shared, recording_only};
use crate::{
    proc::{Process, parse_stat, real_uid, uid_of_pid},
    sandbox::{
        dirs,
        items::Part,
        mounts::{self, Kernel},
        names::{Serving, SocketId},
        resolver::{self, Cover},
    },
    sync::{lock, read},
};

/// How long a process is waited for to come to a stop. It stops as it leaves
/// the system call it was caught in, which takes microseconds.
const STOP_WAIT: Duration = Duration::from_secs(2);

pub(super) struct Work {
    pub event: GateEvent,
    pub policy: Option<Arc<Gated>>,
}

pub(super) struct Done {
    pub process: Option<Process>,
    pub held: anyhow::Result<Held>,
    pub cause: Part,
    lost: bool,
}

pub(super) struct Worker {
    pub starts: mpsc::SyncSender<Work>,
    pub results: async_mpsc::UnboundedReceiver<Done>,
    recording: Option<mpsc::SyncSender<Work>>,
}

impl Worker {
    pub(super) fn record(&self, mut work: Work) {
        let Some(policy) = work
            .policy
            .as_ref()
            .filter(|app| app.resolver == Resolver::Record)
        else {
            return;
        };
        let failed = policy.relay_failed.clone();
        work.event.kind = GATE_RECORD;
        if !self
            .recording
            .as_ref()
            .is_some_and(|queue| queue.try_send(work).is_ok())
            && let Some(failed) = failed
        {
            // Recording can miss this start while its own worker is busy or gone.
            failed.store(true, Ordering::Relaxed);
        }
    }
}

pub(super) fn start(shared: &Arc<Shared>, scratch: &Path) -> anyhow::Result<Worker> {
    let (ready, started) = mpsc::sync_channel(1);
    let (starts, work) = mpsc::sync_channel(1);
    let (done, results) = async_mpsc::unbounded_channel();
    let mut keeper = Keeper::new(shared.clone(), scratch);
    std::thread::Builder::new()
        .name("mount-gate".into())
        .spawn(move || {
            let (home, cpuinfo) = match setup() {
                Ok(setup) => setup,
                Err(e) => {
                    // The owner can already have dropped the startup reply.
                    let _ = ready.send(Err(e));
                    return;
                }
            };
            if ready.send(Ok(())).is_err() {
                return;
            }
            while let Ok(work) = work.recv() {
                let result = keeper.prepare(&work, &home, &cpuinfo);
                let lost = result.lost;
                if done.send(result).is_err() || lost {
                    break;
                }
            }
        })
        .context("starting the mount worker")?;
    started
        .recv()
        .context("the mount worker ended as it started")??;
    Ok(Worker {
        starts,
        results,
        recording: start_recording(shared, scratch),
    })
}

fn start_recording(shared: &Arc<Shared>, scratch: &Path) -> Option<mpsc::SyncSender<Work>> {
    let (starts, work) = mpsc::sync_channel(16);
    let mut keeper = Keeper::new(shared.clone(), scratch);
    let worker = std::thread::Builder::new()
        .name("recording-mounts".into())
        .spawn(move || {
            let home = match own_namespace() {
                Ok(home) => home,
                Err(e) => {
                    eprintln!("bbd: sandbox: cannot prepare recording namespaces: {e:#}");
                    return;
                }
            };
            while let Ok(work) = work.recv() {
                // There is no admission result to wait for on this lane.
                if keeper.prepare(&work, &home, "").lost {
                    eprintln!("bbd: sandbox: the recording worker could not leave a namespace");
                    break;
                }
            }
        });
    match worker {
        Ok(_) => Some(starts),
        Err(e) => {
            eprintln!("bbd: sandbox: cannot start the recording mount worker: {e}");
            None
        }
    }
}

fn setup() -> anyhow::Result<(File, String)> {
    let home = own_namespace()?;
    let real = fs::read_to_string(mounts::CPUINFO).context("reading /proc/cpuinfo")?;
    Ok((home, mounts::cpuinfo_template(&real)))
}

pub(super) struct Keeper {
    shared: Arc<Shared>,
    kernel: Kernel,
}

pub(super) enum Held {
    Sandboxed,
    NotOurs,
    New,
    Stale,
    Refused,
}

impl Keeper {
    fn new(shared: Arc<Shared>, scratch: &Path) -> Self {
        Self {
            shared,
            kernel: Kernel::new(scratch),
        }
    }

    fn prepare(&mut self, work: &Work, home: &File, cpuinfo: &str) -> Done {
        let event = &work.event;
        let cause = Part::MountStartFailed;
        let process = match open_process(event) {
            Ok(Some(process)) => process,
            Ok(None) => {
                return Done {
                    process: None,
                    held: Ok(Held::NotOurs),
                    cause,
                    lost: false,
                };
            }
            Err(e) => {
                return Done {
                    process: None,
                    held: Err(e),
                    cause,
                    lost: false,
                };
            }
        };
        let mut lost = false;
        let (held, cause) = if event.kind != GATE_RECORD
            && let Err(e) = wait_stopped(event.pid as i32)
        {
            (Err(e), cause)
        } else {
            (
                self.set_up(&process, work, home, cpuinfo, &mut lost),
                Part::MountSetupFailed,
            )
        };
        Done {
            process: Some(process),
            held,
            cause,
            lost,
        }
    }

    fn set_up(
        &mut self,
        process: &Process,
        work: &Work,
        home: &File,
        cpuinfo: &str,
        lost: &mut bool,
    ) -> anyhow::Result<Held> {
        let event = &work.event;
        let pid = event.pid as i32;
        let uid = event.uid;
        let gated = work.policy.as_deref();
        // A queued hold may arrive after only recording is left. The
        // optional cover must not make that start depend on mount setup.
        let recording = event.kind == GATE_RECORD;
        if gated.is_some_and(|app| match recording {
            true => app.resolver != Resolver::Record,
            false => recording_only((app.mounts, app.resolver)),
        }) {
            return Ok(Held::NotOurs);
        }
        let Some(parent) = check_started_by_zygote(pid)? else {
            return Ok(Held::NotOurs);
        };
        // A new app put in the sandbox since it was stopped is given it now.
        let Some(gated) = gated else {
            return Ok(
                if event.kind == GATE_NEW && uid_of_pid(event.pid) == Some(uid) {
                    Held::New
                } else {
                    Held::NotOurs
                },
            );
        };
        let result = (|| {
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
            if lock(&gated.health).refuses_start() {
                return Ok((Held::Refused, false));
            }
            // Mounting in a namespace shared with zygote would affect other apps.
            let theirs =
                File::open(format!("/proc/{pid}/ns/mnt")).context("opening the app namespace")?;
            let zygote = File::open(format!("/proc/{parent}/ns/mnt"))
                .context("opening the parent namespace")?;
            check_namespace(&theirs, &zygote, home)?;
            let covered = self.mount_for(event, gated, &theirs, home, cpuinfo, lost)?;
            Ok((Held::Sandboxed, covered))
        })();
        if (recording || gated.resolver == Resolver::Names)
            && let Some(failed) = &gated.relay_failed
        {
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
        let recording = event.kind == GATE_RECORD;
        let required = match gated.resolver {
            Resolver::Record => Cover::None,
            Resolver::Names if !self.shared.relay.alive() => Cover::Null,
            resolver => resolver.cover(),
        };
        if recording || required != Cover::None {
            let mountinfo = fs::read_to_string(format!("/proc/{}/mountinfo", event.pid))
                .context("reading its mounts")?;
            anyhow::ensure!(
                resolver::spreads(&mountinfo) == Some(false),
                "its mounts are shared with others"
            );
        }
        enter(theirs).context("entering its namespace")?;
        let mounted = if recording {
            self.record_resolver(event.uid, gated)
        } else {
            self.mount_required(event.uid, gated, cpuinfo, required)
        };
        if let Err(e) = enter(home) {
            *lost = true;
            return Err(e).context("returning from its namespace");
        }
        mounted
    }
    fn mount_required(
        &mut self,
        uid: u32,
        gated: &Gated,
        cpuinfo: &str,
        required: Cover,
    ) -> anyhow::Result<bool> {
        self.kernel.generation = format!("{uid}-{}", gated.install_id);
        let files: &[(&str, &str)] = if gated.mounts.cpu_info {
            &[(mounts::CPUINFO, cpuinfo)]
        } else {
            &[]
        };
        mounts::apply(
            &mut self.kernel,
            uid,
            &gated.packages,
            files,
            gated.mounts.storage,
            required,
        )?;
        Ok(required == Cover::Relay)
    }

    fn record_resolver(&self, uid: u32, gated: &Gated) -> anyhow::Result<bool> {
        let current = || read(&self.shared.apps).get(&uid).cloned();
        if !current().is_some_and(|app| std::ptr::eq(app.as_ref(), gated)) {
            return Ok(false);
        }
        cover_recording(
            &self.shared.relay,
            |serving| {
                current().is_some_and(|app| {
                    app.install_id == gated.install_id
                        && (app.resolver == Resolver::Names
                            || (app.resolver == Resolver::Record && serving))
                })
            },
            resolver::record_here,
            resolver::uncover_recording_here,
        )
    }
}

fn cover_recording(
    relay: &Serving,
    keep_cover: impl FnOnce(bool) -> bool,
    bind: impl FnOnce(SocketId) -> io::Result<()>,
    uncover: impl FnOnce(SocketId) -> io::Result<()>,
) -> anyhow::Result<bool> {
    // The relay lock covers only the identity read. A mount can block in
    // the kernel, and status, policy changes and shutdown all need that lock.
    let serving = *relay.socket();
    let Some(id) = serving else { return Ok(false) };
    if !mounts::apply_resolver(Cover::Relay, true, |_| bind(id))? {
        return Ok(false);
    }
    let still_serving = *relay.socket() == Some(id);
    if keep_cover(still_serving) {
        return Ok(still_serving);
    }
    // A policy change may already have removed or replaced the cover.
    uncover(id).context("cleaning up a stale recording cover")?;
    Ok(false)
}

/// Verify the generation after opening the descriptor so recovery cannot
/// signal a process that took over the numeric PID.
pub(super) fn open_process(event: &GateEvent) -> anyhow::Result<Option<Process>> {
    let process = match Process::open(event.pid as i32) {
        Ok(process) => process,
        Err(e) if e.raw_os_error() == Some(libc::ESRCH) => return Ok(None),
        Err(_) => match Process::open_proc(event.pid) {
            Ok(process) => process,
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(e).context("opening the held process"),
        },
    };
    let stat = match fs::read_to_string(format!("/proc/{}/stat", event.pid)) {
        Ok(stat) => stat,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e).context("reading the held process generation"),
    };
    let actual = parse_stat(&stat)
        .and_then(|stat| stat.start_time())
        .context("reading the held process generation")?;
    if actual != start_ticks(event) {
        return Ok(None);
    }
    Ok(Some(process))
}

fn start_ticks(event: &GateEvent) -> u64 {
    let start = (u64::from(event.start_high) << 32) | u64::from(event.start_low);
    let hz = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
    (u128::from(start) * hz as u128 / 1_000_000_000) as u64
}

pub(super) fn check_started_by_zygote(pid: i32) -> anyhow::Result<Option<i32>> {
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
pub(super) fn wait_stopped(pid: i32) -> anyhow::Result<()> {
    wait_for_stop(pid, STOP_WAIT)
}

pub(super) fn wait_for_stop(pid: i32, timeout: Duration) -> anyhow::Result<()> {
    let deadline = Instant::now() + timeout;
    let mut waits = 0u32;
    loop {
        // A disappearing proc entry is handled as an exited process.
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

#[cfg(test)]
mod recording_tests;

#[cfg(test)]
pub(super) mod tests {
    use super::super::{Resolver, watchdog::Completion};
    use super::*;
    use crate::sandbox::items::Mounts;
    use crate::sync::write;
    use crate::{
        proc::process_start,
        sandbox::{Sandbox, tests::Fixture},
    };

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
    pub(in crate::sandbox::gate) fn keeper(
        scratch: &Path,
    ) -> (Keeper, Arc<Shared>, Arc<Mutex<Vec<u32>>>) {
        keeper_with_relay(scratch, Serving::default())
    }

    pub(super) fn keeper_with_relay(
        scratch: &Path,
        relay: Serving,
    ) -> (Keeper, Arc<Shared>, Arc<Mutex<Vec<u32>>>) {
        let shared = Arc::new(Shared {
            apps: RwLock::default(),
            auto: Arc::default(),
            stats: Mutex::default(),
            open: AtomicBool::new(true),
            relay,
            admitted: Mutex::new(None),
            unknown: Mutex::new(None),
        });
        let told = Arc::new(Mutex::new(Vec::new()));
        let collected = told.clone();
        *lock(&shared.unknown) = Some(Box::new(move |uid| lock(&collected).push(uid)));
        (Keeper::new(shared.clone(), scratch), shared, told)
    }

    pub(in crate::sandbox::gate) fn new_app_start(pid: u32) -> GateEvent {
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
        let (_, shared, told) = keeper(&dir);
        let mut completion = Completion::new(shared.clone());

        let mut fixture = Fixture::new("gate-new");
        let mut sandbox = fixture.sandbox();
        sandbox
            .set_auto(&fixture.writer, &mut fixture.packages, true)
            .unwrap();
        *write(&shared.auto) = sandbox.auto_admission();

        completion
            .finish(
                &new_app_start(child.id()),
                None,
                &process,
                Ok(Held::New),
                Part::MountSetupFailed,
            )
            .unwrap();
        assert!(exited(&process));
        assert!(!child.wait().unwrap().success());
        assert_eq!(*lock(&told), [1_010_230]);
        let stats = lock(&shared.stats);
        assert_eq!((stats.unknown, stats.failed, stats.admitted), (1, 0, 0));
        drop(stats);
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
        let (_, shared, told) = keeper(&fixture.dir);
        let mut completion = Completion::new(shared.clone());
        let event = new_app_start(child.id());
        *write(&shared.auto) = sandbox.auto_admission();
        assert!(read(&shared.auto).ends_queued_start(event.uid));

        // No directory is available for the observed profile, so the scan
        // releases it even if this start is still queued for disposition.
        sandbox.auto_started(&fixture.writer, &mut fixture.packages, [event.uid].into());
        *write(&shared.auto) = sandbox.auto_admission();
        completion
            .finish(
                &event,
                None,
                &process,
                Ok(Held::New),
                Part::MountSetupFailed,
            )
            .unwrap();

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
        let (_, shared, told) = keeper(Path::new("/unused"));
        let mut completion = Completion::new(shared.clone());
        let mut event = new_app_start(child.id());
        // The kernel already held this UID for the previous installation.
        event.kind = bb_common::GATE_REQUIRED;

        let policy = Arc::new(Gated {
            package: "com.example.app".into(),
            install_id: "install".into(),
            packages: Vec::new(),
            mounts: Mounts {
                storage: true,
                cpu_info: false,
            },
            resolver: Resolver::None,
            health: Default::default(),
            relay_failed: None,
        });
        write(&shared.apps).insert(event.uid, policy.clone());
        completion
            .finish(
                &event,
                Some(&policy),
                &process,
                Ok(Held::Stale),
                Part::MountSetupFailed,
            )
            .unwrap();

        assert!(exited(&process));
        assert!(!child.wait().unwrap().success());
        assert_eq!(*lock(&told), [event.uid]);
        assert_eq!(lock(&shared.stats).failed, 1);
    }

    fn exited(process: &Process) -> bool {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            match process.exited(remaining.as_millis() as i32) {
                Ok(exited) => return exited,
                Err(e) if e.kind() == io::ErrorKind::Interrupted && !remaining.is_zero() => {}
                Err(e) => panic!("waiting for the child to exit: {e}"),
            }
        }
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

        let event = new_app_start(child.id());
        let work = Work {
            event,
            policy: None,
        };
        let done = keeper.prepare(&work, &home, "");
        Completion::new(shared.clone())
            .finish(&event, None, &done.process.unwrap(), done.held, done.cause)
            .unwrap();
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
