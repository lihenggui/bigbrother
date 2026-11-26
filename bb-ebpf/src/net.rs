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
        bpf_get_current_pid_tgid, bpf_get_current_uid_gid, bpf_ktime_get_ns, bpf_probe_read_user,
    },
    macros::{kprobe, map, raw_tracepoint, socket_filter},
    maps::{HashMap, LruHashMap},
    programs::{ProbeContext, RawTracePointContext, SkBuffContext},
};
use bb_common::filter::{NET_PUBLIC, ipv4_class, ipv6_class, netlink_matters};
use bb_common::{
    AF_INET, AF_INET6, ConnectEvent, EV_CONNECT, EV_NETLINK, EV_TRAFFIC, FIRST_APPLICATION_UID,
    NetRuleKey, NetlinkEvent, OFF_SK_DADDR, OFF_SK_DPORT, OFF_SK_FAMILY, OFF_SK_UID,
    OFF_SK_V6_DADDR, OFF_SKB_DATA, OFF_SOCK_PROTOCOL, OFF_TCP_ACKED, OFF_TCP_RECEIVED,
    PER_USER_RANGE, TrafficEvent,
};

use crate::{
    common::{
        EVENTS, OFFSETS, REPORT_INTERVAL_NS, arch, count_drop, header, read_at, should_trace,
        wakeup_flags,
    },
    paths::expect_result,
};

#[repr(C)]
#[derive(Clone, Copy)]
struct Destination {
    tgid: u32,
    port: u32,
    addr: [u8; 16],
}

bb_common::assert_no_padding!(Destination { tgid, port, addr });

/// Destinations reported recently, with the time of the report. An app
/// streaming to a device on the local network sends thousands of datagrams a
/// second to the same address; one report a second says as much.
#[map]
static RECENT_DESTINATIONS: LruHashMap<Destination, u64> = LruHashMap::with_max_entries(1024, 0);

// ---- connect / sendto -------------------------------------------------------

/// `sizeof(struct sockaddr_in)` and `sizeof(struct sockaddr_in6)`.
const SOCKADDR_IN_LEN: u64 = 16;
const SOCKADDR_IN6_LEN: u64 = 28;
/// Offset of `sin6_addr` in `struct sockaddr_in6`.
const SIN6_ADDR_OFFSET: u64 = 8;

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
    // Destinations on the internet are those of the apps the user has the
    // connections recorded of, and of no other.
    if class == NET_PUBLIC && unsafe { NETWORK_RECORDED.get(&uid) }.is_none() {
        return Ok(());
    }

    let pid_tgid = bpf_get_current_pid_tgid();
    let destination = Destination {
        tgid: (pid_tgid >> 32) as u32,
        port,
        addr,
    };
    let now = unsafe { bpf_ktime_get_ns() };
    if let Some(last) = unsafe { RECENT_DESTINATIONS.get(&destination) }
        && now.wrapping_sub(*last) < REPORT_INTERVAL_NS
    {
        return Ok(());
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

// ---- sandbox network --------------------------------------------------------

/// The uids the sandbox keeps from the network.
#[map]
static NETWORK_REFUSED: HashMap<u32, u32> = HashMap::with_max_entries(1024, 0);

/// The uids of the sandboxed apps the user has the connections recorded of
/// (see `report_destination`).
#[map]
static NETWORK_RECORDED: HashMap<u32, u32> = HashMap::with_max_entries(1024, 0);

/// The uids that have destinations they are kept from, so that the packets
/// of every other app are not looked into.
#[map]
static NETWORK_RULED: HashMap<u32, u32> = HashMap::with_max_entries(1024, 0);

/// The destinations apps are kept from: an address with a port, or with
/// port 0 for every port.
#[map]
static NETWORK_RULES: HashMap<NetRuleKey, u32> = HashMap::with_max_entries(8192, 0);

/// `bpf_skb_load_bytes_relative`: offsets from the network header.
const BPF_HDR_START_NET: u32 = 1;
const IPPROTO_TCP: u8 = 6;
const IPPROTO_UDP: u8 = 17;
const IPV6_MAX_EXTENSIONS: u32 = 4;

/// Whether a packet is sent from a socket of an app kept from the network,
/// or to a destination the app is kept from: the match of a rule of the
/// packet filter (`-m bpf`), which refuses the packets it matches. Nonzero
/// is a match.
///
/// The rule exists only while an app is kept from the network or from a
/// destination. While it does, this runs for every packet that leaves the
/// device: a uid check, and for an app's packet two map lookups, however
/// many apps are listed. Only the packets of an app with destinations it
/// is kept from are looked into, for two more lookups.
#[socket_filter]
pub fn network_refused(ctx: SkBuffContext) -> i64 {
    // The uid of the socket's owner, whichever process is sending; the
    // overflow uid for a packet that comes from no socket.
    let uid = ctx.skb.get_socket_uid();
    if uid % PER_USER_RANGE < FIRST_APPLICATION_UID {
        return 0;
    }
    if unsafe { NETWORK_REFUSED.get(&uid) }.is_some() {
        return 1;
    }
    if unsafe { NETWORK_RULED.get(&uid) }.is_none() {
        return 0;
    }
    i64::from(destination_refused(&ctx, uid).unwrap_or(false))
}

/// The state a TCP socket ends in (`TCP_CLOSE`).
const TCP_CLOSE: u64 = 7;

/// A socket has changed state. For a TCP connection that has ended, of an
/// app whose connections are recorded, reports what the kernel counted
/// over it: nothing is counted here, packet by packet.
///
/// Runs for every state change of every socket: a comparison, and for a
/// socket that ends, its owner looked up among the apps recorded.
#[raw_tracepoint(tracepoint = "inet_sock_set_state")]
pub fn inet_sock_set_state(ctx: RawTracePointContext) -> i32 {
    let _ = try_sock_state(&ctx);
    0
}

fn try_sock_state(ctx: &RawTracePointContext) -> Result<(), i32> {
    // (sk, oldstate, newstate)
    let new_state: u64 = ctx.arg(2);
    if new_state != TCP_CLOSE {
        return Ok(());
    }
    let sk: *const u8 = ctx.arg(0);
    let off = OFFSETS.load();
    let uid = unsafe { read_at::<u32>(sk, off[OFF_SK_UID])? };
    if unsafe { NETWORK_RECORDED.get(&uid) }.is_none() {
        return Ok(());
    }
    // The tracepoint is that of other protocols too; the counts are TCP's.
    if unsafe { read_at::<u16>(sk, off[OFF_SOCK_PROTOCOL])? } != u16::from(IPPROTO_TCP) {
        return Ok(());
    }
    let family = u32::from(unsafe { read_at::<u16>(sk, off[OFF_SK_FAMILY])? });
    let mut addr = [0u8; 16];
    let class = if family == AF_INET {
        let v4: [u8; 4] = unsafe { read_at(sk, off[OFF_SK_DADDR])? };
        addr[0] = v4[0];
        addr[1] = v4[1];
        addr[2] = v4[2];
        addr[3] = v4[3];
        ipv4_class(v4)
    } else if family == AF_INET6 {
        addr = unsafe { read_at(sk, off[OFF_SK_V6_DADDR])? };
        ipv6_class(&addr)
    } else {
        return Ok(());
    };
    // Only what went to the internet is kept, as of the connections: an
    // app talking to itself or to the local network fills no buffer.
    if class != NET_PUBLIC {
        return Ok(());
    }
    let port = u16::from_be(unsafe { read_at::<u16>(sk, off[OFF_SK_DPORT])? });
    let sent = unsafe { read_at::<u64>(sk, off[OFF_TCP_ACKED])? };
    let received = unsafe { read_at::<u64>(sk, off[OFF_TCP_RECEIVED])? };
    // A socket that never connected, or carried nothing.
    if port == 0 || (sent == 0 && received == 0) {
        return Ok(());
    }
    let Some(mut entry) = EVENTS.reserve::<TrafficEvent>(0) else {
        count_drop();
        return Err(-1);
    };
    entry.write(TrafficEvent {
        hdr: header(EV_TRAFFIC, uid, 0),
        family,
        port: u32::from(port),
        addr,
        sent_low: sent as u32,
        sent_high: (sent >> 32) as u32,
        recv_low: received as u32,
        recv_high: (received >> 32) as u32,
    });
    entry.submit(wakeup_flags());
    Ok(())
}

/// Read `N` bytes of the packet at `offset` from its network header.
#[inline(always)]
fn packet_bytes<const N: usize>(ctx: &SkBuffContext, offset: u32) -> Option<[u8; N]> {
    let mut bytes = [0u8; N];
    let rc = unsafe {
        aya_ebpf::helpers::generated::bpf_skb_load_bytes_relative(
            aya_ebpf::EbpfContext::as_ptr(ctx) as *const _,
            offset,
            bytes.as_mut_ptr() as *mut _,
            N as u32,
            BPF_HDR_START_NET,
        )
    };
    (rc == 0).then_some(bytes)
}

/// Whether the packet goes to a destination `uid` is kept from. A packet
/// that cannot be read is let through: the rules are about destinations,
/// and one without is not a connection to any.
#[inline(always)]
fn destination_refused(ctx: &SkBuffContext, uid: u32) -> Option<bool> {
    let first: [u8; 1] = packet_bytes(ctx, 0)?;
    let mut key = NetRuleKey {
        uid,
        port: 0,
        addr: [0; 16],
    };
    // Where the transport header starts, and its protocol.
    let ipv6 = first[0] >> 4 == 6;
    let (mut transport, mut protocol) = if first[0] >> 4 == 4 {
        let address: [u8; 4] = packet_bytes(ctx, 16)?;
        key.addr[0] = address[0];
        key.addr[1] = address[1];
        key.addr[2] = address[2];
        key.addr[3] = address[3];
        let protocol: [u8; 1] = packet_bytes(ctx, 9)?;
        (u32::from(first[0] & 0xf) * 4, protocol[0])
    } else if ipv6 {
        key.addr = packet_bytes(ctx, 24)?;
        let next: [u8; 1] = packet_bytes(ctx, 6)?;
        (40, next[0])
    } else {
        return Some(false);
    };
    // Every port of the address first: the port need not be read then.
    if unsafe { NETWORK_RULES.get(&key) }.is_some() {
        return Some(true);
    }
    if ipv6 {
        (transport, protocol) = ipv6_transport(ctx, protocol)?;
    }
    if protocol != IPPROTO_TCP && protocol != IPPROTO_UDP {
        return Some(false);
    }
    let port: [u8; 2] = packet_bytes(ctx, transport + 2)?;
    key.port = u32::from(u16::from_be_bytes(port));
    Some(unsafe { NETWORK_RULES.get(&key) }.is_some())
}

#[inline(always)]
fn ipv6_transport(ctx: &SkBuffContext, mut protocol: u8) -> Option<(u32, u8)> {
    let mut offset = 40;
    for _ in 0..IPV6_MAX_EXTENSIONS {
        match protocol {
            0 | 43 | 60 => {
                let header: [u8; 2] = packet_bytes(ctx, offset)?;
                protocol = header[0];
                offset += (u32::from(header[1]) + 1) * 8;
            }
            44 => {
                let header: [u8; 4] = packet_bytes(ctx, offset)?;
                // Only the first fragment has a transport header.
                if header[2] != 0 || header[3] & 0xf8 != 0 {
                    return None;
                }
                protocol = header[0];
                offset += 8;
            }
            _ => break,
        }
    }
    Some((offset, protocol))
}
