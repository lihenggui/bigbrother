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
    },
    macros::{kprobe, map},
    maps::{Array, HashMap, RingBuf},
    programs::ProbeContext,
};
use bb_common::{GATE_STALE_NS, GateEvent, KNOWN_APP_WORDS, PER_USER_RANGE, known_app_bit};

use crate::common::{BPF_RB_FORCE_WAKEUP, OFFSETS, arch, read_at};

/// The uids the user put in the sandbox.
#[map]
static SANDBOX_UIDS: HashMap<u32, u32> = HashMap::with_max_entries(1024, 0);

/// Processes stopped at the gate, for the daemon's gate thread. Apart from
/// the records: letting an app start must not wait behind them.
#[map]
static GATE: RingBuf = RingBuf::with_byte_size(64 * 1024, 0);

/// Slot 0: since when (`bpf_ktime_get_ns`) a stopped process has been
/// waiting for the daemon, 0 while none is. The daemon clears it after each
/// process it lets go. A time that stays for `GATE_STALE_NS` means the
/// daemon's gate thread is stuck; `GATE_CLOSED` that it has ended.
///
/// This is how a stuck daemon is noticed without it having to say that it is
/// alive on a timer, which would wake the device for as long as any app is
/// sandboxed.
#[map]
static GATE_WAITING_SINCE: Array<u64> = Array::with_max_entries(1, 0);

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
/// sandbox, where they are in `SANDBOX_UIDS` if anything is mounted for
/// them, or taken out again by the user.
#[map]
static KNOWN_UIDS: HashMap<u32, u32> = HashMap::with_max_entries(1024, 0);

const SIGKILL: u32 = 9;
const SIGSTOP: u32 = 19;

/// Entry of `setresuid`. zygote's child calls it once, as root, to become
/// the app, after zygote set up its mount namespace and before any of the
/// app's code runs: the one moment at which what the app will see can still
/// be replaced. A child taking a sandboxed uid is stopped here and reported
/// to the daemon, which mounts the sandbox in its namespace and lets it
/// continue.
///
/// An app that cannot be given its sandbox must not run without it: when the
/// daemon cannot be told, or is not letting anything through, the process is
/// killed, and the system starts the app again when it is next wanted.
///
/// While newly installed apps are put in the sandbox, a child taking the uid
/// of an app the daemon has not dealt with yet is stopped and reported the
/// same way. Such an app was installed and started before the daemon got to
/// it; the daemon ends this start and puts the app in the sandbox for the
/// next.
#[kprobe]
pub fn gate_setresuid(ctx: ProbeContext) -> u32 {
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
    let unknown = if unsafe { SANDBOX_UIDS.get(&uid) }.is_some() {
        false
    } else if unknown_app(uid) {
        true
    } else {
        return Ok(());
    };
    // A child of zygote still has zygote's thread name here ("main"), or
    // that of the pool of spare processes it was taken from ("usap32",
    // "usap64"). Anything else taking the uid (`su`, `run-as`) is not an app
    // starting, and has no mount namespace of its own to change.
    let task_id = bpf_get_current_pid_tgid();
    if task_id as u32 != (task_id >> 32) as u32 {
        return Ok(());
    }
    let comm = bpf_get_current_comm().map_err(|e| e as i32)?;
    let zygote_child =
        (comm[0] == b'm' && comm[1] == b'a' && comm[2] == b'i' && comm[3] == b'n' && comm[4] == 0)
            || (comm[0] == b'u' && comm[1] == b's' && comm[2] == b'a' && comm[3] == b'p');
    if !zygote_child {
        return Ok(());
    }

    // Read before the clock: a time another CPU stored in between would be
    // later than `now`, and the difference would pass for a very long wait.
    let waiting = GATE_WAITING_SINCE.get_ptr_mut(0).ok_or(-1)?;
    let since = unsafe { *waiting };
    let now = unsafe { bpf_ktime_get_ns() };
    let task = unsafe { aya_ebpf::helpers::bpf_get_current_task() } as *const u8;
    let Ok(start) = (unsafe { read_at::<u64>(task, OFFSETS.load()[bb_common::OFF_TASK_START]) })
    else {
        kill();
        return Ok(());
    };
    let event = GateEvent {
        start_low: start as u32,
        start_high: (start >> 32) as u32,
        pid: task_id as u32,
        uid,
        at_ns_low: now as u32,
        at_ns_high: (now >> 32) as u32,
        unknown: u32::from(unknown),
    };
    if since != 0 && now.wrapping_sub(since) > GATE_STALE_NS {
        // Reported all the same: a daemon that is there after all clears
        // the time when it reads this, and the next start is held again.
        let _ = GATE.output(&event, BPF_RB_FORCE_WAKEUP);
        kill();
        return Ok(());
    }
    // Before the daemon is told, so that it cannot have cleared the time
    // already when it is set.
    if since == 0 {
        unsafe { *waiting = now };
    }
    // The one event that wakes its reader every time: a process is stopped
    // until the daemon has seen it. It happens once per start of a sandboxed
    // app, or of a new app not dealt with yet, and for nothing else.
    if GATE.output(&event, BPF_RB_FORCE_WAKEUP).is_err() {
        kill();
        return Ok(());
    }
    // Takes effect as the process leaves this system call.
    if unsafe { aya_ebpf::helpers::generated::bpf_send_signal(SIGSTOP) } != 0 {
        kill();
    }
    Ok(())
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

#[inline(always)]
fn kill() {
    unsafe { aya_ebpf::helpers::generated::bpf_send_signal(SIGKILL) };
}
