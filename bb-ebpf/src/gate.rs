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
    helpers::{
        bpf_get_current_comm, bpf_get_current_pid_tgid, bpf_get_current_uid_gid, bpf_ktime_get_ns,
        generated::bpf_send_signal,
    },
    macros::{kprobe, kretprobe, map},
    maps::{Array, HashMap, RingBuf},
    programs::{ProbeContext, RetProbeContext},
};
use bb_common::{
    GATE_MISSED, GATE_MISSED_NEW, GATE_NEW, GATE_RECORD, GATE_REQUIRED, GATE_STORAGE_READY,
    GateEvent, KNOWN_APP_WORDS, PER_USER_RANGE, known_app_bit,
};

use crate::common::{BPF_RB_FORCE_WAKEUP, OFFSETS, arch, read_at};

/// The uids the user put in the sandbox.
#[map]
static SANDBOX_UIDS: HashMap<u32, u32> = HashMap::with_max_entries(1024, 0);

/// Starts reported to the gate thread. Required mounts and auto-isolation
/// hold the process, so their events must not wait behind the records.
#[map]
static GATE: RingBuf = RingBuf::with_byte_size(64 * 1024, 0);

/// Open only after userspace can supervise the stops.
#[map]
static GATE_OPEN: Array<u32> = Array::with_max_entries(1, 0);

/// Written before asking for SIGSTOP, and pinned by userspace across daemon
/// restarts. A ring event on its own would be lost with the daemon.
#[map]
static GATE_PENDING: HashMap<[u32; 3], [u32; 4]> = HashMap::with_max_entries(1024, 0);

/// Slot 0: 1 while newly installed apps are put in the sandbox (`bbd sandbox
/// auto`). A start of an app the daemon has not dealt with yet is then held
/// too, so that none of its code runs before it is in the sandbox.
#[map]
static AUTO_ISOLATE: Array<u32> = Array::with_max_entries(1, 0);

/// App ids whose starts are not held as new, in every Android user: those
/// installed when that was turned on, and the system's. One bit per app id
/// (`known_app_bit`).
#[map]
static KNOWN_APP_IDS: Array<u64> = Array::with_max_entries(KNOWN_APP_WORDS, 0);

/// Uids of apps installed since that the daemon has dealt with: put in the
/// sandbox, taken out again by the user, or released after auto-isolation
/// failed. A UID with mounts in the sandbox is also in `SANDBOX_UIDS`.
#[map]
static KNOWN_UIDS: HashMap<u32, u32> = HashMap::with_max_entries(1024, 0);

const SIGKILL: u32 = 9;
const SIGSTOP: u32 = 19;

/// Entry of `setresuid`. zygote's child calls it once, as root, to become
/// the app, after zygote set up its mount namespace and before any of the
/// app's code runs: the one moment at which what the app will see can still
/// be replaced before the app can use it. The daemon receives the event
/// and mounts the sandbox in the child's namespace. A child that only
/// needs a recording cover is reported without stopping it.
///
/// A child that needs a required mount is stopped only once the request
/// can be supervised. Otherwise it is ended before it can run unmounted.
///
/// While newly installed apps are put in the sandbox, a child taking the uid
/// of an app the daemon has not dealt with yet is stopped and reported the
/// same way. Such an app was installed and started before the daemon got to
/// it; the daemon ends this start and puts the app in the sandbox for the
/// next.
#[kprobe]
pub fn gate_setresuid(ctx: ProbeContext) -> u32 {
    // The probe leaves the syscall alone when it cannot read a candidate.
    let _ = try_gate(&ctx);
    0
}

fn try_gate(ctx: &ProbeContext) -> Result<(), i32> {
    // Runs for every process that changes uid; these two checks are all
    // that most of them cost. Only root can take another uid.
    if bpf_get_current_uid_gid() as u32 != 0 {
        return Ok(());
    }
    let regs: *const u8 = ctx.arg(0).ok_or(-1)?;
    let uid = unsafe { read_at::<u64>(regs, arch::ARG_OFFSETS[0])? } as u32;
    let kind = if let Some(kind) = unsafe { SANDBOX_UIDS.get(&uid) } {
        *kind
    } else if unknown_app(uid) {
        GATE_NEW
    } else {
        return Ok(());
    };
    let gate_open = open();
    if !gate_open && kind != GATE_REQUIRED {
        return Ok(());
    }
    // A child of zygote still has zygote's thread name here ("main"), or
    // that of the pool of spare processes it was taken from ("usap32",
    // "usap64"). Anything else taking the uid (`su`, `run-as`) is not an app
    // starting, and has no mount namespace of its own to change.
    let task_id = bpf_get_current_pid_tgid();
    if task_id as u32 != (task_id >> 32) as u32 {
        return Ok(());
    }
    let comm = bpf_get_current_comm()?;
    let zygote_child =
        (comm[0] == b'm' && comm[1] == b'a' && comm[2] == b'i' && comm[3] == b'n' && comm[4] == 0)
            || (comm[0] == b'u' && comm[1] == b's' && comm[2] == b'a' && comm[3] == b'p');
    if !zygote_child {
        return Ok(());
    }

    let task = unsafe { aya_ebpf::helpers::bpf_get_current_task() } as *const u8;
    let start = unsafe { read_at::<u64>(task, OFFSETS.load()[bb_common::OFF_TASK_START]) };
    let now = unsafe { bpf_ktime_get_ns() };
    let mut event = GateEvent {
        start_low: 0,
        start_high: 0,
        pid: task_id as u32,
        uid,
        at_ns_low: now as u32,
        at_ns_high: (now >> 32) as u32,
        kind,
    };
    if let Ok(start) = start {
        event.start_low = start as u32;
        event.start_high = (start >> 32) as u32;
    }
    if start.is_err() || !gate_open {
        if kind == GATE_REQUIRED {
            end_required_start();
            event.kind = GATE_MISSED;
            if GATE.output(&event, BPF_RB_FORCE_WAKEUP).is_err() {
                close();
            }
        }
    } else if kind == GATE_RECORD {
        // A full ring may lose an optional recording cover.
        let _ = GATE.output(&event, BPF_RB_FORCE_WAKEUP);
    } else {
        hold_child(event);
    }
    Ok(())
}

#[inline(always)]
fn hold_child(event: GateEvent) {
    // Reserve the notification before stopping anything. Submitting it
    // afterwards also prevents a reply from racing the stop request.
    let Some(mut slot) = GATE.reserve::<GateEvent>(0) else {
        if event.kind == GATE_REQUIRED {
            end_required_start();
            close();
        }
        return;
    };
    let key = event.process_key();
    let pending = [event.uid, event.at_ns_low, event.at_ns_high, event.kind];
    let accepted = GATE_PENDING.insert(&key, &pending, 1).is_ok();
    let stopped = accepted && open() && unsafe { bpf_send_signal(SIGSTOP) } == 0;
    if accepted && !stopped {
        // An entry that survives deletion is checked against a stopped PID.
        let _ = GATE_PENDING.remove(&key);
    }
    let kind = if stopped {
        event.kind
    } else if event.kind == GATE_REQUIRED {
        end_required_start();
        GATE_MISSED
    } else {
        GATE_MISSED_NEW
    };
    slot.write(GateEvent { kind, ..event });
    slot.submit(BPF_RB_FORCE_WAKEUP);
}

#[inline(always)]
fn end_required_start() {
    // The watchdog can use its pidfd fallback if the miss can be reported.
    let _ = unsafe { bpf_send_signal(SIGKILL) };
}

#[inline(always)]
fn open() -> bool {
    GATE_OPEN.get(0).is_some_and(|open| *open != 0)
}

#[inline(always)]
fn close() {
    if let Some(open) = GATE_OPEN.get_ptr_mut(0) {
        unsafe { *open = 0 };
    }
}

/// A key can make a verified sandbox directory accessible without any
/// change to the directory itself that inotify could report.
#[kretprobe]
pub fn gate_storage_ready(ctx: RetProbeContext) -> u32 {
    if bpf_get_current_uid_gid() as u32 == 0 && ctx.ret::<i64>() == 0 && open() {
        let event = GateEvent {
            start_low: 0,
            start_high: 0,
            pid: 0,
            uid: 0,
            at_ns_low: 0,
            at_ns_high: 0,
            kind: GATE_STORAGE_READY,
        };
        // A start can use the new storage even if this sweep is missed.
        let _ = GATE.output(&event, BPF_RB_FORCE_WAKEUP);
    }
    0
}

/// Whether `uid` belongs to an app installed since newly installed apps are
/// put in the sandbox, which the daemon has not dealt with yet. Most uids
/// are answered by the range of app ids or the switch; one known since it
/// was turned on costs an array read, one installed since a lookup more.
#[inline(always)]
fn unknown_app(uid: u32) -> bool {
    let Some((word, bit)) = known_app_bit(uid % PER_USER_RANGE) else {
        return false;
    };
    if AUTO_ISOLATE.get(0).is_none_or(|on| *on == 0) {
        return false;
    }
    if KNOWN_APP_IDS
        .get(word)
        .is_some_and(|known| known & bit != 0)
    {
        return false;
    }
    unsafe { KNOWN_UIDS.get(&uid) }.is_none()
}
