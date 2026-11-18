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
    fs,
    path::{Path, PathBuf},
};

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
const SPOOL_FILE: &str = "events.log";
/// A spool file claimed for reading; see [`Spool::collect`].
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

pub struct Spool {
    data_root: PathBuf,
}

impl Spool {
    pub fn new() -> Self {
        Self::at(DATA_ROOT)
    }

    fn at(data_root: impl Into<PathBuf>) -> Self {
        Self {
            data_root: data_root.into(),
        }
    }

    /// Existing spool directories, and whether each is a storage provider's.
    /// `hosts` lists the packages to look under, with that flag.
    fn spool_dirs(&self, hosts: &[(String, bool)]) -> Vec<(PathBuf, bool)> {
        let Ok(users) = fs::read_dir(&self.data_root) else {
            return Vec::new();
        };
        users
            .flatten()
            .flat_map(|user| {
                hosts.iter().map(move |(package, storage)| {
                    (user.path().join(package).join(SPOOL_DIR), *storage)
                })
            })
            .filter(|(dir, _)| dir.is_dir())
            .collect()
    }

    /// Return the events ready to be ingested. Call this periodically.
    ///
    /// The hook appends to the spool file while we read, so a file is taken
    /// in two steps a call apart: first it is renamed (claimed), which makes
    /// the hook start a fresh one; only on the next call is the claimed file
    /// read and deleted. A writer that had the old file open across the
    /// rename finishes its append long before then, so nothing is lost.
    ///
    /// `hosts` are the packages whose data directories may hold a spool, and
    /// whether each belongs to a storage provider (see [`hosts`]).
    pub fn collect(&mut self, hosts: &[(String, bool)]) -> Vec<ProviderEvent> {
        let mut events = Vec::new();
        for (dir, storage_provider) in self.spool_dirs(hosts) {
            let claimed = dir.join(CLAIMED_FILE);
            if let Ok(text) = fs::read_to_string(&claimed) {
                events.extend(
                    text.lines()
                        .filter_map(|line| parse_line(line, storage_provider)),
                );
            }
            // Delete even if unreadable: a file we can't parse now won't
            // become parseable, and leaving it would block the spool.
            remove_if_exists(&claimed);
            let _ = fs::rename(dir.join(SPOOL_FILE), &claimed);
        }
        events
    }
}

/// The packages a spool may be found under: the provider packages and those
/// sharing a uid with them, as `sharing_uid` reports for a list of packages.
pub fn hosts(sharing_uid: impl Fn(&[&str]) -> Vec<String>) -> Vec<(String, bool)> {
    let mut hosts: Vec<(String, bool)> = Vec::new();
    for (packages, storage) in [(PROVIDER_PACKAGES, true), (PERSONAL_PACKAGES, false)] {
        let listed = packages.iter().map(|p| p.to_string());
        for package in listed.chain(sharing_uid(packages)) {
            if !hosts.iter().any(|(known, _)| *known == package) {
                hosts.push((package, storage));
            }
        }
    }
    hosts
}

fn remove_if_exists(path: &Path) {
    if let Err(e) = fs::remove_file(path)
        && e.kind() != std::io::ErrorKind::NotFound
    {
        eprintln!("bbd: cannot remove {}: {e}", path.display());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
        let hosts = hosts(|listed| {
            if listed.contains(&PERSONAL_PACKAGES[0]) {
                vec!["com.android.providers.blockednumber".to_string()]
            } else {
                Vec::new()
            }
        });
        let mut collect = move || spool.collect(&hosts);
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
