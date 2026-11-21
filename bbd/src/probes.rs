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

//! Load, attach and inspect the daemon's eBPF programs.

use std::{collections::HashSet, time::Duration};

use anyhow::Context as _;
use aya::{
    EbpfLoader,
    maps::{HashMap as BpfHashMap, MapData, PerCpuArray},
    programs::{KProbe, RawTracePoint},
};
use bb_common::{BinderKey, BinderValue, OFF_COUNT, OFF_MISSING};

use crate::{btf::Btf, syscalls};

/// (program, kernel function) pairs a feature is attached to.
pub type Plan = Vec<(&'static str, &'static str)>;

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
    pub features: Vec<(&'static syscalls::Feature, anyhow::Result<Plan>)>,
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
    let modern = aya::include_bytes_aligned!(concat!(env!("OUT_DIR"), "/bb-ebpf"));
    let legacy = aya::include_bytes_aligned!(concat!(env!("OUT_DIR"), "/bb-ebpf-legacy"));
    let mut ebpf = match load_file_programs(modern, &offsets, true) {
        Ok(ebpf) => ebpf,
        Err(error) => {
            eprintln!(
                "bbd: using the syscall hash table: {error} ({})",
                error.root_cause()
            );
            load_file_programs(legacy, &offsets, false)?
        }
    };

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
    let selinux = syscalls::selinux_running();
    let hook = syscalls::file_open_hook(&symbols, selinux);
    kprobe
        .attach(hook, 0)
        .with_context(|| format!("attaching kprobe {hook}"))?;
    eprintln!("bbd: file_open on {hook}");

    // Syscall probes: one entry program per argument layout plus a shared
    // return program, attached to each syscall function this kernel has.
    // The return program is shared by every feature that reports outcomes.
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

/// Verify the lookup, insertion and return paths before attaching anything.
/// Some kernels advertise BTF but lack the map type or one of its helpers.
fn load_file_programs(
    object: &[u8],
    offsets: &[u32; OFF_COUNT],
    task_storage: bool,
) -> anyhow::Result<aya::Ebpf> {
    let mut loader = EbpfLoader::new();
    if task_storage {
        // Aya can load task storage but has no userspace map wrapper for it.
        loader.allow_unsupported_maps();
    }
    let mut ebpf = loader
        .override_global("OFFSETS", &offsets[..], true)
        .load(object)
        .context("loading eBPF object (need root and a BPF-enabled kernel)")?;
    for name in ["file_open", "enter_openat", "sys_ret"] {
        let program: &mut KProbe = ebpf
            .program_mut(name)
            .with_context(|| format!("program {name} missing from object"))?
            .try_into()?;
        program
            .load()
            .with_context(|| format!("verifier rejected {name}"))?;
    }
    Ok(ebpf)
}

/// Load and attach the probes of one optional feature. Returns the
/// (program, kernel function) pairs attached.
pub fn attach_feature(
    ebpf: &mut aya::Ebpf,
    feature: &syscalls::Feature,
    symbols: &HashSet<&'static str>,
    selinux: bool,
    offsets: &[u32],
) -> anyhow::Result<Plan> {
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
        if program.fd().is_err() {
            program
                .load()
                .with_context(|| format!("verifier rejected {name}"))?;
        }
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
            if let Some(Ok(program)) = ebpf.program_mut(name).map(<&mut KProbe>::try_from)
                && let Err(e) = program.detach(link)
            {
                eprintln!("bbd: probes: could not detach {name} after an attach failure: {e}");
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
pub fn attach_traffic(ebpf: &mut aya::Ebpf, offsets: &[u32]) -> anyhow::Result<()> {
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
pub fn attach_binder(ebpf: &mut aya::Ebpf) -> anyhow::Result<BinderWatch> {
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

pub type BinderWatch = BpfHashMap<MapData, BinderKey, BinderValue>;

/// Tell the kernel side which calls to report.
pub fn watch(map: &mut BinderWatch, entries: Vec<(BinderKey, BinderValue)>) -> anyhow::Result<()> {
    for (key, value) in entries {
        map.insert(&key, &value, 0)?;
    }
    Ok(())
}

/// Per-program run counts and time, as measured by the kernel. This is the
/// CPU time the tracing itself adds to the processes being traced.
pub fn print_profile(ebpf: &aya::Ebpf) {
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

/// Events the kernel side has dropped so far, over every CPU.
pub fn dropped_events(map: &PerCpuArray<MapData, u64>) -> u64 {
    map.get(&0, 0)
        .map_or(0, |per_cpu| per_cpu.iter().sum::<u64>())
}
