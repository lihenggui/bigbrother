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

use std::{sync::mpsc::Receiver, time::Duration};

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
    appops, clock::now, control, probes::dropped_events, proc::process_alive, spool::Spool,
    store::Writer, tracer::Tracer,
};

const PRUNE_INTERVAL: Duration = Duration::from_secs(6 * 3600);
/// How long a write waits for the sensor list to be read, when what it
/// would write needs it: short, since the app's queries wait for the
/// write. What still waits is written with the next one.
const SENSOR_LIST_WAIT: Duration = Duration::from_millis(300);
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

            self.on_flush(request);
        }
        Ok(())
    }

    fn on_flush(&mut self, request: Option<UnixStream>) {
        self.tracer.drain_pending(self.events.ring.get_mut());
        self.tracer.counters.dropped = self.events.dropped();
        self.tracer.expire(now() - RESULT_TIMEOUT_SECS);
        self.tracer.prune_isolated_owners();
        self.tracer.ingest_provider_events(Spool::collect);
        self.tracer.take_refused_lookups();
        self.permissions.take_permission_uses(&mut self.tracer);
        self.tracer.settle_sensors(SENSOR_LIST_WAIT);
        self.drop_cleared_net();

        // A failed write (disk full, database busy) keeps the batch for the
        // next attempt; it is not a reason to stop recording.
        let written = match request {
            Some(_) => self
                .tracer
                .writer
                .flush_for_query(&self.tracer.counters, now()),
            None => self.tracer.writer.flush(&self.tracer.counters, now()),
        };
        if let Err(e) = written {
            self.tracer
                .write_errors
                .log(format_args!("bbd: could not write records: {e:#}"));
        }
        if let Some(stream) = request {
            control::answer(&stream);
        }
    }

    fn drop_cleared_net(&mut self) {
        // What the sandbox cleared of an app's network is not written from
        // what was held of it.
        while let Ok(uid) = self.net_cleared.try_recv() {
            if let Err(e) = self.tracer.writer.net_clear(uid) {
                self.tracer
                    .write_errors
                    .log(format_args!("bbd: could not clear network records: {e:#}"));
            }
        }
    }

    pub(super) fn finish(&mut self) -> anyhow::Result<()> {
        let tracer = &mut self.tracer;
        tracer.drain_pending(self.events.ring.get_mut());
        tracer.ingest_provider_events(Spool::collect);
        tracer.take_refused_lookups();
        tracer.settle_sensors(Duration::from_secs(2));
        tracer.counters.dropped = self.events.dropped();
        tracer.expire(i64::MAX);
        tracer.writer.flush(&tracer.counters, now())?;
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

pub(super) struct PermissionUses {
    readings: Receiver<Option<Vec<appops::Entry>>>,
    tracker: appops::Tracker,
    coverage: i64,
}

impl PermissionUses {
    pub(super) fn start(writer: &Writer) -> anyhow::Result<Self> {
        // The system's own permission usage records, read periodically.
        writer.set_meta("coverage_appops", COVERAGE_PENDING)?;
        Ok(Self {
            coverage: COVERAGE_PENDING,
            readings: super::helpers::spawn_appops_reader(),
            tracker: appops::Tracker::new(now()),
        })
    }

    fn take_permission_uses(&mut self, tracer: &mut Tracer) {
        while let Ok(reading) = self.readings.try_recv() {
            // A reading at all shows the records can be read here. Written
            // when that changes, not with every reading.
            let covered = i64::from(reading.is_some());
            if covered != self.coverage && tracer.write_coverage("coverage_appops", covered) {
                self.coverage = covered;
            }
            if let Some(entries) = reading {
                tracer.record_permission_uses(self.tracker.update(&entries));
            }
        }
    }
}
