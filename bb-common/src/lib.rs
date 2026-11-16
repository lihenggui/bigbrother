//! Types shared between the eBPF programs and the userspace daemon.
//!
//! Everything here crosses the kernel/user boundary as raw bytes, so structs
//! are `repr(C)` with only 4-byte-aligned fields and no implicit padding.
//! Every event starts with an [`EventHeader`]; `EventHeader::kind` says which
//! struct the bytes are.

#![no_std]

pub mod filter;

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
pub const OFF_COUNT: usize = 9;

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

/// What the app did with the path (`PathEvent::op`).
pub const OP_OPEN: u32 = 0;
/// stat / access / readlink: learns that a file exists and its metadata.
pub const OP_PROBE: u32 = 1;
/// inotify_add_watch: gets notified when the file or directory changes.
pub const OP_WATCH: u32 = 2;

pub const ABI_NATIVE: u32 = 0;
pub const ABI_COMPAT32: u32 = 1;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct EventHeader {
    pub kind: u32,
    pub uid: u32,
    pub pid: u32,
    pub tid: u32,
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

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ResultEvent {
    pub hdr: EventHeader,
    /// fd on success, negative errno on failure.
    pub ret: i32,
}

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
