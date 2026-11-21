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
//! The programs cover these hooks:
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
//! * `network_refused` — not a recorder either: a socket filter the kernel's
//!   packet filter asks, for each packet leaving the device, whether the
//!   socket it comes from is one of an app the sandbox keeps from the
//!   network.
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

mod binder;
mod common;
mod files;
mod gate;
mod net;
mod paths;
mod pending;
mod process;

#[cfg(not(test))]
#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    loop {}
}

#[unsafe(link_section = "license")]
#[unsafe(no_mangle)]
static LICENSE: [u8; 4] = *b"GPL\0";
