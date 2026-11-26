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

//! Drain the kernel ring and write records on the daemon's runtime.

use std::{
    sync::mpsc::Receiver,
    time::{Duration, Instant},
};

use anyhow::Context as _;
use aya::maps::{MapData, PerCpuArray, RingBuf};
use tokio::{
    io::{Interest, unix::AsyncFd},
    net::{UnixListener, UnixStream},
    signal::unix::{SignalKind, signal},
    time::{MissedTickBehavior, interval},
};

use super::{BinderCatalog, COVERAGE_PENDING, FLUSH_INTERVAL, SensorCheck, next_request};
use crate::{
    appops,
    clock::now,
    control,
    helper::{Bridge, InitialUsage},
    probes::dropped_events,
    proc::process_alive,
    spool::Spool,
    store::Writer,
    tracer::Tracer,
};

const PRUNE_INTERVAL: Duration = Duration::from_secs(6 * 3600);
/// How long a write waits for the sensor list to be read, when what it
/// would write needs it: short, since the app's queries wait for the
/// write. What still waits is written with the next one.
const SENSOR_LIST_WAIT: Duration = Duration::from_millis(300);
const USAGE_QUERY_WAIT: Duration = Duration::from_millis(300);
const USAGE_SHUTDOWN_WAIT: Duration = Duration::from_millis(500);
const APPOPS_POLL_MIN_INTERVAL: Duration = Duration::from_secs(30);
/// How long an opened file waits for its syscall result before being
/// recorded as successful anyway (the result event can be lost).
const RESULT_TIMEOUT_SECS: i64 = 10;

pub(super) struct KernelEvents {
    pub(super) ring: AsyncFd<RingBuf<MapData>>,
    dropped: PerCpuArray<MapData, u64>,
}

impl KernelEvents {
    pub(super) fn open(ebpf: &mut aya::Ebpf) -> anyhow::Result<Self> {
        let dropped: PerCpuArray<MapData, u64> =
            PerCpuArray::try_from(ebpf.take_map("DROPPED").context("DROPPED map missing")?)?;
        let ring = RingBuf::try_from(ebpf.take_map("EVENTS").context("EVENTS map missing")?)?;
        // SAFETY: the RingBuf owns its fd, which stays open and unchanged for as
        // long as the AsyncFd owns the RingBuf.
        let ring = unsafe { AsyncFd::register_with_interest(ring, Interest::READABLE) }?;
        Ok(Self { ring, dropped })
    }

    /// Events the kernel side lost, summed over CPUs.
    pub(super) fn dropped(&self) -> u64 {
        dropped_events(&self.dropped)
    }
}

pub(super) struct Recording {
    pub(super) tracer: Tracer,
    pub(super) events: KernelEvents,
    pub(super) permissions: PermissionUses,
    pub(super) net_cleared: Receiver<u32>,
}

impl Recording {
    pub(super) async fn run_loop(
        &mut self,
        requests: Option<&UnixListener>,
        retention_days: u32,
        binder: &mut BinderCatalog,
    ) -> anyhow::Result<()> {
        let mut flush_tick = interval(FLUSH_INTERVAL);
        flush_tick.set_missed_tick_behavior(MissedTickBehavior::Delay);
        let mut prune_tick = interval(PRUNE_INTERVAL);
        prune_tick.tick().await; // first tick fires immediately; already pruned above
        let mut sigint = signal(SignalKind::interrupt())?;
        let mut sigterm = signal(SignalKind::terminate())?;
        let mut sensor_check = SensorCheck::default();

        loop {
            sensor_check.arm(self.tracer.sensors.any_on());
            // Every branch that does not end in a flush continues the loop.
            let request = tokio::select! {
                _ = sigint.recv() => break,
                _ = sigterm.recv() => break,
                _ = flush_tick.tick() => None,
                // Sessions whose process has ended end now, and are written
                // with the next write. Sensors turned on since that did not
                // wake the daemon (the kernel side wakes it at most once a
                // second for them) are taken in first.
                _ = sensor_check.tick(), if sensor_check.armed() => {
                    self.tracer.drain_pending(self.events.ring.get_mut());
                    self.tracer.sensors.check_processes(now(), process_alive);
                    continue;
                }
                // The device's transaction codes are known: watch the services
                // that need them now, not at the next write.
                loaded = async { binder.loader.as_mut().expect("guarded").await },
                    if binder.loader.is_some() =>
                {
                    binder.catalog_loaded(&mut self.tracer, loaded);
                    continue;
                }
                Some(reading) = self.permissions.initial.recv() => {
                    self.permissions.ingest_initial(&mut self.tracer, reading);
                    continue;
                }
                request = next_request(requests) => match request {
                    Some(stream) => Some(stream),
                    None => continue,
                },
                _ = prune_tick.tick() => {
                    if let Err(e) = self.tracer.writer.prune(retention_days, now()) {
                        eprintln!("bbd: could not prune old records: {e:#}");
                    }
                    continue;
                }
                // The kernel side's buffer is filling up.
                guard = self.events.ring.readable_mut() => {
                    let mut guard = guard?;
                    if self.tracer.drain_batch(guard.get_inner_mut()) { guard.clear_ready(); }
                    continue;
                }
            };

            self.on_flush(request).await;
        }
        Ok(())
    }

    async fn on_flush(&mut self, request: Option<UnixStream>) {
        self.tracer.drain_pending(self.events.ring.get_mut());
        self.tracer.counters.dropped = self.events.dropped();
        flush_records(
            &mut self.tracer,
            &mut self.permissions,
            &self.net_cleared,
            request.is_some(),
            now,
        )
        .await;
        if let Some(stream) = request {
            control::answer(&stream);
        }
    }

    pub(super) async fn finish(&mut self) -> anyhow::Result<()> {
        let tracer = &mut self.tracer;
        tracer.drain_pending(self.events.ring.get_mut());
        tracer.ingest_provider_events(Spool::collect);
        tracer.take_refused_lookups();
        tracer.settle_sensors(Duration::from_secs(2));
        tracer.counters.dropped = self.events.dropped();
        tracer.expire(i64::MAX);
        while let Ok(reading) = self.permissions.initial.try_recv() {
            self.permissions.ingest_initial(tracer, reading);
        }
        self.permissions.read(tracer, FlushKind::Shutdown).await;
        tracer.flush(false, now())?;
        // Sensors still on when it stopped were on at least until now; the next
        // run ends them here if their apps have ended by then.
        tracer.writer.set_meta("stopped_at", now())?;
        let c = &tracer.counters;
        eprintln!(
            "bbd: stopped; seen={} kept={} unresolved={} dropped={}",
            c.seen, c.kept, c.unresolved, c.dropped
        );
        Ok(())
    }
}

async fn flush_records(
    tracer: &mut Tracer,
    permissions: &mut PermissionUses,
    net_cleared: &Receiver<u32>,
    query: bool,
    now: impl Fn() -> i64,
) {
    tracer.expire(now() - RESULT_TIMEOUT_SECS);
    tracer.prune_isolated_owners();
    tracer.ingest_provider_events(Spool::collect);
    tracer.take_refused_lookups();
    permissions
        .read(
            tracer,
            if query {
                FlushKind::Query
            } else {
                FlushKind::Periodic
            },
        )
        .await;
    tracer.settle_sensors(SENSOR_LIST_WAIT);
    drop_cleared_net(tracer, net_cleared);

    // A failed write keeps the batch for the next attempt.
    if let Err(e) = tracer.flush(query, now()) {
        tracer
            .write_errors
            .log(format_args!("bbd: could not write records: {e:#}"));
    }
}

fn drop_cleared_net(tracer: &mut Tracer, net_cleared: &Receiver<u32>) {
    // What the sandbox cleared of an app's network is not written from
    // what was held of it.
    while let Ok(uid) = net_cleared.try_recv() {
        if let Err(e) = tracer.writer.net_clear(uid) {
            tracer
                .write_errors
                .log(format_args!("bbd: could not clear network records: {e:#}"));
        }
    }
}

#[derive(Clone, Copy)]
enum FlushKind {
    Periodic,
    Query,
    Shutdown,
}

#[derive(Default)]
struct PollSchedule {
    last: Option<Instant>,
    activity: u64,
}

impl PollSchedule {
    fn wait(
        &mut self,
        kind: FlushKind,
        now: Instant,
        dirty: bool,
        activity: u64,
    ) -> Option<Duration> {
        let wait = match kind {
            FlushKind::Query => USAGE_QUERY_WAIT,
            FlushKind::Shutdown => USAGE_SHUTDOWN_WAIT,
            FlushKind::Periodic => {
                let active = activity != self.activity
                    && self
                        .last
                        .is_none_or(|last| now.duration_since(last) >= APPOPS_POLL_MIN_INTERVAL);
                if !dirty && !active {
                    return None;
                }
                USAGE_QUERY_WAIT
            }
        };
        self.last = Some(now);
        self.activity = activity;
        Some(wait)
    }
}

pub(super) struct PermissionUses {
    bridge: Bridge,
    initial: InitialUsage,
    tracker: appops::Tracker,
    coverage: i64,
    schedule: PollSchedule,
}

#[cfg(test)]
mod tests;

impl PermissionUses {
    pub(super) fn start(
        writer: &Writer,
        bridge: Bridge,
        initial: InitialUsage,
    ) -> anyhow::Result<Self> {
        writer.set_meta("coverage_appops", COVERAGE_PENDING)?;
        Ok(Self {
            bridge,
            initial,
            coverage: COVERAGE_PENDING,
            tracker: appops::Tracker::new(now()),
            schedule: PollSchedule::default(),
        })
    }

    async fn read(&mut self, tracer: &mut Tracer, kind: FlushKind) {
        if let Some(reading) = self.bridge.take_late_usage() {
            self.ingest(tracer, Some(reading));
        }
        let wait = self.schedule.wait(
            kind,
            Instant::now(),
            self.bridge.dirty(),
            tracer.user_activity,
        );
        if let Some(wait) = wait
            && let Ok(reading) = self.bridge.poll(wait).await
        {
            self.ingest(tracer, Some(reading));
        }
    }

    fn ingest_initial(&mut self, tracer: &mut Tracer, reading: Option<Vec<appops::Entry>>) {
        if reading.is_some() {
            self.schedule.last = Some(Instant::now());
        }
        self.ingest(tracer, reading);
    }

    fn ingest(&mut self, tracer: &mut Tracer, reading: Option<Vec<appops::Entry>>) {
        let covered = i64::from(reading.is_some());
        if covered != self.coverage && tracer.write_coverage("coverage_appops", covered) {
            self.coverage = covered;
        }
        if let Some(entries) = reading {
            tracer.record_permission_uses(self.tracker.update(&entries));
        }
    }
}
