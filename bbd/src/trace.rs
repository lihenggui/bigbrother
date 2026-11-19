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

//! `bbd run`: load the eBPF programs and turn kernel events into stored rows.

use std::{
    collections::{HashMap, HashSet},
    fs,
    path::Path,
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use anyhow::Context as _;
use aya::{
    EbpfLoader,
    maps::{HashMap as BpfHashMap, MapData, PerCpuArray, RingBuf},
    programs::{KProbe, RawTracePoint},
};
use bb_common::{
    BinderEvent, BinderKey, BinderValue, ConnectEvent, EV_BINDER, EV_CONNECT, EV_EXEC, EV_FILE,
    EV_NETLINK, EV_PATH, EV_PROCESS, EV_RESULT, EV_TRAFFIC, EXEC_ARGS, EventHeader, ExecEvent,
    FUSE_SUPER_MAGIC, FileEvent, NetlinkEvent, OFF_COUNT, OFF_MISSING, OP_PROBE, OP_WATCH,
    PROC_SUPER_MAGIC, PROCESS_READ, PROCESS_WRITE, PathEvent, ProcessEvent, ResultEvent,
    SELINUX_MAGIC, SYSFS_MAGIC, read_event,
};
use tokio::{
    io::{Interest, unix::AsyncFd},
    net::{UnixListener, UnixStream},
    signal::unix::{SignalKind, signal},
    sync::Notify,
    time::{MissedTickBehavior, interval},
};

use crate::{
    appops,
    binder::{self, Catalog},
    btf::Btf,
    commands, control, helper,
    mounts::Mounts,
    net::{self, Destination, Listeners},
    packages::{FIRST_ISOLATED_APP_ID, PER_USER_RANGE, Packages},
    paths, personal, rules,
    sandbox::{self, Sandbox},
    selftest, sensors,
    spool::{self, Spool},
    store::net::{NetKey, NetSeen},
    store::{
        Category, Counters, MODE_CALL, MODE_CONNECT, MODE_DIR, MODE_EXEC, MODE_ISOLATED,
        MODE_PROBE, MODE_QUERY, MODE_READ, MODE_VIA_PROVIDER, MODE_WATCH, MODE_WRITE, Mode, Writer,
    },
    syscalls,
};

/// A destination of the network records: a name, an address and a port.
type NetDestination = (String, String, u16);

/// The most destinations an app has rows of its own for in an hour.
const NET_DESTINATIONS_PER_HOUR: usize = 512;

const AT_FDCWD: i32 = -100;
/// File type bits of an inode's mode.
const S_IFMT: u32 = 0o170000;
const S_IFDIR: u32 = 0o040000;
/// Uids from this app id up belong to installed apps (and isolated processes).
const FIRST_APPLICATION_UID: u32 = 10_000;

/// How long the owner found for an isolated process is remembered after it
/// was last seen, for events handled after it has exited. Short, because an
/// isolated uid is handed to another process, possibly another app's, once
/// it is free.
const ISOLATED_OWNER_TTL: Duration = Duration::from_secs(60);
/// How often what the kernel and the hook hold is taken in and written. A
/// write costs about the same whether it holds one record or a thousand, and
/// the query commands ask for one before they read (see `control`), so this
/// need not be often. The kernel side wakes the daemon sooner if its buffer
/// is filling up, and events carry the time they happened, so they can wait.
const FLUSH_INTERVAL: Duration = Duration::from_secs(30);
const PRUNE_INTERVAL: Duration = Duration::from_secs(6 * 3600);
/// How long a write waits for the sensor list to be read, when what it
/// would write needs it: short, since the app's queries wait for the
/// write. What still waits is written with the next one.
const SENSOR_LIST_WAIT: Duration = Duration::from_millis(300);
/// How often, while a sensor session is open, its process is checked. A
/// session whose process has ended ends when the process was last found
/// running, so this is the most of it that is lost when an app is killed
/// or swiped away, which often happens seconds after it turned a sensor on.
/// A check reads one small /proc file per such process and writes nothing;
/// with no session open there is no timer at all.
const SENSOR_PROCESS_CHECK: Duration = Duration::from_secs(5);
/// How long an opened file waits for its syscall result before being
/// recorded as successful anyway (the result event can be lost).
const RESULT_TIMEOUT_SECS: i64 = 10;
/// How long before a listener that failed to accept is tried again.
const ACCEPT_RETRY: Duration = Duration::from_secs(1);
/// Most command connections waiting to send their request at once. Only
/// root can connect; this only keeps a stuck client from piling them up.
const MAX_PENDING_COMMANDS: usize = 8;
/// Uids of new apps whose start the gate ended, waiting for the main loop.
/// One look at the package list deals with all of them; a uid that finds no
/// room is reported again by its next start.
const NEW_STARTS_QUEUE: usize = 16;
/// Where every user's apps keep the data they can reach before the user
/// unlocks the device, a directory per user and package: there for every
/// installed app from the start.
const USER_DATA: &str = "/data/user_de";

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
    // Sensors the previous run saw turned on and not off. When it was last
    // up: when it stopped or, if it did not get to say so, last wrote.
    let mut sensors = sensors::Sensors::new(sensors::dumpsys);
    let last_up = writer
        .meta("stopped_at")?
        .max(writer.meta("last_flush")?)
        .unwrap_or(0);
    sensors.adopt(
        writer.open_sensor_sessions()?,
        |pid, start| {
            sensors::process_alive(pid, start)
                .then(|| uid_of_pid(pid))
                .flatten()
        },
        last_up,
        Instant::now(),
        now(),
    );
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

    let Probes {
        mut ebpf,
        offsets,
        symbols,
        selinux,
        features,
    } = load_probes()?;
    let functions = features
        .iter()
        .filter_map(|(_, attached)| attached.as_ref().ok())
        .flatten()
        .filter(|(program, _)| *program != "file_open")
        .count();

    // The system's own permission usage records, read periodically.
    writer.set_meta("coverage_appops", COVERAGE_PENDING)?;
    let mut appops_coverage = COVERAGE_PENDING;
    let permission_uses = spawn_appops_reader();
    let mut appops = appops::Tracker::new(now());

    // Calls to system services. The tracepoint is attached now. Services
    // with fixed transaction codes (the sensor service) are watched at once;
    // the others once the device's codes are known, which takes a running
    // app_process and so a system that has come up. `coverage_binder` turns
    // 1 at that point.
    writer.set_meta("coverage_binder", COVERAGE_PENDING)?;
    let mut binder_watch = match attach_binder(&mut ebpf) {
        Ok(map) => Some(map),
        Err(e) => {
            eprintln!("bbd: warning: not recording service calls: {e:#}");
            writer.set_meta("coverage_binder", 0)?;
            None
        }
    };
    let mut catalog_loader = binder_watch.as_ref().map(|_| spawn_catalog_loader());

    let dropped: PerCpuArray<MapData, u64> =
        PerCpuArray::try_from(ebpf.take_map("DROPPED").context("DROPPED map missing")?)?;
    // Events the kernel side lost, summed over CPUs.
    let dropped_events = || selftest::dropped_events(&dropped);
    let ring = RingBuf::try_from(ebpf.take_map("EVENTS").context("EVENTS map missing")?)?;
    // SAFETY: the RingBuf owns its fd, which stays open and unchanged for as
    // long as the AsyncFd owns the RingBuf.
    let mut ring = unsafe { AsyncFd::register_with_interest(ring, Interest::READABLE) }?;

    eprintln!(
        "bbd: tracing {functions} syscall functions; db={} retention={}d pruned={pruned}",
        opts.db.display(),
        opts.retention_days
    );

    let mut tracer = Tracer::new(&opts, writer, sensors);
    // A probe can attach and never fire (see `selftest`): what is covered
    // is what fires. The events of other processes meanwhile are handled
    // as usual. Drained first, so that the buffer has room for the child's
    // events and only drops while it runs count.
    let checking = Instant::now();
    tracer.drain(ring.get_mut());
    let dropped_before = dropped_events();
    let (made, child) = selftest::exercise();
    tracer.selftest = selftest::Seen::of(child);
    tracer.drain(ring.get_mut());
    let lost = dropped_events() > dropped_before;
    let seen = std::mem::take(&mut tracer.selftest);
    let states = selftest::judge(&features, made, seen, lost);
    for ((feature, _), (_, state)) in features.iter().zip(&states) {
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
    if let Some(map) = &mut binder_watch {
        let fixed = Catalog::parse("");
        match watch(map, &fixed) {
            Ok(()) => {
                tracer.catalog = Some(fixed);
                tracer.sensors.watching(Instant::now());
            }
            Err(e) => eprintln!("bbd: warning: not recording sensor use: {e:#}"),
        }
    }
    // The sandbox (see `sandbox`). Like the optional probes, it costs only
    // itself where it cannot work: the daemon records regardless.
    let (new_starts_sender, mut new_starts) = tokio::sync::mpsc::channel(NEW_STARTS_QUEUE);
    // Woken when the app ops helper finds a permission changed by the user,
    // which is kept in its entry at once (`Sandbox::keep_found`, below).
    let found = Arc::new(Notify::new());
    let mut sandbox = if opts.sandbox {
        start_sandbox(
            &mut ebpf,
            &symbols,
            selinux,
            &offsets,
            &mut tracer,
            opts.db,
            new_starts_sender,
            &found,
        )
    } else {
        None
    };
    // Commands from `bbd sandbox` (see `control`). Listened for before the
    // flush socket, which is what tells a client that the daemon is up.
    let commands = sandbox
        .as_ref()
        .and_then(|_| match control::listen_commands(opts.db) {
            Ok(listener) => Some(listener),
            Err(e) => {
                eprintln!("bbd: warning: not taking sandbox commands: {e:#}");
                None
            }
        });
    // Queries ask for what is held to be written first (see `control`).
    let requests = match control::listen(opts.db) {
        Ok(listener) => Some(listener),
        Err(e) => {
            eprintln!("bbd: warning: queries will not wait for records to be written: {e:#}");
            None
        }
    };
    let mut command_reads = tokio::task::JoinSet::new();
    let mut flush_tick = interval(FLUSH_INTERVAL);
    flush_tick.set_missed_tick_behavior(MissedTickBehavior::Delay);
    let mut prune_tick = interval(PRUNE_INTERVAL);
    prune_tick.tick().await; // first tick fires immediately; already pruned above
    let mut sigint = signal(SignalKind::interrupt())?;
    let mut sigterm = signal(SignalKind::terminate())?;
    let mut write_errors = RecurringLog::default();
    let mut sensor_check = SensorCheck::default();
    // The package list, watched while newly installed apps are sandboxed.
    let mut package_watch = sandbox::auto::Watch::default();

    loop {
        sensor_check.arm(tracer.sensors.any_on());
        if let Some(sandbox) = sandbox.as_mut()
            && package_watch.arm(
                sandbox.auto_on(),
                Path::new(sandbox::auto::PACKAGE_DIR),
                Path::new(USER_DATA),
            )
        {
            // What was installed before the watch began.
            sandbox.isolate_new(&tracer.writer, &mut tracer.packages);
        }
        // A new app left out of the sandbox is tried again then; no timer
        // while none is.
        let next_look = sandbox.as_ref().and_then(Sandbox::auto_next_look);
        // Every branch that does not end in a flush continues the loop.
        let request = tokio::select! {
            _ = sigint.recv() => break,
            _ = sigterm.recv() => break,
            _ = flush_tick.tick() => None,
            _ = found.notified() => None,
            // Sessions whose process has ended end now, and are written
            // with the next write. Sensors turned on since that did not
            // wake the daemon (the kernel side wakes it at most once a
            // second for them) are taken in first.
            _ = sensor_check.tick(), if sensor_check.armed() => {
                tracer.drain(ring.get_mut());
                tracer.sensors.check_processes(now(), sensors::process_alive);
                continue;
            }
            // The device's transaction codes are known: watch the services
            // that need them now, not at the next write.
            loaded = async { catalog_loader.as_mut().expect("guarded").await },
                if catalog_loader.is_some() =>
            {
                catalog_loader = None;
                let loaded = loaded.unwrap_or_else(|e| {
                    eprintln!("bbd: warning: not recording service calls: {e:#}");
                    None
                });
                if let Some(map) = &mut binder_watch {
                    tracer.use_catalog(map, loaded);
                }
                continue;
            }
            request = next_request(requests.as_ref()) => match request {
                Some(stream) => Some(stream),
                None => continue,
            },
            // A command is read without holding up the loop, and carried
            // out once it has arrived.
            stream = next_request(commands.as_ref()) => {
                if let Some(stream) = stream
                    && command_reads.len() < MAX_PENDING_COMMANDS
                {
                    command_reads.spawn(control::read_request(stream));
                }
                continue;
            }
            Some(read) = command_reads.join_next(), if !command_reads.is_empty() => {
                if let (Ok(Some((stream, line))), Some(sandbox)) = (read, sandbox.as_mut()) {
                    let answer = sandbox.answer(&line, &tracer.writer, &mut tracer.packages);
                    control::send_answer(stream, answer);
                }
                continue;
            }
            // An app was installed, updated or removed.
            changed = package_watch.changed(), if package_watch.armed() => {
                match changed {
                    Ok(()) => {
                        if let Some(sandbox) = sandbox.as_mut() {
                            sandbox.isolate_new(&tracer.writer, &mut tracer.packages);
                        }
                    }
                    Err(e) => package_watch.give_up(&e),
                }
                continue;
            }
            _ = sleep_until(next_look), if next_look.is_some() => {
                if let Some(sandbox) = sandbox.as_mut() {
                    sandbox.isolate_new(&tracer.writer, &mut tracer.packages);
                }
                continue;
            }
            // The gate ended a start for a new app or a stale entry.
            Some(_) = new_starts.recv() => {
                while new_starts.try_recv().is_ok() {}
                if let Some(sandbox) = sandbox.as_mut() {
                    if sandbox.auto_on() {
                        sandbox.isolate_new(&tracer.writer, &mut tracer.packages);
                    } else {
                        sandbox.release_reassigned(&tracer.writer, &mut tracer.packages);
                    }
                }
                continue;
            }
            _ = prune_tick.tick() => {
                if let Err(e) = tracer.writer.prune(opts.retention_days, now()) {
                    eprintln!("bbd: could not prune old records: {e:#}");
                }
                continue;
            }
            // The kernel side's buffer is filling up.
            guard = ring.readable_mut() => {
                let mut guard = guard?;
                if tracer.drain(guard.get_inner_mut()) { guard.clear_ready(); }
                continue;
            }
        };

        tracer.drain(ring.get_mut());
        tracer.counters.dropped = dropped_events();
        tracer.expire(now() - RESULT_TIMEOUT_SECS);
        tracer.prune_isolated_owners();
        tracer.ingest_provider_events();
        tracer.take_refused_lookups();
        while let Ok(reading) = permission_uses.try_recv() {
            // A reading at all shows the records can be read here. Written
            // when that changes, not with every reading.
            let covered = i64::from(reading.is_some());
            if covered != appops_coverage
                && tracer.writer.set_meta("coverage_appops", covered).is_ok()
            {
                appops_coverage = covered;
            }
            if let Some(entries) = reading {
                tracer.record_permission_uses(appops.update(&entries));
            }
        }
        tracer.settle_sensors(SENSOR_LIST_WAIT);
        if let Some(sandbox) = sandbox.as_mut() {
            sandbox.release_reassigned(&tracer.writer, &mut tracer.packages);
            sandbox.keep_found(&tracer.writer);
        }
        // A failed write (disk full, database busy) keeps the batch for the
        // next attempt; it is not a reason to stop recording.
        let written = match request {
            Some(_) => tracer.writer.flush_for_query(&tracer.counters, now()),
            None => tracer.writer.flush(&tracer.counters, now()),
        };
        if let Err(e) = written {
            write_errors.log(format!("bbd: could not write records: {e:#}"));
        }
        if let Some(stream) = request {
            control::answer(&stream);
        }
    }

    if let Some(loader) = catalog_loader {
        loader.abort();
        let _ = loader.await;
    }
    control::unlisten(opts.db);
    if commands.is_some() {
        control::unlisten_commands(opts.db);
    }
    if opts.profile {
        print_profile(&ebpf);
    }
    tracer.drain(ring.get_mut());
    tracer.ingest_provider_events();
    tracer.settle_sensors(Duration::from_secs(2));
    tracer.counters.dropped = dropped_events();
    tracer.expire(i64::MAX);
    if let Some(sandbox) = sandbox.as_mut() {
        sandbox.keep_found(&tracer.writer);
    }
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

/// Load the sandbox, attach its gate and put the apps that were sandboxed
/// back in. `None`, with a warning, if the sandbox's entries cannot be
/// read; a gate that cannot be attached leaves a sandbox that refuses to
/// take apps in. The gate sends `new_starts` the uid of each new app whose
/// start it ended (see `sandbox::auto`), and the app ops helper wakes
/// `found` when it finds a permission changed by the user.
#[allow(clippy::too_many_arguments)]
fn start_sandbox(
    ebpf: &mut aya::Ebpf,
    symbols: &HashSet<&'static str>,
    selinux: bool,
    offsets: &[u32],
    tracer: &mut Tracer,
    db: &Path,
    new_starts: tokio::sync::mpsc::Sender<u32>,
    found: &Arc<Notify>,
) -> Option<Sandbox> {
    let user_data = Path::new(USER_DATA);
    let mut sandbox = match Sandbox::load(&tracer.writer, Vec::new(), Some(user_data)) {
        Ok(sandbox) => sandbox,
        Err(e) => {
            eprintln!("bbd: warning: the sandbox is off: {e:#}");
            return None;
        }
    };
    // Attached while no uid is listed for it, so it holds nothing yet. The
    // uids are listed next, and what started in between is ended last
    // (`sweep`): no start falls between the two.
    let storage = attach_feature(ebpf, &syscalls::GATE, symbols, selinux, offsets)
        .and_then(|_| sandbox::gate::Storage::start(ebpf, &db.with_file_name("scratch")));
    let _ = tracer.writer.set_meta(
        &format!("coverage_{}", syscalls::GATE.name),
        i64::from(storage.is_ok()),
    );
    let appops = sandbox::appops::AppOps::start();
    let mut new_apps = None;
    // Whether an app is kept from single destinations: what the one effect
    // finds out and the other needs the packet filter's rules for.
    let ruled = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let destinations =
        sandbox::destinations::Destinations::start(ebpf, sandbox.book(), ruled.clone());
    let network = sandbox::network::Network::start(ebpf, ruled);
    // The names apps look up, heard by standing between them and netd.
    let names = std::sync::Arc::new(sandbox::names::Names::default());
    let (lookups_refused, refused_lookups) = std::sync::mpsc::channel();
    let refuses: sandbox::names::Refuses = {
        let (snapshot, book) = (sandbox.snapshot(), sandbox.book());
        std::sync::Arc::new(move |uid, host: Option<&str>| {
            // Only of an app in the sandbox: taken out, it is kept from
            // nothing.
            snapshot
                .read()
                .unwrap_or_else(|e| e.into_inner())
                .contains_key(&uid)
                && book
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .get(&uid)
                    .is_some_and(|targets| {
                        targets.iter().any(|target| match host {
                            Some(host) => target.covers_name(host),
                            // A name that could not be read: refused to
                            // an app kept from any name.
                            None => !matches!(target, crate::netctl::Target::Ip(..)),
                        })
                    })
        })
    };
    let serves: sandbox::names::Serves = {
        let (snapshot, global, book) = (sandbox.snapshot(), sandbox.global(), sandbox.book());
        std::sync::Arc::new(move |uid| {
            // The apps given the daemon's socket, and no other: the socket
            // can be found by any process, and an app kept from the
            // network is to have no resolver at all.
            let snapshot = snapshot.read().unwrap_or_else(|e| e.into_inner());
            let Some(entry) = snapshot.get(&uid) else {
                return false;
            };
            let items = sandbox::items_of(entry, &global.read().unwrap_or_else(|e| e.into_inner()));
            !items.network_refused()
                && (items.network_recorded()
                    || book
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .get(&uid)
                        .is_some_and(|targets| {
                            targets
                                .iter()
                                .any(|target| !matches!(target, crate::netctl::Target::Ip(..)))
                        }))
        })
    };
    let relay = sandbox::names::start(names.clone(), serves, refuses, lookups_refused);
    if let Err(e) = &relay {
        eprintln!("bbd: warning: the names apps look up are not known: {e:#}");
    }
    let _ = tracer
        .writer
        .set_meta("coverage_net_names", i64::from(relay.is_ok()));
    let names = relay.is_ok().then_some(names);
    tracer.refused_lookups = Some(refused_lookups);
    tracer.net = Some(sandbox::destinations::View {
        snapshot: sandbox.snapshot(),
        global: sandbox.global(),
        book: sandbox.book(),
        names: names.clone(),
    });
    let found = found.clone();
    appops.on_found(move || found.notify_one());
    let storage: Box<dyn sandbox::Effect> = match storage {
        Ok(mut storage) => {
            storage.on_admitted(appops.verifier());
            // The resolver goes with the network, and stays without it.
            if network.is_ok() {
                storage.with_network();
            }
            if names.is_some() {
                storage.with_names(sandbox.book());
            }
            // Not waited for: the gate thread waits for nothing.
            storage.on_unknown(move |uid| {
                let _ = new_starts.try_send(uid);
            });
            new_apps = sandbox::auto::Kernel::take(ebpf)
                .inspect_err(|e| eprintln!("bbd: warning: starts of new apps are not held: {e:#}"))
                .ok();
            Box::new(storage)
        }
        Err(e) => {
            eprintln!("bbd: warning: the sandbox is not available: {e:#}");
            Box::new(sandbox::gate::Unavailable)
        }
    };
    // Libraries are told from apps by the package manager, through the
    // helper, which is started for that only when a new app is found.
    sandbox.tell_libraries_with(Box::new(appops.librarian()));
    sandbox.add_effect(Box::new(appops));
    sandbox.add_effect(Box::new(sandbox::identity::Identity::new(
        &sandbox.snapshot(),
        &sandbox.global(),
    )));
    // What went over a connection is told by another program, which reads
    // the same list of apps.
    let traffic = destinations
        .as_ref()
        .map_err(|e| anyhow::anyhow!("{e:#}"))
        .and_then(|_| attach_traffic(ebpf, offsets));
    if let Err(e) = &traffic {
        eprintln!("bbd: warning: the bytes of connections are not counted: {e:#}");
    }
    let _ = tracer
        .writer
        .set_meta("coverage_net_traffic", i64::from(traffic.is_ok()));
    match destinations {
        Ok(mut destinations) => {
            destinations.with_traffic(traffic.is_ok());
            if let Some(names) = names {
                destinations.with_names(names);
            }
            sandbox.add_effect(Box::new(destinations));
        }
        Err(e) => eprintln!("bbd: warning: connections are not recorded: {e:#}"),
    }
    let network: Box<dyn sandbox::Effect> = match network {
        Ok(network) => Box::new(network),
        Err(e) => {
            eprintln!("bbd: warning: apps cannot be kept from the network: {e:#}");
            Box::new(sandbox::network::Unavailable::new())
        }
    };
    sandbox.add_effect(network);
    // Adding the UID to the storage gate lets a held start proceed. Its
    // other policies must already be in place at that point.
    sandbox.add_effect(storage);
    sandbox.add_effect(Box::new(sandbox::sweep::Restart));
    sandbox.resume(&tracer.writer, &mut tracer.packages);
    sandbox.note_recording(&tracer.writer);
    sandbox.resume_auto(new_apps, &tracer.writer, &mut tracer.packages);
    Some(sandbox)
}

/// The eBPF programs, loaded, with the recorders attached.
pub struct Probes {
    pub ebpf: aya::Ebpf,
    pub offsets: [u32; OFF_COUNT],
    pub symbols: HashSet<&'static str>,
    /// Whether SELinux is running (see `syscalls`).
    pub selinux: bool,
    /// The file probes, then each optional feature, with what it is
    /// attached to or why it is not. The file probes' plan begins with the
    /// security hook `file_open` is attached to.
    pub features: Vec<(&'static syscalls::Feature, anyhow::Result<selftest::Plan>)>,
}

/// Load the eBPF programs and attach the file probes and every optional
/// feature the kernel allows. Fails only without the file probes.
pub fn load_probes() -> anyhow::Result<Probes> {
    // Kernels before 5.11 account BPF memory against RLIMIT_MEMLOCK
    // (https://lwn.net/Articles/837122/); GKI 5.10 is one of them.
    let rlim = libc::rlimit {
        rlim_cur: libc::RLIM_INFINITY,
        rlim_max: libc::RLIM_INFINITY,
    };
    if unsafe { libc::setrlimit(libc::RLIMIT_MEMLOCK, &rlim) } != 0 {
        eprintln!("bbd: warning: could not raise RLIMIT_MEMLOCK");
    }

    let offsets = Btf::from_kernel()?.kernel_offsets()?;
    let mut ebpf = EbpfLoader::new()
        .override_global("OFFSETS", &offsets[..], true)
        .load(aya::include_bytes_aligned!(concat!(
            env!("OUT_DIR"),
            "/bb-ebpf"
        )))
        .context("loading eBPF object (need root and a BPF-enabled kernel)")?;

    let symbols = syscalls::kernel_symbols().context("reading /proc/kallsyms")?;
    anyhow::ensure!(
        symbols.contains(syscalls::REQUIRED),
        "{} not in /proc/kallsyms (unsupported kernel, or kallsyms restricted)",
        syscalls::REQUIRED
    );

    let kprobe: &mut KProbe = ebpf
        .program_mut("file_open")
        .context("program file_open missing from object")?
        .try_into()?;
    kprobe.load().context("verifier rejected file_open")?;
    let selinux = syscalls::selinux_running();
    let hook = syscalls::file_open_hook(&symbols, selinux);
    kprobe
        .attach(hook, 0)
        .with_context(|| format!("attaching kprobe {hook}"))?;
    eprintln!("bbd: file_open on {hook}");

    // Syscall probes: one entry program per argument layout plus a shared
    // return program, attached to each syscall function this kernel has.
    // The return program is shared by every feature that reports outcomes.
    let returns: &mut KProbe = ebpf
        .program_mut("sys_ret")
        .context("program sys_ret missing from object")?
        .try_into()?;
    returns.load().context("verifier rejected sys_ret")?;
    let mut files = vec![("file_open", hook)];
    files.extend(
        attach_feature(&mut ebpf, &syscalls::FILES, &symbols, selinux, &offsets)
            .context("setting up the file probes")?,
    );
    let mut features = vec![(&syscalls::FILES, Ok(files))];

    // Everything else is recorded where the kernel allows it. A probe that
    // can't be set up costs its own records, not the daemon: what is and
    // isn't covered is stored for `bbd stats` to report.
    for feature in syscalls::FEATURES {
        let attached = attach_feature(&mut ebpf, feature, &symbols, selinux, &offsets);
        if let Err(e) = &attached {
            eprintln!("bbd: warning: not recording {}: {e:#}", feature.name);
        }
        features.push((feature, attached));
    }
    Ok(Probes {
        ebpf,
        offsets,
        symbols,
        selinux,
        features,
    })
}

/// Load and attach the probes of one optional feature. Returns the
/// (program, kernel function) pairs attached.
fn attach_feature(
    ebpf: &mut aya::Ebpf,
    feature: &syscalls::Feature,
    symbols: &HashSet<&'static str>,
    selinux: bool,
    offsets: &[u32],
) -> anyhow::Result<selftest::Plan> {
    anyhow::ensure!(
        feature
            .offsets
            .iter()
            .all(|&slot| offsets[slot] != OFF_MISSING),
        "this kernel's type information lacks a field it reads"
    );
    let plan = syscalls::attach_plan(feature, symbols, selinux);
    anyhow::ensure!(
        !plan.is_empty(),
        "this kernel has none of the functions it attaches to"
    );
    for (name, _) in feature.table {
        let program: &mut KProbe = ebpf
            .program_mut(name)
            .with_context(|| format!("program {name} missing from object"))?
            .try_into()?;
        program
            .load()
            .with_context(|| format!("verifier rejected {name}"))?;
    }
    // All or nothing: a feature left half attached would go on producing
    // records, some of them wrong (a call whose return is not seen counts
    // as successful), while being reported as not recorded.
    let mut links = Vec::new();
    let attached = (|| -> anyhow::Result<()> {
        for (entry, function) in &plan {
            let returns = feature.with_return.then_some("sys_ret");
            for name in std::iter::once(*entry).chain(returns) {
                let program: &mut KProbe = ebpf
                    .program_mut(name)
                    .with_context(|| format!("program {name} missing from object"))?
                    .try_into()?;
                let link = program
                    .attach(function, 0)
                    .with_context(|| format!("attaching {name} to {function}"))?;
                links.push((name, link));
            }
        }
        Ok(())
    })();
    if let Err(e) = attached {
        for (name, link) in links {
            if let Some(Ok(program)) = ebpf.program_mut(name).map(<&mut KProbe>::try_from) {
                let _ = program.detach(link);
            }
        }
        return Err(e);
    }
    // Which of the alternatives was taken depends on the kernel.
    if feature.alternatives {
        for (_, function) in &plan {
            eprintln!("bbd: {} on {function}", feature.name);
        }
    }
    Ok(plan)
}

/// Load and attach the program that reports what went over the TCP
/// connections of the apps recorded, as each ends.
fn attach_traffic(ebpf: &mut aya::Ebpf, offsets: &[u32]) -> anyhow::Result<()> {
    anyhow::ensure!(
        [
            bb_common::OFF_SOCK_PROTOCOL,
            bb_common::OFF_SK_FAMILY,
            bb_common::OFF_SK_DADDR,
            bb_common::OFF_SK_DPORT,
            bb_common::OFF_SK_V6_DADDR,
            bb_common::OFF_SK_UID,
            bb_common::OFF_TCP_ACKED,
            bb_common::OFF_TCP_RECEIVED,
        ]
        .iter()
        .all(|&slot| offsets[slot] != OFF_MISSING),
        "this kernel's type information lacks a field it reads"
    );
    let program: &mut RawTracePoint = ebpf
        .program_mut("inet_sock_set_state")
        .context("program inet_sock_set_state missing from object")?
        .try_into()?;
    program
        .load()
        .context("verifier rejected inet_sock_set_state")?;
    program
        .attach("inet_sock_set_state")
        .context("attaching to the inet_sock_set_state tracepoint")?;
    Ok(())
}

/// Load and attach the binder tracepoint program, and return the map that
/// tells it which calls to report.
fn attach_binder(ebpf: &mut aya::Ebpf) -> anyhow::Result<BinderWatch> {
    let program: &mut RawTracePoint = ebpf
        .program_mut("binder_ioctl")
        .context("program binder_ioctl missing from object")?
        .try_into()?;
    program.load().context("verifier rejected binder_ioctl")?;
    program
        .attach("binder_ioctl")
        .context("attaching to the binder_ioctl tracepoint")?;
    Ok(BpfHashMap::try_from(
        ebpf.take_map("BINDER_WATCH")
            .context("BINDER_WATCH map missing")?,
    )?)
}

type BinderWatch = BpfHashMap<MapData, BinderKey, BinderValue>;

/// Tell the kernel side which calls to report.
fn watch(map: &mut BinderWatch, catalog: &Catalog) -> anyhow::Result<()> {
    for (key, value) in catalog.map_entries() {
        map.insert(&key, &value, 0)?;
    }
    Ok(())
}

/// Value of a `coverage_*` entry while it is not yet known whether the
/// feature works on this device: neither 1 (recording) nor 0 (can't).
const COVERAGE_PENDING: i64 = -1;

/// How soon the program that reads the permission usage records is started
/// again after it ends: `APPOPS_RETRY` after the first failure, twice as long
/// after each further one, up to `APPOPS_RETRY_MAX`; each start costs a Java
/// runtime's start-up. A run of `APPOPS_HEALTHY` or longer starts the count
/// again. After `APPOPS_GIVE_UP` failures without a single reading the
/// records are reported as unavailable (the attempts go on regardless). The
/// helper itself waits for the system service at boot, so these are for a
/// helper that fails or ends later.
const APPOPS_RETRY: Duration = Duration::from_secs(30);
const APPOPS_RETRY_MAX: Duration = Duration::from_secs(3600);
const APPOPS_HEALTHY: Duration = Duration::from_secs(600);
const APPOPS_GIVE_UP: u32 = 5;

/// How long to wait before the next start, after `failures` in a row.
fn appops_retry_delay(failures: u32) -> Duration {
    let doublings = failures.saturating_sub(1).min(16);
    APPOPS_RETRY
        .saturating_mul(1 << doublings)
        .min(APPOPS_RETRY_MAX)
}

/// How often the system's permission usage records are read. The records
/// carry their own times, so the interval costs precision only when an app
/// uses the same permission more than once in between.
const APPOPS_INTERVAL: Duration = Duration::from_secs(30);

/// Read the permission usage records on a thread of its own, for as long as
/// the daemon runs: one helper process is kept running and prints a reading
/// per interval, rather than a Java runtime being started for each. Sends
/// each reading, or `None` once to say that there seems to be none to be
/// had.
fn spawn_appops_reader() -> std::sync::mpsc::Receiver<Option<Vec<appops::Entry>>> {
    let (sender, receiver) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let mut failures = 0;
        let mut read_once = false;
        let mut log = RecurringLog::default();
        loop {
            let started = Instant::now();
            // Returns when the helper has ended, which it is not meant to.
            let e = match stream_appops(&sender, &mut read_once) {
                StreamEnd::Helper(e) => e,
                StreamEnd::DaemonGone => return,
            };
            if started.elapsed() >= APPOPS_HEALTHY {
                failures = 0;
            }
            failures += 1;
            log.log(format!("bbd: reading permission use: {e:#}"));
            // Reported once, when it stops looking like a system that is
            // still starting.
            if failures == APPOPS_GIVE_UP && !read_once {
                eprintln!("bbd: warning: not recording permission use");
                if sender.send(None).is_err() {
                    return;
                }
            }
            std::thread::sleep(appops_retry_delay(failures));
        }
    });
    receiver
}

/// How often a message that keeps recurring is logged again.
const RECURRING_LOG_INTERVAL: Duration = Duration::from_secs(3600);

/// Logs a message that keeps recurring once, rather than each time: again
/// when it changes, or once an hour has passed, with how often it recurred.
/// The log is only rotated at boot.
#[derive(Default)]
struct RecurringLog {
    last: Option<(String, Instant)>,
    /// Times `last` recurred without being logged.
    unlogged: u32,
}

impl RecurringLog {
    fn log(&mut self, message: String) {
        if let Some(line) = self.line(message, Instant::now()) {
            eprintln!("{line}");
        }
    }

    /// What to log for `message` at `now`, if anything.
    fn line(&mut self, message: String, now: Instant) -> Option<String> {
        let mut line = message.clone();
        if let Some((last, logged)) = &self.last
            && *last == message
        {
            if now.duration_since(*logged) < RECURRING_LOG_INTERVAL {
                self.unlogged += 1;
                return None;
            }
            if self.unlogged > 0 {
                line = format!("{message} ({} more since)", self.unlogged);
            }
        }
        self.last = Some((message, now));
        self.unlogged = 0;
        Some(line)
    }
}

/// Why [`stream_appops`] returned.
enum StreamEnd {
    /// The helper could not be started or stopped printing.
    Helper(anyhow::Error),
    /// Nobody is listening any more.
    DaemonGone,
}

/// Run the helper and pass on each reading it prints, until it ends.
fn stream_appops(
    sender: &std::sync::mpsc::Sender<Option<Vec<appops::Entry>>>,
    read_once: &mut bool,
) -> StreamEnd {
    use std::io::BufRead as _;

    let interval = APPOPS_INTERVAL.as_secs().to_string();
    let mut args: Vec<&str> = vec!["--every", &interval];
    for op in appops::ops() {
        args.push(op);
    }
    // Its stdin is held open for as long as `helper` lives; the helper
    // leaves when it closes, so it does not outlive the daemon.
    let mut helper = match helper::spawn(appops::HELPER_CLASS, args.into_iter()) {
        Ok(helper) => helper,
        Err(e) => return StreamEnd::Helper(e),
    };
    let output = helper.stdout.take().expect("spawned with a piped stdout");
    // What it says when it fails, for the message; read as it comes, so
    // that the helper never waits for room in the pipe.
    let errors = helper
        .stderr
        .take()
        .map(|stderr| std::thread::spawn(move || first_line(std::io::BufReader::new(stderr))));
    let mut reading = String::new();
    for line in std::io::BufReader::new(output).lines() {
        let Ok(line) = line else { break };
        if line != appops::END_OF_READING {
            reading.push_str(&line);
            reading.push('\n');
            continue;
        }
        *read_once = true;
        if sender.send(Some(appops::parse(&reading))).is_err() {
            let _ = helper.kill();
            let _ = helper.wait();
            return StreamEnd::DaemonGone;
        }
        reading.clear();
    }
    let status = match helper.wait() {
        Ok(status) => status.to_string(),
        Err(e) => e.to_string(),
    };
    let said = errors
        .and_then(|reader| reader.join().ok())
        .flatten()
        .map_or_else(String::new, |line| format!(": {line}"));
    StreamEnd::Helper(anyhow::anyhow!("the helper program ended ({status}){said}"))
}

/// The first line of `text` that has anything on it, shortened, after
/// reading all of it.
fn first_line(text: impl std::io::BufRead) -> Option<String> {
    const MAX_CHARS: usize = 200;
    let mut first = None;
    for line in text.lines() {
        let Ok(line) = line else { break };
        if first.is_none() && !line.trim().is_empty() {
            first = Some(line.trim().chars().take(MAX_CHARS).collect());
        }
    }
    first
}

/// How often, and how many times, the transaction codes are asked for
/// before giving up. The first attempts fail while the system is starting.
const CATALOG_RETRY: Duration = Duration::from_secs(30);
const CATALOG_ATTEMPTS: u32 = 20;
const CATALOG_TIMEOUT: Duration = Duration::from_secs(10);

/// Look up the device's transaction codes without holding up recording.
/// A retry and its subprocess wait are both cancellable at shutdown.
fn spawn_catalog_loader() -> tokio::task::JoinHandle<Option<Catalog>> {
    tokio::spawn(async {
        for attempt in 1..=CATALOG_ATTEMPTS {
            let error = match tokio::time::timeout(CATALOG_TIMEOUT, binder::load()).await {
                Ok(Ok(catalog)) => return Some(catalog),
                Ok(Err(e)) => e,
                Err(e) => anyhow::Error::new(e).context("reading the transaction catalog"),
            };
            if attempt == CATALOG_ATTEMPTS {
                eprintln!("bbd: warning: not recording service calls: {error:#}");
                break;
            }
            tokio::time::sleep(CATALOG_RETRY).await;
        }
        None
    })
}

/// Per-program run counts and time, as measured by the kernel. This is the
/// CPU time the tracing itself adds to the processes being traced.
fn print_profile(ebpf: &aya::Ebpf) {
    eprintln!("bbd: eBPF program cost");
    let mut total = Duration::ZERO;
    let mut programs: Vec<_> = ebpf.programs().collect();
    programs.sort_by_key(|(name, _)| *name);
    for (name, program) in programs {
        let Ok(info) = program.info() else { continue };
        let (runs, time) = (info.run_count(), info.run_time());
        total += time;
        let average = if runs > 0 {
            time.as_nanos() / u128::from(runs)
        } else {
            0
        };
        eprintln!(
            "  {name:<14} runs={runs:<9} total={:>9.3}ms avg={average}ns",
            time.as_secs_f64() * 1000.0
        );
    }
    eprintln!(
        "  all programs   total={:.3}ms",
        total.as_secs_f64() * 1000.0
    );
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() as i64)
}

/// Turns the times the kernel gives events, nanoseconds since boot, into
/// unix time.
struct EventClock {
    /// Unix time at boot, in nanoseconds.
    boot_unix_ns: i64,
}

impl EventClock {
    /// Taken anew for each batch of events, so that the wall clock being set
    /// carries over.
    fn now() -> Self {
        Self {
            boot_unix_ns: clock_ns(libc::CLOCK_REALTIME) - clock_ns(libc::CLOCK_BOOTTIME),
        }
    }

    fn unix_secs(&self, boot_ns: u64) -> i64 {
        (self.boot_unix_ns + boot_ns as i64).div_euclid(1_000_000_000)
    }
}

fn clock_ns(clock: libc::clockid_t) -> i64 {
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    // Cannot fail for these clocks and a valid pointer.
    unsafe { libc::clock_gettime(clock, &mut ts) };
    ts.tv_sec * 1_000_000_000 + ts.tv_nsec
}

/// A file the kernel opened, held until the syscall's return value arrives.
struct Opened {
    uid: u32,
    pid: u32,
    path: String,
    mode: Mode,
    ts: i64,
}

/// Something an app did, held until its syscall's return value arrives.
enum Held {
    File(Opened),
    /// A program it started; `subject` is what to store (see `commands`).
    Command {
        uid: u32,
        pid: u32,
        subject: String,
        /// [`MODE_ISOLATED`] if an isolated process started it.
        origin: Mode,
        ts: i64,
    },
    /// Anything else: what to store is already decided, only the outcome is
    /// missing.
    Record {
        uid: u32,
        cat: Category,
        subject: String,
        mode: Mode,
        ts: i64,
    },
}

impl Held {
    fn ts(&self) -> i64 {
        match self {
            Self::File(opened) => opened.ts,
            Self::Command { ts, .. } | Self::Record { ts, .. } => *ts,
        }
    }
}

struct Tracer {
    packages: Packages,
    mounts: Mounts,
    spool: Spool,
    listeners: Listeners,
    /// Ports each app has contacted, to recognise a scan.
    scans: net::Scans,
    /// What the sandbox refuses of the network, for the connections
    /// recorded of its apps.
    net: Option<sandbox::destinations::View>,
    /// The lookups refused since they were last taken in: the uid and the
    /// name of each.
    refused_lookups: Option<std::sync::mpsc::Receiver<(u32, String)>>,
    /// The destinations each app has rows of in the hour (see `net_key`).
    net_listed: (i64, HashMap<u32, HashSet<NetDestination>>),
    /// Commands being looked for along `PATH`.
    searches: commands::Searches,
    /// The service methods being watched; `None` until they are known.
    catalog: Option<Catalog>,
    /// The sensors apps have on.
    sensors: sensors::Sensors,
    /// Sensor sessions not kept for being over the limits, as last written
    /// (`sensor_sessions_dropped` in `bbd stats`), and its log.
    sensors_dropped: u64,
    sensors_dropped_log: RecurringLog,
    writer: Writer,
    counters: Counters,
    /// Keyed by thread id.
    awaiting_result: HashMap<u32, Vec<Held>>,
    /// (pid, isolated uid) -> the app it was attributed to, and when last seen.
    isolated_owners: HashMap<(u32, u32), (u32, Instant)>,
    verbose: bool,
    explain_discards: bool,
    ingest_provider_events: bool,
    /// The self-check's events (see `selftest`), which are not recorded.
    selftest: selftest::Seen,
}

impl Tracer {
    fn new(opts: &Options<'_>, writer: Writer, sensors: sensors::Sensors) -> Self {
        Self {
            packages: Packages::new(opts.packages_list),
            mounts: Mounts::new(),
            spool: Spool::new(),
            listeners: Listeners::new(),
            scans: net::Scans::default(),
            net: None,
            refused_lookups: None,
            net_listed: (0, HashMap::new()),
            searches: commands::Searches::default(),
            catalog: None,
            sensors,
            sensors_dropped: 0,
            sensors_dropped_log: RecurringLog::default(),
            writer,
            counters: Counters::default(),
            awaiting_result: HashMap::new(),
            isolated_owners: HashMap::new(),
            verbose: opts.verbose,
            explain_discards: opts.explain_discards,
            ingest_provider_events: opts.ingest_provider_events,
            selftest: selftest::Seen::default(),
        }
    }

    /// Watch the services whose transaction codes the device's catalog gives,
    /// besides the fixed ones already watched. Without a catalog only the
    /// fixed ones stay watched, and service calls count as not recorded.
    fn use_catalog(&mut self, map: &mut BinderWatch, loaded: Option<Catalog>) {
        let watching = loaded.and_then(|catalog| match watch(map, &catalog) {
            Ok(()) => Some(catalog),
            Err(e) => {
                eprintln!("bbd: warning: not recording service calls: {e:#}");
                None
            }
        });
        let covered = i64::from(watching.is_some());
        if let Some(catalog) = watching {
            eprintln!("bbd: watching {} service methods", catalog.len());
            self.catalog = Some(catalog);
        }
        self.writer.set_meta("coverage_binder", covered).ok();
    }

    /// Yield to control requests even when the kernel keeps producing.
    fn drain(&mut self, ring: &mut RingBuf<MapData>) -> bool {
        let clock = EventClock::now();
        for _ in 0..1024 {
            let Some(item) = ring.next() else { return true };
            self.on_event(&item, &clock);
        }
        false
    }

    fn hold_result(&mut self, tid: u32, event: Held) {
        if self.awaiting_result.len() < 1024 || self.awaiting_result.contains_key(&tid) {
            let waiting = self.awaiting_result.entry(tid).or_default();
            if waiting.len() < 16 {
                waiting.push(event);
                return;
            }
        }
        self.counters.unresolved += 1;
    }

    fn on_event(&mut self, bytes: &[u8], clock: &EventClock) {
        let Some(hdr) = read_event::<EventHeader>(bytes) else {
            return;
        };
        if self.selftest.claim(bytes) {
            return;
        }
        let ts = clock.unix_secs(hdr.boot_ns());
        match hdr.kind {
            EV_FILE => {
                let Some(ev) = read_event::<FileEvent>(bytes) else {
                    return;
                };
                self.counters.seen += 1;
                // A path that didn't fit is missing its outermost
                // components; joined to the mount point it would name a
                // different, wrong file.
                if ev.truncated != 0 {
                    self.counters.unresolved += 1;
                    self.explain("file");
                    return;
                }
                let Some(path) = self.file_path(&ev) else {
                    self.counters.unresolved += 1;
                    self.explain("file");
                    return;
                };
                let opened = Opened {
                    uid: hdr.uid,
                    pid: hdr.pid,
                    path,
                    mode: access_mode(ev.flags, ev.mode),
                    ts,
                };
                if ev.in_syscall != 0 {
                    self.hold_result(hdr.tid, Held::File(opened));
                } else {
                    self.record(&opened, true);
                }
            }
            EV_RESULT => {
                let Some(ev) = read_event::<ResultEvent>(bytes) else {
                    return;
                };
                for held in self.awaiting_result.remove(&hdr.tid).unwrap_or_default() {
                    self.release(held, Some(ev.ret));
                }
            }
            EV_PATH => {
                let Some(ev) = read_event::<PathEvent>(bytes) else {
                    return;
                };
                self.counters.seen += 1;
                if ev.path_len == 0 {
                    return;
                }
                let len = (ev.path_len as usize - 1).min(ev.path.len());
                let raw = String::from_utf8_lossy(&ev.path[..len]);
                let absolute = if raw.starts_with('/') {
                    raw.into_owned()
                } else {
                    match resolve_relative(hdr.pid, ev.dfd, &raw) {
                        Some(p) => p,
                        None => {
                            self.counters.unresolved += 1;
                            self.explain("path-relative");
                            return;
                        }
                    }
                };
                let mode = match ev.op {
                    OP_PROBE => MODE_PROBE,
                    OP_WATCH => MODE_WATCH,
                    _ => access_mode(ev.flags, 0),
                };
                let opened = Opened {
                    uid: hdr.uid,
                    pid: hdr.pid,
                    path: paths::lexical_normalize(&absolute),
                    mode,
                    ts,
                };
                self.record(&opened, ev.ret >= 0);
            }
            EV_EXEC => {
                let Some(ev) = read_event::<ExecEvent>(bytes) else {
                    return;
                };
                self.counters.seen += 1;
                if ev.path_len == 0 {
                    return;
                }
                let path = lossy(&ev.path[..(ev.path_len as usize - 1).min(ev.path.len())]);
                let args: Vec<String> = ev.args[..(ev.argc as usize).min(EXEC_ARGS)]
                    .iter()
                    .map(|slot| lossy(slot.split(|&b| b == 0).next().unwrap_or_default()))
                    .collect();
                let Some(subject) = commands::subject(&path, &args) else {
                    self.explain("exec");
                    return;
                };
                let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
                self.hold_result(
                    hdr.tid,
                    Held::Command {
                        uid,
                        pid: hdr.pid,
                        subject,
                        origin,
                        ts,
                    },
                );
            }
            EV_PROCESS => {
                let Some(ev) = read_event::<ProcessEvent>(bytes) else {
                    return;
                };
                self.counters.seen += 1;
                let Some(subject) = rules::process_subject(ev.how) else {
                    return;
                };
                // An app's own processes (a child it debugs, a helper) are
                // the app looking at itself. A process that is already gone
                // can't be attributed and is skipped.
                if uid_of_pid(ev.target).is_none_or(|owner| owner == hdr.uid) {
                    self.explain("process");
                    return;
                }
                let mode = match ev.how {
                    PROCESS_READ => MODE_READ,
                    PROCESS_WRITE => MODE_WRITE,
                    _ => MODE_WATCH,
                };
                let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
                self.hold_result(
                    hdr.tid,
                    Held::Record {
                        uid,
                        cat: Category::Process,
                        subject: subject.to_string(),
                        mode: mode | origin,
                        ts,
                    },
                );
            }
            EV_BINDER => {
                let Some(ev) = read_event::<BinderEvent>(bytes) else {
                    return;
                };
                self.counters.seen += 1;
                let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
                let args = &ev.args[..(ev.args_len as usize).min(ev.args.len())];
                // Sensors turned on and off are kept as sessions, and only
                // turning one on as a call; see `sensors`.
                let request = self
                    .catalog
                    .as_ref()
                    .and_then(|catalog| catalog.sensor_request(ev.interface, ev.code, args));
                if let Some(request) = request {
                    if !self.excluded(uid) {
                        let call = sensors::Call {
                            uid,
                            process_uid: hdr.uid,
                            mode: MODE_CALL | origin,
                            pid: hdr.pid,
                            connection: ev.target,
                            request,
                            ts,
                        };
                        if self.verbose {
                            println!(
                                "uid={uid} pid={} connection={} sensor {request:?}",
                                hdr.pid, ev.target
                            );
                        }
                        let known =
                            self.sensors
                                .on_call(call, sensors::process_start, Instant::now());
                        if let Some((uid, mode, subject, ts)) = known {
                            self.store(uid, Category::Service, subject, mode, true, ts, 0);
                        }
                    }
                    return;
                }
                let subject = self.catalog.as_ref().and_then(|catalog| {
                    catalog.subject(
                        ev.interface,
                        ev.code,
                        args,
                        &[hdr.uid, uid],
                        self.packages.for_uid(uid),
                    )
                });
                let Some(mut subject) = subject else {
                    self.explain("binder");
                    return;
                };
                // Whether the app asked about is there is known only now: by
                // the time the records are read it may have been installed
                // or removed.
                if let Some(package) = binder::asked_package(&subject)
                    && !self.packages.is_installed(package)
                    && let Some(marked) = binder::mark_absent(&subject)
                {
                    subject = marked;
                }
                // Only the request is seen, not what came back.
                self.store(
                    uid,
                    Category::Service,
                    subject,
                    MODE_CALL | origin,
                    true,
                    ts,
                    0,
                );
            }
            EV_TRAFFIC => {
                let Some(ev) = read_event::<bb_common::TrafficEvent>(bytes) else {
                    return;
                };
                self.counters.seen += 1;
                // Only what went to the internet, as the connections are.
                let Some(address) = net::public(ev.family, ev.port, &ev.addr) else {
                    return;
                };
                let wide = |low: u32, high: u32| (u64::from(high) << 32) | u64::from(low);
                let host = self.host_of(hdr.uid, address.ip());
                let key = self.net_key(hdr.uid, ts, host, address.ip().to_string(), address.port());
                self.writer.net_record(
                    key,
                    NetSeen {
                        sent: wide(ev.sent_low, ev.sent_high),
                        recv: wide(ev.recv_low, ev.recv_high),
                        counted: 1,
                        ..NetSeen::default()
                    },
                    ts,
                );
            }
            EV_NETLINK => {
                let Some(ev) = read_event::<NetlinkEvent>(bytes) else {
                    return;
                };
                self.counters.seen += 1;
                let Some(subject) = net::netlink_subject(ev.protocol, ev.msg_type) else {
                    return;
                };
                let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
                self.hold_result(
                    hdr.tid,
                    Held::Record {
                        uid,
                        cat: Category::Network,
                        subject: subject.to_string(),
                        mode: MODE_READ | origin,
                        ts,
                    },
                );
            }
            EV_CONNECT => {
                let Some(ev) = read_event::<ConnectEvent>(bytes) else {
                    return;
                };
                self.counters.seen += 1;
                if let Some(address) = net::public(ev.family, ev.port, &ev.addr) {
                    let (uid, _) = self.owner_uid(hdr.uid, hdr.pid);
                    let refused = self
                        .net
                        .as_ref()
                        .is_some_and(|net| net.refuses(uid, address));
                    let host = self.host_of(uid, address.ip());
                    let key = self.net_key(uid, ts, host, address.ip().to_string(), address.port());
                    self.writer.net_record(
                        key,
                        NetSeen {
                            conn: u32::from(!refused),
                            refused: u32::from(refused),
                            ..NetSeen::default()
                        },
                        ts,
                    );
                    return;
                }
                let Some(destination) = Destination::new(ev.family, ev.port, &ev.addr) else {
                    return;
                };
                // A port of the app's own is the app talking to itself. The
                // socket tables are compared by the uid the kernel sees.
                if destination.is_loopback() && self.listeners.owned_by(destination.port(), hdr.uid)
                {
                    self.explain("connect");
                    return;
                }
                let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
                // The outcome is not known (see the kernel side); what is
                // recorded is that the app tried.
                let subject = self.scans.subject(uid, &destination, ts);
                self.store(
                    uid,
                    Category::Network,
                    subject,
                    MODE_CONNECT | origin,
                    true,
                    ts,
                    0,
                );
            }
            _ => {}
        }
    }

    /// Hand what is known of the sensors apps have on to the writer, after
    /// ending the sessions of processes that have ended. What waits for the
    /// sensor list is held for at most `wait`.
    fn settle_sensors(&mut self, wait: Duration) {
        if self.sensors.any_on() {
            self.sensors.check_processes(now(), sensors::process_alive);
        }
        let settled = self.sensors.settle(Instant::now(), now(), wait);
        for (uid, mode, subject, ts) in settled.calls {
            self.store(uid, Category::Service, subject, mode, true, ts, 0);
        }
        for session in settled.sessions {
            self.writer.sensor_session(session);
        }
        // Written when it changes, which takes an app over the limits.
        let dropped = self.sensors.dropped();
        if dropped != self.sensors_dropped
            && self
                .writer
                .set_meta("sensor_sessions_dropped", dropped as i64)
                .is_ok()
        {
            self.sensors_dropped_log
                .log("bbd: sensor sessions not kept: an app has too many open".into());
            self.sensors_dropped = dropped;
        }
    }

    /// Whether nothing is recorded of `uid`: the storage providers serve
    /// shared storage to every other app. Indexing and opening user files is
    /// their function, and would account for most of the records.
    fn excluded(&mut self, uid: u32) -> bool {
        self.packages
            .for_uid(uid)
            .iter()
            .any(|p| spool::PROVIDER_PACKAGES.contains(&p.as_str()))
    }

    /// Store what the system recorded about permissions being used.
    fn record_permission_uses(&mut self, uses: Vec<appops::Use>) {
        for used in uses {
            self.counters.seen += 1;
            if used.uid % PER_USER_RANGE < FIRST_APPLICATION_UID {
                continue;
            }
            self.store(
                used.uid,
                Category::Permission,
                used.subject,
                MODE_READ,
                used.allowed,
                used.ts,
                0,
            );
        }
    }

    /// Record something whose syscall has returned (`ret`), or whose result
    /// is no longer expected (`None`), which is taken for success.
    fn release(&mut self, held: Held, ret: Option<i32>) {
        let ok = ret.is_none_or(|ret| ret >= 0);
        match held {
            Held::File(opened) => self.record(&opened, ok),
            Held::Command {
                uid,
                pid,
                subject,
                origin,
                ts,
            } => {
                if let Some(ok) = self.searches.outcome(pid, uid, origin, &subject, ts, ret) {
                    self.store(
                        uid,
                        Category::Command,
                        subject,
                        MODE_EXEC | origin,
                        ok,
                        ts,
                        0,
                    );
                }
            }
            Held::Record {
                uid,
                cat,
                subject,
                mode,
                ts,
            } => self.store(uid, cat, subject, mode, ok, ts, 0),
        }
    }

    /// Absolute path of the file a `FileEvent` describes.
    fn file_path(&mut self, ev: &FileEvent) -> Option<String> {
        let relative = join_components(&ev.data[..(ev.data_len as usize).min(ev.data.len())]);
        let base = match ev.magic {
            PROC_SUPER_MAGIC => "/proc",
            SYSFS_MAGIC => "/sys",
            SELINUX_MAGIC => "/sys/fs/selinux",
            FUSE_SUPER_MAGIC => match self.mounts.mount_point(ev.dev) {
                Some(mount_point) => mount_point,
                // Mount not visible from the daemon's namespace. Emulated
                // storage is rooted one level above the per-user directories.
                None if relative.split('/').next().is_some_and(is_number) => "/storage/emulated",
                None => return None,
            },
            // A block device node; these live in /dev.
            _ => "/dev",
        };
        Some(format!("{base}/{relative}"))
    }

    fn explain(&self, source: &str) {
        if self.explain_discards {
            println!("discard {source}");
        }
    }

    /// Decide whether an access seen by the kernel is worth keeping, and
    /// store it.
    fn record(&mut self, opened: &Opened, ok: bool) {
        let source = if opened.mode & (MODE_PROBE | MODE_WATCH) != 0 {
            "path"
        } else {
            "open"
        };
        let (uid, origin) = self.owner_uid(opened.uid, opened.pid);
        let pkgs = self.packages.for_uid(uid);
        let (cat, path) = match paths::canonicalize(&opened.path, uid / PER_USER_RANGE) {
            Some(path) if paths::is_own_dir(&path, pkgs) => {
                self.explain(source);
                return;
            }
            Some(path) => (Category::Storage, path),
            // A process reading its own /proc entries is the one that
            // opened them, so the comparison is with its own uid, which for
            // an isolated process is not its app's.
            None => match rules::classify(&opened.path, opened.uid, uid_of_pid) {
                Some(hit) => hit,
                None => {
                    self.explain(source);
                    return;
                }
            },
        };
        self.store(uid, cat, path, opened.mode | origin, ok, opened.ts, 0);
    }

    /// The app a process should be attributed to, and [`MODE_ISOLATED`] if
    /// it was traced from one of the app's isolated processes (0 otherwise).
    /// Isolated processes run under a uid of their own. Best effort: when
    /// the process is gone and its uid was not seen recently, the record
    /// stays under the isolated uid, without the flag.
    /// What a row of the network records is about. An app has so many
    /// destinations of its own in an hour; what it connects to past them
    /// is counted together, of no destination: an app that goes through
    /// addresses, or looks up names made up one after another, would
    /// otherwise fill the database, which every record shares.
    fn net_key(&mut self, uid: u32, ts: i64, host: String, addr: String, port: u16) -> NetKey {
        let hour = ts.div_euclid(3600);
        if self.net_listed.0 != hour {
            self.net_listed = (hour, HashMap::new());
        }
        let listed = self.net_listed.1.entry(uid).or_default();
        let destination = (host, addr, port);
        if listed.len() >= NET_DESTINATIONS_PER_HOUR && !listed.contains(&destination) {
            return NetKey {
                uid,
                hour,
                host: String::new(),
                addr: String::new(),
                port: 0,
            };
        }
        listed.insert(destination.clone());
        let (host, addr, port) = destination;
        NetKey {
            uid,
            hour,
            host,
            addr,
            port,
        }
    }

    /// The name `uid` was given `address` for; empty if none is known.
    fn host_of(&self, uid: u32, address: std::net::IpAddr) -> String {
        self.net
            .as_ref()
            .and_then(|net| net.names.as_ref())
            .and_then(|names| names.host_of(uid, address))
            .unwrap_or_default()
    }

    /// Record the lookups refused since the last time: each an attempt at
    /// a name that went to no address.
    fn take_refused_lookups(&mut self) {
        let Some(refused) = &self.refused_lookups else {
            return;
        };
        let ts = now();
        let lookups: Vec<(u32, String)> = refused.try_iter().collect();
        for (uid, host) in lookups {
            let key = self.net_key(uid, ts, host, String::new(), 0);
            self.writer.net_record(
                key,
                NetSeen {
                    refused: 1,
                    ..NetSeen::default()
                },
                ts,
            );
        }
    }

    fn owner_uid(&mut self, uid: u32, pid: u32) -> (u32, Mode) {
        if uid % PER_USER_RANGE < FIRST_ISOLATED_APP_ID {
            return (uid, 0);
        }
        let now = Instant::now();
        // A process keeps its owner for life, so one already traced is not
        // looked up again: a renderer sends many events.
        if let Some((owner, seen)) = self.isolated_owners.get_mut(&(pid, uid)) {
            *seen = now;
            return (*owner, MODE_ISOLATED);
        }
        if let Some(owner) = isolated_owner(&self.packages, uid, pid) {
            self.isolated_owners.insert((pid, uid), (owner, now));
            return (owner, MODE_ISOLATED);
        }
        // The process has exited: fall back on the owner found for another
        // process of the same uid a moment ago.
        self.isolated_owners
            .iter()
            .filter(|((_, isolated), _)| *isolated == uid)
            .max_by_key(|(_, (_, seen))| *seen)
            .map_or((uid, 0), |(_, (owner, _))| (*owner, MODE_ISOLATED))
    }

    /// Forget isolated processes not seen for a while: their pids and uids
    /// are handed out again.
    fn prune_isolated_owners(&mut self) {
        let now = Instant::now();
        self.isolated_owners
            .retain(|_, (_, seen)| now.duration_since(*seen) < ISOLATED_OWNER_TTL);
    }

    /// Take in what the LSPosed hook recorded in the provider processes.
    fn ingest_provider_events(&mut self) {
        if !self.ingest_provider_events {
            return;
        }
        for ev in self.spool.collect() {
            self.counters.seen += 1;
            // Calls between system components are not app behaviour.
            if ev.uid % PER_USER_RANGE < FIRST_APPLICATION_UID {
                continue;
            }
            if let Some(collection) = personal::collection(&ev.uri) {
                let mode = match ev.op {
                    spool::Op::Query => MODE_QUERY,
                    spool::Op::Open if ev.write => MODE_WRITE | MODE_VIA_PROVIDER,
                    spool::Op::Open => MODE_READ | MODE_VIA_PROVIDER,
                };
                self.store(
                    ev.uid,
                    Category::Personal,
                    collection,
                    mode,
                    true,
                    ev.ts,
                    ev.items,
                );
                continue;
            }
            // The processes that hold personal data serve other things too
            // (dictionaries, blocked numbers, carrier settings); only the
            // storage providers are recorded in full.
            if !ev.storage_provider {
                continue;
            }
            let (cat, path, mode) = match ev.op {
                spool::Op::Query => (
                    Category::Provider,
                    spool::normalize_uri(&ev.uri),
                    MODE_QUERY,
                ),
                spool::Op::Open => {
                    let mut mode = MODE_VIA_PROVIDER;
                    if ev.read || !ev.write {
                        mode |= MODE_READ;
                    }
                    if ev.write {
                        mode |= MODE_WRITE;
                    }
                    // Prefer the real file; fall back to the URI when the
                    // provider served something that isn't on shared storage.
                    match paths::canonicalize(&ev.path, ev.uid / PER_USER_RANGE) {
                        Some(path) if paths::is_own_dir(&path, self.packages.for_uid(ev.uid)) => {
                            continue;
                        }
                        Some(path) => (Category::Storage, path, mode),
                        None => (Category::Provider, spool::normalize_uri(&ev.uri), mode),
                    }
                }
            };
            self.store(ev.uid, cat, path, mode, true, ev.ts, ev.items);
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn store(
        &mut self,
        uid: u32,
        cat: Category,
        path: String,
        mode: Mode,
        ok: bool,
        ts: i64,
        items: u32,
    ) {
        if self.excluded(uid) {
            return;
        }
        let pkg = self
            .packages
            .for_uid(uid)
            .first()
            .map_or("", String::as_str);
        self.counters.kept += 1;
        if self.verbose {
            println!(
                "cat={} how={} {}",
                cat.as_str(),
                mode_label(mode),
                if ok { "ok" } else { "failed" },
            );
        }
        self.writer.record(uid, pkg, cat, path, mode, ok, ts, items);
    }

    /// Record what was held for a result that never arrived (seen before
    /// `cutoff`), as successful. For a program started from a thread other
    /// than the main one this is the normal case: the exec moves the thread
    /// to the main thread's id, so its return is not matched to the call.
    fn expire(&mut self, cutoff: i64) {
        let stale: Vec<u32> = self
            .awaiting_result
            .iter()
            .filter(|(_, held)| held.iter().all(|h| h.ts() <= cutoff))
            .map(|(tid, _)| *tid)
            .collect();
        for tid in stale {
            for held in self.awaiting_result.remove(&tid).unwrap_or_default() {
                self.release(held, None);
            }
        }
        // Commands looked for and, the search being over, not found.
        for (uid, origin, subject, ts) in self.searches.finished(cutoff) {
            self.store(
                uid,
                Category::Command,
                subject,
                MODE_EXEC | origin,
                false,
                ts,
                0,
            );
        }
    }
}

fn lossy(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}

fn is_number(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
}

/// Turn the kernel's leaf-first, NUL-separated component list into a normal
/// relative path: `a.jpg\0Camera\0DCIM\0` -> `DCIM/Camera/a.jpg`.
fn join_components(data: &[u8]) -> String {
    let mut parts: Vec<&[u8]> = data.split(|&b| b == 0).filter(|p| !p.is_empty()).collect();
    parts.reverse();
    String::from_utf8_lossy(&parts.join(&b'/')).into_owned()
}

/// Resolve a relative path against the directory it was relative to: the
/// process's cwd, or the directory `dfd` refers to. Best effort — the process
/// may have exited or closed the fd by the time we look.
fn resolve_relative(pid: u32, dfd: i32, relative: &str) -> Option<String> {
    let link = if dfd == AT_FDCWD {
        format!("/proc/{pid}/cwd")
    } else {
        format!("/proc/{pid}/fd/{dfd}")
    };
    let base = fs::read_link(link).ok()?;
    let base = base.to_str()?;
    base.starts_with('/').then(|| format!("{base}/{relative}"))
}

/// The owner of a live isolated process. One forked from an app zygote has
/// that zygote, which runs as the app itself, for a parent: the answer from
/// the first moment. Otherwise the process's name says which app it belongs
/// to. A pid may already belong to another process, so it must still run as
/// `uid`.
fn isolated_owner(packages: &Packages, uid: u32, pid: u32) -> Option<u32> {
    let status = fs::read_to_string(format!("/proc/{pid}/status")).ok()?;
    if parse_status_uid(&status) != Some(uid) {
        return None;
    }
    let parent = parse_status_field(&status, "PPid:").and_then(uid_of_pid);
    if let Some(parent) = parent
        && parent / PER_USER_RANGE == uid / PER_USER_RANGE
        && (FIRST_APPLICATION_UID..FIRST_ISOLATED_APP_ID).contains(&(parent % PER_USER_RANGE))
    {
        return Some(parent);
    }
    let cmdline = fs::read(format!("/proc/{pid}/cmdline")).ok()?;
    let name = cmdline.split(|&b| b == 0).next()?;
    packages.owner_of_isolated(uid, std::str::from_utf8(name).ok()?)
}

/// The real uid that owns `pid`, or None if the process is gone. Read from
/// `status` rather than from the directory's owner: /proc/<pid> is owned by
/// root for non-dumpable processes, which most Android apps are.
fn uid_of_pid(pid: u32) -> Option<u32> {
    let status = fs::read_to_string(format!("/proc/{pid}/status")).ok()?;
    parse_status_uid(&status)
}

fn parse_status_uid(status: &str) -> Option<u32> {
    parse_status_field(status, "Uid:")
}

/// The first number of a `/proc/<pid>/status` line such as `PPid:\t123`.
fn parse_status_field(status: &str, field: &str) -> Option<u32> {
    status
        .lines()
        .find_map(|line| line.strip_prefix(field))?
        .split_ascii_whitespace()
        .next()?
        .parse()
        .ok()
}

/// Access mode bits from open flags and, where known, the inode's mode
/// (0 if not): a directory can be opened without O_DIRECTORY.
fn access_mode(flags: u32, inode_mode: u32) -> Mode {
    let flags = flags as i32;
    let mut mode = match flags & libc::O_ACCMODE {
        libc::O_RDONLY => MODE_READ,
        libc::O_WRONLY => MODE_WRITE,
        _ => MODE_READ | MODE_WRITE,
    };
    let is_dir = inode_mode & S_IFMT == S_IFDIR;
    if flags & libc::O_DIRECTORY != 0 || is_dir {
        mode |= MODE_DIR;
    }
    mode
}

pub fn mode_label(mode: Mode) -> &'static str {
    if mode & MODE_EXEC != 0 {
        return "exec";
    }
    if mode & MODE_CONNECT != 0 {
        return "connect";
    }
    if mode & MODE_CALL != 0 {
        return "call";
    }
    if mode & MODE_QUERY != 0 {
        return "query";
    }
    if mode & MODE_VIA_PROVIDER != 0 {
        return if mode & MODE_WRITE != 0 {
            "write-via-provider"
        } else {
            "read-via-provider"
        };
    }
    if mode & MODE_PROBE != 0 {
        return "probe";
    }
    if mode & MODE_WATCH != 0 {
        return "watch";
    }
    match (mode & MODE_DIR != 0, mode & (MODE_READ | MODE_WRITE)) {
        (true, _) => "list",
        (false, MODE_READ) => "read",
        (false, MODE_WRITE) => "write",
        (false, _) => "read+write",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn complete_path_events_are_recorded_and_other_bytes_are_ignored() {
        let dir = std::env::temp_dir().join(format!("bbd-trace-events-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let db = dir.join("bb.db");
        let list = dir.join("packages.list");
        fs::write(
            &list,
            "com.example.reader 10100 0 /data/user/0/com.example.reader default none\n",
        )
        .unwrap();
        let opts = Options {
            db: &db,
            packages_list: list.to_str().unwrap(),
            retention_days: 30,
            verbose: false,
            profile: false,
            explain_discards: false,
            ingest_provider_events: false,
            sandbox: false,
        };
        let mut tracer = Tracer::new(
            &opts,
            Writer::open(&db).unwrap(),
            sensors::Sensors::new(sensors::dumpsys),
        );
        let clock = EventClock {
            boot_unix_ns: 5_000_000_000,
        };
        let path = b"/sdcard/Documents/example.txt\0";
        let mut event = PathEvent {
            hdr: EventHeader {
                kind: EV_PATH,
                uid: 10100,
                pid: 123,
                tid: 123,
                boot_ns_low: 0,
                boot_ns_high: 0,
            },
            op: bb_common::OP_OPEN,
            ret: 3,
            dfd: AT_FDCWD,
            flags: libc::O_RDONLY as u32,
            abi: bb_common::ABI_NATIVE,
            path_len: path.len() as u32,
            path: [0; bb_common::PATH_CAP],
        };
        event.path[..path.len()].copy_from_slice(path);
        let bytes = selftest::event_bytes(&event);
        tracer.on_event(&bytes[..size_of::<EventHeader>() - 1], &clock);
        tracer.on_event(&bytes[..bytes.len() - 1], &clock);
        let mut unknown = event;
        unknown.hdr.kind = u32::MAX;
        tracer.on_event(selftest::event_bytes(&unknown), &clock);
        let mut unaligned = vec![0];
        unaligned.extend_from_slice(bytes);
        tracer.on_event(&unaligned[1..], &clock);
        tracer.writer.flush(&tracer.counters, 5).unwrap();
        let rows = crate::store::Reader::open(&db)
            .unwrap()
            .files(&[10100], &[Category::Storage], None, None, 0, 10, 0)
            .unwrap();
        assert_eq!(rows.len(), 1);
        let row = &rows[0];
        assert_eq!(row.pkg, "com.example.reader");
        assert_eq!(row.path, "/storage/emulated/0/Documents/example.txt");
        assert_eq!(
            (row.ok, row.fail, row.mode, row.last_ts),
            (1, 0, MODE_READ, 5)
        );
        assert_eq!(tracer.counters.seen, 1);
        drop(tracer);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn open_flags_and_file_type_set_the_mode() {
        assert_eq!(access_mode(libc::O_RDONLY as u32, 0), MODE_READ);
        assert_eq!(
            access_mode((libc::O_WRONLY | libc::O_CREAT) as u32, 0),
            MODE_WRITE
        );
        assert_eq!(access_mode(libc::O_RDWR as u32, 0), MODE_READ | MODE_WRITE);
        assert_eq!(
            access_mode(
                (libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC) as u32,
                0
            ),
            MODE_READ | MODE_DIR
        );
        // A directory opened without O_DIRECTORY is still a directory.
        assert_eq!(
            access_mode(libc::O_RDONLY as u32, S_IFDIR | 0o755),
            MODE_READ | MODE_DIR
        );
        assert_eq!(access_mode(libc::O_RDONLY as u32, 0o100644), MODE_READ);
        assert_eq!(mode_label(MODE_READ | MODE_DIR), "list");
        assert_eq!(mode_label(MODE_READ | MODE_WRITE), "read+write");
        assert_eq!(mode_label(MODE_PROBE), "probe");
        assert_eq!(mode_label(MODE_WATCH), "watch");
        assert_eq!(mode_label(MODE_QUERY), "query");
        assert_eq!(mode_label(MODE_CONNECT), "connect");
        assert_eq!(mode_label(MODE_EXEC), "exec");
        assert_eq!(
            mode_label(MODE_READ | MODE_VIA_PROVIDER),
            "read-via-provider"
        );
        assert_eq!(
            mode_label(MODE_READ | MODE_WRITE | MODE_VIA_PROVIDER),
            "write-via-provider"
        );
    }

    #[test]
    fn components_are_reversed_and_joined() {
        assert_eq!(
            join_components(b"a.jpg\0Camera\0DCIM\x000\0"),
            "0/DCIM/Camera/a.jpg"
        );
        assert_eq!(join_components(b"cpuinfo\0"), "cpuinfo");
        assert_eq!(join_components(b""), "");
        // Non-UTF-8 names don't abort; they are replaced lossily.
        assert_eq!(join_components(b"\xff\0d\0"), "d/\u{fffd}");
    }

    #[test]
    fn a_failing_helper_is_restarted_less_and_less_often() {
        let delays: Vec<u64> = (1..=10)
            .map(|failures| appops_retry_delay(failures).as_secs())
            .collect();
        assert_eq!(delays, [30, 60, 120, 240, 480, 960, 1920, 3600, 3600, 3600]);
        assert_eq!(appops_retry_delay(u32::MAX), APPOPS_RETRY_MAX);
    }

    #[test]
    fn a_recurring_message_is_logged_once_an_hour() {
        let mut log = RecurringLog::default();
        let start = Instant::now();
        let at = |secs| start + Duration::from_secs(secs);
        assert_eq!(log.line("a".into(), at(0)).as_deref(), Some("a"));
        assert_eq!(log.line("a".into(), at(30)), None);
        assert_eq!(log.line("a".into(), at(90)), None);
        assert_eq!(
            log.line("a".into(), at(3600)).as_deref(),
            Some("a (2 more since)")
        );
        assert_eq!(log.line("a".into(), at(3630)), None);
        // A different message is logged at once.
        assert_eq!(log.line("b".into(), at(3660)).as_deref(), Some("b"));
        assert_eq!(log.line("a".into(), at(3690)).as_deref(), Some("a"));
        // One that recurs no more than hourly is logged each time, as is.
        assert_eq!(log.line("a".into(), at(7290)).as_deref(), Some("a"));
    }

    #[test]
    fn a_helper_failure_is_described_by_its_first_line() {
        let said = "\n  java.lang.IllegalStateException: no service\n\tat x.y(Z.java:1)\n";
        assert_eq!(
            first_line(said.as_bytes()).as_deref(),
            Some("java.lang.IllegalStateException: no service")
        );
        assert_eq!(first_line(&b""[..]), None);
    }

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

    #[test]
    fn events_are_dated_by_when_the_kernel_saw_them() {
        let clock = EventClock {
            boot_unix_ns: 1_700_000_000 * 1_000_000_000,
        };
        assert_eq!(clock.unix_secs(5_999_999_999), 1_700_000_005);
        assert_eq!(clock.unix_secs(0), 1_700_000_000);

        // An event from a moment ago, read now.
        let boot_ns = clock_ns(libc::CLOCK_BOOTTIME) as u64 - 20_000_000_000;
        let ts = EventClock::now().unix_secs(boot_ns);
        assert!((now() - 21..=now() - 19).contains(&ts), "{ts}");
    }

    #[test]
    fn status_uid_is_the_real_uid() {
        let status =
            "Name:\tcom.x\nPid:\t500\nPPid:\t7870\nUid:\t10100\t10101\t10102\t10103\nGid:\t10100\n";
        assert_eq!(parse_status_uid(status), Some(10100));
        assert_eq!(parse_status_uid("Name:\tx\n"), None);
        assert_eq!(parse_status_field(status, "PPid:"), Some(7870));
    }
}
