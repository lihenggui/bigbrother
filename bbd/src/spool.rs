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

//! Collects records written by the LSPosed hook.
//!
//! The kernel side only sees an app opening files itself. When an app goes
//! through a system content provider instead — querying MediaStore for the
//! list of photos, or asking it to open one — the provider's process does the
//! work and the app never touches the file system. The hook (part of the
//! Android app, injected into the provider processes) records who asked.
//!
//! The hook runs as the provider, so it cannot reach this daemon's database.
//! It appends lines to a spool file in the provider's own data directory, and
//! the daemon, being root, picks them up from there. No IPC or SELinux policy
//! is involved.
//!
//! Line format, tab separated:
//!   `1  <unix time>  <caller uid>  <q|o>  <mode>  <items>  <uri>  <path>`
//! `q` is a query and `o` an open; `mode` is the open mode ("r", "w", "rw");
//! `items` is the number of rows a query returned; `path` is the file an open
//! resolved to, when known.

use std::{
    collections::BTreeMap,
    fs::{self, File},
    io::Read as _,
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::fs::MetadataExt as _,
    },
    path::PathBuf,
    process::Command,
    thread::JoinHandle,
    time::{Duration, Instant},
};

use bb_common::PER_USER_RANGE;

use crate::sandbox::dirs::Dir;

const MAX_FILE: u64 = 4 * 1024 * 1024;
const MAX_EVENTS: usize = 8192;
const MAX_LINE: usize = 8192;

/// Packages whose processes the hook is injected into. Must match the
/// module's scope (`android/hook/src/main/resources/META-INF/xposed/scope.list`).
pub const PROVIDER_PACKAGES: &[&str] = &[
    "com.android.providers.media.module",
    "com.google.android.providers.media.module",
    "com.android.externalstorage",
    "com.android.providers.downloads",
];

/// Packages that hold personal data, which the hook is injected into as
/// well. Only what is asked of their personal collections is recorded (see
/// `personal`).
///
/// A process hosts every package that shares its uid, and the hook writes
/// its spool to the data directory of whichever of them is loaded first: the
/// contacts provider's records turn up under the blocked numbers provider.
/// The spool is therefore looked for under every package sharing a uid with
/// one of these (see [`Spool::collect`]), and under no others: an app must
/// not be able to plant records by creating a directory of its own.
pub const PERSONAL_PACKAGES: &[&str] = &[
    "com.android.providers.contacts",
    "com.samsung.android.providers.contacts",
    "com.android.providers.telephony",
    "com.android.providers.calendar",
    "com.android.phone",
];

/// Device-encrypted app storage: `<root>/<user>/<package>`. Available before
/// the first unlock, unlike `/data/user`.
const DATA_ROOT: &str = "/data/user_de";
const SPOOL_DIR: &str = "big_brother";
#[cfg(test)]
const SPOOL_FILE: &str = "events.log";
/// A spool file claimed for reading; see [`Spool::collect`].
#[cfg(test)]
const CLAIMED_FILE: &str = "events.log.ingest";
const FORMAT_VERSION: &str = "1";

#[derive(Debug, PartialEq, Eq)]
pub enum Op {
    Query,
    Open,
}

#[derive(Debug, PartialEq, Eq)]
pub struct ProviderEvent {
    pub ts: i64,
    /// The app that called the provider.
    pub uid: u32,
    pub op: Op,
    pub read: bool,
    pub write: bool,
    /// Rows returned by a query.
    pub items: u32,
    pub uri: String,
    /// File the open resolved to; empty when unknown.
    pub path: String,
    /// Whether it was recorded in one of the storage providers
    /// (`PROVIDER_PACKAGES`), as opposed to a personal-data provider.
    pub storage_provider: bool,
}

pub fn parse_line(line: &str, storage_provider: bool) -> Option<ProviderEvent> {
    let mut fields = line.split('\t');
    if fields.next()? != FORMAT_VERSION {
        return None;
    }
    let ts = fields.next()?.parse().ok()?;
    let uid = fields.next()?.parse().ok()?;
    let op = match fields.next()? {
        "q" => Op::Query,
        "o" => Op::Open,
        _ => return None,
    };
    let mode = fields.next()?;
    let items = fields.next()?.parse().ok()?;
    let uri = fields.next()?.to_string();
    let path = fields.next().unwrap_or("").to_string();
    if uri.is_empty() {
        return None;
    }
    Some(ProviderEvent {
        ts,
        uid,
        op,
        // Android open modes: "r", "w", "wt", "wa", "rw", "rwt".
        read: mode.contains('r'),
        write: mode.contains('w'),
        items,
        uri,
        path,
        storage_provider,
    })
}

/// Reduce a content URI to the collection it addresses, so that touching a
/// thousand items of one collection aggregates into one row:
/// `content://media/external/images/media/1234?limit=1` becomes
/// `content://media/external/images/media/[id]`.
pub fn normalize_uri(uri: &str) -> String {
    const MAX_LEN: usize = 300;
    let end = uri.find(['?', '#']).unwrap_or(uri.len());
    let (scheme, rest) = match uri[..end].split_once("://") {
        Some((scheme, rest)) => (scheme, rest),
        None => return uri[..end].chars().take(MAX_LEN).collect(),
    };
    let mut out = format!("{scheme}:/");
    for (i, segment) in rest.split('/').enumerate() {
        out.push('/');
        // The first segment is the authority and is never an id.
        let is_id = i > 0 && !segment.is_empty() && segment.bytes().all(|b| b.is_ascii_digit());
        out.push_str(if is_id { "[id]" } else { segment });
    }
    out.chars().take(MAX_LEN).collect()
}

struct Host {
    package: String,
    app_id: u32,
    storage: bool,
}

pub struct Spool {
    data_root: PathBuf,
    trusted: Vec<Host>,
    lookup: Option<JoinHandle<Vec<Host>>>,
    retry: Option<Instant>,
}

impl Spool {
    pub fn new() -> Self {
        Self::at(DATA_ROOT)
    }

    fn at(data_root: impl Into<PathBuf>) -> Self {
        Self {
            data_root: data_root.into(),
            trusted: Vec::new(),
            lookup: None,
            retry: None,
        }
    }

    pub fn collect(&mut self) -> Vec<ProviderEvent> {
        if self.lookup.as_ref().is_some_and(JoinHandle::is_finished) {
            self.trusted = match self.lookup.take().unwrap().join() {
                Ok(hosts) => hosts,
                Err(_) => {
                    // The retry interval below also bounds how often this can be logged.
                    eprintln!("bbd: provider spool: trusted host lookup panicked");
                    Vec::new()
                }
            };
        }
        if self.trusted.is_empty()
            && self.lookup.is_none()
            && self
                .retry
                .is_none_or(|t| t.elapsed() >= Duration::from_secs(30))
        {
            self.retry = Some(Instant::now());
            self.lookup = Some(std::thread::spawn(|| {
                let Ok(result) = Command::new("/system/bin/cmd")
                    .args([
                        "package", "list", "packages", "-s", "-u", "-U", "--user", "0",
                    ])
                    .output()
                else {
                    return Vec::new();
                };
                if !result.status.success() {
                    return Vec::new();
                }
                system_hosts(&String::from_utf8_lossy(&result.stdout))
            }));
        }
        self.read_hosts()
    }

    fn read_hosts(&self) -> Vec<ProviderEvent> {
        let mut events = Vec::new();
        let Ok(root) = Dir::open(&self.data_root) else {
            return events;
        };
        let Ok(users) = fs::read_dir(&self.data_root) else {
            return events;
        };
        for user in users.flatten() {
            let name = user.file_name();
            let Some(name) = name.to_str() else { continue };
            let Ok(user_id) = name.parse::<u32>() else {
                continue;
            };
            let Some(base) = user_id.checked_mul(PER_USER_RANGE) else {
                continue;
            };
            let Ok(user_dir) = root.open_child(name) else {
                continue;
            };
            for host in &self.trusted {
                let Some(uid) = base.checked_add(host.app_id) else {
                    continue;
                };
                let Ok(pkg) = user_dir.open_child(&host.package) else {
                    continue;
                };
                let Ok(dir) = pkg.open_child(SPOOL_DIR) else {
                    continue;
                };
                let trusted = |dir: &Dir| {
                    dir.stat()
                        .is_ok_and(|s| trusted_owner(uid, s.st_uid, s.st_gid, s.st_mode))
                };
                if !trusted(&pkg) || !trusted(&dir) {
                    continue;
                }
                collect_dir(&dir, uid, host.storage, &mut events);
            }
        }
        events
    }
}

// Only packages the system identifies as preinstalled can establish a
// provider UID. Its preinstalled siblings may host the same process.
fn system_hosts(output: &str) -> Vec<Host> {
    let mut installed = BTreeMap::new();
    for line in output.lines() {
        let mut words = line.split_ascii_whitespace();
        let Some(name) = words.next().and_then(|w| w.strip_prefix("package:")) else {
            continue;
        };
        let Some(uid) = words.find_map(|w| w.strip_prefix("uid:")?.parse::<u32>().ok()) else {
            continue;
        };
        if uid < PER_USER_RANGE {
            installed.insert(name, uid);
        }
    }
    let mut providers = BTreeMap::new();
    for (packages, storage) in [(PERSONAL_PACKAGES, false), (PROVIDER_PACKAGES, true)] {
        for name in packages {
            if let Some(uid) = installed.get(name) {
                providers.insert(*uid, storage);
            }
        }
    }
    installed
        .into_iter()
        .filter_map(|(name, app_id)| {
            Some(Host {
                storage: *providers.get(&app_id)?,
                app_id,
                package: name.to_owned(),
            })
        })
        .collect()
}

fn trusted_owner(uid: u32, owner: u32, group: u32, mode: libc::mode_t) -> bool {
    owner == uid
        && mode & 0o002 == 0
        && (mode & 0o020 == 0 || group == uid || matches!(group, 0 | 1000))
}

fn collect_dir(dir: &Dir, uid: u32, storage: bool, events: &mut Vec<ProviderEvent>) {
    let parent = dir.as_raw_fd();
    let claimed = c"events.log.ingest";
    // O_PATH takes a reference without opening a device or waiting on a FIFO.
    let fd = unsafe {
        libc::openat(
            parent,
            claimed.as_ptr(),
            libc::O_PATH | libc::O_NOFOLLOW | libc::O_CLOEXEC,
        )
    };
    if fd >= 0 {
        let pinned = unsafe { File::from_raw_fd(fd) };
        if pinned.metadata().is_ok_and(|m| {
            m.is_file()
                && trusted_owner(uid, m.uid(), m.gid(), m.mode())
                && m.nlink() == 1
                && m.len() <= MAX_FILE
        }) && events.len() < MAX_EVENTS
            && let Ok(file) = File::open(format!("/proc/self/fd/{}", pinned.as_raw_fd()))
        {
            let mut data = String::new();
            if file.take(MAX_FILE + 1).read_to_string(&mut data).is_ok()
                && data.len() as u64 <= MAX_FILE
            {
                events.extend(
                    data.lines()
                        .filter(|l| l.len() <= MAX_LINE)
                        .filter_map(|l| parse_line(l, storage))
                        .take(MAX_EVENTS - events.len()),
                );
            }
        }
    }
    unsafe {
        libc::unlinkat(parent, claimed.as_ptr(), 0);
        libc::renameat(parent, c"events.log".as_ptr(), parent, claimed.as_ptr());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_authenticated_system_packages_can_nominate_provider_uids() {
        let hosts = system_hosts(
            "package:com.android.providers.contacts uid:10400\npackage:com.vendor.sibling uid:10400\npackage:com.example.other uid:10401",
        );
        assert_eq!(
            hosts.iter().map(|h| h.package.as_str()).collect::<Vec<_>>(),
            ["com.android.providers.contacts", "com.vendor.sibling"]
        );
        assert!(hosts.iter().all(|h| !h.storage && h.app_id == 10400));
        assert!(system_hosts("package:com.vendor.sibling uid:10400").is_empty());
    }

    #[test]
    fn every_line_of_the_hook_is_read_as_it_writes_it() {
        let cases = crate::protocol_cases::spool();
        let flag = |case: &crate::protocol_cases::Case, key| match case.get(key) {
            Some("1") => true,
            Some("0") => false,
            other => panic!("{key}={other:?}"),
        };
        for case in crate::protocol_cases::of(&cases, &["line"])
            .into_iter()
            .chain(crate::protocol_cases::of(&cases, &["read"]))
        {
            let op = || match case.get("op") {
                Some("query") => Op::Query,
                Some("open") => Op::Open,
                other => panic!("op={other:?}"),
            };
            for storage_provider in [true, false] {
                assert_eq!(
                    parse_line(&case.line, storage_provider),
                    Some(ProviderEvent {
                        ts: case.get("ts").unwrap().parse().unwrap(),
                        uid: case.get("uid").unwrap().parse().unwrap(),
                        op: op(),
                        read: flag(case, "read"),
                        write: flag(case, "write"),
                        items: case.get("items").unwrap().parse().unwrap(),
                        uri: case.get("uri").unwrap().into(),
                        path: case.get("path").unwrap().into(),
                        storage_provider,
                    }),
                    "{:?}",
                    case.line
                );
            }
        }
        for case in crate::protocol_cases::of(&cases, &["bad"]) {
            assert_eq!(parse_line(&case.line, true), None, "{:?}", case.line);
        }
    }

    #[test]
    fn uris_collapse_to_their_collection() {
        for (uri, want) in [
            (
                "content://media/external/images/media/1234",
                "content://media/external/images/media/[id]",
            ),
            (
                "content://media/external/images/media?limit=50&offset=100",
                "content://media/external/images/media",
            ),
            (
                "content://media/external_primary/file/9#frag",
                "content://media/external_primary/file/[id]",
            ),
            (
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM/children",
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM/children",
            ),
            // A numeric authority (user-qualified, "10@media") is not an id.
            ("content://0/items/5", "content://0/items/[id]"),
            ("not-a-uri", "not-a-uri"),
        ] {
            assert_eq!(normalize_uri(uri), want, "{uri}");
        }
    }

    #[test]
    fn spool_is_ingested_one_call_after_being_claimed() {
        let root = std::env::temp_dir().join(format!("bbd-test-spool-{}", std::process::id()));
        let dir = root.join("0").join(PROVIDER_PACKAGES[0]).join(SPOOL_DIR);
        fs::create_dir_all(&dir).unwrap();
        // A directory of an unrelated package is never looked at.
        fs::create_dir_all(root.join("0/com.example.other").join(SPOOL_DIR)).unwrap();

        let line = |uid: u32| format!("1\t1700000000\t{uid}\tq\t\t1\tcontent://media/x\t\n");
        fs::write(dir.join(SPOOL_FILE), line(10001)).unwrap();

        let mut spool = Spool::at(&root);
        // Nothing shares a uid with a provider here, bar one package.
        let uid = unsafe { libc::getuid() };
        spool.trusted = vec![
            Host {
                package: PROVIDER_PACKAGES[0].into(),
                app_id: uid,
                storage: true,
            },
            Host {
                package: "com.android.providers.blockednumber".into(),
                app_id: uid,
                storage: false,
            },
        ];
        let collect = || spool.read_hosts();
        // First call only claims the file.
        assert!(collect().is_empty());
        assert!(!dir.join(SPOOL_FILE).exists());
        // The hook keeps writing to a fresh file meanwhile.
        fs::write(dir.join(SPOOL_FILE), line(10002) + "broken line\n").unwrap();

        let uids = |events: Vec<ProviderEvent>| events.iter().map(|e| e.uid).collect::<Vec<_>>();
        assert_eq!(uids(collect()), [10001]);
        assert_eq!(uids(collect()), [10002]);
        assert!(collect().is_empty());
        assert!(!dir.join(CLAIMED_FILE).exists());

        // A personal-data provider's spool is collected too, and marked,
        // from under whichever package of its process holds it.
        let shared = root
            .join("0/com.android.providers.blockednumber")
            .join(SPOOL_DIR);
        fs::create_dir_all(&shared).unwrap();
        fs::write(shared.join(SPOOL_FILE), line(10003)).unwrap();
        collect();
        let marked: Vec<_> = collect()
            .iter()
            .map(|e| (e.uid, e.storage_provider))
            .collect();
        assert_eq!(marked, [(10003, false)]);
        fs::remove_dir_all(root).unwrap();
    }
}
