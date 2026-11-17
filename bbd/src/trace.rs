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
    signal::unix::{SignalKind, signal},
    time::{MissedTickBehavior, interval},
};

use crate::{
    appops,
    binder::{self, Catalog},
    btf::Btf,
    commands, helper,
    mounts::Mounts,
    net::{self, Destination, Listeners},
    packages::{FIRST_ISOLATED_APP_ID, PER_USER_RANGE, Packages},
    paths, personal, rules,
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
const FLUSH_INTERVAL: Duration = Duration::from_secs(5);
const PRUNE_INTERVAL: Duration = Duration::from_secs(6 * 3600);
/// The kernel side only wakes us when the ring buffer is filling up; this
/// timer bounds how long events otherwise sit in it.
const DRAIN_INTERVAL: Duration = Duration::from_secs(1);
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
    let permission_uses = spawn_appops_reader();
    let mut appops = appops::Tracker::new(now());

    // Calls to system services. The tracepoint is attached now and reports
    // nothing until the device's transaction codes are known, which takes a
    // running app_process and so a system that has come up; `coverage_binder`
    // turns 1 at that point.
    writer.set_meta("coverage_binder", COVERAGE_PENDING)?;
    let mut binder_watch = match attach_binder(&mut ebpf) {
        Ok(map) => Some((map, spawn_catalog_loader())),
        Err(e) => {
            eprintln!("bbd: warning: not recording service calls: {e:#}");
            writer.set_meta("coverage_binder", 0)?;
            None
        }
    };

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
        writer,
        counters: Counters::default(),
        awaiting_result: HashMap::new(),
        isolated_owners: HashMap::new(),
        verbose: opts.verbose,
        explain_discards: opts.explain_discards,
        ingest_provider_events: opts.ingest_provider_events,
    };
    let mut flush_tick = interval(FLUSH_INTERVAL);
    flush_tick.set_missed_tick_behavior(MissedTickBehavior::Delay);
    let mut drain_tick = interval(DRAIN_INTERVAL);
    drain_tick.set_missed_tick_behavior(MissedTickBehavior::Delay);
    let mut prune_tick = interval(PRUNE_INTERVAL);
    prune_tick.tick().await; // first tick fires immediately; already pruned above
    let mut sigint = signal(SignalKind::interrupt())?;
    let mut sigterm = signal(SignalKind::terminate())?;

    loop {
        tokio::select! {
            _ = sigint.recv() => break,
            _ = sigterm.recv() => break,
            _ = flush_tick.tick() => {
                tracer.counters.dropped = dropped_events();
                tracer.expire(now() - RESULT_TIMEOUT_SECS);
                tracer.prune_isolated_owners();
                tracer.ingest_provider_events();
                while let Ok(reading) = permission_uses.try_recv() {
                    // A reading at all shows the records can be read here.
                    let covered = i64::from(reading.is_some());
                    tracer.writer.set_meta("coverage_appops", covered).ok();
                    if let Some(entries) = reading {
                        tracer.record_permission_uses(appops.update(&entries));
                    }
                }
                if let Some((map, loaded)) = &mut binder_watch
                    && let Ok(loaded) = loaded.try_recv()
                {
                    let watching = loaded.and_then(|catalog| match watch(map, &catalog) {
                        Ok(()) => Some(catalog),
                        Err(e) => {
                            eprintln!("bbd: warning: not recording service calls: {e:#}");
                            None
                        }
                    });
                    if let Some(catalog) = &watching {
                        eprintln!("bbd: watching {} service methods", catalog.len());
                    }
                    let covered = i64::from(watching.is_some());
                    tracer.writer.set_meta("coverage_binder", covered).ok();
                    tracer.catalog = watching;
                }
                // A failed write (disk full, database busy) keeps the batch
                // for the next attempt; it is not a reason to stop recording.
                if let Err(e) = tracer.writer.flush(&tracer.counters, now()) {
                    eprintln!("bbd: could not write records: {e:#}");
                }
            }
            _ = drain_tick.tick() => {
                let ts = now();
                while let Some(item) = ring.get_mut().next() {
                    tracer.on_event(&item, ts);
                }
            }
            _ = prune_tick.tick() => {
                if let Err(e) = tracer.writer.prune(opts.retention_days, now()) {
                    eprintln!("bbd: could not prune old records: {e:#}");
                }
            }
            guard = ring.readable_mut() => {
                let mut guard = guard?;
                let ts = now();
                while let Some(item) = guard.get_inner_mut().next() {
                    tracer.on_event(&item, ts);
                }
                guard.clear_ready();
            }
        }
    }

    if opts.profile {
        print_profile(&ebpf);
    }
    let ts = now();
    while let Some(item) = ring.get_mut().next() {
        tracer.on_event(&item, ts);
    }
    tracer.ingest_provider_events();
    tracer.counters.dropped = dropped_events();
    tracer.expire(i64::MAX);
    tracer.writer.flush(&tracer.counters, now())?;
    let c = &tracer.counters;
    eprintln!(
        "bbd: stopped; seen={} kept={} unresolved={} dropped={}",
        c.seen, c.kept, c.unresolved, c.dropped
    );
    Ok(())
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
        map.insert(key, value, 0)?;
    }
    Ok(())
}

/// Value of a `coverage_*` entry while it is not yet known whether the
/// feature works on this device: neither 1 (recording) nor 0 (can't).
const COVERAGE_PENDING: i64 = -1;

/// How soon the program that reads the permission usage records is started
/// again after it ends, and after how many times without a single reading
/// the records are reported as unavailable (the attempts go on regardless).
/// The helper itself waits for the system service at boot, so these are for
/// a helper that fails or ends later.
const APPOPS_RETRY: Duration = Duration::from_secs(30);
const APPOPS_GIVE_UP: u32 = 10;

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
        loop {
            // Returns when the helper has ended, which it is not meant to.
            let ended = stream_appops(&sender, &mut read_once);
            if matches!(ended, Err(StreamEnd::DaemonGone)) {
                return;
            }
            failures += 1;
            // Reported once, when it stops looking like a system that is
            // still starting.
            if failures == APPOPS_GIVE_UP && !read_once {
                if let Err(StreamEnd::Helper(e)) = &ended {
                    eprintln!("bbd: warning: not recording permission use: {e:#}");
                }
                if sender.send(None).is_err() {
                    return;
                }
            }
            std::thread::sleep(APPOPS_RETRY);
        }
    });
    receiver
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
) -> Result<(), StreamEnd> {
    use std::io::BufRead as _;

    let interval = APPOPS_INTERVAL.as_secs().to_string();
    let mut args: Vec<&str> = vec!["--every", &interval];
    for op in appops::ops() {
        args.push(op);
    }
    // Its stdin is held open for as long as `helper` lives; the helper
    // leaves when it closes, so it does not outlive the daemon.
    let mut helper =
        helper::spawn(appops::HELPER_CLASS, args.into_iter()).map_err(StreamEnd::Helper)?;
    let output = helper.stdout.take().expect("spawned with a piped stdout");
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
            return Err(StreamEnd::DaemonGone);
        }
        reading.clear();
    }
    let status = helper.wait();
    Err(StreamEnd::Helper(anyhow::anyhow!(
        "the helper program ended ({status:?})"
    )))
}

/// How often, and how many times, the transaction codes are asked for
/// before giving up. The first attempts fail while the system is starting.
const CATALOG_RETRY: Duration = Duration::from_secs(30);
const CATALOG_ATTEMPTS: u32 = 20;

/// Look up the device's transaction codes on a thread of its own: starting
/// the Java runtime that prints them takes about a second, and must not hold
/// up recording. The catalog arrives on the returned channel, once; `None`
/// if it could not be had.
fn spawn_catalog_loader() -> std::sync::mpsc::Receiver<Option<Catalog>> {
    let (sender, receiver) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let mut last_error = None;
        for _ in 0..CATALOG_ATTEMPTS {
            match binder::load() {
                Ok(catalog) => {
                    let _ = sender.send(Some(catalog));
                    return;
                }
                Err(e) => last_error = Some(e),
            }
            std::thread::sleep(CATALOG_RETRY);
        }
        if let Some(e) = last_error {
            eprintln!("bbd: warning: not recording service calls: {e:#}");
        }
        let _ = sender.send(None);
    });
    receiver
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
    fn on_event(&mut self, bytes: &[u8], ts: i64) {
        let Some(hdr) = cast::<EventHeader>(bytes) else {
            return;
        };
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
        let pkgs = self.packages.for_uid(uid);
        // The storage providers serve shared storage to every other app.
        // Indexing and opening user files is their function, and would
        // account for most of the records.
        if pkgs
            .iter()
            .any(|p| spool::PROVIDER_PACKAGES.contains(&p.as_str()))
        {
            return;
        }
        let pkg = pkgs.first().map_or("", String::as_str);
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
    fn status_uid_is_the_real_uid() {
        let status =
            "Name:\tcom.x\nPid:\t500\nPPid:\t7870\nUid:\t10100\t10101\t10102\t10103\nGid:\t10100\n";
        assert_eq!(parse_status_uid(status), Some(10100));
        assert_eq!(parse_status_uid("Name:\tx\n"), None);
        assert_eq!(parse_status_field(status, "PPid:"), Some(7870));
    }
}
