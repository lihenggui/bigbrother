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
        bpf_get_current_pid_tgid, bpf_get_current_uid_gid, bpf_probe_read_user,
        bpf_probe_read_user_str_bytes,
    },
    macros::{kprobe, kretprobe},
    programs::{ProbeContext, RetProbeContext},
};
use bb_common::{
    ABI_COMPAT32, ABI_NATIVE, EV_EXEC, EV_PATH, EV_RESULT, EXEC_ARG_CAP, EXEC_ARGS, ExecEvent,
    OP_OPEN, OP_PAIRED, OP_PROBE, OP_WATCH, PathEvent, ResultEvent,
};

use crate::{
    common::{EVENTS, arch, count_drop, header, read_at, should_trace, wakeup_flags},
    files::{HEAD_LEN, is_interesting},
    pending::{self, Pending},
};

const AT_FDCWD: i32 = -100;

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
    let [a0, a1, a2] = unsafe { arch::path_args(regs)? };

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
    pending::remember(bpf_get_current_pid_tgid(), pending)
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
pub(super) fn expect_result(pid_tgid: u64) -> Result<(), i32> {
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
    pending::remember(pid_tgid, pending)
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
    let Some(pending) = pending::take(pid_tgid) else {
        return Ok(());
    };

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
