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

//! Supervise one Android runtime and route the three framework protocols.

mod process;
mod queries;

use std::{
    io::{self, BufRead as _, BufReader},
    process::Child,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread::{self, JoinHandle, Thread},
    time::{Duration, Instant},
};

use tokio::sync::{mpsc, watch};

use crate::{appops, backoff::Backoff, sync::lock};

pub(crate) use process::Pipe;
use queries::{Queries, Ready};

const READY_WAIT: Duration = Duration::from_secs(4);
const RESTART_BACKOFF: Backoff = Backoff {
    first: Duration::from_secs(10),
    max: Duration::from_secs(60),
    healthy: Some(Duration::from_secs(600)),
};

/// The sandbox owns its protocol state. The bridge owns process lifetime.
pub(crate) trait Guard: Send + Sync {
    fn attach(&self, supervisor: Thread);
    fn connected(&self, pipe: Pipe);
    fn receive(&self, line: &str);
    fn watchdog(&self) -> Result<Option<Instant>, ()>;
    fn disconnected(&self, first_failure: bool);
    fn take_hurry(&self) -> bool;
}

struct Shared {
    queries: Mutex<Queries>,
    ready: watch::Sender<Ready>,
    dirty: AtomicBool,
    stopping: AtomicBool,
}

#[derive(Clone)]
pub(crate) struct Bridge(Arc<Shared>);

impl Bridge {
    pub(crate) fn new() -> Self {
        Self(Arc::new(Shared {
            queries: Mutex::default(),
            ready: watch::Sender::new(Ready::default()),
            dirty: AtomicBool::new(false),
            stopping: AtomicBool::new(false),
        }))
    }

    pub(crate) fn dirty(&self) -> bool {
        self.0.dirty.load(Ordering::Acquire)
    }
}

pub(super) type InitialUsage = mpsc::Receiver<Option<Vec<appops::Entry>>>;

pub(crate) struct Supervisor {
    thread: Option<JoinHandle<()>>,
    initial: tokio::task::JoinHandle<()>,
    bridge: Bridge,
}

impl Supervisor {
    pub(crate) fn start(guard: Option<Arc<dyn Guard>>) -> (Self, Bridge, InitialUsage) {
        Self::start_with(guard, RESTART_BACKOFF, process::spawn)
    }

    fn start_with(
        guard: Option<Arc<dyn Guard>>,
        backoff: Backoff,
        spawn: impl FnMut() -> anyhow::Result<Child> + Send + 'static,
    ) -> (Self, Bridge, InitialUsage) {
        let bridge = Bridge::new();
        let (send, readings) = mpsc::channel(2);
        let initial = tokio::spawn(queries::initial_usage(bridge.clone(), send));
        let supervised = bridge.clone();
        let thread =
            thread::spawn(move || supervise(&supervised.0, guard.as_deref(), backoff, spawn));
        (
            Self {
                thread: Some(thread),
                initial,
                bridge: bridge.clone(),
            },
            bridge,
            readings,
        )
    }
}

impl Drop for Supervisor {
    fn drop(&mut self) {
        self.initial.abort();
        self.bridge.0.stopping.store(true, Ordering::Release);
        if let Some(thread) = self.thread.take() {
            thread.thread().unpark();
            if thread.join().is_err() {
                eprintln!("bbd: framework helper supervisor panicked");
            }
        }
    }
}

fn supervise(
    shared: &Shared,
    guard: Option<&dyn Guard>,
    backoff: Backoff,
    mut spawn: impl FnMut() -> anyhow::Result<Child>,
) {
    if let Some(guard) = guard {
        guard.attach(thread::current());
    }
    let mut failures = 0;
    let mut last = String::new();
    while !shared.stopping.load(Ordering::Acquire) {
        // An explicit command expedites one launch.
        if let Some(guard) = guard {
            guard.take_hurry();
        }
        let started = Instant::now();
        let ended = match spawn() {
            Ok(mut child) => supervise_child(shared, guard, &mut child, READY_WAIT),
            Err(e) => e,
        };
        failures = backoff.failures_after_run(failures, started.elapsed());
        if let Some(guard) = guard {
            guard.disconnected(failures == 1);
        }
        if shared.stopping.load(Ordering::Acquire) {
            break;
        }
        let reason = ended.to_string();
        if reason != last {
            eprintln!("bbd: framework helper: {reason}");
            last = reason;
        }
        let until = Instant::now() + backoff.delay(failures);
        while !shared.stopping.load(Ordering::Acquire) && !guard.is_some_and(Guard::take_hurry) {
            let left = until.saturating_duration_since(Instant::now());
            if left.is_zero() {
                break;
            }
            thread::park_timeout(left);
        }
    }
}

fn supervise_child(
    shared: &Shared,
    guard: Option<&dyn Guard>,
    child: &mut Child,
    ready_wait: Duration,
) -> anyhow::Error {
    let input = child.stdin.take().expect("spawned with a piped stdin");
    let pipe = match Pipe::new(input) {
        Ok(pipe) => pipe,
        Err(e) => {
            process::kill_helper(child);
            return e.into();
        }
    };
    let output = child.stdout.take().expect("spawned with a piped stdout");
    let stderr = child.stderr.take().expect("spawned with a piped stderr");
    let ended = AtomicBool::new(false);
    let supervisor = thread::current();
    let reason = thread::scope(|scope| {
        // Framework diagnostics can name packages; only drain them.
        scope.spawn(move || io::copy(&mut BufReader::new(stderr), &mut io::sink()));
        scope.spawn(|| {
            // A read error ends a session just like EOF.
            for line in BufReader::new(output).lines().map_while(Result::ok) {
                shared.receive(&pipe, guard, &supervisor, &line);
            }
            ended.store(true, Ordering::Release);
            supervisor.unpark();
        });

        let reason = watch_child(shared, guard, &ended, Instant::now() + ready_wait);
        process::kill_helper(child);
        reason
    });
    shared.reset();
    anyhow::anyhow!(reason)
}

fn watch_child(
    shared: &Shared,
    guard: Option<&dyn Guard>,
    ended: &AtomicBool,
    ready_until: Instant,
) -> &'static str {
    loop {
        if ended.load(Ordering::Acquire) || shared.stopping.load(Ordering::Acquire) {
            return "the helper ended";
        }
        let mut deadline = None;
        if !shared.ready.borrow().catalog {
            if Instant::now() >= ready_until {
                return "the catalog did not become ready";
            }
            deadline = Some(ready_until);
        }
        if let Some(guard) = guard {
            match guard.watchdog() {
                Ok(until) => deadline = deadline.into_iter().chain(until).min(),
                Err(()) => return "the guard stopped responding",
            }
        }
        if let Some(until) = deadline {
            thread::park_timeout(until.saturating_duration_since(Instant::now()));
        } else {
            thread::park();
        }
    }
}

impl Shared {
    fn receive(&self, pipe: &Pipe, guard: Option<&dyn Guard>, supervisor: &Thread, line: &str) {
        match line {
            "ready v1 catalog" => {
                let mut queries = lock(&self.queries);
                if queries.input.is_none() {
                    queries.input = Some(pipe.clone());
                    self.ready.send_modify(|ready| ready.catalog = true);
                    supervisor.unpark();
                }
            }
            "ready v1 usage" if self.ready.borrow().catalog => {
                self.ready.send_if_modified(|ready| {
                    if ready.usage {
                        return false;
                    }
                    ready.usage = true;
                    true
                });
            }
            "ready v1 guard" if self.ready.borrow().usage => {
                if let Some(guard) = guard {
                    guard.connected(pipe.clone());
                }
            }
            "usage event dirty" => self.dirty.store(true, Ordering::Release),
            _ => {
                if let Some(line) = line.strip_prefix("guard ") {
                    if let Some(guard) = guard {
                        guard.receive(line);
                    }
                } else if let Some(line) = line.strip_prefix("catalog ") {
                    lock(&self.queries).receive(queries::Domain::Catalog, line, &self.dirty);
                } else if let Some(line) = line.strip_prefix("usage ") {
                    lock(&self.queries).receive(queries::Domain::Usage, line, &self.dirty);
                }
            }
        }
    }

    fn reset(&self) {
        lock(&self.queries).reset(&self.dirty);
        self.ready.send_modify(|ready| {
            *ready = Ready {
                generation: ready.generation + 1,
                ..Ready::default()
            };
        });
    }
}

#[cfg(test)]
mod tests;
