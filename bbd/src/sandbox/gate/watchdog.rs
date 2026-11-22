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

//! Own the stop journal and process dispositions independently of mount work.

use std::{
    collections::VecDeque,
    fs, io,
    sync::{Arc, atomic::Ordering},
    time::Duration,
};

use anyhow::Context as _;
use aya::maps::{Array, HashMap, Map, MapData, RingBuf};
use bb_common::{
    GATE_MISSED, GATE_NEW, GATE_RECORD, GATE_REQUIRED, GATE_STORAGE_READY, GATE_WAIT_NS, GateEvent,
    read_event,
};
use tokio::{
    io::{Interest, unix::AsyncFd},
    sync::{Notify, mpsc, oneshot},
};

use super::{
    Gated, Shared,
    admission::{Done, Held, Work, Worker, check_started_by_zygote, open_process},
    recording_only,
};
use crate::{
    clock::clock_ns,
    log::{Limited, Policy, SANDBOX_INTERVAL},
    proc::{Process, parse_stat, uid_of_pid},
    sandbox::{Sandbox, items::Part},
    sync::{lock, read},
};

pub(super) const PIN: &str = "/sys/fs/bpf/bb_gate_pending";
pub(super) type Journal = HashMap<MapData, [u32; 3], [u32; 4]>;

pub(super) struct Running {
    stop: Option<oneshot::Sender<()>>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl Drop for Running {
    fn drop(&mut self) {
        drop(self.stop.take());
        if let Some(thread) = self.thread.take()
            && thread.join().is_err()
        {
            eprintln!("bbd: sandbox: the gate watchdog panicked");
        }
    }
}

pub(super) fn start(
    ring: RingBuf<MapData>,
    mut open: Array<MapData, u32>,
    journal: Journal,
    shared: &Arc<Shared>,
    worker: Worker,
    storage_ready: Arc<Notify>,
) -> anyhow::Result<Running> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    let ring = {
        let _entered = runtime.enter();
        // SAFETY: the ring owns its descriptor for the life of the registration.
        unsafe { AsyncFd::register_with_interest(ring, Interest::READABLE) }
            .map_err(|e| e.into_parts().1)?
    };
    open.set(0, &1, 0)?;
    let (stop, stopped) = oneshot::channel();
    let mut watch = Watchdog {
        ring,
        open,
        journal,
        completion: Completion::new(shared.clone()),
        storage_ready,
    };
    let thread = std::thread::Builder::new()
        .name("gate".into())
        .spawn(move || runtime.block_on(watch.run(worker, stopped)))
        .context("starting the gate watchdog")?;
    Ok(Running {
        stop: Some(stop),
        thread: Some(thread),
    })
}

struct Watchdog {
    ring: AsyncFd<RingBuf<MapData>>,
    open: Array<MapData, u32>,
    journal: Journal,
    completion: Completion,
    storage_ready: Arc<Notify>,
}

impl Watchdog {
    async fn run(&mut self, mut worker: Worker, mut stopped: oneshot::Receiver<()>) {
        let mut queue = VecDeque::new();
        let mut busy: Option<Work> = None;
        let mut storage_changed = false;
        loop {
            // A worker that saw locked storage can still be placing its
            // stand-in when the key arrives.
            if busy.is_none() && std::mem::take(&mut storage_changed) {
                self.storage_ready.notify_one();
            }
            let deadline = next_deadline(busy.as_ref(), &queue);
            if deadline.is_some_and(|left| left.is_zero()) {
                break;
            }
            if busy.is_none()
                && let Some(event) = queue.pop_front()
            {
                let work = self.completion.work(event);
                if worker
                    .starts
                    .try_send(Work {
                        event,
                        policy: work.policy.clone(),
                    })
                    .is_err()
                {
                    break;
                }
                busy = Some(work);
                continue;
            }
            tokio::select! {
                biased;
                _ = &mut stopped => break,
                done = result_before_deadline(&mut worker.results, deadline) => {
                    let (Some(done), Some(work)) = (done, busy.take()) else { break };
                    if !self.finish_work(&worker, work, done) {
                        break;
                    }
                }
                ready = self.ring.readable_mut() => {
                    let mut ready = match ready {
                        Ok(ready) => ready,
                        Err(e) => {
                            eprintln!("bbd: sandbox: cannot read gate events: {e}");
                            break;
                        }
                    };
                    ready.clear_ready();
                    let ring = ready.get_inner_mut();
                    while let Some(item) = ring.next() {
                        let Some(event) = read_event::<GateEvent>(&item) else { continue };
                        match event.kind {
                            GATE_STORAGE_READY => storage_changed = true,
                            GATE_MISSED => self.completion.missed(&event),
                            GATE_RECORD => worker.record(self.completion.work(event)),
                            _ => queue.push_back(event),
                        }
                    }
                    match self.open.get(&0, 0) {
                        Ok(1) => {}
                        Ok(_) => break,
                        Err(e) => {
                            eprintln!("bbd: sandbox: cannot read the gate state: {e}");
                            break;
                        }
                    }
                }
            }
        }
        self.close();
        drop(worker);
        self.dispose_journal();
    }

    fn finish_work(&mut self, worker: &Worker, work: Work, done: Done) -> bool {
        let event = work.event;
        if let Err(e) = self.completion.complete_work(worker, work, done) {
            self.completion.log(event.uid, &e);
            return false;
        }
        self.forget(&event);
        true
    }

    fn dispose_journal(&mut self) {
        // Include starts reported after the last read, and ones reserved
        // by a probe just before the gate closed.
        match journal_events(&self.journal) {
            Ok(mut events) => {
                events.sort_unstable_by_key(GateEvent::at_ns);
                for event in events {
                    match self.completion.abandon(&event, Part::MountTimedOut) {
                        Ok(()) => self.forget(&event),
                        Err(e) => self.completion.log(event.uid, &e),
                    }
                }
            }
            Err(e) => eprintln!("bbd: sandbox: cannot read the gate journal: {e}"),
        }
        if self.journal.keys().next().is_none()
            && let Err(e) = fs::remove_file(PIN)
        {
            eprintln!("bbd: sandbox: cannot remove the empty gate journal: {e}");
        }
    }

    fn forget(&mut self, event: &GateEvent) {
        if let Err(e) = self.journal.remove(&event.process_key()) {
            self.completion.log(event.uid, &e.into());
        }
    }

    fn close(&mut self) {
        if self.completion.shared.open.swap(false, Ordering::AcqRel)
            && let Err(e) = self.open.set(0, &0, 0)
        {
            eprintln!("bbd: sandbox: cannot mark the gate closed in the kernel: {e:?}");
        }
    }
}

impl Drop for Watchdog {
    fn drop(&mut self) {
        self.close();
    }
}

fn remaining(event: &GateEvent) -> Duration {
    let now = clock_ns(libc::CLOCK_MONOTONIC) as u64;
    Duration::from_nanos(
        event
            .at_ns()
            .saturating_add(GATE_WAIT_NS)
            .saturating_sub(now),
    )
}

fn next_deadline(busy: Option<&Work>, queue: &VecDeque<GateEvent>) -> Option<Duration> {
    busy.into_iter()
        .map(|work| &work.event)
        .chain(queue.front())
        .map(remaining)
        .min()
}

async fn wait_until(remaining: Option<Duration>) {
    match remaining {
        Some(left) => tokio::time::sleep(left).await,
        None => std::future::pending().await,
    }
}

async fn result_before_deadline(
    results: &mut mpsc::UnboundedReceiver<Done>,
    deadline: Option<Duration>,
) -> Option<Done> {
    tokio::select! {
        biased;
        _ = wait_until(deadline) => None,
        done = results.recv() => done,
    }
}

pub(super) struct Completion {
    shared: Arc<Shared>,
    failures: Limited,
}

impl Completion {
    pub(super) fn new(shared: Arc<Shared>) -> Self {
        Self {
            shared,
            failures: Limited::new(SANDBOX_INTERVAL, Policy::Shared),
        }
    }

    fn work(&self, event: GateEvent) -> Work {
        Work {
            policy: read(&self.shared.apps).get(&event.uid).cloned(),
            event,
        }
    }

    pub(super) fn complete_work(
        &mut self,
        worker: &Worker,
        work: Work,
        done: Done,
    ) -> anyhow::Result<()> {
        let may_record = matches!(done.held, Ok(Held::Sandboxed));
        if let Some(process) = done.process {
            self.finish(
                &work.event,
                work.policy.as_ref(),
                &process,
                done.held,
                done.cause,
            )?;
        } else {
            self.abandon(&work.event, done.cause)?;
        }
        if may_record {
            // Recording can have changed while the required mounts were prepared.
            worker.record(self.work(work.event));
        }
        Ok(())
    }

    fn missed(&mut self, event: &GateEvent) {
        let work = self.work(*event);
        if let Some(policy) = work
            .policy
            .as_ref()
            .filter(|app| !recording_only((app.mounts, app.resolver)))
        {
            // A later successful launch does not give mounts to the
            // running process whose stop the kernel missed.
            lock(&policy.health).missed_stop();
            lock(&self.shared.stats).failed += 1;
        } else if work.policy.is_none() {
            self.tell_unknown(event.uid);
        }
    }

    fn abandon(&mut self, event: &GateEvent, cause: Part) -> anyhow::Result<()> {
        let work = self.work(*event);
        let Some(process) = open_process(event)? else {
            if event.kind == GATE_NEW && read(&self.shared.auto).ends_queued_start(event.uid) {
                self.tell_unknown(event.uid);
            }
            return Ok(());
        };
        if !remaining(event).is_zero() {
            // Closing can race a probe that has journaled its stop but
            // has yet to leave the syscall that delivers it.
            // Send the final disposition even if the stop was not observed.
            let _ = super::admission::wait_for_stop(event.pid as i32, remaining(event));
        }
        self.finish(
            event,
            work.policy.as_ref(),
            &process,
            Err(anyhow::anyhow!("admission unavailable")),
            cause,
        )
    }

    pub(super) fn finish(
        &mut self,
        event: &GateEvent,
        policy: Option<&Arc<Gated>>,
        process: &Process,
        held: anyhow::Result<Held>,
        cause: Part,
    ) -> anyhow::Result<()> {
        let shared = &self.shared;
        let mut rescan = false;
        let admitted = matches!(held, Ok(Held::Sandboxed));
        let signal = match held {
            Ok(Held::Sandboxed) => {
                let elapsed =
                    (clock_ns(libc::CLOCK_MONOTONIC) as u64).saturating_sub(event.at_ns());
                lock(&shared.stats).admit(elapsed / 1000);
                if let Some(policy) = policy {
                    lock(&policy.health).admitted();
                }
                libc::SIGCONT
            }
            Ok(Held::NotOurs) => libc::SIGCONT,
            Ok(Held::New) if read(&shared.auto).ends_queued_start(event.uid) => {
                rescan = true;
                lock(&shared.stats).unknown += 1;
                libc::SIGKILL
            }
            Ok(Held::New) => libc::SIGCONT,
            Ok(Held::Stale) if read(&shared.auto).releases(event.uid) => libc::SIGCONT,
            Ok(Held::Stale) => {
                rescan = true;
                lock(&shared.stats).failed += 1;
                self.failure_signal(event, policy, Some(Part::MountSetupFailed), |_| true)
            }
            Ok(Held::Refused) => {
                lock(&shared.stats).failed += 1;
                self.failure_signal(event, policy, None, |_| true)
            }
            Err(e) => {
                let signal = self.failure_signal(event, policy, Some(cause), verified_child);
                rescan = event.kind == GATE_NEW && read(&shared.auto).ends_queued_start(event.uid);
                lock(&shared.stats).failed += 1;
                self.log(event.uid, &e);
                signal
            }
        };
        signal_process(process, signal)?;
        if rescan {
            self.tell_unknown(event.uid);
        }
        if admitted && let Some(told) = lock(&self.shared.admitted).as_ref() {
            told(event.uid);
        }
        Ok(())
    }

    fn failure_signal(
        &self,
        event: &GateEvent,
        policy: Option<&Arc<Gated>>,
        cause: Option<Part>,
        verified: impl FnOnce(&GateEvent) -> bool,
    ) -> i32 {
        if policy
            .is_some_and(|policy| verified(event) && self.shared.refuse(event.uid, policy, cause))
        {
            libc::SIGKILL
        } else {
            libc::SIGCONT
        }
    }

    fn tell_unknown(&self, uid: u32) {
        if let Some(told) = lock(&self.shared.unknown).as_ref() {
            told(uid);
        }
    }

    fn log(&mut self, uid: u32, e: &anyhow::Error) {
        self.failures.log(format_args!(
            "bbd: sandbox: admission of uid {uid} failed: {e:#}"
        ));
    }
}

fn signal_process(process: &Process, signal: i32) -> anyhow::Result<()> {
    match process.signal(signal) {
        Ok(()) => Ok(()),
        Err(e) if e.raw_os_error() == Some(libc::ESRCH) => Ok(()),
        Err(e) => Err(e).context("disposing of a held start"),
    }
}

fn verified_child(event: &GateEvent) -> bool {
    uid_of_pid(event.pid) == Some(event.uid)
        && matches!(check_started_by_zygote(event.pid as i32), Ok(Some(_)))
}

fn journal_events(journal: &Journal) -> Result<Vec<GateEvent>, aya::maps::MapError> {
    journal
        .iter()
        .map(|row| {
            let ([pid, start_low, start_high], [uid, at_ns_low, at_ns_high, kind]) = row?;
            Ok(GateEvent {
                pid,
                start_low,
                start_high,
                uid,
                at_ns_low,
                at_ns_high,
                kind,
            })
        })
        .collect()
}

/// Only an exact, still-stopped generation in the previous daemon's
/// journal belongs to this recovery. Debugger and app stops are untouched.
pub(super) fn recover(sandbox: Option<&Sandbox>) -> anyhow::Result<()> {
    match fs::symlink_metadata(PIN) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(()),
        result => {
            result.context("locating the gate journal")?;
        }
    }
    let mut journal = Journal::try_from(Map::from_map_data(MapData::from_pin(PIN)?)?)?;
    for event in journal_events(&journal)? {
        if let Some(process) = open_process(&event)? {
            let stat = fs::read_to_string(format!("/proc/{}/stat", event.pid));
            // No signal may be sent without observing the old stop.
            let stopped = stat
                .as_deref()
                .ok()
                .and_then(parse_stat)
                .and_then(|stat| stat.state_and_parent())
                .is_some_and(|(state, _)| state == 'T');
            if stopped {
                let still_required = event.kind == GATE_REQUIRED
                    && sandbox.is_some_and(|sandbox| {
                        sandbox
                            .snapshot_read()
                            .get(&event.uid)
                            .is_some_and(|entry| {
                                sandbox.items(entry).mounts().any() && sandbox.owns(entry)
                            })
                    })
                    && verified_child(&event);
                signal_process(
                    &process,
                    if still_required {
                        libc::SIGKILL
                    } else {
                        libc::SIGCONT
                    },
                )?;
            }
        }
        journal.remove(&event.process_key())?;
    }
    fs::remove_file(PIN).context("removing the old gate journal")
}

#[cfg(test)]
mod tests {
    use super::super::{
        Resolver,
        admission::tests::{keeper, new_app_start},
    };
    use super::*;
    use crate::sync::write;
    use std::path::Path;

    #[tokio::test]
    async fn a_stopped_start_is_resumed_when_the_worker_exits_or_misses_the_deadline() {
        for worker_exits in [true, false] {
            let (_, shared, _) = keeper(Path::new("/unused"));
            let mut child = std::process::Command::new("sleep")
                .arg("30")
                .spawn()
                .unwrap();
            let process = Process::open_proc(child.id()).unwrap();
            process.signal(libc::SIGSTOP).unwrap();
            super::super::admission::wait_stopped(child.id() as i32).unwrap();
            let mut event = new_app_start(child.id());
            let now = clock_ns(libc::CLOCK_MONOTONIC) as u64;
            let at = now.saturating_sub(GATE_WAIT_NS - 20_000_000);
            event.at_ns_low = at as u32;
            event.at_ns_high = (at >> 32) as u32;

            let mut stale = event;
            stale.start_low = stale.start_low.wrapping_add(1_000_000_000);
            assert!(open_process(&stale).unwrap().is_none());
            let (send, mut receive) = mpsc::unbounded_channel();
            let _worker = (!worker_exits).then_some(send);
            let mut completion = Completion::new(shared);
            let busy = completion.work(event);
            let deadline = next_deadline(Some(&busy), &VecDeque::new());
            assert!(
                result_before_deadline(&mut receive, deadline)
                    .await
                    .is_none()
            );
            let resumed = completion.abandon(&event, Part::MountTimedOut).is_ok()
                && !still_stopped(child.id())
                && child.try_wait().unwrap().is_none();
            let _ = child.kill();
            let _ = child.wait();
            assert!(resumed);
        }
    }

    fn still_stopped(pid: u32) -> bool {
        let deadline = std::time::Instant::now() + Duration::from_secs(1);
        loop {
            let stopped = fs::read_to_string(format!("/proc/{pid}/stat"))
                .ok()
                .and_then(|stat| parse_stat(&stat)?.state_and_parent())
                .is_some_and(|(state, _)| state == 'T');
            if !stopped || std::time::Instant::now() >= deadline {
                return stopped;
            }
            std::thread::sleep(Duration::from_millis(1));
        }
    }

    #[test]
    fn repeated_mount_failures_refuse_starts_report_the_cause_and_skip_sweeps() {
        use crate::sandbox::{Entry, Resumed, Status, items::Settings, sweep::Restart};

        let (_, shared, _) = keeper(Path::new("/unused"));
        let event = new_app_start(0);
        let app = Resumed {
            entry: Entry {
                uid: event.uid,
                package: "com.example.app".into(),
                install_id: "install".into(),
                enabled: true,
                android_id: String::new(),
                saved_ops: String::new(),
                saved_permissions: String::new(),
                created: 0,
                settings: String::new(),
            },
            items: Settings::default(),
            packages: Vec::new(),
        };
        let gated = Arc::new(Gated {
            package: app.entry.package.clone(),
            install_id: app.entry.install_id.clone(),
            packages: Vec::new(),
            mounts: app.items.mounts(),
            resolver: Resolver::None,
            health: Default::default(),
            relay_failed: None,
        });
        write(&shared.apps).insert(event.uid, gated.clone());
        let completion = Completion::new(shared.clone());
        let mut restart = Restart::new(super::super::MountReadiness(shared.clone()));
        let mut sweeps = 0;
        let apps = std::slice::from_ref(&app);
        let decision = |cause| {
            let work = completion.work(event);
            completion.failure_signal(&event, work.policy.as_ref(), cause, |_| true)
        };
        let status = || {
            let mut status = Status {
                gate: true,
                ..Status::default()
            };
            shared.report_mounts(&mut status);
            status
        };

        assert_eq!(decision(Some(Part::MountSetupFailed)), libc::SIGKILL);
        for _ in 0..5 {
            assert!(lock(&gated.health).refuses_start());
            assert_eq!(decision(None), libc::SIGKILL);
            let status = status();
            assert_eq!(status.mount_failures[&event.uid], Part::MountSetupFailed);
            assert!(status.mount_launch_refused.contains(&event.uid));
            assert!(!status.gated_uids.contains(&event.uid));
            restart.resume_with(apps, |_, _| sweeps += 1);
        }
        assert_eq!(sweeps, 0);

        lock(&gated.health).recover();
        assert!(!lock(&gated.health).refuses_start());
        assert_eq!(status().mount_failures[&event.uid], Part::MountSetupFailed);
        restart.resume_with(apps, |_, _| sweeps += 1);
        assert_eq!(sweeps, 0);

        assert_eq!(decision(Some(Part::MountTimedOut)), libc::SIGKILL);
        assert!(lock(&gated.health).refuses_start());
        assert_eq!(status().mount_failures[&event.uid], Part::MountTimedOut);

        write(&shared.apps).remove(&event.uid);
        assert_eq!(decision(None), libc::SIGCONT);
    }
}
