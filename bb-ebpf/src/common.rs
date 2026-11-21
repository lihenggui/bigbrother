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
    Global,
    helpers::{bpf_ktime_get_boot_ns, bpf_probe_read_kernel},
    macros::map,
    maps::{Array, PerCpuArray, RingBuf},
};
use bb_common::{EventHeader, FIRST_APPLICATION_UID, OFF_COUNT, PER_USER_RANGE};

/// Kernel struct field offsets, filled in by the daemon (see bb-common).
#[unsafe(no_mangle)]
pub(super) static OFFSETS: Global<[u32; OFF_COUNT]> = Global::new([0; OFF_COUNT]);

#[map]
pub(super) static EVENTS: RingBuf = RingBuf::with_byte_size(8 * 1024 * 1024, 0);

/// Slot 0 counts events lost because the ring buffer was full. Per CPU, so
/// that increments on different CPUs don't overwrite each other; the daemon
/// adds the per-CPU values up.
#[map]
static DROPPED: PerCpuArray<u64> = PerCpuArray::with_max_entries(1, 0);

/// Slot 0: when (boot time, ns) a call asking for it last woke the daemon
/// early (see `early_wakeup_flags`). One slot for all CPUs: two of them
/// racing can each wake it once, which keeps to the bound per CPU.
#[map]
static LAST_EARLY_WAKE: Array<u64> = Array::with_max_entries(1, 0);

pub(super) const REPORT_INTERVAL_NS: u64 = 1_000_000_000;

/// Which processes get recorded: everything that runs as an app, in any
/// Android user. That is installed apps (app ids from 10000) and the isolated
/// processes they spawn (from 90000); system daemons, root and the shell are
/// below 10000.
///
/// This runs on every file open and every tracked syscall on the device, so
/// it has to stay a handful of instructions.
#[inline(always)]
pub(super) fn should_trace(uid: u32) -> bool {
    uid % PER_USER_RANGE >= FIRST_APPLICATION_UID
}

const BPF_RB_NO_WAKEUP: u64 = 1;
pub(super) const BPF_RB_FORCE_WAKEUP: u64 = 2;
const BPF_RB_AVAIL_DATA: u64 = 0;
/// Unread bytes in the ring buffer at which the daemon is woken immediately.
const WAKE_THRESHOLD: u64 = 1024 * 1024;

/// Flags for submitting an event. By default every submit wakes the reader,
/// which on a busy device means waking the daemon hundreds of times a second
/// for no benefit: events carry the time they happened, and the daemon reads
/// them on a timer. So events are queued silently, and the daemon is woken
/// early only when the buffer is filling up.
#[inline(always)]
pub(super) fn wakeup_flags() -> u64 {
    if EVENTS.query(BPF_RB_AVAIL_DATA) >= WAKE_THRESHOLD {
        BPF_RB_FORCE_WAKEUP
    } else {
        BPF_RB_NO_WAKEUP
    }
}

/// Least time between two early wakeups for calls that ask for one
/// (`BINDER_WAKE`: turning a sensor on). Such calls are rare, but an app can
/// make them in a loop; this keeps that to one wakeup a second. A call that
/// may not wake the daemon is still recorded, and read with the next batch.
const EARLY_WAKE_INTERVAL_NS: u64 = 1_000_000_000;

/// Flags for submitting an event that asks to wake the daemon at once:
/// the wakeup if none was done for the last `EARLY_WAKE_INTERVAL_NS`,
/// otherwise the usual ones.
#[inline(always)]
pub(super) fn early_wakeup_flags() -> u64 {
    let Some(last) = LAST_EARLY_WAKE.get_ptr_mut(0) else {
        return wakeup_flags();
    };
    let now = unsafe { bpf_ktime_get_boot_ns() };
    unsafe {
        if now.wrapping_sub(*last) < EARLY_WAKE_INTERVAL_NS {
            return wakeup_flags();
        }
        *last = now;
    }
    BPF_RB_FORCE_WAKEUP
}

#[inline(always)]
pub(super) fn count_drop() {
    if let Some(slot) = DROPPED.get_ptr_mut(0) {
        unsafe { *slot += 1 };
    }
}

#[inline(always)]
pub(super) fn header(kind: u32, uid: u32, pid_tgid: u64) -> EventHeader {
    let boot_ns = unsafe { bpf_ktime_get_boot_ns() };
    EventHeader {
        kind,
        uid,
        pid: (pid_tgid >> 32) as u32,
        tid: pid_tgid as u32,
        boot_ns_low: boot_ns as u32,
        boot_ns_high: (boot_ns >> 32) as u32,
    }
}

#[inline(always)]
pub(super) unsafe fn read_at<T>(base: *const u8, offset: u32) -> Result<T, i32> {
    unsafe { bpf_probe_read_kernel(base.add(offset as usize) as *const T) }
}

#[cfg(bpf_target_arch = "aarch64")]
pub(super) mod arch {
    /// `struct pt_regs` starts with `struct user_pt_regs`:
    /// `u64 regs[31]; u64 sp; u64 pc; u64 pstate;` — a stable UAPI layout.
    pub const ARG_OFFSETS: [u32; 6] = [0, 8, 16, 24, 32, 40];
    const PSTATE_OFFSET: u32 = 33 * 8;
    const PSR_MODE32_BIT: u64 = 0x10;

    pub unsafe fn path_args(regs: *const u8) -> Result<[u64; 3], i32> {
        unsafe { super::read_at(regs, ARG_OFFSETS[0]) }
    }

    /// True when the caller is a 32-bit (AArch32) process.
    pub unsafe fn is_compat(regs: *const u8) -> bool {
        match unsafe { super::read_at::<u64>(regs, PSTATE_OFFSET) } {
            Ok(pstate) => pstate & PSR_MODE32_BIT != 0,
            Err(_) => false,
        }
    }
}

#[cfg(bpf_target_arch = "x86_64")]
pub(super) mod arch {
    /// Offsets of di, si, dx, r10, r8, r9 in x86_64 `struct pt_regs`
    /// (emulator / host testing).
    pub const ARG_OFFSETS: [u32; 6] = [14 * 8, 13 * 8, 12 * 8, 7 * 8, 9 * 8, 8 * 8];

    pub unsafe fn path_args(regs: *const u8) -> Result<[u64; 3], i32> {
        let [dx, si, di] = unsafe { super::read_at(regs, ARG_OFFSETS[2])? };
        Ok([di, si, dx])
    }

    pub unsafe fn is_compat(_regs: *const u8) -> bool {
        false
    }
}
