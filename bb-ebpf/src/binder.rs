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

//! Everything read here is binder's userspace ABI (uapi/linux/android/binder.h)
//! or the parcel format of the Android framework; nothing depends on how the
//! driver is implemented.

use aya_ebpf::{
    helpers::{
        bpf_get_current_pid_tgid, bpf_get_current_uid_gid, bpf_probe_read_user,
        bpf_probe_read_user_buf,
    },
    macros::{map, raw_tracepoint},
    maps::HashMap,
    programs::RawTracePointContext,
};
use bb_common::{
    BINDER_ARGS_CAP, BINDER_ARGS_SHORT, BINDER_CODES, BINDER_KEY_HEAD, BINDER_KEY_LEN,
    BINDER_KEY_TAIL, BINDER_MAX_NAME, BINDER_MIN_NAME, BINDER_WAKE, BinderEvent, BinderKey,
    BinderValue, EV_BINDER,
};

use crate::common::{EVENTS, count_drop, early_wakeup_flags, header, should_trace, wakeup_flags};

/// The service methods to report, by interface (see bb-common). Filled by
/// the daemon once it knows the device's transaction codes; empty until then.
#[map]
static BINDER_WATCH: HashMap<BinderKey, BinderValue> = HashMap::with_max_entries(256, 0);

/// `BINDER_WRITE_READ`: _IOWR('b', 1, struct binder_write_read).
const BINDER_WRITE_READ: u32 = 0xc030_6201;
/// `BC_TRANSACTION` and `BC_TRANSACTION_SG`. Both are followed by a
/// `struct binder_transaction_data`.
const BC_TRANSACTION: u32 = 0x4040_6300;
const BC_TRANSACTION_SG: u32 = 0x4048_6311;
/// Commands inspected per ioctl. A transaction is usually the first or comes
/// after a few buffer releases and reference count changes.
const MAX_COMMANDS: usize = 6;
/// Marks a parcel written by the system's own (not vendor) binder: 'SYST'.
const PARCEL_HEADER_SYSTEM: u32 = 0x5359_5354;
/// Before the interface name: strict mode policy, work source, header mark,
/// and the name's length. All 32-bit.
const PARCEL_NAME_OFFSET: u64 = 16;

/// TP_PROTO(unsigned int cmd, unsigned long arg)
#[raw_tracepoint(tracepoint = "binder_ioctl")]
pub fn binder_ioctl(ctx: RawTracePointContext) -> i32 {
    let _ = try_binder_ioctl(&ctx);
    0
}

fn try_binder_ioctl(ctx: &RawTracePointContext) -> Result<(), i32> {
    let uid = bpf_get_current_uid_gid() as u32;
    if !should_trace(uid) {
        return Ok(());
    }
    let cmd: u64 = ctx.arg(0);
    if cmd as u32 != BINDER_WRITE_READ {
        return Ok(());
    }
    let arg: u64 = ctx.arg(1);
    // struct binder_write_read begins: write_size, write_consumed,
    // write_buffer, each 64 bits wide (for 32-bit processes too).
    let bwr: [u64; 3] = unsafe { bpf_probe_read_user(arg as *const [u64; 3])? };
    let end = bwr[2].checked_add(bwr[0]).ok_or(-1)?;
    let mut at = bwr[2].checked_add(bwr[1]).ok_or(-1)?;
    for _ in 0..MAX_COMMANDS {
        if end.saturating_sub(at) < 4 {
            break;
        }
        let command: u32 = unsafe { bpf_probe_read_user(at as *const u32)? };
        // The size of a command's payload is encoded in the command.
        let step = 4 + u64::from((command >> 16) & 0x3fff);
        if step > end - at {
            break;
        }
        if command == BC_TRANSACTION || command == BC_TRANSACTION_SG {
            // One unreadable parcel (or a full ring) need not end the batch.
            let _ = binder_transaction(at + 4, uid);
        }
        at += step;
    }
    Ok(())
}

#[inline(always)]
fn binder_transaction(transaction: u64, uid: u32) -> Result<(), i32> {
    // struct binder_transaction_data: target and cookie (8 bytes each),
    // code and flags (4 each), sender pid and euid (4 each), data_size,
    // offsets_size, data buffer, offsets buffer (8 each). Only four fields
    // are wanted, and the stack is small.
    let code: u32 = unsafe { bpf_probe_read_user((transaction + 16) as *const u32)? };
    let size: u64 = unsafe { bpf_probe_read_user((transaction + 32) as *const u64)? };
    let data: u64 = unsafe { bpf_probe_read_user((transaction + 48) as *const u64)? };
    if code >= BINDER_CODES {
        return Ok(());
    }
    let mut key: BinderKey = [0; BINDER_KEY_LEN];
    // A call without arguments has no interface name either; it is looked up
    // under the all-zero key (`BINDER_UNNAMED_KEY`) and has no arguments to
    // copy.
    let args = if size == 0 {
        0
    } else {
        binder_interface(data, size, &mut key)?
    };
    let Some(watched) = (unsafe { BINDER_WATCH.get(&key) }) else {
        return Ok(());
    };
    // Keep LLVM from folding away the mask the verifier needs for this lookup.
    let index = core::hint::black_box(code >> 6) & 7;
    let word = watched[1 + index as usize];
    if word & (1 << (code & 63)) == 0 {
        return Ok(());
    }
    // The low half of the `target` union: the handle, for a call to another
    // process.
    let target: u32 = unsafe { bpf_probe_read_user(transaction as *const u32)? };
    // A sensor turned on must reach the daemon while the app runs (see
    // `BINDER_WAKE`), within the bound of `early_wakeup_flags`; everything
    // else waits for the next read.
    let flags = if watched[0] & BINDER_WAKE != 0 {
        early_wakeup_flags()
    } else {
        wakeup_flags()
    };

    let Some(mut entry) = EVENTS.reserve::<BinderEvent>(0) else {
        count_drop();
        return Err(-1);
    };
    let ev = entry.as_mut_ptr();
    unsafe {
        (*ev).hdr = header(EV_BINDER, uid, bpf_get_current_pid_tgid());
        (*ev).interface = watched[0] as u32;
        (*ev).code = code;
        (*ev).target = target;
        read_binder_args(ev, data, size, args);
    }
    entry.submit(flags);
    Ok(())
}

#[inline(always)]
fn binder_interface(data: u64, size: u64, key: &mut BinderKey) -> Result<u64, i32> {
    if size < PARCEL_NAME_OFFSET {
        return Err(-1);
    }
    let prefix: [u32; 4] = unsafe { bpf_probe_read_user(data as *const [u32; 4])? };
    let len = prefix[3];
    if prefix[2] != PARCEL_HEADER_SYSTEM || !(BINDER_MIN_NAME..=BINDER_MAX_NAME).contains(&len) {
        return Err(-1);
    }
    // The name: `len` UTF-16 code units, a terminator, padding to 4 bytes.
    let name = data + PARCEL_NAME_OFFSET;
    let name_bytes = u64::from(len) * 2;
    let len_bytes = len.to_ne_bytes();
    key[0] = len_bytes[0];
    key[1] = len_bytes[1];
    key[2] = len_bytes[2];
    key[3] = len_bytes[3];
    unsafe {
        let key_bytes = (key as *mut BinderKey).cast::<u8>();
        bpf_probe_read_user_buf(
            name as *const u8,
            core::slice::from_raw_parts_mut(key_bytes.add(4), BINDER_KEY_HEAD),
        )?;
        bpf_probe_read_user_buf(
            (name + name_bytes - BINDER_KEY_TAIL as u64) as *const u8,
            core::slice::from_raw_parts_mut(key_bytes.add(4 + BINDER_KEY_HEAD), BINDER_KEY_TAIL),
        )?;
    }
    Ok(name + ((name_bytes + 2 + 3) & !3))
}

#[inline(always)]
unsafe fn read_binder_args(ev: *mut BinderEvent, data: u64, size: u64, args: u64) {
    unsafe {
        (*ev).args_len = 0;
        if args != 0 {
            // The arguments are read at a fixed length, which can run past
            // the parcel into whatever follows it; the daemon only trusts
            // what the parcel's size covers.
            let have = (data + size).saturating_sub(args);
            let short = core::slice::from_raw_parts_mut(
                (&raw mut (*ev).args).cast::<u8>(),
                BINDER_ARGS_SHORT,
            );
            let read = if bpf_probe_read_user_buf(args as *const u8, &mut (*ev).args).is_ok() {
                BINDER_ARGS_CAP as u64
            } else if bpf_probe_read_user_buf(args as *const u8, short).is_ok() {
                BINDER_ARGS_SHORT as u64
            } else {
                0
            };
            (*ev).args_len = if have < read { have } else { read } as u32;
        }
    }
}
