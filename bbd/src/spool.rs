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
}

pub fn parse_line(line: &str) -> Option<ProviderEvent> {
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

    fn spool_dirs(&self) -> Vec<PathBuf> {
        let Ok(users) = fs::read_dir(&self.data_root) else {
            return Vec::new();
        };
        users
            .flatten()
            .flat_map(|user| {
                PROVIDER_PACKAGES
                    .iter()
                    .map(move |package| user.path().join(package).join(SPOOL_DIR))
            })
            .filter(|dir| dir.is_dir())
            .collect()
    }

    /// Return the events ready to be ingested. Call this periodically.
    ///
    /// The hook appends to the spool file while we read, so a file is taken
    /// in two steps a call apart: first it is renamed (claimed), which makes
    /// the hook start a fresh one; only on the next call is the claimed file
    /// read and deleted. A writer that had the old file open across the
    /// rename finishes its append long before then, so nothing is lost.
    pub fn collect(&mut self) -> Vec<ProviderEvent> {
        let mut events = Vec::new();
        for dir in self.spool_dirs() {
            let claimed = dir.join(CLAIMED_FILE);
            if let Ok(text) = fs::read_to_string(&claimed) {
                events.extend(text.lines().filter_map(parse_line));
            }
            // Delete even if unreadable: a file we can't parse now won't
            // become parseable, and leaving it would block the spool.
            remove_if_exists(&claimed);
            let _ = fs::rename(dir.join(SPOOL_FILE), &claimed);
        }
        events
    }
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
    fn parses_queries_and_opens() {
        assert_eq!(
            parse_line("1\t1700000000\t10123\tq\t\t3214\tcontent://media/external/images/media\t"),
            Some(ProviderEvent {
                ts: 1_700_000_000,
                uid: 10123,
                op: Op::Query,
                read: false,
                write: false,
                items: 3214,
                uri: "content://media/external/images/media".into(),
                path: String::new(),
            })
        );
        let open = parse_line(
            "1\t1700000001\t10123\to\trw\t0\tcontent://media/external/images/media/7\t/storage/emulated/0/DCIM/a.jpg",
        )
        .unwrap();
        assert_eq!((open.op, open.read, open.write), (Op::Open, true, true));
        assert_eq!(open.path, "/storage/emulated/0/DCIM/a.jpg");
    }

    #[test]
    fn rejects_malformed_lines() {
        for line in [
            "",
            "garbage",
            "2\t1700000000\t10123\tq\t\t0\tcontent://x\t",
            "1\tnot-a-time\t10123\tq\t\t0\tcontent://x\t",
            "1\t1700000000\t10123\tx\t\t0\tcontent://x\t",
            "1\t1700000000\t10123\tq\t\t0\t\t",
            "1\t1700000000\t10123\tq",
        ] {
            assert_eq!(parse_line(line), None, "{line:?}");
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
        // First call only claims the file.
        assert!(spool.collect().is_empty());
        assert!(!dir.join(SPOOL_FILE).exists());
        // The hook keeps writing to a fresh file meanwhile.
        fs::write(dir.join(SPOOL_FILE), line(10002) + "broken line\n").unwrap();

        let uids = |events: Vec<ProviderEvent>| events.iter().map(|e| e.uid).collect::<Vec<_>>();
        assert_eq!(uids(spool.collect()), [10001]);
        assert_eq!(uids(spool.collect()), [10002]);
        assert!(spool.collect().is_empty());
        assert!(!dir.join(CLAIMED_FILE).exists());
        fs::remove_dir_all(root).unwrap();
    }
}
