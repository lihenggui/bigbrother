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
//! The socket is in a directory every process sees, whatever is mounted
//! for it. It is open to whom netd's is, the group of the apps with the
//! network permission, and serves only the apps that are given it: those
//! in the sandbox whose connections are recorded or that are kept from a
//! name, and never one kept from the network, which is to have no
//! resolver at all. Nobody gets through it what netd would not give them
//! directly. An app has a few lookups relayed at once and no more, so
//! that one app cannot keep the others from theirs.
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

mod protocol;

use std::{
    collections::{BTreeSet, HashMap},
    ffi::CString,
    io::{self, Read as _, Write as _},
    net::IpAddr,
    os::{
        fd::AsRawFd as _,
        unix::{
            fs::PermissionsExt as _,
            net::{UnixListener, UnixStream},
        },
    },
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, AtomicUsize, Ordering},
        mpsc::{Receiver, SyncSender, TrySendError, sync_channel},
    },
    time::{Duration, Instant},
};

use anyhow::Context as _;

use self::protocol::{Asked, Parsed, addresses_in};
use super::{
    Sandbox, items_of,
    resolver::{RELAY_SOCKET, SOCKET as NETD},
    target::Target,
};
use crate::sync::{lock, read};

/// The label of netd's processes, and of the file of its socket.
const NETD_LABEL: &str = "u:r:netd:s0";
const SOCKET_LABEL: &str = "u:object_r:dnsproxyd_socket:s0";

/// The group that may connect to netd's socket, which apps with the
/// network permission are in.
const INET_GID: libc::gid_t = 3003;

/// The most lookups relayed at once, for all apps and for one. One more
/// is refused: the app's lookup fails, as it does when netd has too many.
const MOST_AT_ONCE: usize = 64;
const MOST_OF_ONE_APP: usize = 8;

/// How many refusals can wait for the writer, and the most taken in one
/// pass even when lookups keep arriving while it drains the queue.
const REFUSED_LOOKUPS_CAPACITY: usize = 1024;

/// The longest request read, and the longest answer held to be read for
/// its addresses; a longer one is passed on unread.
const REQUEST_CAP: usize = 4096;
const ANSWER_CAP: usize = 64 * 1024;

/// How long a request is waited for, all of it, and how long netd is
/// given to answer, after which the connection ends whatever was said:
/// netd tries its servers for longer than an app usually waits.
const REQUEST_WAIT: Duration = Duration::from_secs(5);
const ANSWER_WAIT: Duration = Duration::from_secs(60);

/// The addresses remembered of one app. Past it, the half remembered
/// longest ago goes: an app that looks up name after name loses its own,
/// not those of the others.
const REMEMBERED: usize = 2048;

/// Which name each app was last given an address for.
#[derive(Default)]
pub struct Names {
    known: Mutex<Known>,
}

#[derive(Default)]
struct Known {
    by_address: HashMap<u32, HashMap<IpAddr, (String, u64)>>,
    /// Counts what is remembered, for the order it was in.
    count: u64,
    /// The addresses each app is refused with the names it is kept from
    /// (see `Destinations`): refused whatever name they were last given
    /// for.
    refused: HashMap<u32, BTreeSet<IpAddr>>,
}

impl Names {
    /// Remember that `uid` was given `addresses` for `host`.
    pub fn learn(&self, uid: u32, host: &str, addresses: &[IpAddr]) {
        let mut known = lock(&self.known);
        let Known {
            by_address, count, ..
        } = &mut *known;
        let of_app = by_address.entry(uid).or_default();
        for address in addresses {
            *count += 1;
            of_app.insert(address.to_canonical(), (host.to_string(), *count));
        }
        if of_app.len() > REMEMBERED {
            let mut order: Vec<u64> = of_app.values().map(|(_, at)| *at).collect();
            order.sort_unstable();
            let oldest_kept = order[order.len() - REMEMBERED / 2];
            of_app.retain(|_, (_, at)| *at >= oldest_kept);
        }
    }

    /// Forget what is remembered of `uid`: it belongs to another app.
    pub fn forget(&self, uid: u32) {
        let mut known = lock(&self.known);
        known.by_address.remove(&uid);
        known.refused.remove(&uid);
    }

    /// Note the addresses `uid` is refused with the names it is kept from.
    pub fn keep_refused(&self, uid: u32, addresses: BTreeSet<IpAddr>) {
        let mut known = lock(&self.known);
        if addresses.is_empty() {
            known.refused.remove(&uid);
        } else {
            known.refused.insert(uid, addresses);
        }
    }

    /// Whether `address` is one `uid` is refused with a name.
    pub fn refused(&self, uid: u32, address: IpAddr) -> bool {
        let known = lock(&self.known);
        known
            .refused
            .get(&uid)
            .is_some_and(|addresses| addresses.contains(&address.to_canonical()))
    }

    /// The name `uid` was last given `address` for.
    pub fn host_of(&self, uid: u32, address: IpAddr) -> Option<String> {
        let known = lock(&self.known);
        known
            .by_address
            .get(&uid)?
            .get(&address.to_canonical())
            .map(|(host, _)| host.clone())
    }

    /// The addresses `uid` was given for the names `covered` holds for.
    pub fn addresses_of(&self, uid: u32, covered: impl Fn(&str) -> bool) -> Vec<IpAddr> {
        let known = lock(&self.known);
        known
            .by_address
            .get(&uid)
            .into_iter()
            .flatten()
            .filter(|(_, (host, _))| covered(host))
            .map(|(address, _)| *address)
            .collect()
    }
}

/// Whether the lookup of a name by a uid is refused; of `None`, a name
/// that could not be read, which an app kept from any name is refused.
pub type Refuses = Arc<dyn Fn(u32, Option<&str>) -> bool + Send + Sync>;

/// Whether a uid is one the daemon relays lookups for: an app given the
/// daemon's socket.
pub type Serves = Arc<dyn Fn(u32) -> bool + Send + Sync>;

impl Sandbox {
    pub(crate) fn name_policy(&self) -> (Serves, Refuses) {
        let refuses: Refuses = {
            let (snapshot, book) = (self.snapshot(), self.book());
            Arc::new(move |uid, host: Option<&str>| {
                // Only of an app in the sandbox: taken out, it is kept from
                // nothing.
                read(&snapshot).contains_key(&uid)
                    && lock(&book).get(&uid).is_some_and(|targets| {
                        targets.iter().any(|target| match host {
                            Some(host) => target.covers_name(host),
                            // A name that could not be read: refused to
                            // an app kept from any name.
                            None => !matches!(target, Target::Ip(..)),
                        })
                    })
            })
        };
        let serves: Serves = {
            let (snapshot, global, book) = (self.snapshot(), self.global(), self.book());
            Arc::new(move |uid| {
                // The apps given the daemon's socket, and no other: the socket
                // can be found by any process, and an app kept from the
                // network is to have no resolver at all.
                let snapshot = read(&snapshot);
                let Some(entry) = snapshot.get(&uid) else {
                    return false;
                };
                let items = items_of(entry, &read(&global));
                !items.network_refused()
                    && (items.network_recorded()
                        || lock(&book).get(&uid).is_some_and(|targets| {
                            targets
                                .iter()
                                .any(|target| !matches!(target, Target::Ip(..)))
                        }))
            })
        };
        (serves, refuses)
    }
}

/// The resolver's end of the queue. Recording a refusal never waits for space.
pub struct RefusalReporter {
    pending: SyncSender<(u32, String)>,
    dropped: Arc<AtomicU64>,
}

impl RefusalReporter {
    fn record(&self, uid: u32, host: &str) {
        if let Err(TrySendError::Full(_)) = self.pending.try_send((uid, host.to_owned())) {
            self.dropped.fetch_add(1, Ordering::Relaxed);
        }
    }
}

/// The daemon's end of the queue, with a count of records lost to overload.
pub struct RefusedLookups {
    pending: Receiver<(u32, String)>,
    dropped: Arc<AtomicU64>,
}

impl RefusedLookups {
    pub fn channel() -> (RefusalReporter, Self) {
        let (sender, receiver) = sync_channel(REFUSED_LOOKUPS_CAPACITY);
        let dropped = Arc::new(AtomicU64::new(0));
        (
            RefusalReporter {
                pending: sender,
                dropped: dropped.clone(),
            },
            Self {
                pending: receiver,
                dropped,
            },
        )
    }

    pub fn batch(&self) -> impl Iterator<Item = (u32, String)> + '_ {
        self.pending.try_iter().take(REFUSED_LOOKUPS_CAPACITY)
    }

    pub fn dropped(&self) -> u64 {
        self.dropped.load(Ordering::Relaxed)
    }
}

/// What the threads that relay lookups share.
struct Shared {
    names: Arc<Names>,
    serves: Serves,
    refuses: Refuses,
    refused: RefusalReporter,
    at_once: AtomicUsize,
    /// How many lookups of each uid are being relayed.
    of_each: Mutex<HashMap<u32, usize>>,
}

impl Shared {
    /// Take a place for a lookup of `uid`, if it and all apps together
    /// have one left.
    fn take_place(&self, uid: u32) -> bool {
        let mut of_each = lock(&self.of_each);
        let of_this = of_each.entry(uid).or_insert(0);
        if *of_this >= MOST_OF_ONE_APP || self.at_once.load(Ordering::Relaxed) >= MOST_AT_ONCE {
            if *of_this == 0 {
                of_each.remove(&uid);
            }
            return false;
        }
        *of_this += 1;
        self.at_once.fetch_add(1, Ordering::Relaxed);
        true
    }

    fn leave_place(&self, uid: u32) {
        let mut of_each = lock(&self.of_each);
        if let Some(of_this) = of_each.get_mut(&uid) {
            *of_this -= 1;
            if *of_this == 0 {
                of_each.remove(&uid);
            }
        }
        self.at_once.fetch_sub(1, Ordering::Relaxed);
    }
}

/// Start listening for the lookups of the apps given the daemon's socket,
/// on a thread that does nothing else. Fails where the socket cannot be
/// made as an app's process must find it.
pub fn start(
    names: Arc<Names>,
    serves: Serves,
    refuses: Refuses,
    refused: RefusalReporter,
) -> anyhow::Result<()> {
    let shared = Arc::new(Shared {
        names,
        serves,
        refuses,
        refused,
        at_once: AtomicUsize::new(0),
        of_each: Mutex::default(),
    });
    let (ready, started) = sync_channel(1);
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
                // Whose it is, and whether it is served, before anything
                // is spent on it: the socket can be found by any process.
                let Ok(uid) = peer_uid(&client) else {
                    continue;
                };
                if !(shared.serves)(uid) || !shared.take_place(uid) {
                    continue;
                }
                let lookup = shared.clone();
                let spawned = std::thread::Builder::new()
                    .name("lookup".into())
                    .spawn(move || {
                        // What fails here fails the app's lookup, and
                        // says nothing: the reason would name the app.
                        let _ = relay(client, uid, &lookup);
                        lookup.leave_place(uid);
                    });
                if spawned.is_err() {
                    // The closure was not run, nor its place left.
                    shared.leave_place(uid);
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
    match std::fs::remove_file(RELAY_SOCKET) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => {
            return Err(e).context("removing the socket of the daemon before");
        }
        _ => {}
    }
    let listener = UnixListener::bind(RELAY_SOCKET).context("binding the socket")?;
    // To whom netd's own is open: root, and the group of the apps with
    // the network permission.
    std::os::unix::fs::chown(RELAY_SOCKET, Some(0), Some(INET_GID))
        .and_then(|()| {
            std::fs::set_permissions(RELAY_SOCKET, std::fs::Permissions::from_mode(0o660))
        })
        .context("opening the socket to the apps with the network permission")?;
    let (path, name, label) = (
        CString::new(RELAY_SOCKET)?,
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

fn relay(mut client: UnixStream, uid: u32, shared: &Shared) -> io::Result<()> {
    client.set_write_timeout(Some(REQUEST_WAIT))?;
    // The whole request within the time, however slowly it comes.
    let asked_by = Instant::now() + REQUEST_WAIT;
    let mut request = Vec::new();
    let mut chunk = [0u8; 1024];
    while !request.contains(&0) {
        let left = asked_by.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Ok(());
        }
        client.set_read_timeout(Some(left))?;
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
        && (shared.refuses)(uid, Some(host))
    {
        shared.refused.record(uid, host);
        // Closed without an answer: the lookup fails.
        return Ok(());
    }
    // A lookup of what cannot be read here might be read by netd as a name
    // the app is kept from.
    if asked == Asked::Unreadable && (shared.refuses)(uid, None) {
        return Ok(());
    }

    let mut netd = connect_as(uid)?;
    netd.set_write_timeout(Some(REQUEST_WAIT))?;
    netd.write_all(&request)?;
    let answered_by = Instant::now() + ANSWER_WAIT;
    // The answer to a lookup of addresses is read whole first, so that the
    // addresses are remembered before the app has them to connect to.
    let mut answer = Vec::new();
    if let Asked::Addresses(host) = &asked {
        loop {
            match addresses_in(&answer) {
                Parsed::Whole(addresses) => {
                    shared.names.learn(uid, host, &addresses);
                    // All there is to it: the app has no more to wait for.
                    return client.write_all(&answer);
                }
                Parsed::Unknown => break,
                Parsed::Partial if answer.len() > ANSWER_CAP => break,
                Parsed::Partial => {}
            }
            let left = answered_by.saturating_duration_since(Instant::now());
            if left.is_zero() {
                break;
            }
            netd.set_read_timeout(Some(left))?;
            let n = netd.read(&mut chunk)?;
            if n == 0 {
                break;
            }
            answer.extend_from_slice(&chunk[..n]);
        }
        client.write_all(&answer)?;
    }
    // The rest of the answer, until one of them closes or the time is up.
    pump(&mut client, &mut netd, answered_by)
}

/// Whether what was read is one command and nothing after it: text ended
/// by a zero byte, which is the last byte. A second command behind the
/// first would reach netd without having been looked at.
fn one_command(request: &[u8]) -> bool {
    request.iter().position(|b| *b == 0) == Some(request.len().wrapping_sub(1))
}

/// Pass on what netd sends to the app, until one of them closes or it is
/// `until`. Nothing more is passed on from the app: if it sends anything,
/// the connection ends.
fn pump(client: &mut UnixStream, netd: &mut UnixStream, until: Instant) -> io::Result<()> {
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
        let left = until.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Ok(());
        }
        let ready = unsafe { libc::poll(fds.as_mut_ptr(), 2, left.as_millis() as i32) };
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    #[test]
    fn an_app_has_a_few_lookups_at_once_and_all_apps_some_more() {
        let (refused, _) = RefusedLookups::channel();
        let shared = Shared {
            names: Arc::default(),
            serves: Arc::new(|_| true),
            refuses: Arc::new(|_, _| false),
            refused,
            at_once: AtomicUsize::new(0),
            of_each: Mutex::default(),
        };
        for _ in 0..MOST_OF_ONE_APP {
            assert!(shared.take_place(10001));
        }
        // One more of the same app has none; another app has.
        assert!(!shared.take_place(10001));
        assert!(shared.take_place(10002));
        shared.leave_place(10001);
        assert!(shared.take_place(10001));
        // All apps together.
        let mut uid = 20000;
        while shared.at_once.load(Ordering::Relaxed) < MOST_AT_ONCE {
            uid += 1;
            assert!(shared.take_place(uid));
        }
        assert!(!shared.take_place(30000));
        assert!(!shared.of_each.lock().unwrap().contains_key(&30000));
        shared.leave_place(uid);
        assert!(shared.take_place(30000));
        // Every place left is a place again.
        let uids: Vec<u32> = shared.of_each.lock().unwrap().keys().copied().collect();
        for uid in uids {
            loop {
                let has = shared.of_each.lock().unwrap().contains_key(&uid);
                if !has {
                    break;
                }
                shared.leave_place(uid);
            }
        }
        assert_eq!(shared.at_once.load(Ordering::Relaxed), 0);
    }

    #[test]
    fn a_full_refusal_queue_drops_the_record_and_still_refuses_the_lookup() {
        let (refused, pending) = RefusedLookups::channel();
        for _ in 0..REFUSED_LOOKUPS_CAPACITY {
            refused.record(10001, "queued.example");
        }
        let shared = Shared {
            names: Arc::default(),
            serves: Arc::new(|_| true),
            refuses: Arc::new(|_, host| host == Some("blocked.example")),
            refused,
            at_once: AtomicUsize::new(0),
            of_each: Mutex::default(),
        };
        let (mut client, server) = UnixStream::pair().unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        client
            .write_all(b"getaddrinfo blocked.example ^ 0 0 0 0 0\0")
            .unwrap();

        std::thread::scope(|scope| {
            let task = scope.spawn(move || relay(server, 10002, &shared));
            let Ok(closed) = client.read(&mut [0u8]) else {
                drop(pending);
                panic!("the refusal waited for space in the queue");
            };
            task.join().unwrap().unwrap();
            assert_eq!(closed, 0);
            assert_eq!(pending.dropped(), 1);
            let queued: Vec<_> = pending.batch().collect();
            assert_eq!(queued.len(), REFUSED_LOOKUPS_CAPACITY);
            assert_eq!(pending.batch().count(), 0);
            assert!(
                queued
                    .iter()
                    .all(|(uid, host)| { *uid == 10001 && host == "queued.example" })
            );
        });
    }

    #[test]
    fn a_batch_ends_even_when_new_refusals_keep_arriving() {
        let (refused, pending) = RefusedLookups::channel();
        for _ in 0..REFUSED_LOOKUPS_CAPACITY {
            refused.record(10001, "queued.example");
        }
        let drained = pending
            .batch()
            .take(REFUSED_LOOKUPS_CAPACITY + 1)
            .inspect(|_| refused.record(10002, "arriving.example"))
            .count();
        assert_eq!(drained, REFUSED_LOOKUPS_CAPACITY);
        assert_eq!(pending.dropped(), 0);
        let queued: Vec<_> = pending.batch().collect();
        assert_eq!(queued.len(), REFUSED_LOOKUPS_CAPACITY);
        assert!(
            queued
                .iter()
                .all(|(uid, host)| { *uid == 10002 && host == "arriving.example" })
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

    #[test]
    fn an_app_that_looks_up_many_names_keeps_those_of_the_others() {
        let names = Names::default();
        let other = IpAddr::V4(Ipv4Addr::new(203, 0, 113, 9));
        names.learn(10002, "other.example.com", &[other]);
        for i in 0..=2 * REMEMBERED as u32 {
            names.learn(10001, "h.example.com", &[IpAddr::V4(Ipv4Addr::from(i))]);
        }
        assert_eq!(
            names.host_of(10002, other).as_deref(),
            Some("other.example.com")
        );
    }

    #[test]
    fn nothing_is_remembered_of_a_uid_that_went_to_another_app() {
        let names = Names::default();
        let address = IpAddr::V4(Ipv4Addr::new(203, 0, 113, 9));
        names.learn(10001, "h.example.com", &[address]);
        names.keep_refused(10001, BTreeSet::from([address]));
        assert!(names.refused(10001, address));
        names.forget(10001);
        assert_eq!(names.host_of(10001, address), None);
        assert!(!names.refused(10001, address));
    }
}
