// Copyright (C) 2026 Mercury Li
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

use aya_ebpf::{
    helpers::{bpf_get_current_pid_tgid, bpf_get_current_uid_gid, bpf_ktime_get_ns},
    macros::{kprobe, map},
    maps::LruHashMap,
    programs::ProbeContext,
};
use bb_common::{EV_PROCESS, PROCESS_READ, PROCESS_TRACE, PROCESS_WRITE, ProcessEvent};

use crate::{
    common::{
        EVENTS, REPORT_INTERVAL_NS, arch, count_drop, header, read_at, should_trace, wakeup_flags,
    },
    paths::expect_result,
};

/// Processes an app went after recently, keyed by caller and target pid,
/// with the time of the report. A debugger or a sandbox that traces its own
/// children makes thousands of these calls a second.
#[map]
static RECENT_TARGETS: LruHashMap<u64, u64> = LruHashMap::with_max_entries(1024, 0);

const PTRACE_TRACEME: u64 = 0;

/// Common body of the probes for calls that name another process. `pid_arg`
/// is the index of the pid argument.
#[inline(always)]
fn process_enter(ctx: &ProbeContext, how: u32, pid_arg: usize) -> Result<(), i32> {
    let uid = bpf_get_current_uid_gid() as u32;
    if !should_trace(uid) {
        return Ok(());
    }
    let regs: *const u8 = ctx.arg(0).ok_or(-1)?;
    // ptrace(PTRACE_TRACEME) asks to be traced; it names no one.
    if how == PROCESS_TRACE
        && unsafe { read_at::<u64>(regs, arch::ARG_OFFSETS[0])? } == PTRACE_TRACEME
    {
        return Ok(());
    }
    let target = unsafe { read_at::<u64>(regs, arch::ARG_OFFSETS[pid_arg])? } as u32;
    let pid_tgid = bpf_get_current_pid_tgid();
    if target == (pid_tgid >> 32) as u32 {
        return Ok(());
    }
    let pair = (pid_tgid & 0xffff_ffff_0000_0000) | target as u64;
    let now = unsafe { bpf_ktime_get_ns() };
    if let Some(last) = unsafe { RECENT_TARGETS.get(&pair) } {
        if now.wrapping_sub(*last) < REPORT_INTERVAL_NS {
            return Ok(());
        }
    }
    let _ = RECENT_TARGETS.insert(&pair, &now, 0);
    let Some(mut entry) = EVENTS.reserve::<ProcessEvent>(0) else {
        count_drop();
        return Err(-1);
    };
    entry.write(ProcessEvent {
        hdr: header(EV_PROCESS, uid, pid_tgid),
        how,
        target,
    });
    entry.submit(wakeup_flags());
    expect_result(pid_tgid)
}

/// ptrace(request, pid, addr, data)
#[kprobe]
pub fn enter_ptrace(ctx: ProbeContext) -> u32 {
    let _ = process_enter(&ctx, PROCESS_TRACE, 1);
    0
}

/// process_vm_readv(pid, ...)
#[kprobe]
pub fn enter_vm_read(ctx: ProbeContext) -> u32 {
    let _ = process_enter(&ctx, PROCESS_READ, 0);
    0
}

/// process_vm_writev(pid, ...)
#[kprobe]
pub fn enter_vm_write(ctx: ProbeContext) -> u32 {
    let _ = process_enter(&ctx, PROCESS_WRITE, 0);
    0
}
