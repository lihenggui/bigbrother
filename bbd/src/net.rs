// Copyright (C) 2026 Mercury Li
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License version 3 as
// published by the Free Software Foundation.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

//! Network destinations worth recording, and what contacting one reveals.
//!
//! Only destinations on this device and on the local network are kept; the
//! kernel side drops the rest (`bb_common::filter`). Connecting to a port on
//! the device itself is how an app finds out which other apps and services
//! are running; connecting to private addresses, or sending to multicast
//! groups, is how it finds the other devices in the user's home.
//!
//! A destination is stored as `net://<address>:<port>`.

use std::{
    collections::HashMap,
    fs,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    time::{Duration, Instant},
};

use bb_common::{
    AF_INET, AF_INET6,
    filter::{
        NET_LOCAL, NET_LOOPBACK, NET_MULTICAST, NET_PUBLIC, NETLINK_ROUTE, NETLINK_SOCK_DIAG,
        RTM_GETADDR, RTM_GETLINK, RTM_GETNEIGH, RTM_GETROUTE, ipv4_class, ipv6_class,
    },
};

use crate::topic;

/// Prefix of a stored destination.
pub const SCHEME: &str = "net://";

fn class_of(ip: IpAddr) -> u32 {
    match ip {
        IpAddr::V4(v4) => ipv4_class(v4.octets()),
        IpAddr::V6(v6) => ipv6_class(&v6.octets()),
    }
}

pub struct Destination {
    address: SocketAddr,
    class: u32,
}

/// The destination of a `ConnectEvent` if it is on the internet: reported
/// only of the sandboxed apps the user has the connections recorded of
/// (`sandbox::destinations`), and kept apart from the other records.
pub fn public(family: u32, port: u32, addr: &[u8; 16]) -> Option<SocketAddr> {
    let ip = match family {
        AF_INET => IpAddr::V4(Ipv4Addr::new(addr[0], addr[1], addr[2], addr[3])),
        AF_INET6 => Ipv6Addr::from(*addr).to_canonical(),
        _ => return None,
    };
    let port = u16::try_from(port).ok().filter(|p| *p != 0)?;
    (class_of(ip) == NET_PUBLIC).then(|| SocketAddr::new(ip, port))
}

impl Destination {
    /// From the fields of a `ConnectEvent`. `None` for an address family
    /// that isn't IP, a destination on the internet, or port 0.
    pub fn new(family: u32, port: u32, addr: &[u8; 16]) -> Option<Self> {
        let ip = match family {
            AF_INET => IpAddr::V4(Ipv4Addr::new(addr[0], addr[1], addr[2], addr[3])),
            // An IPv4 host reached through an IPv6 socket is that IPv4 host.
            AF_INET6 => Ipv6Addr::from(*addr).to_canonical(),
            _ => return None,
        };
        let class = class_of(ip);
        let port = u16::try_from(port).ok().filter(|p| *p != 0)?;
        (class != NET_PUBLIC).then(|| Self {
            address: SocketAddr::new(ip, port),
            class,
        })
    }

    pub fn is_loopback(&self) -> bool {
        self.class == NET_LOOPBACK
    }

    pub fn port(&self) -> u16 {
        self.address.port()
    }

    /// What to store: `net://192.168.1.1:80`, `net://[fe80::1]:80`.
    pub fn subject(&self) -> String {
        format!("{SCHEME}{}", self.address)
    }

    /// What to store once the app has tried too many ports of this address
    /// to list: `net://192.168.1.1:*`.
    fn any_port_subject(&self) -> String {
        match self.address.ip() {
            IpAddr::V4(ip) => format!("{SCHEME}{ip}:{ANY_PORT}"),
            IpAddr::V6(ip) => format!("{SCHEME}[{ip}]:{ANY_PORT}"),
        }
    }
}

/// Stands for "many ports" in a stored destination.
const ANY_PORT: &str = "*";
/// How many ports of one address an app may contact in an hour before they
/// stop being listed one by one.
const LISTED_PORTS: usize = 16;

/// Keeps a port scan from filling the database.
///
/// An app that walks the ports of an address would otherwise leave a row
/// per port, tens of thousands of them, burying everything else it did.
/// Past [`LISTED_PORTS`] distinct ports of one address in an hour, further
/// ones are stored together as `<address>:*`.
#[derive(Default)]
pub struct Scans {
    hour: i64,
    /// Ports seen this hour, per app and address.
    ports: HashMap<(u32, IpAddr), Vec<u16>>,
}

impl Scans {
    /// What to store for `uid` contacting `destination` at time `ts`.
    pub fn subject(&mut self, uid: u32, destination: &Destination, ts: i64) -> String {
        if ts / 3600 != self.hour {
            self.hour = ts / 3600;
            self.ports.clear();
        }
        let ports = self
            .ports
            .entry((uid, destination.address.ip()))
            .or_default();
        let port = destination.port();
        if ports.contains(&port) {
            return destination.subject();
        }
        if ports.len() >= LISTED_PORTS {
            return destination.any_port_subject();
        }
        ports.push(port);
        destination.subject()
    }
}

/// What a stored destination reveals. `None` if `subject` is not one.
pub fn topic_of(subject: &str) -> Option<&'static str> {
    let address = subject.strip_prefix(SCHEME)?;
    // "Many ports" (see `Scans`) is as good as any one port here.
    let address = match address.strip_suffix(ANY_PORT) {
        Some(host) => format!("{host}1"),
        None => address.to_string(),
    };
    let address: SocketAddr = address.parse().ok()?;
    match class_of(address.ip()) {
        NET_LOOPBACK => Some(topic::LOCAL_PORTS),
        NET_LOCAL => Some(topic::LOCAL_NETWORK),
        NET_MULTICAST => Some(topic::DEVICE_DISCOVERY),
        _ => None,
    }
}

/// Netlink requests, stored as `netlink:<family>/<request>`:
/// (protocol, message type or `None` for any, what to store, topic).
const NETLINK_REQUESTS: &[(u32, Option<u32>, &str, &str)] = &[
    // The interface list carries each interface's hardware address.
    (
        NETLINK_ROUTE,
        Some(RTM_GETLINK),
        "netlink:route/RTM_GETLINK",
        topic::MAC_ADDRESS,
    ),
    (
        NETLINK_ROUTE,
        Some(RTM_GETADDR),
        "netlink:route/RTM_GETADDR",
        topic::NETWORK_INTERFACES,
    ),
    (
        NETLINK_ROUTE,
        Some(RTM_GETROUTE),
        "netlink:route/RTM_GETROUTE",
        topic::NETWORK_STATE,
    ),
    (
        NETLINK_ROUTE,
        Some(RTM_GETNEIGH),
        "netlink:route/RTM_GETNEIGH",
        topic::ARP_TABLE,
    ),
    // Every socket on the device, with the app that owns it.
    (
        NETLINK_SOCK_DIAG,
        None,
        "netlink:sock_diag",
        topic::NETWORK_STATE,
    ),
];

/// Prefix of a stored netlink request.
pub const NETLINK_SCHEME: &str = "netlink:";

/// What to store for a netlink request, from the fields of a `NetlinkEvent`.
pub fn netlink_subject(protocol: u32, msg_type: u32) -> Option<&'static str> {
    NETLINK_REQUESTS
        .iter()
        .find(|(p, t, ..)| *p == protocol && t.is_none_or(|t| t == msg_type))
        .map(|(_, _, subject, _)| *subject)
}

/// What a stored netlink request reveals.
pub fn netlink_topic_of(subject: &str) -> Option<&'static str> {
    NETLINK_REQUESTS
        .iter()
        .find(|(_, _, s, _)| *s == subject)
        .map(|(.., topic)| *topic)
}

/// Who is listening on which port of this device.
///
/// An app talking to a server of its own on localhost (a media proxy, a
/// debug bridge) is the app talking to itself, and is not recorded. Whose
/// port it is can only be told from the socket tables, which are read when
/// needed and kept for a moment.
pub struct Listeners {
    /// Uids with a listening TCP or bound UDP socket, by port.
    by_port: HashMap<u16, Vec<u32>>,
    loaded_at: Option<Instant>,
}

/// (file, whether only sockets in the LISTEN state count).
const SOCKET_TABLES: &[(&str, bool)] = &[
    ("/proc/net/tcp", true),
    ("/proc/net/tcp6", true),
    ("/proc/net/udp", false),
    ("/proc/net/udp6", false),
];
/// How long the tables are trusted for a port that is in them.
const MAX_AGE: Duration = Duration::from_secs(2);
/// How soon they are read again for a port that is not: it may have been
/// opened since.
const RECHECK_AFTER: Duration = Duration::from_millis(250);
/// `TCP_LISTEN`, as /proc/net/tcp prints it.
const STATE_LISTEN: &str = "0A";

impl Listeners {
    pub fn new() -> Self {
        Self {
            by_port: HashMap::new(),
            loaded_at: None,
        }
    }

    /// True if `uid` itself has a socket on `port`.
    pub fn owned_by(&mut self, port: u16, uid: u32) -> bool {
        let age = self.loaded_at.map(|at| at.elapsed());
        if age.is_none_or(|age| age > MAX_AGE) {
            self.reload();
        }
        if self.has(port, uid) {
            return true;
        }
        if age.is_some_and(|age| age > RECHECK_AFTER && age <= MAX_AGE) {
            self.reload();
            return self.has(port, uid);
        }
        false
    }

    fn has(&self, port: u16, uid: u32) -> bool {
        self.by_port
            .get(&port)
            .is_some_and(|uids| uids.contains(&uid))
    }

    fn reload(&mut self) {
        self.loaded_at = Some(Instant::now());
        self.by_port.clear();
        for (file, listening_only) in SOCKET_TABLES {
            // IPv6 may be compiled out; a missing table is an empty one.
            let Ok(text) = fs::read_to_string(file) else {
                continue;
            };
            for (port, uid) in parse_sockets(&text, *listening_only) {
                let uids = self.by_port.entry(port).or_default();
                if !uids.contains(&uid) {
                    uids.push(uid);
                }
            }
        }
    }
}

/// (local port, owning uid) of the sockets in a /proc/net/{tcp,udp}[6] table:
///   `sl local_address rem_address st tx:rx tr:when retrnsmt uid timeout inode ...`
fn parse_sockets(text: &str, listening_only: bool) -> impl Iterator<Item = (u16, u32)> + '_ {
    text.lines().filter_map(move |line| {
        let fields: Vec<&str> = line.split_ascii_whitespace().collect();
        let (local, state, uid) = (fields.get(1)?, fields.get(3)?, fields.get(7)?);
        if listening_only && *state != STATE_LISTEN {
            return None;
        }
        let port = u16::from_str_radix(local.rsplit(':').next()?, 16).ok()?;
        (port != 0).then_some((port, uid.parse().ok()?))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v4(a: [u8; 4], port: u32) -> Option<String> {
        let mut addr = [0u8; 16];
        addr[..4].copy_from_slice(&a);
        Destination::new(AF_INET, port, &addr).map(|d| d.subject())
    }

    fn v6(text: &str, port: u32) -> Option<String> {
        let addr = text.parse::<Ipv6Addr>().unwrap().octets();
        Destination::new(AF_INET6, port, &addr).map(|d| d.subject())
    }

    #[test]
    fn local_destinations_are_kept_and_named() {
        for (subject, want, topic) in [
            (
                v4([127, 0, 0, 1], 5555),
                "net://127.0.0.1:5555",
                topic::LOCAL_PORTS,
            ),
            (v4([0, 0, 0, 0], 80), "net://0.0.0.0:80", topic::LOCAL_PORTS),
            (v6("::1", 27042), "net://[::1]:27042", topic::LOCAL_PORTS),
            (
                v4([192, 168, 1, 1], 80),
                "net://192.168.1.1:80",
                topic::LOCAL_NETWORK,
            ),
            (
                v4([10, 0, 0, 7], 22),
                "net://10.0.0.7:22",
                topic::LOCAL_NETWORK,
            ),
            (
                v4([172, 31, 255, 1], 1),
                "net://172.31.255.1:1",
                topic::LOCAL_NETWORK,
            ),
            (
                v4([169, 254, 1, 1], 1),
                "net://169.254.1.1:1",
                topic::LOCAL_NETWORK,
            ),
            (
                v6("fe80::1", 80),
                "net://[fe80::1]:80",
                topic::LOCAL_NETWORK,
            ),
            (
                v6("fd12::5", 80),
                "net://[fd12::5]:80",
                topic::LOCAL_NETWORK,
            ),
            // How Java sockets spell an IPv4 address.
            (
                v6("::ffff:192.168.1.1", 80),
                "net://192.168.1.1:80",
                topic::LOCAL_NETWORK,
            ),
            (
                v6("::ffff:127.0.0.1", 80),
                "net://127.0.0.1:80",
                topic::LOCAL_PORTS,
            ),
            (
                v4([239, 255, 255, 250], 1900),
                "net://239.255.255.250:1900",
                topic::DEVICE_DISCOVERY,
            ),
            (
                v4([224, 0, 0, 251], 5353),
                "net://224.0.0.251:5353",
                topic::DEVICE_DISCOVERY,
            ),
            (
                v4([255, 255, 255, 255], 67),
                "net://255.255.255.255:67",
                topic::DEVICE_DISCOVERY,
            ),
            (
                v6("ff02::fb", 5353),
                "net://[ff02::fb]:5353",
                topic::DEVICE_DISCOVERY,
            ),
        ] {
            assert_eq!(subject.as_deref(), Some(want));
            assert_eq!(topic_of(want), Some(topic), "{want}");
        }
    }

    #[test]
    fn the_internet_is_not_recorded() {
        assert_eq!(v4([8, 8, 8, 8], 53), None);
        assert_eq!(v4([172, 32, 0, 1], 80), None);
        assert_eq!(v4([192, 169, 0, 1], 80), None);
        assert_eq!(v6("2001:4860:4860::8888", 53), None);
        assert_eq!(v6("::ffff:8.8.8.8", 53), None);
        // No port: not a destination.
        assert_eq!(v4([127, 0, 0, 1], 0), None);
        assert_eq!(Destination::new(1, 80, &[0; 16]).map(|d| d.subject()), None);
        assert_eq!(topic_of("net://8.8.8.8:53"), None);
        assert_eq!(topic_of("net://nonsense"), None);
        assert_eq!(topic_of("/proc/net/arp"), None);
    }

    #[test]
    fn a_port_scan_is_stored_as_one_destination() {
        let mut scans = Scans::default();
        let at = |ip: [u8; 4], port: u32| {
            let mut addr = [0u8; 16];
            addr[..4].copy_from_slice(&ip);
            Destination::new(AF_INET, port, &addr).unwrap()
        };
        let local = [127, 0, 0, 1];
        let hour = 500 * 3600;
        // The first ports are listed, a repeat of one of them too.
        for port in 1..=LISTED_PORTS as u32 {
            let subject = scans.subject(10100, &at(local, port), hour);
            assert_eq!(subject, format!("net://127.0.0.1:{port}"));
        }
        assert_eq!(
            scans.subject(10100, &at(local, 3), hour),
            "net://127.0.0.1:3"
        );
        // Further ones are not.
        for port in 1000..1100 {
            assert_eq!(
                scans.subject(10100, &at(local, port), hour),
                "net://127.0.0.1:*"
            );
        }
        assert_eq!(topic_of("net://127.0.0.1:*"), Some(topic::LOCAL_PORTS));
        // Another app, another address and the next hour start afresh.
        assert_eq!(
            scans.subject(10200, &at(local, 2000), hour),
            "net://127.0.0.1:2000"
        );
        assert_eq!(
            scans.subject(10100, &at([192, 168, 1, 9], 80), hour),
            "net://192.168.1.9:80"
        );
        assert_eq!(
            scans.subject(10100, &at(local, 3000), hour + 3600),
            "net://127.0.0.1:3000"
        );
        let v6 = Destination::new(
            AF_INET6,
            80,
            &"fe80::1".parse::<Ipv6Addr>().unwrap().octets(),
        );
        assert_eq!(v6.unwrap().any_port_subject(), "net://[fe80::1]:*");
        assert_eq!(topic_of("net://[fe80::1]:*"), Some(topic::LOCAL_NETWORK));
    }

    #[test]
    fn only_netlink_reads_of_network_state_are_recorded() {
        use bb_common::filter::netlink_matters;

        assert_eq!(
            netlink_subject(NETLINK_ROUTE, RTM_GETNEIGH),
            Some("netlink:route/RTM_GETNEIGH")
        );
        assert_eq!(
            netlink_topic_of("netlink:route/RTM_GETNEIGH"),
            Some(topic::ARP_TABLE)
        );
        assert_eq!(
            netlink_subject(NETLINK_SOCK_DIAG, 20),
            Some("netlink:sock_diag")
        );
        // RTM_NEWLINK changes something rather than reads it; uevents are
        // not about the network.
        assert_eq!(netlink_subject(NETLINK_ROUTE, 16), None);
        assert_eq!(netlink_subject(15, 0), None);
        assert_eq!(netlink_topic_of("netlink:other"), None);
        // The kernel side passes exactly what is stored.
        for protocol in 0..32 {
            for msg_type in 0..64 {
                assert_eq!(
                    netlink_matters(protocol, msg_type),
                    netlink_subject(protocol, msg_type).is_some(),
                    "{protocol}/{msg_type}"
                );
            }
        }
    }

    #[test]
    fn listening_ports_are_read_from_socket_tables() {
        let tcp = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 4242 1 0000000000000000 100 0 0 10 0
   1: 0100007F:9C40 0100007F:1F90 01 00000000:00000000 00:00000000 00000000 10200        0 4243 1 0000000000000000 20 4 30 10 -1
   2: 00000000000000000000000000000000:15B3 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 77 1 0000000000000000 100 0 0 10 0
";
        assert_eq!(
            parse_sockets(tcp, true).collect::<Vec<_>>(),
            [(8080, 10123), (5555, 2000)]
        );
        let udp = "   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops
  510: 00000000:14E9 00000000:0000 07 00000000:00000000 00:00000000 00000000 10300        0 99 2 0000000000000000 0
  511: 00000000:0000 00000000:0000 07 00000000:00000000 00:00000000 00000000 10300        0 98 2 0000000000000000 0
";
        assert_eq!(
            parse_sockets(udp, false).collect::<Vec<_>>(),
            [(5353, 10300)]
        );

        let mut listeners = Listeners {
            by_port: HashMap::from([(8080, vec![10123])]),
            loaded_at: Some(Instant::now()),
        };
        assert!(listeners.owned_by(8080, 10123));
        assert!(!listeners.owned_by(8080, 10200));
        assert!(!listeners.owned_by(9, 10123));
    }
}
