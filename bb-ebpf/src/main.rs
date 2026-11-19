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

//! Kernel side: records what app processes touch on shared storage and in
//! the kernel's information filesystems.
//!
//! Two groups of programs cooperate:
//!
//! * `file_open` — kprobe on the `file_open` security hook,
//!   `selinux_file_open(struct file *)`, or `security_file_open` on a kernel
//!   without SELinux's hook (the daemon picks the function). This is
//!   where the kernel has finished resolving the path, so we see the file
//!   that is really being opened no matter how the app spelled the path
//!   (`..`, symlinks, `/proc/self/root/...`). It classifies by filesystem and
//!   reports the path by walking the dentry chain.
//! * `enter_*` / `sys_ret` — kprobes and a kretprobe on the individual
//!   syscall entry functions (`__arm64_sys_openat` and friends; the daemon
//!   picks the symbols). They pair each tracked syscall with its return
//!   value, and report path *strings* for the calls that never produce a
//!   `struct file`: stat/access/readlink/inotify, and opens that fail during
//!   lookup.
//!
//! * `enter_execve*` — the programs an app starts, with their arguments.
//!   Reported at entry, because a successful exec replaces the memory the
//!   arguments are in; `sys_ret` supplies the outcome.
//! * `enter_connect` / `enter_sendto` / `enter_sendmsg` — connections and
//!   datagrams to this device or the local network. Destinations on the internet are dropped
//!   here.
//!
//! * `enter_ptrace` / `enter_vm_read` / `enter_vm_write` — attempts to trace
//!   another process or reach into its memory.
//! * `enter_netlink` — kprobe on the `netlink_send` security hook
//!   (`selinux_netlink_send` or `security_netlink_send`), where the kernel
//!   decides whether a netlink request is allowed: requests for the
//!   interfaces, addresses, routes, neighbours and sockets of the device.
//!
//! * `binder_ioctl` — raw tracepoint of the same name, hit when a process
//!   hands the binder driver its commands. Finds the transaction among them
//!   and reports calls to the service methods the daemon listed.
//!
//! * `gate_setresuid` — not a recorder: kprobe on `setresuid`, which stops a
//!   starting app the user put in the sandbox until the daemon has replaced
//!   the storage it will see, and a newly installed app until the daemon has
//!   put it in the sandbox.
//!
//! Probing the specific syscall functions, rather than the `sys_enter` /
//! `sys_exit` tracepoints, matters for overhead: the tracepoints run on every
//! syscall of every process, while these run only for the dozen file-related
//! syscalls we track.
//!
//! Kernel struct offsets are not compiled in: the daemon resolves them from
//! the running kernel's BTF and stores them in `OFFSETS` before loading.

#![no_std]
#![no_main]

use aya_ebpf::{
    Global,
    helpers::{
        bpf_get_current_comm, bpf_get_current_pid_tgid, bpf_get_current_uid_gid,
        bpf_ktime_get_boot_ns, bpf_ktime_get_ns, bpf_probe_read_kernel,
        bpf_probe_read_kernel_str_bytes, bpf_probe_read_user, bpf_probe_read_user_buf,
        bpf_probe_read_user_str_bytes,
    },
    macros::{kprobe, kretprobe, map, raw_tracepoint},
    maps::{Array, HashMap, LruHashMap, PerCpuArray, RingBuf},
    programs::{ProbeContext, RawTracePointContext, RetProbeContext},
};
use bb_common::filter::{
    NET_PUBLIC, ipv4_class, ipv6_class, netlink_matters, own_proc_entry_matters,
    proc_entry_matters, sysfs_entry_matters,
};
use bb_common::{
    ABI_COMPAT32, ABI_NATIVE, AF_INET, AF_INET6, BINDER_ARGS_CAP, BINDER_ARGS_SHORT, BINDER_CODES,
    BINDER_KEY_HEAD, BINDER_KEY_LEN, BINDER_KEY_TAIL, BINDER_MAX_NAME, BINDER_MIN_NAME,
    BINDER_WAKE, BinderEvent, BinderKey, BinderValue, ConnectEvent, DATA_START_LIMIT, EV_BINDER,
    EV_CONNECT, EV_EXEC, EV_FILE, EV_NETLINK, EV_PATH, EV_PROCESS, EV_RESULT, EXEC_ARG_CAP,
    EXEC_ARGS, EventHeader, ExecEvent, FUSE_SUPER_MAGIC, FileEvent, GATE_STALE_NS, GateEvent,
    KNOWN_APP_WORDS, NAME_CAP, NetlinkEvent, OFF_COUNT, OFF_DENTRY_INODE, OFF_DENTRY_NAME,
    OFF_DENTRY_PARENT, OFF_DENTRY_SB, OFF_FILE_DENTRY, OFF_FILE_FLAGS, OFF_INODE_MODE, OFF_SB_DEV,
    OFF_SB_MAGIC, OFF_SKB_DATA, OFF_SOCK_PROTOCOL, OP_OPEN, OP_PAIRED, OP_PROBE, OP_WATCH,
    PROC_SUPER_MAGIC, PROCESS_READ, PROCESS_TRACE, PROCESS_WRITE, PathEvent, ProcessEvent,
    ResultEvent, SELINUX_MAGIC, SYSFS_MAGIC, known_app_bit,
};

const AT_FDCWD: i32 = -100;
const S_IFMT: u32 = 0o170000;
const S_IFBLK: u32 = 0o060000;

/// Android multi-user layout: uid = user_id * 100_000 + app_id.
const PER_USER_RANGE: u32 = 100_000;
const FIRST_APPLICATION_UID: u32 = 10_000;

/// Deepest path reported; deeper ones are flagged as truncated.
const MAX_DEPTH: usize = 32;

/// Kernel struct field offsets, filled in by the daemon (see bb-common).
#[unsafe(no_mangle)]
static OFFSETS: Global<[u32; OFF_COUNT]> = Global::new([0; OFF_COUNT]);

/// In-flight tracked syscalls, keyed by pid_tgid. LRU so entries orphaned by
/// a thread dying mid-syscall get evicted instead of leaking.
#[map]
static PENDING: LruHashMap<u64, Pending> = LruHashMap::with_max_entries(8192, 0);

#[map]
static EVENTS: RingBuf = RingBuf::with_byte_size(8 * 1024 * 1024, 0);

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

/// Where `file_open` assembles its event. Most of what it sees is dropped
/// once the path is known, and a record reserved in the ring buffer and then
/// discarded would still take up space there until the daemon next reads it.
/// So the event is copied to the ring buffer only once it is to be kept.
#[map]
static FILE_SCRATCH: PerCpuArray<FileEvent> = PerCpuArray::with_max_entries(1, 0);

/// Destinations reported recently, with the time of the report. An app
/// streaming to a device on the local network sends thousands of datagrams a
/// second to the same address; one report a second says as much.
#[map]
static RECENT_DESTINATIONS: LruHashMap<Destination, u64> = LruHashMap::with_max_entries(1024, 0);

/// Processes an app went after recently, keyed by caller and target pid,
/// with the time of the report. A debugger or a sandbox that traces its own
/// children makes thousands of these calls a second.
#[map]
static RECENT_TARGETS: LruHashMap<u64, u64> = LruHashMap::with_max_entries(1024, 0);

/// The service methods to report, by interface (see bb-common). Filled by
/// the daemon once it knows the device's transaction codes; empty until then.
#[map]
static BINDER_WATCH: HashMap<BinderKey, BinderValue> = HashMap::with_max_entries(256, 0);

#[repr(C)]
#[derive(Clone, Copy)]
struct Destination {
    tgid: u32,
    port: u32,
    addr: [u8; 16],
}

bb_common::assert_no_padding!(Destination { tgid, port, addr });

#[repr(C)]
#[derive(Clone, Copy)]
struct Pending {
    path_ptr: u64,
    /// openat2 only: user pointer to `struct open_how`, else 0.
    how_ptr: u64,
    dfd: i32,
    flags: u32,
    abi: u32,
    op: u32,
    /// Set by `file_open` once it has reported a file for this syscall.
    reported: u32,
    /// Always 0. Fills what would be padding after `reported`, which no
    /// store would write (see `assert_no_padding`).
    _pad: u32,
}

bb_common::assert_no_padding!(Pending {
    path_ptr,
    how_ptr,
    dfd,
    flags,
    abi,
    op,
    reported,
    _pad
});

/// Which processes get recorded: everything that runs as an app, in any
/// Android user. That is installed apps (app ids from 10000) and the isolated
/// processes they spawn (from 90000); system daemons, root and the shell are
/// below 10000.
///
/// This runs on every file open and every tracked syscall on the device, so
/// it has to stay a handful of instructions.
#[inline(always)]
fn should_trace(uid: u32) -> bool {
    uid % PER_USER_RANGE >= FIRST_APPLICATION_UID
}

const BPF_RB_NO_WAKEUP: u64 = 1;
const BPF_RB_FORCE_WAKEUP: u64 = 2;
const BPF_RB_AVAIL_DATA: u64 = 0;
/// Unread bytes in the ring buffer at which the daemon is woken immediately.
const WAKE_THRESHOLD: u64 = 1024 * 1024;

/// Flags for submitting an event. By default every submit wakes the reader,
/// which on a busy device means waking the daemon hundreds of times a second
/// for no benefit: events carry the time they happened, and the daemon reads
/// them on a timer. So events are queued silently, and the daemon is woken
/// early only when the buffer is filling up.
#[inline(always)]
fn wakeup_flags() -> u64 {
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
fn early_wakeup_flags() -> u64 {
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
fn count_drop() {
    if let Some(slot) = DROPPED.get_ptr_mut(0) {
        unsafe { *slot += 1 };
    }
}

#[inline(always)]
fn header(kind: u32, uid: u32, pid_tgid: u64) -> EventHeader {
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

// ---- file_open security hook ------------------------------------------------

#[inline(always)]
unsafe fn read_at<T>(base: *const u8, offset: u32) -> Result<T, i32> {
    unsafe { bpf_probe_read_kernel(base.add(offset as usize) as *const T) }
}

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
    let is_sysfs = magic == SYSFS_MAGIC;
    let mut top_name: *const u8 = core::ptr::null();
    let mut second_name: *const u8 = core::ptr::null();
    let mut len: usize = 0;
    let mut truncated = 1u32;
    let mut d = dentry;
    let walked: Result<(), i32> = (|| {
        for _ in 0..MAX_DEPTH {
            let parent: *const u8 = unsafe { read_at(d, off[OFF_DENTRY_PARENT])? };
            if parent == d || parent.is_null() {
                truncated = 0;
                break;
            }
            if len >= DATA_START_LIMIT {
                break;
            }
            let name: *const u8 = unsafe { read_at(d, off[OFF_DENTRY_NAME])? };
            // The verifier must be able to prove `start + NAME_CAP` stays
            // inside `data`. The comparison above doesn't do it: the compiler
            // keeps `len` in a stack slot whose bounds the verifier doesn't
            // refine. A mask applied right at the pointer arithmetic does —
            // and `black_box` keeps the compiler from deleting the mask as
            // redundant with the comparison.
            let start = core::hint::black_box(len) & (DATA_START_LIMIT - 1);
            let dst = unsafe {
                core::slice::from_raw_parts_mut(
                    (&raw mut (*ev).data).cast::<u8>().add(start),
                    NAME_CAP,
                )
            };
            let written = unsafe { bpf_probe_read_kernel_str_bytes(name, dst)? }.len();
            len += written + 1;
            second_name = top_name;
            top_name = name;
            d = parent;
        }
        Ok(())
    })();

    // Most of what apps open under /proc and /sys is of no interest: their
    // own /proc/<pid> entries (where /proc/self resolves to), CPU and memory
    // statistics, kernel tunables. Drop those here rather than pass them to
    // the daemon.
    let mut top = [0u8; 8];
    let mut second = [0u8; 8];
    // If the outermost name can't be read (which includes the filesystem's
    // root itself, which has none), nothing is known about the path and the
    // event is kept for the daemon to judge.
    let mut known = false;
    if is_proc || is_sysfs {
        known = unsafe { bpf_probe_read_kernel_str_bytes(top_name, &mut top) }.is_ok();
        let _ = unsafe { bpf_probe_read_kernel_str_bytes(second_name, &mut second) };
    }
    let boring = if !known {
        false
    } else if is_proc {
        match leading_number(&top) {
            // Another process's entry is kept. The caller's own is not,
            // except where it stands for system-wide information.
            Some(pid) => {
                (pid == (pid_tgid >> 32) as u32 || pid == pid_tgid as u32)
                    && !own_proc_entry_matters(&second)
            }
            None => !proc_entry_matters(&top),
        }
    } else {
        is_sysfs && !sysfs_entry_matters(&top, &second)
    };

    if walked.is_err() {
        count_drop();
        return Ok(());
    }
    if boring {
        return Ok(());
    }

    let in_syscall = match PENDING.get_ptr_mut(&pid_tgid) {
        Some(pending) => {
            unsafe { (*pending).reported = 1 };
            1
        }
        None => 0,
    };
    unsafe {
        (*ev).hdr = header(EV_FILE, uid, pid_tgid);
        (*ev).flags = read_at::<u32>(file, off[OFF_FILE_FLAGS]).unwrap_or(0);
        (*ev).magic = magic;
        (*ev).dev = read_at::<u32>(sb, off[OFF_SB_DEV]).unwrap_or(0);
        (*ev).mode = mode;
        (*ev).in_syscall = in_syscall;
        (*ev).truncated = truncated;
        (*ev).data_len = len as u32;
    }
    if EVENTS
        .output::<FileEvent>(unsafe { &*ev }, wakeup_flags())
        .is_err()
    {
        count_drop();
        return Err(-1);
    }
    Ok(())
}

// ---- syscall probes ---------------------------------------------------------

/// Bytes of the path inspected in-kernel to decide whether to emit an event.
/// The string copy always NUL-terminates, so prefixes compared against it
/// can be at most `HEAD_LEN - 1` characters long.
const HEAD_LEN: usize = 16;

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

/// True for `/proc/self`, `/proc/thread-self` and `/proc/<pid>` where pid is
/// the caller's own process or thread.
#[inline(always)]
fn is_own_proc(head: &[u8; HEAD_LEN], tgid: u32, tid: u32) -> bool {
    // The mount table and network state are reached through the process's
    // own directory but are not about the process (see
    // `own_proc_entry_matters`).
    if starts_with(head, b"/proc/self/net") || starts_with(head, b"/proc/self/moun") {
        return false;
    }
    if starts_with(head, b"/proc/self") || starts_with(head, b"/proc/thread-se") {
        return true;
    }
    let mut digits = [0u8; 8];
    let mut i = 0;
    while i < 8 {
        digits[i] = head[6 + i]; // after "/proc/"
        i += 1;
    }
    leading_number(&digits).is_some_and(|pid| pid == tgid || pid == tid)
}

/// Coarse filter for path *strings*: keep only paths userspace might record.
/// Userspace does the exact matching, so this must never reject something
/// userspace would keep. (Being a string check it can be dodged by spelling
/// the path differently — which is why opens are reported by `file_open`
/// instead, and this only covers calls that never yield a `struct file`.)
#[inline(always)]
fn is_interesting(head: &[u8; HEAD_LEN], tgid: u32, tid: u32) -> bool {
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

/// How a tracked syscall lays out its arguments.
#[derive(Clone, Copy)]
enum Shape {
    /// (path, ...)
    Path0,
    /// (path, flags, ...)
    Path0Flags1,
    /// (dfd, path, ...)
    DfdPath1,
    /// (dfd, path, flags, ...)
    DfdPath1Flags2,
    /// (dfd, path, struct open_how *, ...)
    DfdPath1How2,
    /// (other fd, path, ...) with the path relative to the cwd
    CwdPath1,
}

#[cfg(bpf_target_arch = "aarch64")]
mod arch {
    /// `struct pt_regs` starts with `struct user_pt_regs`:
    /// `u64 regs[31]; u64 sp; u64 pc; u64 pstate;` — a stable UAPI layout.
    pub const ARG_OFFSETS: [u32; 6] = [0, 8, 16, 24, 32, 40];
    const PSTATE_OFFSET: u32 = 33 * 8;
    const PSR_MODE32_BIT: u64 = 0x10;

    /// True when the caller is a 32-bit (AArch32) process.
    pub unsafe fn is_compat(regs: *const u8) -> bool {
        match unsafe { super::read_at::<u64>(regs, PSTATE_OFFSET) } {
            Ok(pstate) => pstate & PSR_MODE32_BIT != 0,
            Err(_) => false,
        }
    }
}

#[cfg(bpf_target_arch = "x86_64")]
mod arch {
    /// Offsets of di, si, dx, r10, r8, r9 in x86_64 `struct pt_regs`
    /// (emulator / host testing).
    pub const ARG_OFFSETS: [u32; 6] = [14 * 8, 13 * 8, 12 * 8, 7 * 8, 9 * 8, 8 * 8];

    pub unsafe fn is_compat(_regs: *const u8) -> bool {
        false
    }
}

/// Common body of the syscall-entry probes: remember the arguments until the
/// syscall returns.
///
/// Syscall entry functions take a single argument, the `struct pt_regs *`
/// holding the user-space registers, so the real arguments are read from it.
#[inline(always)]
fn syscall_enter(ctx: &ProbeContext, op: u32, shape: Shape) -> Result<(), i32> {
    if !should_trace(bpf_get_current_uid_gid() as u32) {
        return Ok(());
    }
    let regs: *const u8 = ctx.arg(0).ok_or(-1)?;
    let a0 = unsafe { read_at::<u64>(regs, arch::ARG_OFFSETS[0])? };
    let a1 = unsafe { read_at::<u64>(regs, arch::ARG_OFFSETS[1])? };
    let a2 = unsafe { read_at::<u64>(regs, arch::ARG_OFFSETS[2])? };

    let (dfd, path_ptr, flags, how_ptr) = match shape {
        Shape::Path0 => (AT_FDCWD, a0, 0, 0),
        Shape::Path0Flags1 => (AT_FDCWD, a0, a1 as u32, 0),
        Shape::DfdPath1 => (a0 as i32, a1, 0, 0),
        Shape::DfdPath1Flags2 => (a0 as i32, a1, a2 as u32, 0),
        Shape::DfdPath1How2 => (a0 as i32, a1, 0, a2),
        Shape::CwdPath1 => (AT_FDCWD, a1, 0, 0),
    };
    let pending = Pending {
        path_ptr,
        how_ptr,
        dfd,
        flags,
        abi: if unsafe { arch::is_compat(regs) } {
            ABI_COMPAT32
        } else {
            ABI_NATIVE
        },
        op,
        reported: 0,
        _pad: 0,
    };
    PENDING
        .insert(&bpf_get_current_pid_tgid(), &pending, 0)
        .map_err(|e| e as i32)
}

// One entry program per argument layout; the daemon attaches each to every
// syscall with that layout (see `bbd/src/syscalls.rs`).

/// open(path, flags, mode)
#[kprobe]
pub fn enter_open(ctx: ProbeContext) -> u32 {
    let _ = syscall_enter(&ctx, OP_OPEN, Shape::Path0Flags1);
    0
}

/// openat(dfd, path, flags, mode)
#[kprobe]
pub fn enter_openat(ctx: ProbeContext) -> u32 {
    let _ = syscall_enter(&ctx, OP_OPEN, Shape::DfdPath1Flags2);
    0
}

/// openat2(dfd, path, how, size)
#[kprobe]
pub fn enter_openat2(ctx: ProbeContext) -> u32 {
    let _ = syscall_enter(&ctx, OP_OPEN, Shape::DfdPath1How2);
    0
}

/// stat(path, ...), access(path, ...), readlink(path, ...)
#[kprobe]
pub fn enter_probe(ctx: ProbeContext) -> u32 {
    let _ = syscall_enter(&ctx, OP_PROBE, Shape::Path0);
    0
}

/// newfstatat / statx / faccessat / readlinkat (dfd, path, ...)
#[kprobe]
pub fn enter_probe_at(ctx: ProbeContext) -> u32 {
    let _ = syscall_enter(&ctx, OP_PROBE, Shape::DfdPath1);
    0
}

/// inotify_add_watch(inotify_fd, path, mask)
#[kprobe]
pub fn enter_watch(ctx: ProbeContext) -> u32 {
    let _ = syscall_enter(&ctx, OP_WATCH, Shape::CwdPath1);
    0
}

/// Have `sys_ret` send the return value of the call this thread is in,
/// which has just been reported.
#[inline(always)]
fn expect_result(pid_tgid: u64) -> Result<(), i32> {
    let pending = Pending {
        path_ptr: 0,
        how_ptr: 0,
        dfd: AT_FDCWD,
        flags: 0,
        abi: ABI_NATIVE,
        op: OP_PAIRED,
        reported: 1,
        _pad: 0,
    };
    PENDING.insert(&pid_tgid, &pending, 0).map_err(|e| e as i32)
}

// ---- execve -----------------------------------------------------------------

/// Common body of the exec probes. `first` is the index of the path
/// argument; the argument vector follows it.
#[inline(always)]
fn exec_enter(ctx: &ProbeContext, first: usize) -> Result<(), i32> {
    let uid = bpf_get_current_uid_gid() as u32;
    if !should_trace(uid) {
        return Ok(());
    }
    let regs: *const u8 = ctx.arg(0).ok_or(-1)?;
    let path_ptr = unsafe { read_at::<u64>(regs, arch::ARG_OFFSETS[first])? };
    let argv = unsafe { read_at::<u64>(regs, arch::ARG_OFFSETS[first + 1])? };
    let compat = unsafe { arch::is_compat(regs) };
    let pid_tgid = bpf_get_current_pid_tgid();

    let Some(mut entry) = EVENTS.reserve::<ExecEvent>(0) else {
        count_drop();
        return Err(-1);
    };
    let ev = entry.as_mut_ptr();
    // Everything is read now, unlike the paths of the other probes: if the
    // exec succeeds, the caller's memory is gone by the time it returns. The
    // caller has only just written these strings, so they are present.
    let mut argc = 0u32;
    let mut ended = false;
    unsafe {
        (*ev).hdr = header(EV_EXEC, uid, pid_tgid);
        (*ev).path_len = match bpf_probe_read_user_str_bytes(path_ptr as *const u8, &mut (*ev).path)
        {
            Ok(s) => s.len() as u32 + 1,
            Err(_) => 0,
        };
        let mut i = 0;
        while i < EXEC_ARGS {
            let slot = core::slice::from_raw_parts_mut(
                (&raw mut (*ev).args).cast::<u8>().add(i * EXEC_ARG_CAP),
                EXEC_ARG_CAP,
            );
            // The buffer is not zeroed; an unused slot must read as empty.
            slot[0] = 0;
            if !ended {
                // argv[i + 1]: a 32-bit process passes 32-bit pointers.
                let arg = if compat {
                    bpf_probe_read_user((argv + (i as u64 + 1) * 4) as *const u32).map(|p| p as u64)
                } else {
                    bpf_probe_read_user((argv + (i as u64 + 1) * 8) as *const u64)
                }
                .unwrap_or(0);
                if arg == 0 {
                    ended = true;
                } else {
                    if bpf_probe_read_user_str_bytes(arg as *const u8, slot).is_err() {
                        slot[0] = 0;
                    }
                    argc = i as u32 + 1;
                }
            }
            i += 1;
        }
        (*ev).argc = argc;
    }
    entry.submit(wakeup_flags());
    expect_result(pid_tgid)
}

/// execve(path, argv, envp)
#[kprobe]
pub fn enter_execve(ctx: ProbeContext) -> u32 {
    let _ = exec_enter(&ctx, 0);
    0
}

/// execveat(dfd, path, argv, envp, flags)
#[kprobe]
pub fn enter_execveat(ctx: ProbeContext) -> u32 {
    let _ = exec_enter(&ctx, 1);
    0
}

// ---- connect / sendto -------------------------------------------------------

/// `sizeof(struct sockaddr_in)` and `sizeof(struct sockaddr_in6)`.
const SOCKADDR_IN_LEN: u64 = 16;
const SOCKADDR_IN6_LEN: u64 = 28;
/// Offset of `sin6_addr` in `struct sockaddr_in6`.
const SIN6_ADDR_OFFSET: u64 = 8;
const REPORT_INTERVAL_NS: u64 = 1_000_000_000;

/// Common body of the network probes. `addr_arg` and `len_arg` are the
/// indexes of the `struct sockaddr *` argument and its length.
///
/// Reported at entry and without the outcome: most connections are made
/// non-blocking, for which the return value says nothing, and this way the
/// (far more numerous) calls to internet addresses cost one probe each
/// rather than two. If the address is not in memory the call is missed.
#[inline(always)]
fn connect_enter(ctx: &ProbeContext, addr_arg: usize, len_arg: usize) -> Result<(), i32> {
    let uid = bpf_get_current_uid_gid() as u32;
    if !should_trace(uid) {
        return Ok(());
    }
    let regs: *const u8 = ctx.arg(0).ok_or(-1)?;
    let sockaddr = unsafe { read_at::<u64>(regs, arch::ARG_OFFSETS[addr_arg])? };
    let len = unsafe { read_at::<u64>(regs, arch::ARG_OFFSETS[len_arg])? } & 0xffff_ffff;
    report_destination(uid, sockaddr, len)
}

/// Body of the sendmsg probes: the destination is in the `struct msghdr`
/// the second argument points to (for sendmmsg, in the first message's).
#[inline(always)]
fn sendmsg_enter(ctx: &ProbeContext) -> Result<(), i32> {
    let uid = bpf_get_current_uid_gid() as u32;
    if !should_trace(uid) {
        return Ok(());
    }
    let regs: *const u8 = ctx.arg(0).ok_or(-1)?;
    let msg = unsafe { read_at::<u64>(regs, arch::ARG_OFFSETS[1])? };
    // struct msghdr begins with `void *msg_name; int msg_namelen;`, which
    // a 32-bit process lays out with a 32-bit pointer.
    let (sockaddr, len) = if unsafe { arch::is_compat(regs) } {
        let head: [u32; 2] = unsafe { bpf_probe_read_user(msg as *const [u32; 2])? };
        (head[0] as u64, head[1] as u64)
    } else {
        let head: [u64; 2] = unsafe { bpf_probe_read_user(msg as *const [u64; 2])? };
        (head[0], head[1] & 0xffff_ffff)
    };
    report_destination(uid, sockaddr, len)
}

/// Report a call's destination address, if it is one worth reporting.
#[inline(always)]
fn report_destination(uid: u32, sockaddr: u64, len: u64) -> Result<(), i32> {
    // A send on a connected socket has no address.
    if sockaddr == 0 {
        return Ok(());
    }
    if len < SOCKADDR_IN_LEN {
        return Ok(());
    }
    // Family, port and, for IPv4, the address.
    let head: [u8; 8] = unsafe { bpf_probe_read_user(sockaddr as *const [u8; 8])? };
    let family = u16::from_ne_bytes([head[0], head[1]]) as u32;
    let port = u16::from_be_bytes([head[2], head[3]]) as u32;
    let mut addr = [0u8; 16];
    let class = if family == AF_INET {
        addr[0] = head[4];
        addr[1] = head[5];
        addr[2] = head[6];
        addr[3] = head[7];
        ipv4_class([head[4], head[5], head[6], head[7]])
    } else if family == AF_INET6 && len >= SOCKADDR_IN6_LEN {
        addr = unsafe { bpf_probe_read_user((sockaddr + SIN6_ADDR_OFFSET) as *const [u8; 16])? };
        ipv6_class(&addr)
    } else {
        return Ok(());
    };
    if class == NET_PUBLIC {
        return Ok(());
    }

    let pid_tgid = bpf_get_current_pid_tgid();
    let destination = Destination {
        tgid: (pid_tgid >> 32) as u32,
        port,
        addr,
    };
    let now = unsafe { bpf_ktime_get_ns() };
    if let Some(last) = unsafe { RECENT_DESTINATIONS.get(&destination) } {
        if now.wrapping_sub(*last) < REPORT_INTERVAL_NS {
            return Ok(());
        }
    }
    let _ = RECENT_DESTINATIONS.insert(&destination, &now, 0);

    let Some(mut entry) = EVENTS.reserve::<ConnectEvent>(0) else {
        count_drop();
        return Err(-1);
    };
    entry.write(ConnectEvent {
        hdr: header(EV_CONNECT, uid, pid_tgid),
        family,
        port,
        addr,
    });
    entry.submit(wakeup_flags());
    Ok(())
}

/// connect(fd, addr, addrlen)
#[kprobe]
pub fn enter_connect(ctx: ProbeContext) -> u32 {
    let _ = connect_enter(&ctx, 1, 2);
    0
}

/// sendto(fd, buf, len, flags, addr, addrlen)
#[kprobe]
pub fn enter_sendto(ctx: ProbeContext) -> u32 {
    let _ = connect_enter(&ctx, 4, 5);
    0
}

/// sendmsg(fd, msg, flags) and sendmmsg(fd, msgvec, vlen, flags)
#[kprobe]
pub fn enter_sendmsg(ctx: ProbeContext) -> u32 {
    let _ = sendmsg_enter(&ctx);
    0
}

// ---- other processes --------------------------------------------------------

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

// ---- netlink ----------------------------------------------------------------

/// Offset of `nlmsg_type` in `struct nlmsghdr` (UAPI): it follows the
/// 32-bit length.
const NLMSG_TYPE_OFFSET: u32 = 4;

/// selinux_netlink_send(struct sock *sk, struct sk_buff *skb), or
/// security_netlink_send with the same arguments.
///
/// The hook's return value is the verdict: Android's SELinux policy refuses
/// most of these requests to apps, and the attempt is recorded either way.
#[kprobe]
pub fn enter_netlink(ctx: ProbeContext) -> u32 {
    let _ = try_enter_netlink(&ctx);
    0
}

fn try_enter_netlink(ctx: &ProbeContext) -> Result<(), i32> {
    let uid = bpf_get_current_uid_gid() as u32;
    if !should_trace(uid) {
        return Ok(());
    }
    let off = OFFSETS.load();
    let sk: *const u8 = ctx.arg(0).ok_or(-1)?;
    let skb: *const u8 = ctx.arg(1).ok_or(-1)?;
    let protocol = unsafe { read_at::<u16>(sk, off[OFF_SOCK_PROTOCOL])? } as u32;
    // The message starts at the buffer's data pointer.
    let data: *const u8 = unsafe { read_at(skb, off[OFF_SKB_DATA])? };
    let msg_type = unsafe { read_at::<u16>(data, NLMSG_TYPE_OFFSET)? } as u32;
    if !netlink_matters(protocol, msg_type) {
        return Ok(());
    }
    let pid_tgid = bpf_get_current_pid_tgid();
    let Some(mut entry) = EVENTS.reserve::<NetlinkEvent>(0) else {
        count_drop();
        return Err(-1);
    };
    entry.write(NetlinkEvent {
        hdr: header(EV_NETLINK, uid, pid_tgid),
        protocol,
        msg_type,
    });
    entry.submit(wakeup_flags());
    expect_result(pid_tgid)
}

// ---- binder -----------------------------------------------------------------
//
// Everything read here is binder's userspace ABI (uapi/linux/android/binder.h)
// or the parcel format of the Android framework; nothing depends on how the
// driver is implemented.

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
    let end = bwr[2].wrapping_add(bwr[0]);
    let mut at = bwr[2].wrapping_add(bwr[1]);
    let mut i = 0;
    while i < MAX_COMMANDS {
        if at.wrapping_add(4) > end {
            return Ok(());
        }
        let command: u32 = unsafe { bpf_probe_read_user(at as *const u32)? };
        if command == BC_TRANSACTION || command == BC_TRANSACTION_SG {
            return binder_transaction(at + 4, uid);
        }
        // The size of a command's payload is encoded in the command.
        at = at.wrapping_add(4 + ((command >> 16) & 0x3fff) as u64);
        i += 1;
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
    let mut args = 0;
    if size != 0 {
        if size < PARCEL_NAME_OFFSET {
            return Ok(());
        }
        let prefix: [u32; 4] = unsafe { bpf_probe_read_user(data as *const [u32; 4])? };
        let len = prefix[3];
        if prefix[2] != PARCEL_HEADER_SYSTEM || len < BINDER_MIN_NAME || len > BINDER_MAX_NAME {
            return Ok(());
        }
        // The name: `len` UTF-16 code units, a terminator, padding to 4 bytes.
        let name = data + PARCEL_NAME_OFFSET;
        let name_bytes = len as u64 * 2;
        let len_bytes = len.to_ne_bytes();
        key[0] = len_bytes[0];
        key[1] = len_bytes[1];
        key[2] = len_bytes[2];
        key[3] = len_bytes[3];
        unsafe {
            let key_bytes = (&raw mut key).cast::<u8>();
            bpf_probe_read_user_buf(
                name as *const u8,
                core::slice::from_raw_parts_mut(key_bytes.add(4), BINDER_KEY_HEAD),
            )
            .map_err(|e| e as i32)?;
            bpf_probe_read_user_buf(
                (name + name_bytes - BINDER_KEY_TAIL as u64) as *const u8,
                core::slice::from_raw_parts_mut(
                    key_bytes.add(4 + BINDER_KEY_HEAD),
                    BINDER_KEY_TAIL,
                ),
            )
            .map_err(|e| e as i32)?;
        }
        args = name + ((name_bytes + 2 + 3) & !3);
    }
    let Some(watched) = (unsafe { BINDER_WATCH.get(&key) }) else {
        return Ok(());
    };
    // `code` is below BINDER_CODES, so the word index is in range; the mask
    // tells the verifier.
    let word = watched[1 + ((code >> 6) & 7) as usize];
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
    entry.submit(flags);
    Ok(())
}

// ---- sandbox gate -----------------------------------------------------------

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

/// Return of any tracked syscall, and of the `netlink_send` security hook.
#[kretprobe]
pub fn sys_ret(ctx: RetProbeContext) -> u32 {
    let _ = try_sys_ret(&ctx);
    0
}

fn try_sys_ret(ctx: &RetProbeContext) -> Result<(), i32> {
    let uid = bpf_get_current_uid_gid() as u32;
    if !should_trace(uid) {
        return Ok(());
    }
    let pid_tgid = bpf_get_current_pid_tgid();
    let Some(pending) = (unsafe { PENDING.get(&pid_tgid) }).copied() else {
        return Ok(());
    };
    let _ = PENDING.remove(&pid_tgid);

    let ret = ctx.ret::<i64>() as i32;

    // `file_open` already reported the real file; just attach the result.
    if pending.reported != 0 {
        let Some(mut entry) = EVENTS.reserve::<ResultEvent>(0) else {
            count_drop();
            return Err(-1);
        };
        entry.write(ResultEvent {
            hdr: header(EV_RESULT, uid, pid_tgid),
            ret,
        });
        entry.submit(wakeup_flags());
        return Ok(());
    }
    // A successful open that `file_open` did not report was on a filesystem
    // it does not cover. A failed one never reached a file, so the string is
    // all there is.
    if pending.op == OP_OPEN && ret >= 0 {
        return Ok(());
    }

    // The path is read here rather than at entry: BPF can't fault user pages
    // in, and by now the kernel has touched the string.
    let mut head = [0u8; HEAD_LEN];
    if unsafe { bpf_probe_read_user_str_bytes(pending.path_ptr as *const u8, &mut head) }.is_err() {
        return Ok(());
    }
    if !is_interesting(&head, (pid_tgid >> 32) as u32, pid_tgid as u32) {
        return Ok(());
    }
    // A relative path has to be resolved by the daemon through /proc, which
    // costs far more than the event is worth for a mere existence check.
    // stat-style calls relative to a directory are also extremely common
    // (every directory scan makes one per entry), and the scan itself is
    // already recorded when the directory is opened.
    if head[0] != b'/' && pending.op == OP_PROBE {
        return Ok(());
    }

    // `struct open_how` begins with `__u64 flags`.
    let flags = if pending.how_ptr != 0 {
        unsafe { bpf_probe_read_user(pending.how_ptr as *const u64) }.unwrap_or(0) as u32
    } else {
        pending.flags
    };

    let Some(mut entry) = EVENTS.reserve::<PathEvent>(0) else {
        count_drop();
        return Err(-1);
    };
    let ev = entry.as_mut_ptr();
    unsafe {
        (*ev).hdr = header(EV_PATH, uid, pid_tgid);
        (*ev).op = pending.op;
        (*ev).ret = ret;
        (*ev).dfd = pending.dfd;
        (*ev).flags = flags;
        (*ev).abi = pending.abi;
        (*ev).path_len =
            match bpf_probe_read_user_str_bytes(pending.path_ptr as *const u8, &mut (*ev).path) {
                // The helper's slice excludes the NUL; count it so userspace
                // can tell "empty path" from "unreadable".
                Ok(s) => s.len() as u32 + 1,
                Err(_) => 0,
            };
    }
    entry.submit(wakeup_flags());
    Ok(())
}

#[cfg(not(test))]
#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    loop {}
}

#[unsafe(link_section = "license")]
#[unsafe(no_mangle)]
static LICENSE: [u8; 4] = *b"GPL\0";
