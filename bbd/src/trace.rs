//! `bbd run`: load the eBPF programs and turn kernel events into stored rows.

use std::{
    collections::HashMap,
    fs,
    path::Path,
    ptr,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use anyhow::Context as _;
use aya::{
    EbpfLoader,
    maps::{MapData, PerCpuArray, RingBuf},
    programs::KProbe,
};
use bb_common::{
    EV_FILE, EV_PATH, EV_RESULT, EventHeader, FUSE_SUPER_MAGIC, FileEvent, OP_PROBE, OP_WATCH,
    PROC_SUPER_MAGIC, PathEvent, ResultEvent, SELINUX_MAGIC, SYSFS_MAGIC,
};
use tokio::{
    io::{Interest, unix::AsyncFd},
    signal::unix::{SignalKind, signal},
    time::{MissedTickBehavior, interval},
};

use crate::{
    btf::Btf,
    mounts::Mounts,
    packages::{FIRST_ISOLATED_APP_ID, PER_USER_RANGE, Packages},
    paths, rules,
    spool::{self, Spool},
    store::{
        Category, Counters, MODE_DIR, MODE_PROBE, MODE_QUERY, MODE_READ, MODE_VIA_PROVIDER,
        MODE_WATCH, MODE_WRITE, Writer,
    },
    syscalls,
};

const AT_FDCWD: i32 = -100;
/// File type bits of an inode's mode.
const S_IFMT: u32 = 0o170000;
const S_IFDIR: u32 = 0o040000;
/// Uids from this app id up belong to installed apps (and isolated processes).
const FIRST_APPLICATION_UID: u32 = 10_000;
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
    for name in syscalls::ENTRY_PROGRAMS.into_iter().chain(["sys_ret"]) {
        let program: &mut KProbe = ebpf
            .program_mut(name)
            .with_context(|| format!("program {name} missing from object"))?
            .try_into()?;
        program
            .load()
            .with_context(|| format!("verifier rejected {name}"))?;
    }
    let plan = syscalls::attach_plan(&symbols);
    for (entry, function) in &plan {
        for name in [*entry, "sys_ret"] {
            let program: &mut KProbe = ebpf.program_mut(name).unwrap().try_into()?;
            program
                .attach(function, 0)
                .with_context(|| format!("attaching {name} to {function}"))?;
        }
    }

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
        "bbd: tracing {} syscall functions; db={} retention={}d pruned={pruned}",
        plan.len(),
        opts.db.display(),
        opts.retention_days
    );

    let mut tracer = Tracer {
        packages: Packages::new(opts.packages_list),
        mounts: Mounts::new(),
        spool: Spool::new(),
        writer,
        counters: Counters::default(),
        awaiting_result: HashMap::new(),
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
                tracer.ingest_provider_events();
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
    mode: u8,
    ts: i64,
}

struct Tracer {
    packages: Packages,
    mounts: Mounts,
    spool: Spool,
    writer: Writer,
    counters: Counters,
    /// Keyed by thread id.
    awaiting_result: HashMap<u32, Vec<Opened>>,
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
                        .push(opened);
                } else {
                    self.record(&opened, true);
                }
            }
            EV_RESULT => {
                let Some(ev) = cast::<ResultEvent>(bytes) else {
                    return;
                };
                for opened in self.awaiting_result.remove(&hdr.tid).unwrap_or_default() {
                    self.record(&opened, ev.ret >= 0);
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
            _ => {}
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
        let uid = self.owner_uid(opened.uid, opened.pid);
        let pkgs = self.packages.for_uid(uid);
        let (cat, path) = match paths::canonicalize(&opened.path, uid / PER_USER_RANGE) {
            Some(path) if paths::is_own_dir(&path, pkgs) => {
                self.explain(source, "<own app directory>");
                return;
            }
            Some(path) => (Category::Storage, path),
            None => match rules::classify(&opened.path, uid, uid_of_pid) {
                Some(hit) => hit,
                None => {
                    self.explain(source, &opened.path);
                    return;
                }
            },
        };
        self.store(uid, cat, path, opened.mode, ok, opened.ts, 0);
    }

    /// The app a process should be attributed to. Isolated processes run
    /// under a uid of their own; they are attributed to the app that owns
    /// them, found through the process name. Best effort: if the process is
    /// gone, the record stays under the isolated uid.
    fn owner_uid(&self, uid: u32, pid: u32) -> u32 {
        if uid % PER_USER_RANGE < FIRST_ISOLATED_APP_ID {
            return uid;
        }
        fs::read(format!("/proc/{pid}/cmdline"))
            .ok()
            .and_then(|cmdline| {
                let name = cmdline.split(|&b| b == 0).next()?;
                self.packages
                    .owner_of_isolated(uid, std::str::from_utf8(name).ok()?)
            })
            .unwrap_or(uid)
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
        mode: u8,
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

    /// Record opens whose result never arrived (seen before `cutoff`).
    fn expire(&mut self, cutoff: i64) {
        let stale: Vec<u32> = self
            .awaiting_result
            .iter()
            .filter(|(_, held)| held.iter().all(|o| o.ts <= cutoff))
            .map(|(tid, _)| *tid)
            .collect();
        for tid in stale {
            for opened in self.awaiting_result.remove(&tid).unwrap_or_default() {
                self.record(&opened, true);
            }
        }
    }
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

/// The real uid that owns `pid`, or None if the process is gone. Read from
/// `status` rather than from the directory's owner: /proc/<pid> is owned by
/// root for non-dumpable processes, which most Android apps are.
fn uid_of_pid(pid: u32) -> Option<u32> {
    let status = fs::read_to_string(format!("/proc/{pid}/status")).ok()?;
    parse_status_uid(&status)
}

fn parse_status_uid(status: &str) -> Option<u32> {
    status
        .lines()
        .find_map(|line| line.strip_prefix("Uid:"))?
        .split_ascii_whitespace()
        .next()?
        .parse()
        .ok()
}

/// Access mode bits from open flags and, where known, the inode's mode
/// (0 if not): a directory can be opened without O_DIRECTORY.
fn access_mode(flags: u32, inode_mode: u32) -> u8 {
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

pub fn mode_label(mode: u8) -> &'static str {
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
    fn access_modes() {
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
        let status = "Name:\tcom.x\nPid:\t500\nUid:\t10100\t10101\t10102\t10103\nGid:\t10100\n";
        assert_eq!(parse_status_uid(status), Some(10100));
        assert_eq!(parse_status_uid("Name:\tx\n"), None);
    }
}
