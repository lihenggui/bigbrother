//! Which procfs and sysfs entries matter.
//!
//! The daemon decides precisely what to record (`bbd/src/rules.rs`). These
//! checks are a coarse version of the same rules, applied in the kernel to
//! the first one or two path components so that the bulk of irrelevant opens
//! never leaves it. They may let through more than the daemon keeps, never
//! less; the daemon's tests check that against its own rules.
//!
//! They live here, rather than in the eBPF crate, so that those tests can
//! call them.

/// How much of a path component the eBPF side reads: the in-kernel string
/// copy always NUL-terminates, so an 8-byte buffer holds at most 7 characters.
pub const COMPONENT_CHARS: usize = 7;

/// True if `name` is `expected`. `name` holds the first [`COMPONENT_CHARS`]
/// characters of a path component, NUL padded; `expected` must be given
/// truncated to the same length.
#[inline(always)]
pub fn component_is(name: &[u8; 8], expected: &[u8]) -> bool {
    let mut i = 0;
    while i < 8 {
        let want = if i < expected.len() { expected[i] } else { 0 };
        if name[i] != want {
            return false;
        }
        i += 1;
    }
    true
}

/// `top` is the first component below /proc of a system-wide entry.
#[inline(always)]
pub fn proc_entry_matters(top: &[u8; 8]) -> bool {
    component_is(top, b"net")
        || component_is(top, b"cpuinfo")
        || component_is(top, b"version")
        || component_is(top, b"mounts")
        || component_is(top, b"filesys") // filesystems
        || component_is(top, b"modules")
        || component_is(top, b"kallsym") // kallsyms
        || component_is(top, b"partiti") // partitions
        || component_is(top, b"sys")
        || component_is(top, b"bus")
}

/// `below_pid` is the component below a process's own `/proc/<pid>`.
///
/// `/proc/mounts` and `/proc/net` are symlinks into the calling process's
/// directory (`self/mounts`, `self/net`), so reading the mount table or the
/// network state arrives looking like a process inspecting itself. Those two
/// are information about the system, not about the process.
#[inline(always)]
pub fn own_proc_entry_matters(below_pid: &[u8; 8]) -> bool {
    component_is(below_pid, b"net")
        || component_is(below_pid, b"mounts")
        || component_is(below_pid, b"mountin") // mountinfo
}

/// `top` and `second` are the first two components below /sys.
#[inline(always)]
pub fn sysfs_entry_matters(top: &[u8; 8], second: &[u8; 8]) -> bool {
    if component_is(top, b"devices") {
        // /sys/devices/system holds CPU and memory topology and statistics.
        return !component_is(second, b"system");
    }
    if component_is(top, b"fs") {
        return component_is(second, b"selinux");
    }
    !(component_is(top, b"kernel")
        || component_is(top, b"module")
        || component_is(top, b"power")
        || component_is(top, b"firmwar")) // firmware
}
