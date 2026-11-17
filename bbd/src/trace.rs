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
    collections::HashMap,
    fs,
    path::Path,
    ptr,
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
    EV_NETLINK, EV_PATH, EV_PROCESS, EV_RESULT, EXEC_ARGS, EventHeader, ExecEvent,
    FUSE_SUPER_MAGIC, FileEvent, NetlinkEvent, OFF_MISSING, OP_PROBE, OP_WATCH, PROC_SUPER_MAGIC,
    PROCESS_READ, PROCESS_WRITE, PathEvent, ProcessEvent, ResultEvent, SELINUX_MAGIC, SYSFS_MAGIC,
};
use tokio::{
    io::{Interest, unix::AsyncFd},
    net::{UnixListener, UnixStream},
    signal::unix::{SignalKind, signal},
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
    paths, personal, rules, sensors,
    spool::{self, Spool},
    store::{
        Category, Counters, MODE_CALL, MODE_CONNECT, MODE_DIR, MODE_EXEC, MODE_ISOLATED,
        MODE_PROBE, MODE_QUERY, MODE_READ, MODE_VIA_PROVIDER, MODE_WATCH, MODE_WRITE, Mode, Writer,
    },
    syscalls,
};

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
}

pub async fn run(opts: Options<'_>) -> anyhow::Result<()> {
    // Kernels before 5.11 account BPF memory against RLIMIT_MEMLOCK
    // (https://lwn.net/Articles/837122/); GKI 5.10 is one of them.
    let rlim = libc::rlimit {
        rlim_cur: libc::RLIM_INFINITY,
        rlim_max: libc::RLIM_INFINITY,
    };
    if unsafe { libc::setrlimit(libc::RLIMIT_MEMLOCK, &rlim) } != 0 {
        eprintln!("bbd: warning: could not raise RLIMIT_MEMLOCK");
    }

    let writer = Writer::open(opts.db)?;
    // Besides expiring old rows, drop what earlier versions recorded but the
    // current rules exclude.
    let pruned = writer.prune(opts.retention_days, now())?
        + writer.delete_packages(spool::PROVIDER_PACKAGES)?
        + writer.delete_untopical()?;
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

    let offsets = Btf::from_kernel()?.kernel_offsets()?;
    let mut ebpf = EbpfLoader::new()
        .override_global("OFFSETS", &offsets[..], true)
        .load(aya::include_bytes_aligned!(concat!(
            env!("OUT_DIR"),
            "/bb-ebpf"
        )))
        .context("loading eBPF object (need root and a BPF-enabled kernel)")?;

    let kprobe: &mut KProbe = ebpf
        .program_mut("file_open")
        .context("program file_open missing from object")?
        .try_into()?;
    kprobe.load().context("verifier rejected file_open")?;
    kprobe
        .attach("security_file_open", 0)
        .context("attaching kprobe security_file_open")?;

    // Syscall probes: one entry program per argument layout plus a shared
    // return program, attached to each syscall function this kernel has.
    let symbols = syscalls::kernel_symbols().context("reading /proc/kallsyms")?;
    anyhow::ensure!(
        symbols.contains(syscalls::REQUIRED),
        "{} not in /proc/kallsyms (unsupported kernel, or kallsyms restricted)",
        syscalls::REQUIRED
    );
    // The return program is shared by every feature that reports outcomes.
    let returns: &mut KProbe = ebpf
        .program_mut("sys_ret")
        .context("program sys_ret missing from object")?
        .try_into()?;
    returns.load().context("verifier rejected sys_ret")?;
    let mut functions = attach_feature(&mut ebpf, &syscalls::FILES, &symbols, &offsets)
        .context("setting up the file probes")?;

    // Everything else is recorded where the kernel allows it. A probe that
    // can't be set up costs its own records, not the daemon: what is and
    // isn't covered is stored for `bbd stats` to report.
    for feature in syscalls::FEATURES {
        let attached = attach_feature(&mut ebpf, feature, &symbols, &offsets);
        match &attached {
            Ok(count) => functions += count,
            Err(e) => eprintln!("bbd: warning: not recording {}: {e:#}", feature.name),
        }
        writer.set_meta(
            &format!("coverage_{}", feature.name),
            i64::from(attached.is_ok()),
        )?;
    }

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
    let dropped_events = || {
        dropped
            .get(&0, 0)
            .map_or(0, |per_cpu| per_cpu.iter().sum::<u64>())
    };
    let ring = RingBuf::try_from(ebpf.take_map("EVENTS").context("EVENTS map missing")?)?;
    // SAFETY: the RingBuf owns its fd, which stays open and unchanged for as
    // long as the AsyncFd owns the RingBuf.
    let mut ring = unsafe { AsyncFd::register_with_interest(ring, Interest::READABLE) }?;

    eprintln!(
        "bbd: tracing {functions} syscall functions; db={} retention={}d pruned={pruned}",
        opts.db.display(),
        opts.retention_days
    );

    let mut tracer = Tracer {
        packages: Packages::new(opts.packages_list),
        mounts: Mounts::new(),
        spool: Spool::new(),
        listeners: Listeners::new(),
        scans: net::Scans::default(),
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
    };
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
    // Queries ask for what is held to be written first (see `control`).
    let requests = match control::listen(opts.db) {
        Ok(listener) => Some(listener),
        Err(e) => {
            eprintln!("bbd: warning: queries will not wait for records to be written: {e:#}");
            None
        }
    };
    let mut flush_tick = interval(FLUSH_INTERVAL);
    flush_tick.set_missed_tick_behavior(MissedTickBehavior::Delay);
    let mut prune_tick = interval(PRUNE_INTERVAL);
    prune_tick.tick().await; // first tick fires immediately; already pruned above
    let mut sigint = signal(SignalKind::interrupt())?;
    let mut sigterm = signal(SignalKind::terminate())?;
    let mut write_errors = RecurringLog::default();
    let mut sensor_check = SensorCheck::default();

    loop {
        sensor_check.arm(tracer.sensors.any_on());
        // Every branch that does not end in a flush continues the loop.
        let request = tokio::select! {
            _ = sigint.recv() => break,
            _ = sigterm.recv() => break,
            _ = flush_tick.tick() => None,
            // Sessions whose process has ended end now, and are written
            // with the next write.
            _ = sensor_check.tick(), if sensor_check.armed() => {
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
            _ = prune_tick.tick() => {
                if let Err(e) = tracer.writer.prune(opts.retention_days, now()) {
                    eprintln!("bbd: could not prune old records: {e:#}");
                }
                continue;
            }
            // The kernel side's buffer is filling up.
            guard = ring.readable_mut() => {
                let mut guard = guard?;
                tracer.drain(guard.get_inner_mut());
                guard.clear_ready();
                continue;
            }
        };

        tracer.drain(ring.get_mut());
        tracer.counters.dropped = dropped_events();
        tracer.expire(now() - RESULT_TIMEOUT_SECS);
        tracer.prune_isolated_owners();
        tracer.ingest_provider_events();
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

    control::unlisten(opts.db);
    if opts.profile {
        print_profile(&ebpf);
    }
    tracer.drain(ring.get_mut());
    tracer.ingest_provider_events();
    tracer.settle_sensors(Duration::from_secs(2));
    tracer.counters.dropped = dropped_events();
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

/// The next query asking for the records to be written; `None` if accepting
/// it failed. Never ready without a listener.
async fn next_request(listener: Option<&UnixListener>) -> Option<UnixStream> {
    match listener {
        Some(listener) => listener.accept().await.ok().map(|(stream, _)| stream),
        None => std::future::pending().await,
    }
}

/// Load and attach the probes of one optional feature. Returns how many
/// kernel functions they were attached to.
fn attach_feature(
    ebpf: &mut aya::Ebpf,
    feature: &syscalls::Feature,
    symbols: &std::collections::HashSet<&'static str>,
    offsets: &[u32],
) -> anyhow::Result<usize> {
    anyhow::ensure!(
        feature
            .offsets
            .iter()
            .all(|&slot| offsets[slot] != OFF_MISSING),
        "this kernel's type information lacks a field it reads"
    );
    let plan = syscalls::attach_plan(feature.table, symbols);
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
    Ok(plan.len())
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

/// Look up the device's transaction codes on a thread of its own: starting
/// the Java runtime that prints them takes about a second, and must not hold
/// up recording. The task ends with the catalog, or `None` if it could not
/// be had; the event loop waits for it alongside everything else.
fn spawn_catalog_loader() -> tokio::task::JoinHandle<Option<Catalog>> {
    tokio::task::spawn_blocking(|| {
        let mut last_error = None;
        for _ in 0..CATALOG_ATTEMPTS {
            match binder::load() {
                Ok(catalog) => return Some(catalog),
                Err(e) => last_error = Some(e),
            }
            std::thread::sleep(CATALOG_RETRY);
        }
        if let Some(e) = last_error {
            eprintln!("bbd: warning: not recording service calls: {e:#}");
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

/// Reinterpret ring buffer bytes as an event struct.
fn cast<T: Copy>(bytes: &[u8]) -> Option<T> {
    // SAFETY: only used with the repr(C) plain-data event structs from
    // bb-common, for which any bit pattern is valid.
    (bytes.len() >= size_of::<T>()).then(|| unsafe { ptr::read_unaligned(bytes.as_ptr().cast()) })
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
}

impl Tracer {
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

    /// Take in everything waiting in the ring buffer.
    fn drain(&mut self, ring: &mut RingBuf<MapData>) {
        let clock = EventClock::now();
        while let Some(item) = ring.next() {
            self.on_event(&item, &clock);
        }
    }

    fn on_event(&mut self, bytes: &[u8], clock: &EventClock) {
        let Some(hdr) = cast::<EventHeader>(bytes) else {
            return;
        };
        let ts = clock.unix_secs(hdr.boot_ns());
        match hdr.kind {
            EV_FILE => {
                let Some(ev) = cast::<FileEvent>(bytes) else {
                    return;
                };
                self.counters.seen += 1;
                // A path that didn't fit is missing its outermost
                // components; joined to the mount point it would name a
                // different, wrong file.
                if ev.truncated != 0 {
                    self.counters.unresolved += 1;
                    self.explain("file", "truncated");
                    return;
                }
                let Some(path) = self.file_path(&ev) else {
                    self.counters.unresolved += 1;
                    self.explain("file", "unresolved");
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
                    self.awaiting_result
                        .entry(hdr.tid)
                        .or_default()
                        .push(Held::File(opened));
                } else {
                    self.record(&opened, true);
                }
            }
            EV_RESULT => {
                let Some(ev) = cast::<ResultEvent>(bytes) else {
                    return;
                };
                for held in self.awaiting_result.remove(&hdr.tid).unwrap_or_default() {
                    self.release(held, Some(ev.ret));
                }
            }
            EV_PATH => {
                let Some(ev) = cast::<PathEvent>(bytes) else {
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
                            self.explain("path-relative", &raw);
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
                let Some(ev) = cast::<ExecEvent>(bytes) else {
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
                    self.explain("exec", &path);
                    return;
                };
                let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
                self.awaiting_result
                    .entry(hdr.tid)
                    .or_default()
                    .push(Held::Command {
                        uid,
                        pid: hdr.pid,
                        subject,
                        origin,
                        ts,
                    });
            }
            EV_PROCESS => {
                let Some(ev) = cast::<ProcessEvent>(bytes) else {
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
                    self.explain("process", "<own or gone>");
                    return;
                }
                let mode = match ev.how {
                    PROCESS_READ => MODE_READ,
                    PROCESS_WRITE => MODE_WRITE,
                    _ => MODE_WATCH,
                };
                let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
                self.awaiting_result
                    .entry(hdr.tid)
                    .or_default()
                    .push(Held::Record {
                        uid,
                        cat: Category::Process,
                        subject: subject.to_string(),
                        mode: mode | origin,
                        ts,
                    });
            }
            EV_BINDER => {
                let Some(ev) = cast::<BinderEvent>(bytes) else {
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
                    self.explain("binder", "<about itself, or not watched>");
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
            EV_NETLINK => {
                let Some(ev) = cast::<NetlinkEvent>(bytes) else {
                    return;
                };
                self.counters.seen += 1;
                let Some(subject) = net::netlink_subject(ev.protocol, ev.msg_type) else {
                    return;
                };
                let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
                self.awaiting_result
                    .entry(hdr.tid)
                    .or_default()
                    .push(Held::Record {
                        uid,
                        cat: Category::Network,
                        subject: subject.to_string(),
                        mode: MODE_READ | origin,
                        ts,
                    });
            }
            EV_CONNECT => {
                let Some(ev) = cast::<ConnectEvent>(bytes) else {
                    return;
                };
                self.counters.seen += 1;
                let Some(destination) = Destination::new(ev.family, ev.port, &ev.addr) else {
                    return;
                };
                // A port of the app's own is the app talking to itself. The
                // socket tables are compared by the uid the kernel sees.
                if destination.is_loopback() && self.listeners.owned_by(destination.port(), hdr.uid)
                {
                    self.explain("connect", "<own port>");
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

    fn explain(&self, source: &str, path: &str) {
        if self.explain_discards {
            println!("discard {source} {path}");
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
                self.explain(source, "<own app directory>");
                return;
            }
            Some(path) => (Category::Storage, path),
            // A process reading its own /proc entries is the one that
            // opened them, so the comparison is with its own uid, which for
            // an isolated process is not its app's.
            None => match rules::classify(&opened.path, opened.uid, uid_of_pid) {
                Some(hit) => hit,
                None => {
                    self.explain(source, &opened.path);
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
        let hosts = spool::hosts(|listed| self.packages.sharing_uid_with(listed));
        for ev in self.spool.collect(&hosts) {
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
                "uid={uid} pkg={} cat={} how={} {} {path}",
                if pkg.is_empty() { "?" } else { pkg },
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
