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

//! Types shared between the eBPF programs and the userspace daemon.
//!
//! Everything here crosses the kernel/user boundary as raw bytes, so structs
//! are `repr(C)` with only 4-byte-aligned fields and no implicit padding.
//! Every event starts with an [`EventHeader`]; `EventHeader::kind` says which
//! struct the bytes are.

#![no_std]

pub mod filter;

/// Fails to compile unless `$t` is exactly as large as its fields together,
/// that is, has no padding. The fields must all be named: the pattern below
/// does not compile when one is left out, so a field added later is checked
/// too.
///
/// Padding is never written, and the verifier of kernels before 6.6 rejects
/// a program that passes a value with unwritten bytes from its stack to a
/// helper (`invalid indirect read from stack`): a map key or value, or an
/// event output in one go. So every struct that may be on an eBPF program's
/// stack has none, explicit filler fields included.
#[macro_export]
macro_rules! assert_no_padding {
    ($t:ident { $($field:ident),+ $(,)? }) => {
        const _: () = {
            #[allow(dead_code)]
            fn all_fields_named(value: $t) {
                let $t { $($field: _),+ } = value;
            }
            const fn pointee_size<P>(_: *const P) -> usize {
                core::mem::size_of::<P>()
            }
            let value = core::mem::MaybeUninit::<$t>::uninit();
            let base = value.as_ptr();
            // SAFETY: only field addresses are taken, nothing is read.
            let fields = 0 $(+ pointee_size(unsafe { &raw const (*base).$field }))+;
            assert!(core::mem::size_of::<$t>() == fields, "struct has padding");
        };
    };
}

// ---- kernel struct offsets --------------------------------------------------

/// Indexes into the `OFFSETS` global: byte offsets of kernel struct fields,
/// resolved by the daemon from the running kernel's BTF and handed to the
/// eBPF programs at load time. This is what lets one binary run on kernels
/// with different struct layouts.
pub const OFF_FILE_DENTRY: usize = 0; // struct file    -> f_path.dentry
pub const OFF_FILE_FLAGS: usize = 1; //  struct file    -> f_flags
pub const OFF_DENTRY_PARENT: usize = 2; // struct dentry -> d_parent
pub const OFF_DENTRY_NAME: usize = 3; //  struct dentry -> d_name.name
pub const OFF_DENTRY_SB: usize = 4; //    struct dentry -> d_sb
pub const OFF_DENTRY_INODE: usize = 5; // struct dentry -> d_inode
pub const OFF_INODE_MODE: usize = 6; //   struct inode  -> i_mode
pub const OFF_SB_MAGIC: usize = 7; //     struct super_block -> s_magic
pub const OFF_SB_DEV: usize = 8; //       struct super_block -> s_dev
pub const OFF_SOCK_PROTOCOL: usize = 9; // struct sock   -> sk_protocol
pub const OFF_SKB_DATA: usize = 10; //     struct sk_buff -> data
pub const OFF_COUNT: usize = 11;

/// Stands in for an offset the running kernel's BTF does not have. Only the
/// offsets of optional probes may be missing; such a probe is not attached.
pub const OFF_MISSING: u32 = u32::MAX;

// ---- filesystem identification ---------------------------------------------

pub const FUSE_SUPER_MAGIC: u32 = 0x6573_5546;
pub const PROC_SUPER_MAGIC: u32 = 0x9fa0;
pub const SYSFS_MAGIC: u32 = 0x6265_6572;
/// selinuxfs, mounted at /sys/fs/selinux. A filesystem of its own, not part
/// of sysfs.
pub const SELINUX_MAGIC: u32 = 0xf97c_ff8c;

// ---- events -----------------------------------------------------------------

/// A file the kernel actually opened (from `security_file_open`).
pub const EV_FILE: u32 = 1;
/// Return value of the syscall that produced the preceding `EV_FILE` events
/// on the same thread.
pub const EV_RESULT: u32 = 2;
/// A path string taken from syscall arguments: stat/access/inotify calls, and
/// opens that failed before reaching a file.
pub const EV_PATH: u32 = 3;

/// A program the app started (execve), reported when the call is made. An
/// `EV_RESULT` on the same thread follows with the outcome.
pub const EV_EXEC: u32 = 4;
/// A connection or datagram to an address on this device or the local
/// network (connect, sendto), reported when the call is made.
pub const EV_CONNECT: u32 = 5;

/// An attempt to trace another process or reach into its memory (ptrace,
/// process_vm_readv/writev). An `EV_RESULT` follows.
pub const EV_PROCESS: u32 = 6;
/// A netlink request for network configuration, seen at the security hook
/// that decides whether to allow it. An `EV_RESULT` follows.
pub const EV_NETLINK: u32 = 7;

/// A call to a watched method of a system service (see `BinderEvent`).
pub const EV_BINDER: u32 = 8;

/// What the app did with the path (`PathEvent::op`).
pub const OP_OPEN: u32 = 0;
/// stat / access / readlink: learns that a file exists and its metadata.
pub const OP_PROBE: u32 = 1;
/// inotify_add_watch: gets notified when the file or directory changes.
pub const OP_WATCH: u32 = 2;

/// A call that was reported when it was made; only its return value is
/// still wanted.
pub const OP_PAIRED: u32 = 3;

pub const ABI_NATIVE: u32 = 0;
pub const ABI_COMPAT32: u32 = 1;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct EventHeader {
    pub kind: u32,
    pub uid: u32,
    pub pid: u32,
    pub tid: u32,
    /// When it happened, in nanoseconds since boot, time suspended included
    /// (`bpf_ktime_get_boot_ns`). Two halves, low first, to keep to 4-byte
    /// fields; see [`EventHeader::boot_ns`].
    pub boot_ns_low: u32,
    pub boot_ns_high: u32,
}

assert_no_padding!(EventHeader {
    kind,
    uid,
    pid,
    tid,
    boot_ns_low,
    boot_ns_high
});

impl EventHeader {
    pub fn boot_ns(&self) -> u64 {
        u64::from(self.boot_ns_high) << 32 | u64::from(self.boot_ns_low)
    }
}

/// Longest single path component (NAME_MAX + NUL).
pub const NAME_CAP: usize = 256;
/// A new component may start anywhere below this offset in `FileEvent::data`.
/// Must be a power of two: the eBPF side bounds the offset with a bit mask.
pub const DATA_START_LIMIT: usize = 2048;
/// Space for the components of one path: every allowed start offset leaves
/// room for a full-length component.
pub const DATA_CAP: usize = DATA_START_LIMIT + NAME_CAP;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FileEvent {
    pub hdr: EventHeader,
    /// `file->f_flags`: O_* flags as the kernel recorded them.
    pub flags: u32,
    /// Filesystem magic of the file's superblock.
    pub magic: u32,
    /// Kernel `dev_t` of the superblock (`major << 20 | minor`).
    pub dev: u32,
    /// `inode->i_mode`.
    pub mode: u32,
    /// 1 if this open happened inside a tracked open() syscall, in which case
    /// an `EV_RESULT` follows; 0 if the result is not observable.
    pub in_syscall: u32,
    /// 1 if the path was too deep or too long and `data` is incomplete.
    pub truncated: u32,
    /// Bytes used in `data`.
    pub data_len: u32,
    /// Path components relative to the filesystem root, leaf first, each
    /// NUL-terminated: "a.jpg\0Camera\0DCIM\0" for DCIM/Camera/a.jpg.
    pub data: [u8; DATA_CAP],
}

assert_no_padding!(FileEvent {
    hdr,
    flags,
    magic,
    dev,
    mode,
    in_syscall,
    truncated,
    data_len,
    data
});

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ResultEvent {
    pub hdr: EventHeader,
    /// fd on success, negative errno on failure.
    pub ret: i32,
}

assert_no_padding!(ResultEvent { hdr, ret });

/// Bytes of path captured per `PathEvent`. Longer paths are truncated.
pub const PATH_CAP: usize = 512;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct PathEvent {
    pub hdr: EventHeader,
    pub op: u32,
    /// Syscall return value: 0 or fd on success, negative errno on failure.
    pub ret: i32,
    /// Directory fd the path is relative to (AT_FDCWD = -100).
    pub dfd: i32,
    /// O_* flags for opens, 0 otherwise.
    pub flags: u32,
    pub abi: u32,
    /// Bytes written to `path`, including the trailing NUL. 0 if unreadable.
    pub path_len: u32,
    pub path: [u8; PATH_CAP],
}

assert_no_padding!(PathEvent {
    hdr,
    op,
    ret,
    dfd,
    flags,
    abi,
    path_len,
    path
});

/// Bytes of the program path captured per `ExecEvent`.
pub const EXEC_PATH_CAP: usize = 256;
/// Arguments captured per `ExecEvent`, not counting `argv[0]`.
pub const EXEC_ARGS: usize = 6;
/// Bytes captured per argument, including the NUL. Longer ones are cut.
pub const EXEC_ARG_CAP: usize = 64;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ExecEvent {
    pub hdr: EventHeader,
    /// Bytes written to `path`, including the trailing NUL. 0 if unreadable.
    pub path_len: u32,
    /// How many slots of `args` are filled.
    pub argc: u32,
    pub path: [u8; EXEC_PATH_CAP],
    /// `argv[1..]`, one NUL-terminated string per fixed-size slot.
    pub args: [[u8; EXEC_ARG_CAP]; EXEC_ARGS],
}

assert_no_padding!(ExecEvent {
    hdr,
    path_len,
    argc,
    path,
    args
});

pub const AF_INET: u32 = 2;
pub const AF_INET6: u32 = 10;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ConnectEvent {
    pub hdr: EventHeader,
    /// `AF_INET` or `AF_INET6`.
    pub family: u32,
    /// Destination port, host byte order.
    pub port: u32,
    /// Destination address in network order; IPv4 uses the first 4 bytes.
    pub addr: [u8; 16],
}

assert_no_padding!(ConnectEvent {
    hdr,
    family,
    port,
    addr
});

/// How a process went after another one (`ProcessEvent::how`).
pub const PROCESS_TRACE: u32 = 0;
pub const PROCESS_READ: u32 = 1;
pub const PROCESS_WRITE: u32 = 2;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ProcessEvent {
    pub hdr: EventHeader,
    pub how: u32,
    /// Pid of the process it went after.
    pub target: u32,
}

assert_no_padding!(ProcessEvent { hdr, how, target });

#[repr(C)]
#[derive(Clone, Copy)]
pub struct NetlinkEvent {
    pub hdr: EventHeader,
    /// Netlink protocol of the socket (`NETLINK_ROUTE`...).
    pub protocol: u32,
    /// `nlmsghdr::nlmsg_type` of the request.
    pub msg_type: u32,
}

assert_no_padding!(NetlinkEvent {
    hdr,
    protocol,
    msg_type
});

// ---- binder -----------------------------------------------------------------
//
// Apps reach system services through binder. The kernel side reads, from the
// caller's memory, the transaction it is about to send: the interface name
// at the start of the parcel and the method's code. Which of them are worth
// reporting is in a map the daemon fills, keyed by a fingerprint of the
// interface name.

/// Fingerprint of an interface name: its length in UTF-16 code units (u32,
/// native endian), its first `BINDER_KEY_HEAD` bytes and its last
/// `BINDER_KEY_TAIL` bytes as UTF-16LE. Fixed-size pieces from known places,
/// so the kernel side can build it without loops.
pub const BINDER_KEY_HEAD: usize = 16;
pub const BINDER_KEY_TAIL: usize = 32;
pub const BINDER_KEY_LEN: usize = 4 + BINDER_KEY_HEAD + BINDER_KEY_TAIL;
pub type BinderKey = [u8; BINDER_KEY_LEN];

/// Shortest interface name that can be watched, in code units: head and tail
/// must both lie inside the name.
pub const BINDER_MIN_NAME: u32 = (BINDER_KEY_TAIL / 2) as u32;
/// Longest interface name the kernel side accepts.
pub const BINDER_MAX_NAME: u32 = 512;

/// Transaction codes below this can be watched.
pub const BINDER_CODES: u32 = 512;
/// Map value: `[0]` is an id the daemon chose for the interface, the rest a
/// bitmap of the watched transaction codes.
pub const BINDER_VALUE_WORDS: usize = 1 + (BINDER_CODES / 64) as usize;
pub type BinderValue = [u64; BINDER_VALUE_WORDS];
/// Set in `[0]` next to the id: calls to the interface wake the daemon at
/// once instead of waiting for its next read. Turning a sensor on starts a
/// session the daemon must know of while the app's process still runs.
/// Such calls are rare, and the kernel side wakes the daemon for them at
/// most once a second; other calls never wake it early.
pub const BINDER_WAKE: u64 = 1 << 32;

/// Calls that carry no interface name, which some native interfaces make
/// (`ISensorEventConnection::destroy` sends an empty parcel), are looked up
/// under this key: no name has length 0. Only calls without any arguments
/// are looked up this way, and the daemon tells them apart by the binder
/// handle they went to (`BinderEvent::target`).
pub const BINDER_UNNAMED_KEY: BinderKey = [0; BINDER_KEY_LEN];

/// Bytes of the call's arguments captured per `BinderEvent`: enough to reach
/// the strings that follow the caller's identity in a provider call.
pub const BINDER_ARGS_CAP: usize = 512;
/// What is captured instead when that much can't be read, as happens when a
/// small parcel sits at the end of mapped memory.
pub const BINDER_ARGS_SHORT: usize = 96;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct BinderEvent {
    pub hdr: EventHeader,
    /// The id from the map value.
    pub interface: u32,
    pub code: u32,
    /// The caller's handle for the object called: within one process, it
    /// tells apart two objects of the same interface, such as two sensor
    /// connections, for as long as the caller holds them.
    pub target: u32,
    /// Bytes of `args` that are valid; 0 if they could not be read.
    pub args_len: u32,
    /// The parcel from the first argument on.
    pub args: [u8; BINDER_ARGS_CAP],
}

assert_no_padding!(BinderEvent {
    hdr,
    interface,
    code,
    target,
    args_len,
    args
});

// ---- sandbox gate -----------------------------------------------------------
//
// Not a record: the gate holds a starting app until the daemon has replaced
// what it sees (see `gate_setresuid` in bb-ebpf). It has a ring buffer of its
// own, read by a thread that does nothing else.

/// A process stopped as it was about to take a sandboxed uid.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct GateEvent {
    pub pid: u32,
    /// The uid it is taking.
    pub uid: u32,
    /// When it was stopped, `CLOCK_MONOTONIC` nanoseconds (`bpf_ktime_get_ns`),
    /// low half first.
    pub at_ns_low: u32,
    pub at_ns_high: u32,
}

assert_no_padding!(GateEvent {
    pid,
    uid,
    at_ns_low,
    at_ns_high
});

impl GateEvent {
    pub fn at_ns(&self) -> u64 {
        u64::from(self.at_ns_high) << 32 | u64::from(self.at_ns_low)
    }
}

/// How long the daemon may leave a stopped process waiting before the kernel
/// side takes it to be stuck, and stops holding further starts for it: they
/// are killed instead (see `GATE_WAITING_SINCE` in bb-ebpf).
pub const GATE_STALE_NS: u64 = 3_000_000_000;
/// Stored in place of a time once the daemon's gate thread has ended: every
/// start of a sandboxed app is killed from then on.
pub const GATE_CLOSED: u64 = 1;
