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
//! (`/dev/socket/dnsproxyd`). For an app whose connections are recorded
//! or that is kept from names, the daemon's socket can cover it in that
//! app's mount namespaces ([`super::resolver`]):
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
//! network permission. It serves sandboxed apps whose connections are
//! recorded or that are kept from a name, while they have network access.
//! An app has a few lookups relayed at once, so it cannot keep the others
//! from theirs.
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
mod socket;

use std::{
    collections::{BTreeSet, HashMap, VecDeque},
    io::{self, Read as _, Write as _},
    net::IpAddr,
    os::{
        fd::AsRawFd as _,
        unix::{
            fs::MetadataExt as _,
            net::{UnixListener, UnixStream},
        },
    },
    path::{Path, PathBuf},
    sync::{
        Arc, Condvar, Mutex, MutexGuard,
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
        mpsc::{Receiver, SyncSender, TrySendError, sync_channel},
    },
    time::{Duration, Instant},
};

use anyhow::Context as _;
use tokio::sync::Notify;

use self::{
    protocol::{Asked, Parsed, addresses_in},
    socket::{connect_as, listen, peer_uid, remove_relay_socket},
};
use crate::{
    log::{Limited, Policy, RECURRING_INTERVAL},
    sync::{lock, recover},
};

/// Where netd listens for the lookups of apps.
pub(super) const NETD: &str = "/dev/socket/dnsproxyd";

/// Where the daemon listens for lookups. Beside netd's, in the filesystem
/// every mount namespace has; the name says nothing of the sandbox, as an
/// app can read its own mounts.
pub(super) const RELAY_SOCKET: &str = "/dev/socket/dnsproxyd_relay";

/// The most lookups relayed at once, for all apps and for one. One more
/// is refused: the app's lookup fails, as it does when netd has too many.
const MOST_AT_ONCE: usize = 64;
const MOST_OF_ONE_APP: usize = 8;
const LOOKUP_WORKERS: usize = 16;
const LOOKUP_QUEUE: usize = MOST_AT_ONCE - LOOKUP_WORKERS;
const LOOKUP_IDLE: Duration = Duration::from_secs(60);

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

// The dnsproxyd getaddrinfo answer has no TTL. Bound how long a saved
// address can keep affecting connections after the app was given it.
const ADDRESS_LIFETIME: i64 = 24 * 60 * 60;

/// Which name each app was last given an address for.
#[derive(Default)]
pub struct Names {
    known: Mutex<Known>,
}

#[derive(Default)]
struct Known {
    by_address: HashMap<u32, HashMap<IpAddr, Answer>>,
    /// Counts what is remembered, for the order it was in.
    count: u64,
    /// The addresses each app is refused with the names it is kept from
    /// (see `Destinations`): refused whatever name they were last given
    /// for.
    refused: HashMap<u32, BTreeSet<IpAddr>>,
}

struct Answer {
    install_id: Arc<str>,
    host: String,
    order: u64,
    expires: i64,
}

impl Names {
    /// Remember that `uid` was given `addresses` for `host`.
    pub fn learn(&self, uid: u32, install_id: Arc<str>, host: &str, addresses: &[IpAddr]) {
        let mut known = lock(&self.known);
        let Known {
            by_address, count, ..
        } = &mut *known;
        let of_app = by_address.entry(uid).or_default();
        let expires = crate::clock::now() + ADDRESS_LIFETIME;
        for address in addresses {
            *count += 1;
            of_app.insert(
                address.to_canonical(),
                Answer {
                    install_id: install_id.clone(),
                    host: host.to_owned(),
                    order: *count,
                    expires,
                },
            );
        }
        if of_app.len() > REMEMBERED {
            let mut order: Vec<u64> = of_app.values().map(|answer| answer.order).collect();
            order.sort_unstable();
            let oldest_kept = order[order.len() - REMEMBERED / 2];
            of_app.retain(|_, answer| answer.order >= oldest_kept);
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
            .map(|answer| answer.host.clone())
    }

    /// The addresses `uid` was given for the names `covered` holds for.
    pub fn addresses_of(
        &self,
        uid: u32,
        install_id: &str,
        now: i64,
        covered: impl Fn(&str) -> bool,
    ) -> Vec<(IpAddr, i64)> {
        let known = lock(&self.known);
        known
            .by_address
            .get(&uid)
            .into_iter()
            .flatten()
            .filter(|(_, answer)| {
                answer.install_id.as_ref() == install_id
                    && answer.expires > now
                    && covered(&answer.host)
            })
            .map(|(address, answer)| (*address, answer.expires))
            .collect()
    }
}

/// Whether the lookup of a name by a uid is refused; of `None`, a name
/// that could not be read, which an app kept from any name is refused.
pub type Refuses = Arc<dyn Fn(u32, Option<&str>) -> bool + Send + Sync>;

/// The installation served by the relay, if any. Keep its identity from
/// when the lookup starts so a late answer cannot follow a reused UID.
pub type Serves = Arc<dyn Fn(u32) -> Option<Arc<str>> + Send + Sync>;

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

pub(super) type SocketId = (u64, u64);

pub(super) fn socket_id(path: impl AsRef<Path>) -> io::Result<SocketId> {
    let metadata = std::fs::metadata(path)?;
    Ok((metadata.dev(), metadata.ino()))
}

/// The socket served by this generation.
#[derive(Clone, Default)]
pub struct Serving(Arc<RelayState>);

#[derive(Default)]
struct RelayState {
    socket: Mutex<Option<ServedSocket>>,
    active: AtomicBool,
}

pub(super) struct ServedSocket {
    pub(super) id: SocketId,
    bound: Option<(Arc<UnixListener>, PathBuf)>,
}

impl Serving {
    pub fn alive(&self) -> bool {
        // Lookups need not wait for a namespace changing its socket mount.
        self.0.active.load(Ordering::Acquire)
    }

    pub(super) fn socket(&self) -> MutexGuard<'_, Option<ServedSocket>> {
        lock(&self.0.socket)
    }

    fn set_listener(&self, listener: Arc<UnixListener>, path: &Path) -> io::Result<()> {
        let id = socket_id(path)?;
        let mut socket = self.socket();
        *socket = Some(ServedSocket {
            id,
            bound: Some((listener, path.to_owned())),
        });
        self.0.active.store(true, Ordering::Release);
        Ok(())
    }

    #[cfg(test)]
    pub(super) fn listening(id: SocketId) -> Self {
        Self(Arc::new(RelayState {
            socket: Mutex::new(Some(ServedSocket { id, bound: None })),
            active: AtomicBool::new(true),
        }))
    }

    pub fn stop(&self) -> bool {
        let mut serving = self.socket();
        let Some(socket) = serving.take() else {
            return false;
        };
        self.0.active.store(false, Ordering::Release);
        if let Some((listener, path)) = socket.bound {
            // Wake accept without needing to connect through the app-facing socket.
            // SAFETY: listener owns the fd until shutdown returns.
            if unsafe { libc::shutdown(listener.as_raw_fd(), libc::SHUT_RDWR) } != 0 {
                eprintln!(
                    "bbd: cannot stop the resolver listener: {}",
                    io::Error::last_os_error()
                );
            }
            match socket_id(&path) {
                Ok(id) if id == socket.id => remove_relay_socket(&path),
                Ok(_) => {}
                Err(e) if e.kind() == io::ErrorKind::NotFound => {}
                Err(e) => eprintln!("bbd: cannot identify the resolver socket for cleanup: {e}"),
            }
        }
        true
    }
}

struct ListenerLifetime {
    serving: Serving,
    stopped: Arc<Notify>,
}

impl Drop for ListenerLifetime {
    fn drop(&mut self) {
        if self.serving.stop() {
            self.stopped.notify_one();
        }
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
    serving: Serving,
    stopped: Arc<Notify>,
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
            let lifetime = ListenerLifetime { serving, stopped };
            let started = start_listener(&lifetime.serving);
            let (result, running) = match started {
                Ok(running) => (Ok(()), Some(running)),
                Err(error) => (Err(error), None),
            };
            // Without the caller there is no sandbox to serve.
            if ready.send(result).is_ok()
                && let Some(listener) = running
            {
                let workers = lookup_pool(lifetime.serving.clone(), shared.clone());
                accept_lookups(&listener, &lifetime.serving, &shared, |job| {
                    workers.dispatch(job)
                });
            }
        })
        .context("starting the thread")?;
    started.recv().context("the thread ended as it started")?
}

struct Lookup {
    client: UnixStream,
    uid: u32,
    install_id: Arc<str>,
    asked_by: Instant,
}

fn start_listener(serving: &Serving) -> anyhow::Result<Arc<UnixListener>> {
    let listener = Arc::new(listen()?);
    if let Err(error) = serving.set_listener(listener.clone(), Path::new(RELAY_SOCKET)) {
        remove_relay_socket(Path::new(RELAY_SOCKET));
        return Err(error).context("identifying the relay socket");
    }
    Ok(listener)
}

fn lookup_pool(serving: Serving, shared: Arc<Shared>) -> LookupPool {
    LookupPool::new(LOOKUP_WORKERS, LOOKUP_QUEUE, LOOKUP_IDLE, move |lookup| {
        let Lookup {
            client,
            uid,
            install_id,
            asked_by,
        } = lookup;
        if serving.alive() && (shared.serves)(uid).as_deref() == Some(install_id.as_ref()) {
            // A relay error ends this lookup. Its details could identify the app.
            let _ = relay(client, uid, install_id, asked_by, &shared);
        }
        shared.leave_place(uid);
    })
}

struct LookupPool {
    core: Arc<LookupWorkers>,
    max_workers: usize,
    capacity: usize,
}

struct LookupWorkers {
    state: Mutex<PoolState>,
    ready: Condvar,
    idle_timeout: Duration,
    handle: Box<dyn Fn(Lookup) + Send + Sync>,
}

#[derive(Default)]
struct PoolState {
    pending: VecDeque<Lookup>,
    workers: usize,
    busy: usize,
    closed: bool,
}

impl LookupPool {
    fn new(
        max_workers: usize,
        capacity: usize,
        idle_timeout: Duration,
        handle: impl Fn(Lookup) + Send + Sync + 'static,
    ) -> Self {
        Self {
            core: Arc::new(LookupWorkers {
                state: Mutex::default(),
                ready: Condvar::new(),
                idle_timeout,
                handle: Box::new(handle),
            }),
            max_workers,
            capacity,
        }
    }

    fn dispatch(&self, job: Lookup) -> io::Result<()> {
        self.dispatch_with(job, |workers| {
            std::thread::Builder::new()
                .name("lookup".into())
                .spawn(move || workers.run())?;
            Ok(())
        })
    }

    fn dispatch_with(
        &self,
        job: Lookup,
        spawn: impl FnOnce(Arc<LookupWorkers>) -> io::Result<()>,
    ) -> io::Result<()> {
        let mut state = lock(&self.core.state);
        if state.pending.len() >= self.capacity {
            return Err(io::Error::new(
                io::ErrorKind::WouldBlock,
                "lookup queue full",
            ));
        }
        // A queued job already claims an idle or newly created worker.
        let available = state.workers - state.busy;
        if state.pending.len() >= available && state.workers < self.max_workers {
            match spawn(self.core.clone()) {
                Ok(()) => state.workers += 1,
                Err(error) if state.workers == 0 => return Err(error),
                // An existing worker can handle the job when it becomes free.
                Err(_) => {}
            }
        }
        state.pending.push_back(job);
        self.core.ready.notify_one();
        Ok(())
    }
}

impl Drop for LookupPool {
    fn drop(&mut self) {
        lock(&self.core.state).closed = true;
        self.core.ready.notify_all();
    }
}

impl LookupWorkers {
    fn run(&self) {
        let mut state = lock(&self.state);
        loop {
            (state, _) = recover(self.ready.wait_timeout_while(
                state,
                self.idle_timeout,
                |state| state.pending.is_empty() && !state.closed,
            ));
            // Retire under the queue's lock so a racing dispatch sees either
            // a worker ready to take the job or capacity to create one.
            let Some(job) = state.pending.pop_front() else {
                state.workers -= 1;
                return;
            };
            state.busy += 1;
            drop(state);
            (self.handle)(job);
            state = lock(&self.state);
            state.busy -= 1;
        }
    }
}

fn accept_lookups(
    listener: &UnixListener,
    serving: &Serving,
    shared: &Shared,
    mut dispatch: impl FnMut(Lookup) -> io::Result<()>,
) {
    let mut dispatch_errors = Limited::new(RECURRING_INTERVAL, Policy::Shared);
    for client in listener.incoming() {
        if !serving.alive() {
            break;
        }
        let client = match client {
            Ok(client) => client,
            Err(e)
                if matches!(
                    e.kind(),
                    io::ErrorKind::Interrupted | io::ErrorKind::ConnectionAborted
                ) =>
            {
                continue;
            }
            Err(e) => {
                eprintln!("bbd: warning: the resolver relay stopped accepting lookups: {e}");
                break;
            }
        };
        // The socket is public. Check the peer and the policy before queuing anything.
        // A peer that cannot be identified is ineligible.
        let Ok(uid) = peer_uid(&client) else { continue };
        let Some(install_id) = (shared.serves)(uid) else {
            continue;
        };
        if !shared.take_place(uid) {
            continue;
        }
        let lookup = Lookup {
            client,
            uid,
            install_id,
            asked_by: Instant::now() + REQUEST_WAIT,
        };
        if let Err(error) = dispatch(lookup) {
            shared.leave_place(uid);
            dispatch_errors.log(format_args!(
                "bbd: warning: could not dispatch a resolver lookup: {error}"
            ));
        }
    }
}

fn relay(
    mut client: UnixStream,
    uid: u32,
    install_id: Arc<str>,
    asked_by: Instant,
    shared: &Shared,
) -> io::Result<()> {
    client.set_write_timeout(Some(REQUEST_WAIT))?;
    // The whole request within the time, however slowly it comes.
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
                    shared.names.learn(uid, install_id, host, &addresses);
                    if (shared.refuses)(uid, Some(host)) {
                        shared.refused.record(uid, host);
                        return Ok(());
                    }
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
mod tests;
