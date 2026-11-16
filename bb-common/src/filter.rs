//! Which procfs and sysfs entries, and which network destinations, matter.
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

// ---- network destinations ---------------------------------------------------

/// Somewhere on the internet. Not recorded: where an app's traffic goes is a
/// firewall's subject, and there is far too much of it.
pub const NET_PUBLIC: u32 = 0;
/// This device: how an app finds out what else is listening on it.
pub const NET_LOOPBACK: u32 = 1;
/// A private or link-local address: another device on the local network.
pub const NET_LOCAL: u32 = 2;
/// Multicast or broadcast: how devices on the local network are discovered.
pub const NET_MULTICAST: u32 = 3;

/// Where an IPv4 address (network byte order) leads.
#[inline(always)]
pub fn ipv4_class(a: [u8; 4]) -> u32 {
    // 0.0.0.0 as a destination reaches the local host.
    if a[0] == 127 || (a[0] == 0 && a[1] == 0 && a[2] == 0 && a[3] == 0) {
        return NET_LOOPBACK;
    }
    if a[0] == 10
        || (a[0] == 172 && a[1] & 0xf0 == 16)
        || (a[0] == 192 && a[1] == 168)
        || (a[0] == 169 && a[1] == 254)
    {
        return NET_LOCAL;
    }
    // 224.0.0.0/4, and the limited broadcast address.
    if a[0] & 0xf0 == 224 || (a[0] == 255 && a[1] == 255 && a[2] == 255 && a[3] == 255) {
        return NET_MULTICAST;
    }
    NET_PUBLIC
}

/// The IPv4 address inside an IPv4-mapped IPv6 address (`::ffff:a.b.c.d`).
/// Android's Java sockets are IPv6 sockets and reach IPv4 hosts this way.
#[inline(always)]
pub fn mapped_ipv4(a: &[u8; 16]) -> Option<[u8; 4]> {
    let mut i = 0;
    while i < 10 {
        if a[i] != 0 {
            return None;
        }
        i += 1;
    }
    if a[10] != 0xff || a[11] != 0xff {
        return None;
    }
    Some([a[12], a[13], a[14], a[15]])
}

/// Where an IPv6 address leads.
#[inline(always)]
pub fn ipv6_class(a: &[u8; 16]) -> u32 {
    if let Some(v4) = mapped_ipv4(a) {
        return ipv4_class(v4);
    }
    if a[0] == 0xff {
        return NET_MULTICAST;
    }
    // fe80::/10 link-local, fc00::/7 unique local.
    if (a[0] == 0xfe && a[1] & 0xc0 == 0x80) || a[0] & 0xfe == 0xfc {
        return NET_LOCAL;
    }
    // ::1, and :: which as a destination reaches the local host.
    let mut i = 0;
    while i < 15 {
        if a[i] != 0 {
            return NET_PUBLIC;
        }
        i += 1;
    }
    if a[15] <= 1 { NET_LOOPBACK } else { NET_PUBLIC }
}

// ---- netlink ----------------------------------------------------------------

pub const NETLINK_ROUTE: u32 = 0;
pub const NETLINK_SOCK_DIAG: u32 = 4;
pub const RTM_GETLINK: u32 = 18;
pub const RTM_GETADDR: u32 = 22;
pub const RTM_GETROUTE: u32 = 26;
pub const RTM_GETNEIGH: u32 = 30;

/// Netlink requests that read something about the device's network: its
/// interfaces and their hardware addresses, addresses, routes, neighbours
/// (the ARP table), and every socket on the device.
#[inline(always)]
pub fn netlink_matters(protocol: u32, msg_type: u32) -> bool {
    if protocol == NETLINK_ROUTE {
        return msg_type == RTM_GETLINK
            || msg_type == RTM_GETADDR
            || msg_type == RTM_GETROUTE
            || msg_type == RTM_GETNEIGH;
    }
    protocol == NETLINK_SOCK_DIAG
}
