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

//! Start recording, establish coverage and coordinate shutdown.

mod event_loop;
mod helpers;
mod sandbox_host;

use std::{
    path::Path,
    time::{Duration, Instant},
};

use anyhow::Context as _;
use tokio::{
    net::{UnixListener, UnixStream},
    task::JoinHandle,
    time::MissedTickBehavior,
};

use crate::{
    binder::Catalog,
    clock::now,
    control,
    probes::{self, BinderWatch, Probes},
    proc::{process_alive, uid_of_pid},
    selftest, sensors,
    store::Writer,
    tracer::Tracer,
};

use self::{
    event_loop::{KernelEvents, PermissionUses, Recording},
    helpers::spawn_catalog_loader,
    sandbox_host::SandboxThread,
};

/// How often what the kernel and the hook hold is taken in and written. A
/// write costs about the same whether it holds one record or a thousand, and
/// the query commands ask for one before they read (see `control`), so this
/// need not be often. The kernel side wakes the daemon sooner if its buffer
/// is filling up, and events carry the time they happened, so they can wait.
const FLUSH_INTERVAL: Duration = Duration::from_secs(30);
/// How often, while a sensor session is open, its process is checked. A
/// session whose process has ended ends when the process was last found
/// running, so this is the most of it that is lost when an app is killed
/// or swiped away, which often happens seconds after it turned a sensor on.
/// A check reads one small /proc file per such process and writes nothing;
/// with no session open there is no timer at all.
const SENSOR_PROCESS_CHECK: Duration = Duration::from_secs(5);
/// How long before a listener that failed to accept is tried again.
const ACCEPT_RETRY: Duration = Duration::from_secs(1);

/// Value of a `coverage_*` entry while it is not yet known whether the
/// feature works on this device: neither 1 (recording) nor 0 (can't).
const COVERAGE_PENDING: i64 = -1;

pub struct Options<'a> {
    pub db: &'a Path,
    pub packages_list: &'a str,
    pub retention_days: u32,
    /// Also print each recorded event to stdout.
    pub verbose: bool,
    /// Have the kernel time the eBPF programs and report the totals on exit.
    pub profile: bool,
    /// Print every event that reaches userspace and is then discarded, to
    /// show what the in-kernel filter could have dropped instead.
    pub explain_discards: bool,
    /// Collect what the LSPosed hook recorded. Off for a diagnostic run next
    /// to the real daemon, which would otherwise lose those records to it.
    pub ingest_provider_events: bool,
    /// Run the sandbox. Off for a diagnostic run next to the real daemon:
    /// two daemons would both stop, and mount for, every starting app.
    pub sandbox: bool,
}

pub async fn run(opts: Options<'_>) -> anyhow::Result<()> {
    let writer = Writer::open(opts.db)?;
    let pruned = writer.prune(opts.retention_days, now())?;
    writer.forget_system_paths(|path| crate::rules::topic_of(path).is_some())?;
    let sensors = adopt_sensor_sessions(&writer)?;
    writer.set_meta("started_at", now())?;

    // Held for the daemon's lifetime: timing stays on while this fd is open.
    let _stats = if opts.profile {
        Some(
            aya::sys::enable_stats(aya::sys::Stats::RunTime)
                .context("enabling BPF run-time stats")?,
        )
    } else {
        None
    };

    let mut probes = probes::load_probes()?;
    let functions = probes
        .features
        .iter()
        .filter_map(|(_, attached)| attached.as_ref().ok())
        .flatten()
        .filter(|(program, _)| *program != "file_open")
        .count();

    let permissions = PermissionUses::start(&writer)?;
    let mut binder = BinderCatalog::start(&mut probes.ebpf, &writer)?;
    let mut events = KernelEvents::open(&mut probes.ebpf)?;

    eprintln!(
        "bbd: tracing {functions} syscall functions; db={} retention={}d pruned={pruned}",
        opts.db.display(),
        opts.retention_days
    );

    let mut tracer = Tracer::new(
        opts.packages_list,
        writer,
        sensors,
        opts.verbose,
        opts.explain_discards,
        opts.ingest_provider_events,
    );
    run_probe_selfcheck(&mut tracer, &mut events, &probes)?;
    watch_fixed_services(&mut tracer, binder.watch.as_mut());
    let (sandbox, net_cleared) = sandbox_host::start(&opts, &mut probes, &mut tracer);

    // Queries ask for what is held to be written first (see `control`).
    let requests = match control::listen(opts.db) {
        Ok(listener) => Some(listener),
        Err(e) => {
            eprintln!("bbd: warning: queries will not wait for records to be written: {e:#}");
            None
        }
    };
    let mut recording = Recording {
        tracer,
        events,
        permissions,
        net_cleared,
    };
    let outcome = recording
        .run_loop(requests.as_ref(), opts.retention_days, &mut binder)
        .await;
    let stopped = shutdown(&opts, &probes, &mut binder, sandbox, &mut recording).await;
    outcome.and(stopped)
}

fn adopt_sensor_sessions(writer: &Writer) -> anyhow::Result<sensors::Sensors> {
    // Sensors the previous run saw turned on and not off. When it was last
    // up: when it stopped or, if it did not get to say so, last wrote.
    let mut sensors = sensors::Sensors::new(sensors::dumpsys);
    let last_up = writer
        .meta("stopped_at")?
        .max(writer.meta("last_flush")?)
        .unwrap_or(0);
    sensors.adopt(
        writer.open_sensor_sessions()?,
        |pid, start| process_alive(pid, start).then(|| uid_of_pid(pid)).flatten(),
        last_up,
        Instant::now(),
        now(),
    );
    Ok(sensors)
}

fn run_probe_selfcheck(
    tracer: &mut Tracer,
    events: &mut KernelEvents,
    probes: &Probes,
) -> anyhow::Result<()> {
    // A probe can attach and never fire (see `selftest`): what is covered
    // is what fires. The events of other processes meanwhile are handled
    // as usual. Drained first, so that the buffer has room for the child's
    // events and only drops while it runs count.
    let checking = Instant::now();
    tracer.drain_pending(events.ring.get_mut());
    let dropped_before = events.dropped();
    let (made, child) = selftest::exercise();
    tracer.selftest = selftest::Seen::of(child);
    tracer.drain_pending(events.ring.get_mut());
    let lost = events.dropped() > dropped_before;
    let seen = std::mem::take(&mut tracer.selftest);
    let states = selftest::judge(&probes.features, made, seen, lost);
    for ((feature, _), (_, state)) in probes.features.iter().zip(&states) {
        match state {
            selftest::State::Silent(function) => eprintln!(
                "bbd: warning: not recording {}: the probe on {function} attached but did not fire",
                feature.name
            ),
            selftest::State::Unchecked(function) => eprintln!(
                "bbd: warning: could not check whether the {} probe on *{function} fires",
                feature.name
            ),
            selftest::State::Fires | selftest::State::NotAttached => {}
        }
        tracer.writer.set_meta(
            &format!("coverage_{}", feature.name),
            i64::from(state.covered()),
        )?;
    }
    eprintln!(
        "bbd: probe self-check: {} of {} features fire ({} ms)",
        states
            .iter()
            .filter(|(_, state)| *state == selftest::State::Fires)
            .count(),
        states.len(),
        checking.elapsed().as_millis()
    );
    Ok(())
}

fn watch_fixed_services(tracer: &mut Tracer, map: Option<&mut BinderWatch>) {
    if let Some(map) = map {
        let fixed = Catalog::parse("");
        match probes::watch(map, fixed.map_entries()) {
            Ok(()) => {
                tracer.catalog = Some(fixed);
                tracer.sensors.watching(Instant::now());
            }
            Err(e) => eprintln!("bbd: warning: not recording sensor use: {e:#}"),
        }
    }
}

struct BinderCatalog {
    watch: Option<BinderWatch>,
    loader: Option<JoinHandle<Option<Catalog>>>,
}

impl BinderCatalog {
    fn start(ebpf: &mut aya::Ebpf, writer: &Writer) -> anyhow::Result<Self> {
        // Calls to system services. The tracepoint is attached now. Services
        // with fixed transaction codes (the sensor service) are watched at once;
        // the others once the device's codes are known, which takes a running
        // app_process and so a system that has come up. `coverage_binder` turns
        // 1 at that point.
        writer.set_meta("coverage_binder", COVERAGE_PENDING)?;
        let watch = match probes::attach_binder(ebpf) {
            Ok(map) => Some(map),
            Err(e) => {
                eprintln!("bbd: warning: not recording service calls: {e:#}");
                writer.set_meta("coverage_binder", 0)?;
                None
            }
        };
        let loader = watch.as_ref().map(|_| spawn_catalog_loader());
        Ok(Self { watch, loader })
    }

    fn catalog_loaded(
        &mut self,
        tracer: &mut Tracer,
        loaded: Result<Option<Catalog>, tokio::task::JoinError>,
    ) {
        self.loader = None;
        let loaded = loaded.unwrap_or_else(|e| {
            eprintln!("bbd: warning: not recording service calls: {e:#}");
            None
        });
        if let Some(map) = &mut self.watch {
            use_catalog(tracer, map, loaded);
        }
    }
}

/// Watch the services whose transaction codes the device's catalog gives,
/// besides the fixed ones already watched. Without a catalog only the
/// fixed ones stay watched, and service calls count as not recorded.
fn use_catalog(tracer: &mut Tracer, map: &mut BinderWatch, loaded: Option<Catalog>) {
    let watching = loaded.and_then(|catalog| match probes::watch(map, catalog.map_entries()) {
        Ok(()) => Some(catalog),
        Err(e) => {
            eprintln!("bbd: warning: not recording service calls: {e:#}");
            None
        }
    });
    let covered = i64::from(watching.is_some());
    if let Some(catalog) = watching {
        eprintln!("bbd: watching {} service methods", catalog.len());
        tracer.catalog = Some(catalog);
    }
    tracer.write_coverage("coverage_binder", covered);
}

async fn shutdown(
    opts: &Options<'_>,
    probes: &Probes,
    binder: &mut BinderCatalog,
    sandbox: Option<SandboxThread>,
    recording: &mut Recording,
) -> anyhow::Result<()> {
    if let Some(loader) = binder.loader.take() {
        loader.abort();
        if let Err(e) = loader.await
            && e.is_panic()
        {
            eprintln!("bbd: service catalog loader panicked");
        }
    }
    control::unlisten(opts.db);
    // Ends with what it has learned kept, and takes no more commands.
    if let Some(sandbox) = sandbox {
        sandbox.stop();
    }
    if opts.profile {
        probes::print_profile(&probes.ebpf);
    }
    recording.finish()
}

/// The timer of the check of sensor sessions' processes, which exists only
/// while a session is open (see `SENSOR_PROCESS_CHECK`).
#[derive(Default)]
struct SensorCheck(Option<tokio::time::Interval>);

impl SensorCheck {
    /// Start the timer when a sensor is on and stop it when none is.
    fn arm(&mut self, any_on: bool) {
        match (any_on, &self.0) {
            (true, None) => {
                let first = tokio::time::Instant::now() + SENSOR_PROCESS_CHECK;
                let mut timer = tokio::time::interval_at(first, SENSOR_PROCESS_CHECK);
                timer.set_missed_tick_behavior(MissedTickBehavior::Delay);
                self.0 = Some(timer);
            }
            (false, Some(_)) => self.0 = None,
            _ => {}
        }
    }

    fn armed(&self) -> bool {
        self.0.is_some()
    }

    /// The next check; never, while not armed.
    async fn tick(&mut self) {
        match &mut self.0 {
            Some(timer) => {
                timer.tick().await;
            }
            None => std::future::pending().await,
        }
    }
}

/// Until `at`; never, without one.
async fn sleep_until(at: Option<std::time::Instant>) {
    match at {
        Some(at) => tokio::time::sleep_until(at.into()).await,
        None => std::future::pending().await,
    }
}

/// The next connection to `listener`: a query asking for the records to be
/// written, or a command. `None` if accepting it failed. Never ready without
/// a listener.
async fn next_request(listener: Option<&UnixListener>) -> Option<UnixStream> {
    match listener {
        Some(listener) => match listener.accept().await {
            Ok((stream, _)) => Some(stream),
            Err(_) => {
                // An error that stays (no descriptors left) would otherwise
                // be met again at once, and again.
                tokio::time::sleep(ACCEPT_RETRY).await;
                None
            }
        },
        None => std::future::pending().await,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn the_sensor_process_check_runs_only_while_a_sensor_is_on() {
        let soon = Duration::from_millis(50);
        let mut check = SensorCheck::default();
        check.arm(false);
        assert!(!check.armed());
        // No timer: it never fires.
        assert!(tokio::time::timeout(soon, check.tick()).await.is_err());
        check.arm(true);
        assert!(check.armed());
        // Not before the interval is up.
        assert!(tokio::time::timeout(soon, check.tick()).await.is_err());
        // The last session ending takes the timer away.
        check.arm(false);
        assert!(!check.armed());
        assert!(check.0.is_none());
    }
}
