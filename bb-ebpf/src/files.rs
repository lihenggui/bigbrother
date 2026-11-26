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
    helpers::{bpf_get_current_pid_tgid, bpf_get_current_uid_gid, bpf_probe_read_kernel_str_bytes},
    macros::{kprobe, map},
    maps::PerCpuArray,
    programs::ProbeContext,
};
use bb_common::filter::{own_proc_entry_matters, proc_entry_matters, sysfs_entry_matters};
use bb_common::{
    DATA_CAP, DATA_START_LIMIT, EV_FILE, FUSE_SUPER_MAGIC, FileEvent, NAME_CAP, OFF_DENTRY_INODE,
    OFF_DENTRY_NAME, OFF_DENTRY_PARENT, OFF_DENTRY_SB, OFF_FILE_DENTRY, OFF_FILE_FLAGS,
    OFF_INODE_MODE, OFF_SB_DEV, OFF_SB_MAGIC, PROC_SUPER_MAGIC, SELINUX_MAGIC, SYSFS_MAGIC,
};

use crate::{
    common::{EVENTS, OFFSETS, count_drop, header, read_at, should_trace, wakeup_flags},
    pending,
};

const S_IFMT: u32 = 0o170000;
const S_IFBLK: u32 = 0o060000;

/// Deepest path reported; deeper ones are flagged as truncated.
const MAX_DEPTH: usize = 32;

#[repr(C)]
struct FileScratch {
    event: FileEvent,
    data: [u8; DATA_CAP],
}

bb_common::assert_no_padding!(FileScratch { event, data });

/// Where `file_open` assembles its event. Most of what it sees is dropped
/// once the path is known, and a record reserved in the ring buffer and then
/// discarded would still take up space there until the daemon next reads it.
/// So the event is copied to the ring buffer only once it is to be kept.
#[map]
static FILE_SCRATCH: PerCpuArray<FileScratch> = PerCpuArray::with_max_entries(1, 0);

#[kprobe]
pub fn file_open(ctx: ProbeContext) -> u32 {
    let _ = try_file_open(&ctx);
    0
}

fn try_file_open(ctx: &ProbeContext) -> Result<(), i32> {
    let uid = bpf_get_current_uid_gid() as u32;
    if !should_trace(uid) {
        return Ok(());
    }
    let off = OFFSETS.load();
    let file: *const u8 = ctx.arg(0).ok_or(-1)?;

    let (dentry, sb, magic, mode) = file_metadata(file, &off)?;

    // Shared storage is the only FUSE filesystem apps can reach; procfs,
    // sysfs and selinuxfs carry device/system/process information; a raw
    // block device is notable wherever its node lives. Everything else (app
    // data, system files, sockets, binder...) is ignored.
    let is_proc = magic == PROC_SUPER_MAGIC;
    if !(is_proc
        || magic == FUSE_SUPER_MAGIC
        || magic == SYSFS_MAGIC
        || magic == SELINUX_MAGIC
        || mode & S_IFMT == S_IFBLK)
    {
        return Ok(());
    }

    let pid_tgid = bpf_get_current_pid_tgid();
    // Per CPU, and a kprobe program does not run nested on its own CPU, so
    // nothing else writes to it meanwhile.
    let ev = FILE_SCRATCH.get_ptr_mut(0).ok_or(-1)?;

    // Walk dentry -> d_parent up to the filesystem root, writing each name.
    // `top_name` and `second_name` point at the names of the two outermost
    // components seen so far ("devices" and "system" for
    // /sys/devices/system/cpu/...), so that procfs and sysfs paths can be
    // filtered below without reading back from the event buffer at a
    // variable offset. Only the pointers are carried through the loop: the
    // verifier follows every iteration, and carrying the names themselves
    // multiplies the states it has to explore past its limit.
    let mut top_name: *const u8 = core::ptr::null();
    let mut second_name: *const u8 = core::ptr::null();
    // The cursor lives in scratch so the verifier does not have to track
    // every possible accumulated path length across all 32 iterations.
    let cursor = unsafe { &raw mut (*ev).event.data_len };
    unsafe { cursor.write_volatile(0) };
    let mut truncated = 1u32;
    let mut d = dentry;
    let walked: Result<(), i32> = (|| {
        for _ in 0..MAX_DEPTH {
            let parent: *const u8 = unsafe { read_at(d, off[OFF_DENTRY_PARENT])? };
            if parent == d || parent.is_null() {
                truncated = 0;
                break;
            }
            let len = unsafe { cursor.read_volatile() } as usize;
            if len >= DATA_START_LIMIT {
                break;
            }
            let name: *const u8 = unsafe { read_at(d, off[OFF_DENTRY_NAME])? };
            // Mask at the pointer arithmetic so older verifiers can see
            // that a full component fits. Keep LLVM from folding the mask
            // away on the strength of the preceding comparison.
            let start = core::hint::black_box(len) & (DATA_START_LIMIT - 1);
            let dst = unsafe {
                core::slice::from_raw_parts_mut(
                    (&raw mut (*ev).data).cast::<u8>().add(start),
                    NAME_CAP,
                )
            };
            let written = unsafe { bpf_probe_read_kernel_str_bytes(name, dst)? }.len();
            unsafe { cursor.write_volatile((len + written + 1) as u32) };
            second_name = top_name;
            top_name = name;
            d = parent;
        }
        Ok(())
    })();
    if walked.is_err() {
        count_drop();
        return Ok(());
    }

    if boring_file(magic, top_name, second_name, pid_tgid) {
        return Ok(());
    }

    let in_syscall = u32::from(pending::mark_reported(pid_tgid));
    unsafe {
        (*ev).event.hdr = header(EV_FILE, uid, pid_tgid);
        (*ev).event.flags = read_at::<u32>(file, off[OFF_FILE_FLAGS]).unwrap_or(0);
        (*ev).event.magic = magic;
        (*ev).event.dev = read_at::<u32>(sb, off[OFF_SB_DEV]).unwrap_or(0);
        (*ev).event.mode = mode;
        (*ev).event.in_syscall = in_syscall;
        (*ev).event.truncated = truncated;
    }
    // Only the initialized components belong to this record. Bound the size
    // at the helper call as well, so the verifier can check the scratch read.
    let used = unsafe { cursor.read_volatile() } as usize;
    let size = size_of::<FileEvent>() + used.min(DATA_CAP);
    let bytes = unsafe { core::slice::from_raw_parts(ev.cast::<u8>(), size) };
    if EVENTS.output(bytes, wakeup_flags()).is_err() {
        count_drop();
        return Err(-1);
    }
    Ok(())
}

#[inline(always)]
fn file_metadata(
    file: *const u8,
    off: &[u32; bb_common::OFF_COUNT],
) -> Result<(*const u8, *const u8, u32, u32), i32> {
    let (dentry, sb, inode): (*const u8, *const u8, *const u8) = unsafe {
        let dentry: *const u8 = read_at(file, off[OFF_FILE_DENTRY])?;
        (
            dentry,
            read_at(dentry, off[OFF_DENTRY_SB])?,
            read_at(dentry, off[OFF_DENTRY_INODE])?,
        )
    };
    // s_magic is `unsigned long`; every magic we care about fits in 32 bits.
    let magic = unsafe { read_at::<u64>(sb, off[OFF_SB_MAGIC])? } as u32;
    let mode = unsafe { read_at::<u16>(inode, off[OFF_INODE_MODE])? } as u32;
    Ok((dentry, sb, magic, mode))
}

// Keep unknown paths for the daemon to judge. Most procfs entries need
// only their outermost component, so read the next one only if it matters.
#[inline(always)]
fn boring_file(magic: u32, top_name: *const u8, second_name: *const u8, pid_tgid: u64) -> bool {
    if magic != PROC_SUPER_MAGIC && magic != SYSFS_MAGIC {
        return false;
    }
    let mut top = [0u8; 8];
    if unsafe { bpf_probe_read_kernel_str_bytes(top_name, &mut top) }.is_err() {
        return false;
    }
    if magic == PROC_SUPER_MAGIC {
        let Some(pid) = leading_number(&top) else {
            return !proc_entry_matters(&top);
        };
        if pid != (pid_tgid >> 32) as u32 && pid != pid_tgid as u32 {
            return false;
        }
    }
    let mut second = [0u8; 8];
    let _ = unsafe { bpf_probe_read_kernel_str_bytes(second_name, &mut second) };
    if magic == PROC_SUPER_MAGIC {
        !own_proc_entry_matters(&second)
    } else {
        !sysfs_entry_matters(&top, &second)
    }
}

/// Parse a leading decimal number. Returns None if `bytes` doesn't start
/// with a digit.
#[inline(always)]
fn leading_number<const N: usize>(bytes: &[u8; N]) -> Option<u32> {
    let mut value: u32 = 0;
    let mut i = 0;
    while i < N {
        let c = bytes[i];
        if !c.is_ascii_digit() {
            break;
        }
        value = value.wrapping_mul(10).wrapping_add((c - b'0') as u32);
        i += 1;
    }
    (i > 0).then_some(value)
}

/// Bytes of the path inspected in-kernel to decide whether to emit an event.
/// The string copy always NUL-terminates, so prefixes compared against it
/// can be at most `HEAD_LEN - 1` characters long.
pub(super) const HEAD_LEN: usize = 24;

#[inline(always)]
fn starts_with(head: &[u8; HEAD_LEN], prefix: &[u8]) -> bool {
    let mut i = 0;
    while i < prefix.len() && i < HEAD_LEN {
        if head[i] != prefix[i] {
            return false;
        }
        i += 1;
    }
    true
}

/// True for the caller's own proc entries that only describe its process.
#[inline(always)]
fn is_own_proc(head: &[u8; HEAD_LEN], tgid: u32, tid: u32) -> bool {
    let below = if starts_with(head, b"/proc/self/") {
        11
    } else if starts_with(head, b"/proc/thread-self/") {
        18
    } else {
        let mut pid = 0u32;
        let mut end = 6;
        // Linux pids fit in seven decimal digits.
        while end < 13 && head[end].is_ascii_digit() {
            pid = pid * 10 + u32::from(head[end] - b'0');
            end += 1;
        }
        if end == 6 || (pid != tgid && pid != tid) {
            return false;
        }
        match head[end] {
            0 => return true,
            b'/' => end + 1,
            _ => return false,
        }
    };
    // The mount table and network state are reached through the process's
    // own directory. The daemon can distinguish mounts from mountinfo.
    !(head[below] == b'n'
        && head[below + 1] == b'e'
        && head[below + 2] == b't'
        && matches!(head[below + 3], b'/' | 0)
        || head[below] == b'm'
            && head[below + 1] == b'o'
            && head[below + 2] == b'u'
            && head[below + 3] == b'n')
}

/// Coarse filter for path *strings*: keep only paths userspace might record.
/// Userspace does the exact matching, so this must never reject something
/// userspace would keep. (Being a string check it can be dodged by spelling
/// the path differently — which is why opens are reported by `file_open`
/// instead, and this only covers calls that never yield a `struct file`.)
#[inline(always)]
pub(super) fn is_interesting(head: &[u8; HEAD_LEN], tgid: u32, tid: u32) -> bool {
    match head[0] {
        // Empty path: fstat-style call on an already-open fd.
        0 => return false,
        b'/' => {}
        // Relative path: only userspace can resolve it.
        _ => return true,
    }
    if starts_with(head, b"/proc/") {
        if is_own_proc(head, tgid, tid) {
            return false;
        }
        // Another process's entry, or one of the system-wide entries that
        // say something about the device (the same list as
        // `proc_entry_matters`, spelled as path prefixes).
        return head[6].is_ascii_digit()
            || starts_with(head, b"/proc/self/")
            || starts_with(head, b"/proc/thread-self/")
            || starts_with(head, b"/proc/net")
            || starts_with(head, b"/proc/cpuinfo")
            || starts_with(head, b"/proc/version")
            || starts_with(head, b"/proc/mounts")
            || starts_with(head, b"/proc/filesyste") // filesystems
            || starts_with(head, b"/proc/modules")
            || starts_with(head, b"/proc/kallsyms")
            || starts_with(head, b"/proc/partition") // partitions
            || starts_with(head, b"/proc/sys/")
            || starts_with(head, b"/proc/bus/");
    }
    if starts_with(head, b"/sys/") {
        // The same exclusions as `sysfs_entry_matters`.
        if starts_with(head, b"/sys/fs/") {
            return starts_with(head, b"/sys/fs/selinux");
        }
        return !(starts_with(head, b"/sys/devices/sy") // /sys/devices/system
            || starts_with(head, b"/sys/kernel/")
            || starts_with(head, b"/sys/module/")
            || starts_with(head, b"/sys/power/")
            || starts_with(head, b"/sys/firmware/"));
    }
    if starts_with(head, b"/dev/") {
        // Only the handful of device nodes that reveal something.
        return starts_with(head, b"/dev/block")
            || starts_with(head, b"/dev/input")
            || starts_with(head, b"/dev/qemu")
            || starts_with(head, b"/dev/goldfish")
            || starts_with(head, b"/dev/socket/")
            || starts_with(head, b"/dev/vbox");
    }
    starts_with(head, b"/storage/")
        || starts_with(head, b"/sdcard")
        || starts_with(head, b"/mnt/")
        || starts_with(head, b"/data/media/")
}
