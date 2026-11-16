//! Kernel side: records what app processes touch on shared storage and in
//! the kernel's information filesystems.
//!
//! Two groups of programs cooperate:
//!
//! * `file_open` — kprobe on `security_file_open(struct file *)`. This is
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
        bpf_get_current_pid_tgid, bpf_get_current_uid_gid, bpf_probe_read_kernel,
        bpf_probe_read_kernel_str_bytes, bpf_probe_read_user, bpf_probe_read_user_str_bytes,
    },
    macros::{kprobe, kretprobe, map},
    maps::{LruHashMap, PerCpuArray, RingBuf},
    programs::{ProbeContext, RetProbeContext},
};
use bb_common::filter::{own_proc_entry_matters, proc_entry_matters, sysfs_entry_matters};
use bb_common::{
    ABI_COMPAT32, ABI_NATIVE, DATA_START_LIMIT, EV_FILE, EV_PATH, EV_RESULT, EventHeader,
    FUSE_SUPER_MAGIC, FileEvent, NAME_CAP, OFF_COUNT, OFF_DENTRY_INODE, OFF_DENTRY_NAME,
    OFF_DENTRY_PARENT, OFF_DENTRY_SB, OFF_FILE_DENTRY, OFF_FILE_FLAGS, OFF_INODE_MODE, OFF_SB_DEV,
    OFF_SB_MAGIC, OP_OPEN, OP_PROBE, OP_WATCH, PROC_SUPER_MAGIC, PathEvent, ResultEvent,
    SELINUX_MAGIC, SYSFS_MAGIC,
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
}

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
/// for no benefit: it also polls on a timer. So events are queued silently,
/// and the daemon is woken early only when the buffer is filling up.
#[inline(always)]
fn wakeup_flags() -> u64 {
    if EVENTS.query(BPF_RB_AVAIL_DATA) >= WAKE_THRESHOLD {
        BPF_RB_FORCE_WAKEUP
    } else {
        BPF_RB_NO_WAKEUP
    }
}

#[inline(always)]
fn count_drop() {
    if let Some(slot) = DROPPED.get_ptr_mut(0) {
        unsafe { *slot += 1 };
    }
}

#[inline(always)]
fn header(kind: u32, uid: u32, pid_tgid: u64) -> EventHeader {
    EventHeader {
        kind,
        uid,
        pid: (pid_tgid >> 32) as u32,
        tid: pid_tgid as u32,
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

// ---- security_file_open -----------------------------------------------------

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
    let Some(mut entry) = EVENTS.reserve::<FileEvent>(0) else {
        count_drop();
        return Err(-1);
    };
    let ev = entry.as_mut_ptr();

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
    // statistics, kernel tunables. Drop those here rather than wake the
    // daemon for them.
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
        entry.discard(0);
        return Ok(());
    }
    if boring {
        entry.discard(0);
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
    entry.submit(wakeup_flags());
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
    pub const ARG_OFFSETS: [u32; 3] = [0, 8, 16];
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
    /// Offsets of di, si, dx in x86_64 `struct pt_regs` (emulator / host testing).
    pub const ARG_OFFSETS: [u32; 3] = [14 * 8, 13 * 8, 12 * 8];

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

/// Return of any tracked syscall.
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
