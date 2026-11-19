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

//! The names sandboxed apps look up: which, and with what answer.
//!
//! An app asks netd for the addresses of a name over a socket
//! (`/dev/socket/dnsproxyd`). For an app whose connections are recorded,
//! or that is kept from names, a socket of the daemon's is mounted over
//! that one in the app's mount namespaces ([`super::resolver`]), and the
//! daemon stands between the two:
//!
//! - A name the app is kept from is not asked for: the app's connection
//!   is closed, and its lookup fails.
//! - Any other request is passed on to netd as it came, on a connection
//!   made with the app's uid as the effective one, so that netd chooses
//!   the network, and applies the rules, of the app and not of the daemon.
//!   The answer is passed back as it came.
//! - One request is taken on a connection, as the system's library makes
//!   them. netd would take more, and a second one would pass unread:
//!   whatever else the app sends ends the connection.
//!
//! The socket is in a directory every process sees. It is open to whom
//! netd's is, the group of the apps with the network permission, and
//! serves the apps in the sandbox only: nobody gets through it what netd
//! would not give them directly.
//! - The addresses in the answer are remembered for the name
//!   ([`Names`]): a connection the app then makes to one of them is
//!   recorded under the name.
//!
//! The app must be able to connect to the daemon's socket as it can to
//! netd's. SELinux decides that by the labels of the socket and of its
//! file, so both are given netd's own. This needs no rule added to the
//! policy; it needs the daemon's own domain to be free to label a socket,
//! as a root manager's is. Where it is not, the names are not available
//! and the apps keep netd's socket.
//!
//! Names are other apps' data: none is ever logged.

use std::{
    collections::HashMap,
    ffi::CString,
    io::{self, Read as _, Write as _},
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
    os::{
        fd::AsRawFd as _,
        unix::{
            fs::PermissionsExt as _,
            net::{UnixListener, UnixStream},
        },
    },
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
        mpsc::Sender,
    },
    time::{Duration, Instant},
};

use anyhow::Context as _;

use crate::netctl;

/// Where netd listens for the lookups of apps.
const NETD: &str = "/dev/socket/dnsproxyd";

/// Where the daemon listens for them. Beside netd's, in the filesystem
/// every mount namespace has; the name says nothing of the sandbox, as an
/// app can read its own mounts.
pub const SOCKET: &str = "/dev/socket/dnsproxyd_relay";

/// The label of netd's processes, and of the file of its socket.
const NETD_LABEL: &str = "u:r:netd:s0";
const SOCKET_LABEL: &str = "u:object_r:dnsproxyd_socket:s0";

/// The group that may connect to netd's socket, which apps with the
/// network permission are in.
const INET_GID: libc::gid_t = 3003;

/// The most lookups relayed at once. One more is refused: the app's lookup
/// fails, as it does when netd has too many.
const MOST_AT_ONCE: usize = 64;

/// The longest request read, and the longest answer held to be read for
/// its addresses; a longer one is passed on unread.
const REQUEST_CAP: usize = 4096;
const ANSWER_CAP: usize = 64 * 1024;

/// How long a request is waited for, and how long netd is given to answer:
/// netd tries its servers for longer than an app usually waits.
const REQUEST_WAIT: Duration = Duration::from_secs(5);
const ANSWER_WAIT: Duration = Duration::from_secs(60);

/// The addresses remembered, for all apps together. Past it, the half
/// remembered longest ago goes.
const REMEMBERED: usize = 8192;

/// Which name each app was last given an address for.
#[derive(Default)]
pub struct Names {
    known: Mutex<Known>,
}

#[derive(Default)]
struct Known {
    by_address: HashMap<(u32, IpAddr), (String, u64)>,
    /// Counts what is remembered, for the order it was in.
    count: u64,
}

impl Names {
    /// Remember that `uid` was given `addresses` for `host`.
    pub fn learn(&self, uid: u32, host: &str, addresses: &[IpAddr]) {
        let mut known = self.known.lock().unwrap_or_else(|e| e.into_inner());
        for address in addresses {
            known.count += 1;
            let count = known.count;
            known
                .by_address
                .insert((uid, address.to_canonical()), (host.to_string(), count));
        }
        if known.by_address.len() > REMEMBERED {
            let oldest_kept = known.count - (REMEMBERED / 2) as u64;
            known.by_address.retain(|_, (_, at)| *at > oldest_kept);
        }
    }

    /// The name `uid` was last given `address` for.
    pub fn host_of(&self, uid: u32, address: IpAddr) -> Option<String> {
        let known = self.known.lock().unwrap_or_else(|e| e.into_inner());
        known
            .by_address
            .get(&(uid, address.to_canonical()))
            .map(|(host, _)| host.clone())
    }

    /// The addresses `uid` was given for the names `covered` holds for.
    pub fn addresses_of(&self, uid: u32, covered: impl Fn(&str) -> bool) -> Vec<IpAddr> {
        let known = self.known.lock().unwrap_or_else(|e| e.into_inner());
        known
            .by_address
            .iter()
            .filter(|((of, _), (host, _))| *of == uid && covered(host))
            .map(|((_, address), _)| *address)
            .collect()
    }
}

/// Whether the lookup of a name by a uid is refused.
pub type Refuses = Arc<dyn Fn(u32, &str) -> bool + Send + Sync>;

/// Whether a uid is one the daemon relays lookups for: an app in the
/// sandbox.
pub type Serves = Arc<dyn Fn(u32) -> bool + Send + Sync>;

/// What the threads that relay lookups share.
struct Shared {
    names: Arc<Names>,
    serves: Serves,
    refuses: Refuses,
    /// Told the uid and the name of each lookup refused.
    refused: Mutex<Sender<(u32, String)>>,
    at_once: AtomicUsize,
}

/// Start listening for the lookups of the apps given the daemon's socket,
/// on a thread that does nothing else. Fails where the socket cannot be
/// made as an app's process must find it.
pub fn start(
    names: Arc<Names>,
    serves: Serves,
    refuses: Refuses,
    refused: Sender<(u32, String)>,
) -> anyhow::Result<()> {
    let shared = Arc::new(Shared {
        names,
        serves,
        refuses,
        refused: Mutex::new(refused),
        at_once: AtomicUsize::new(0),
    });
    let (ready, started) = std::sync::mpsc::sync_channel(1);
    std::thread::Builder::new()
        .name("names".into())
        .spawn(move || {
            // The label a socket gets is the thread's to set, and stays
            // set for this thread: it makes no other socket.
            let listener = listen();
            let listener = match listener {
                Ok(listener) => {
                    let _ = ready.send(Ok(()));
                    listener
                }
                Err(e) => {
                    let _ = ready.send(Err(e));
                    return;
                }
            };
            for client in listener.incoming().flatten() {
                if shared.at_once.fetch_add(1, Ordering::Relaxed) >= MOST_AT_ONCE {
                    shared.at_once.fetch_sub(1, Ordering::Relaxed);
                    continue;
                }
                let shared = shared.clone();
                let spawned = std::thread::Builder::new()
                    .name("lookup".into())
                    .spawn(move || {
                        // What fails here fails the app's lookup, and
                        // says nothing: the reason would name the app.
                        let _ = relay(client, &shared);
                        shared.at_once.fetch_sub(1, Ordering::Relaxed);
                    });
                if spawned.is_err() {
                    // The closure, with its count, was not run.
                    std::thread::sleep(Duration::from_millis(100));
                }
            }
        })
        .context("starting the thread")?;
    started.recv().context("the thread ended as it started")?
}

/// The daemon's socket, labelled as netd's.
fn listen() -> anyhow::Result<UnixListener> {
    std::fs::write("/proc/thread-self/attr/sockcreate", NETD_LABEL)
        .context("labelling the socket")?;
    match std::fs::remove_file(SOCKET) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => {
            return Err(e).context("removing the socket of the daemon before");
        }
        _ => {}
    }
    let listener = UnixListener::bind(SOCKET).context("binding the socket")?;
    // To whom netd's own is open: root, and the group of the apps with
    // the network permission.
    std::os::unix::fs::chown(SOCKET, Some(0), Some(INET_GID))
        .and_then(|()| std::fs::set_permissions(SOCKET, std::fs::Permissions::from_mode(0o660)))
        .context("opening the socket to the apps with the network permission")?;
    let (path, name, label) = (
        CString::new(SOCKET)?,
        c"security.selinux",
        CString::new(SOCKET_LABEL)?,
    );
    let rc = unsafe {
        libc::setxattr(
            path.as_ptr(),
            name.as_ptr(),
            label.as_ptr().cast(),
            label.as_bytes_with_nul().len(),
            0,
        )
    };
    if rc != 0 {
        return Err(io::Error::last_os_error()).context("labelling the socket's file");
    }
    Ok(listener)
}

/// The uid of the process at the other end, which the kernel tells.
fn peer_uid(client: &UnixStream) -> io::Result<u32> {
    let mut cred = libc::ucred {
        pid: 0,
        uid: 0,
        gid: 0,
    };
    let mut len = size_of::<libc::ucred>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(
            client.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&raw mut cred).cast(),
            &mut len,
        )
    };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(cred.uid)
}

/// Connect to netd with `uid` as this thread's effective uid: netd takes
/// the app to be whoever connects. The thread stays root otherwise, and
/// is root again once connected.
fn connect_as(uid: u32) -> io::Result<UnixStream> {
    // Raw system calls: these change the calling thread only, where the
    // C library's would change every thread of the daemon.
    let set = |euid: u32| -> io::Result<()> {
        if unsafe { libc::syscall(libc::SYS_setresuid, -1i32, euid, -1i32) } != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    };
    // Without root's capabilities the socket is open to its group only.
    let groups = [INET_GID];
    if unsafe { libc::syscall(libc::SYS_setgroups, groups.len(), groups.as_ptr()) } != 0 {
        return Err(io::Error::last_os_error());
    }
    set(uid)?;
    let connected = UnixStream::connect(NETD);
    // A thread that cannot be root again must not go on as the daemon's.
    if set(0).is_err() {
        std::process::abort();
    }
    connected
}

fn relay(mut client: UnixStream, shared: &Shared) -> io::Result<()> {
    let uid = peer_uid(&client)?;
    // The socket can be found by any process; it is there for the apps in
    // the sandbox.
    if !(shared.serves)(uid) {
        return Ok(());
    }
    client.set_read_timeout(Some(REQUEST_WAIT))?;
    client.set_write_timeout(Some(REQUEST_WAIT))?;
    let mut request = Vec::new();
    let mut chunk = [0u8; 1024];
    while !request.contains(&0) {
        let n = client.read(&mut chunk)?;
        if n == 0 || request.len() + n > REQUEST_CAP {
            return Ok(());
        }
        request.extend_from_slice(&chunk[..n]);
    }
    if !one_command(&request) {
        return Ok(());
    }
    let asked = Asked::parse(&request);
    if let Some(host) = asked.host()
        && (shared.refuses)(uid, host)
    {
        let refused = shared.refused.lock().unwrap_or_else(|e| e.into_inner());
        let _ = refused.send((uid, host.to_string()));
        // Closed without an answer: the lookup fails.
        return Ok(());
    }

    let mut netd = connect_as(uid)?;
    netd.set_read_timeout(Some(ANSWER_WAIT))?;
    netd.set_write_timeout(Some(REQUEST_WAIT))?;
    netd.write_all(&request)?;
    // The answer to a lookup of addresses is read whole first, so that the
    // addresses are remembered before the app has them to connect to.
    let mut answer = Vec::new();
    if let Asked::Addresses(host) = &asked {
        let deadline = Instant::now() + ANSWER_WAIT;
        loop {
            match addresses_in(&answer) {
                Parsed::Whole(addresses) => {
                    shared.names.learn(uid, host, &addresses);
                    break;
                }
                Parsed::Unknown => break,
                Parsed::Partial if answer.len() > ANSWER_CAP || Instant::now() > deadline => break,
                Parsed::Partial => {}
            }
            let n = netd.read(&mut chunk)?;
            if n == 0 {
                break;
            }
            answer.extend_from_slice(&chunk[..n]);
        }
        client.write_all(&answer)?;
    }
    // The rest of the answer, until one of them closes.
    pump(&mut client, &mut netd)
}

/// Whether what was read is one command and nothing after it: text ended
/// by a zero byte, which is the last byte. A second command behind the
/// first would reach netd without having been looked at.
fn one_command(request: &[u8]) -> bool {
    request.iter().position(|b| *b == 0) == Some(request.len().wrapping_sub(1))
}

/// Pass on what netd sends to the app, until one of them closes or netd
/// says nothing for as long as an answer is waited for. Nothing more is
/// passed on from the app: if it sends anything, the connection ends.
fn pump(client: &mut UnixStream, netd: &mut UnixStream) -> io::Result<()> {
    let mut chunk = [0u8; 4096];
    loop {
        let mut fds = [
            libc::pollfd {
                fd: client.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd: netd.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
        ];
        let ready = unsafe { libc::poll(fds.as_mut_ptr(), 2, ANSWER_WAIT.as_millis() as i32) };
        if ready <= 0 {
            return Ok(());
        }
        if fds[1].revents != 0 {
            let n = netd.read(&mut chunk)?;
            if n == 0 {
                return Ok(());
            }
            client.write_all(&chunk[..n])?;
        }
        // Closed, or a second request, which is not taken.
        if fds[0].revents != 0 {
            return Ok(());
        }
    }
}

/// What a request asks netd for.
#[derive(Debug, PartialEq, Eq)]
enum Asked {
    /// The addresses of a name (`getaddrinfo`).
    Addresses(String),
    /// A name, in another way than its addresses being listed
    /// (`gethostbyname`, or a DNS query the app wrote itself).
    Name(String),
    /// Anything else, or what could not be read.
    Other,
}

impl Asked {
    /// Read a request: words separated by spaces, up to a zero byte.
    fn parse(request: &[u8]) -> Self {
        let end = request
            .iter()
            .position(|b| *b == 0)
            .unwrap_or(request.len());
        let Ok(text) = std::str::from_utf8(&request[..end]) else {
            return Asked::Other;
        };
        let mut words = text.split(' ');
        match words.next() {
            // getaddrinfo <name> <service> <flags> <family> <type> <protocol> <network>
            Some("getaddrinfo") => words
                .next()
                .and_then(netctl::name)
                .map_or(Asked::Other, Asked::Addresses),
            // gethostbyname <network> <name> <family>
            Some("gethostbyname") => words
                .nth(1)
                .and_then(netctl::name)
                .map_or(Asked::Other, Asked::Name),
            // resnsend <network> <flags> <query, in base64>
            Some("resnsend") => words
                .nth(2)
                .and_then(base64)
                .and_then(|query| question(&query))
                .map_or(Asked::Other, Asked::Name),
            _ => Asked::Other,
        }
    }

    fn host(&self) -> Option<&str> {
        match self {
            Asked::Addresses(host) | Asked::Name(host) => Some(host),
            Asked::Other => None,
        }
    }
}

/// Decode standard base64, with or without padding.
fn base64(text: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(text.len() * 3 / 4);
    let (mut bits, mut have) = (0u32, 0u32);
    for byte in text.bytes() {
        let value = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => break,
            _ => return None,
        };
        bits = (bits << 6) | u32::from(value);
        have += 6;
        if have >= 8 {
            have -= 8;
            out.push((bits >> have) as u8);
        }
    }
    Some(out)
}

/// The name a DNS query asks about: its first question.
fn question(query: &[u8]) -> Option<String> {
    // The header, then the name as labels, each with its length before it.
    let mut at = 12;
    let mut name = String::new();
    loop {
        let len = usize::from(*query.get(at)?);
        if len == 0 {
            break;
        }
        // A pointer to another place, which a question has no use for.
        if len > 63 {
            return None;
        }
        let label = query.get(at + 1..at + 1 + len)?;
        if !name.is_empty() {
            name.push('.');
        }
        name.push_str(std::str::from_utf8(label).ok()?);
        at += 1 + len;
    }
    netctl::name(&name)
}

/// What can be read of an answer to `getaddrinfo` so far.
#[derive(Debug, PartialEq, Eq)]
enum Parsed {
    /// All of it: the addresses, none where netd answered with an error.
    Whole(Vec<IpAddr>),
    /// More is to come.
    Partial,
    /// Not an answer as netd writes them.
    Unknown,
}

/// Read the addresses of an answer: `222`, a zero byte, then for each
/// address a 1, four numbers, the address as the system holds one (with
/// its length before it) and a name (with its length before it), and a 0
/// at the end. Numbers are four bytes, the first the highest. Any other
/// code than 222 is an error, followed by a length and that many bytes.
fn addresses_in(answer: &[u8]) -> Parsed {
    let number = |at: usize| -> Option<usize> {
        let bytes: [u8; 4] = answer.get(at..at + 4)?.try_into().ok()?;
        Some(u32::from_be_bytes(bytes) as usize)
    };
    let Some(code) = answer.get(..4) else {
        return Parsed::Partial;
    };
    if code[3] != 0 || !code[..3].iter().all(u8::is_ascii_digit) {
        return Parsed::Unknown;
    }
    if &code[..3] != b"222" {
        return match number(4) {
            Some(len) if answer.len() >= 8 + len => Parsed::Whole(Vec::new()),
            _ => Parsed::Partial,
        };
    }
    let mut addresses = Vec::new();
    let mut at = 4;
    loop {
        match number(at) {
            None => return Parsed::Partial,
            Some(0) => return Parsed::Whole(addresses),
            Some(1) => {}
            Some(_) => return Parsed::Unknown,
        }
        // Flags, family, type and protocol; then the address.
        let Some(len) = number(at + 20) else {
            return Parsed::Partial;
        };
        let start = at + 24;
        let Some(address) = answer.get(start..start + len) else {
            return Parsed::Partial;
        };
        // The family in the machine's byte order, then for IPv4 the port
        // and the address, for IPv6 the port, four bytes and the address.
        match address.first().zip(address.get(1)) {
            Some((family, _)) if i32::from(*family) == libc::AF_INET && len >= 8 => {
                let v4: [u8; 4] = address[4..8].try_into().unwrap_or_default();
                addresses.push(IpAddr::V4(Ipv4Addr::from(v4)));
            }
            Some((family, _)) if i32::from(*family) == libc::AF_INET6 && len >= 24 => {
                let v6: [u8; 16] = address[8..24].try_into().unwrap_or_default();
                addresses.push(IpAddr::V6(Ipv6Addr::from(v6)).to_canonical());
            }
            _ => {}
        }
        let Some(name) = number(start + len) else {
            return Parsed::Partial;
        };
        at = start + len + 4 + name;
        if at > answer.len() {
            return Parsed::Partial;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requests_name_what_is_looked_up() {
        assert_eq!(
            Asked::parse(b"getaddrinfo www.Example.org ^ 1024 0 1 0 0\0"),
            Asked::Addresses("www.example.org".into())
        );
        assert_eq!(
            Asked::parse(b"gethostbyname 0 t.example.com 2\0"),
            Asked::Name("t.example.com".into())
        );
        // A query for example.com, as an app writes one.
        let query = [
            0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, b'e', b'x', b'a', b'm', b'p', b'l', b'e',
            3, b'c', b'o', b'm', 0, 0, 1, 0, 1,
        ];
        assert_eq!(question(&query), Some("example.com".into()));
        assert_eq!(
            Asked::parse(b"resnsend 0 0 EjQBAAABAAAAAAAAB2V4YW1wbGUDY29tAAABAAE=\0"),
            Asked::Name("example.com".into())
        );
        // What names nothing, or cannot be read, is passed on as it is.
        for request in [
            &b"getdnsnetid 0\0"[..],
            b"getaddrinfo ^ ^ 0 0 0 0 0\0",
            b"getaddrinfo 203.0.113.7 ^ 4 0 1 0 0\0",
            b"resnsend 0 0 not!base64\0",
            b"resnsend 0 0 EjQ=\0",
            b"\xff\xfe\0",
            b"",
        ] {
            assert_eq!(Asked::parse(request), Asked::Other, "{request:?}");
        }
        assert_eq!(base64("aGVsbG8="), Some(b"hello".to_vec()));
        assert_eq!(base64("aGVsbG8"), Some(b"hello".to_vec()));
        // A question that points elsewhere, or runs off the end.
        assert_eq!(question(&[0; 12]), None);
        assert_eq!(
            question(&[0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xc0, 12]),
            None
        );
        assert_eq!(
            question(&[0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, b'a']),
            None
        );
    }

    #[test]
    fn only_one_command_is_taken_on_a_connection() {
        assert!(one_command(b"getaddrinfo example.com ^ 0 0 0 0 0\0"));
        // A second one behind it, whole or begun, and what does not end.
        assert!(!one_command(
            b"getaddrinfo example.com ^ 0 0 0 0 0\0getaddrinfo t.adnet.example ^ 0 0 0 0 0\0"
        ));
        assert!(!one_command(b"getdnsnetid 0\0g"));
        assert!(!one_command(b"getdnsnetid 0"));
        assert!(!one_command(b""));
        assert!(one_command(b"\0"));
    }

    /// One address of an answer, as netd writes it.
    fn entry(address: &[u8]) -> Vec<u8> {
        let mut out = vec![0, 0, 0, 1, 0, 0, 4, 0];
        out.extend([0, 0, 0, address[0], 0, 0, 0, 1, 0, 0, 0, 6]);
        out.extend((address.len() as u32).to_be_bytes());
        out.extend(address);
        out.extend([0, 0, 0, 0]);
        out
    }

    #[test]
    fn the_addresses_of_an_answer_are_read_once_it_is_whole() {
        let v4 = [2, 0, 0, 0, 203, 0, 113, 7, 0, 0, 0, 0, 0, 0, 0, 0];
        let mut v6 = vec![10, 0, 0, 0, 0, 0, 0, 0, 0x20, 0x01, 0x0d, 0xb8];
        v6.extend([0; 11]);
        v6.extend([5, 0, 0, 0, 0]);
        let mut answer = b"222\0".to_vec();
        answer.extend(entry(&v4));
        answer.extend(entry(&v6));
        // Every part of it short of the end is not all of it.
        for len in 0..answer.len() + 4 {
            let mut partial = answer.clone();
            partial.extend([0, 0, 0, 0]);
            partial.truncate(len);
            assert_eq!(addresses_in(&partial), Parsed::Partial, "{len} bytes");
        }
        answer.extend([0, 0, 0, 0]);
        assert_eq!(
            addresses_in(&answer),
            Parsed::Whole(vec![
                "203.0.113.7".parse().unwrap(),
                "2001:db8::5".parse().unwrap()
            ])
        );
        // No address: the name has none.
        assert_eq!(addresses_in(b"222\0\0\0\0\0"), Parsed::Whole(Vec::new()));
        // An error, whole once its bytes are there.
        assert_eq!(addresses_in(b"401\0\0\0\0\x04\0\0\0"), Parsed::Partial);
        assert_eq!(
            addresses_in(b"401\0\0\0\0\x04\0\0\0\x07"),
            Parsed::Whole(Vec::new())
        );
        // Not an answer of this form.
        assert_eq!(addresses_in(b"hello there"), Parsed::Unknown);
        assert_eq!(addresses_in(b"222\0\0\0\0\x09"), Parsed::Unknown);
    }

    #[test]
    fn the_name_an_address_was_last_given_for_is_remembered_per_app() {
        let names = Names::default();
        let (a, b): (IpAddr, IpAddr) = (
            "203.0.113.7".parse().unwrap(),
            "2001:db8::5".parse().unwrap(),
        );
        names.learn(10001, "api.example.com", &[a, b]);
        names.learn(10002, "cdn.example.net", &[a]);
        assert_eq!(names.host_of(10001, a).as_deref(), Some("api.example.com"));
        assert_eq!(names.host_of(10001, b).as_deref(), Some("api.example.com"));
        assert_eq!(names.host_of(10002, a).as_deref(), Some("cdn.example.net"));
        assert_eq!(names.host_of(10002, b), None);
        // An address given for another name since is that name's.
        names.learn(10001, "img.example.com", &[a]);
        assert_eq!(names.host_of(10001, a).as_deref(), Some("img.example.com"));
        // An IPv4 address is the same however it is written.
        assert_eq!(
            names
                .host_of(10001, "::ffff:203.0.113.7".parse().unwrap())
                .as_deref(),
            Some("img.example.com")
        );
        let mut under: Vec<IpAddr> =
            names.addresses_of(10001, |host| host.ends_with(".example.com"));
        under.sort();
        assert_eq!(under, [a, b]);
        assert!(names.addresses_of(10003, |_| true).is_empty());
    }

    #[test]
    fn the_oldest_half_goes_when_too_much_is_remembered() {
        let names = Names::default();
        for i in 0..=REMEMBERED as u32 {
            names.learn(10001, "h.example.com", &[IpAddr::V4(Ipv4Addr::from(i))]);
        }
        let kept = names.addresses_of(10001, |_| true).len();
        assert_eq!(kept, REMEMBERED / 2);
        assert_eq!(names.host_of(10001, IpAddr::V4(Ipv4Addr::from(0))), None);
        assert!(
            names
                .host_of(10001, IpAddr::V4(Ipv4Addr::from(REMEMBERED as u32)))
                .is_some()
        );
    }
}
