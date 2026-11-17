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

//! SQLite storage.
//!
//! Opens are aggregated rather than logged one row each: a row is one
//! (hour, uid, path, access mode) with success/failure counters. An app that
//! reads the same photo 500 times in an hour costs one row.

use std::{collections::HashMap, path::Path};

use anyhow::Context as _;
use rusqlite::{Connection, OpenFlags, params};
use serde::Serialize;

pub const DEFAULT_DB: &str = "/data/adb/big_brother/bb.db";

/// Access mode bits, as stored in `access.mode`.
pub type Mode = u16;

pub const MODE_READ: Mode = 1;
pub const MODE_WRITE: Mode = 2;
pub const MODE_DIR: Mode = 4;
/// stat/access/readlink: the app learned the path exists and its metadata.
pub const MODE_PROBE: Mode = 8;
/// inotify: the app asked to be told when the path changes.
pub const MODE_WATCH: Mode = 16;
/// The access went through a system content provider (MediaStore, the
/// Storage Access Framework, downloads) rather than the app opening the file
/// itself. Combined with the read/write bits for opens.
pub const MODE_VIA_PROVIDER: Mode = 32;
/// A content provider query: the app listed entries without opening them.
pub const MODE_QUERY: Mode = 64;
/// A connection or datagram to a network address.
pub const MODE_CONNECT: Mode = 128;
/// A program the app started.
pub const MODE_EXEC: Mode = 256;
/// A request to a system service.
pub const MODE_CALL: Mode = 512;
/// Done by one of the app's isolated processes (a sandboxed renderer or the
/// like), attributed to the app. Combined with the other bits.
pub const MODE_ISOLATED: Mode = 1024;

/// What kind of information a recorded path exposes.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, clap::ValueEnum)]
pub enum Category {
    /// User files on shared storage.
    Storage,
    /// Hardware and device identifiers (/sys, selected /dev nodes).
    Device,
    /// System-wide kernel information (/proc/cpuinfo, /proc/net, ...).
    System,
    /// Another app's or the system's processes (/proc/<pid>).
    Process,
    /// Content provider collections the app queried (the media library,
    /// document trees, downloads), identified by URI rather than file path.
    Provider,
    /// Addresses on this device and the local network the app contacted,
    /// identified as `net://<address>:<port>`.
    Network,
    /// Programs the app started, identified as `exec:<command line>`.
    Command,
    /// Personal data kept by system content providers (contacts, messages,
    /// the call log, the calendar), identified by the collection's URI.
    Personal,
    /// What the app asked system services for (installed apps, clipboard,
    /// location...), identified as `binder:<interface>/<method>`.
    Service,
    /// Uses of sensitive permissions as the system recorded them (camera,
    /// microphone, location...), identified as `appop:<op name>`.
    Permission,
}

impl Category {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Storage => "storage",
            Self::Device => "device",
            Self::System => "system",
            Self::Process => "process",
            Self::Provider => "provider",
            Self::Network => "network",
            Self::Command => "command",
            Self::Personal => "personal",
            Self::Service => "service",
            Self::Permission => "permission",
        }
    }
}

/// Schema version stored in SQLite's `user_version`. Bump it and add a step
/// to `MIGRATIONS` whenever the schema changes.
const SCHEMA_VERSION: i32 = 3;

/// Most entries returned for one path's history.
const HISTORY_LIMIT: u32 = 2000;

/// How many of an app's folders the per-app summary names.
const TOP_FOLDERS: usize = 3;

/// Hours of records the timeline reads per query while it collects a page.
const TIMELINE_WINDOW_HOURS: i64 = 24;

/// Mode bits of accesses that only looked for a file rather than opened it.
const NOT_FILE_OPEN: Mode = MODE_DIR | MODE_PROBE | MODE_WATCH;

/// Mode bits of accesses that opened a file.
const FILE_OPEN: Mode = MODE_READ | MODE_WRITE;

/// `MIGRATIONS[n]` upgrades a database from version `n + 1` to `n + 2`.
const MIGRATIONS: &[&str] = &[
    // 1 -> 2: number of entries a provider query returned.
    "ALTER TABLE access ADD COLUMN items INTEGER NOT NULL DEFAULT 0;",
    // 2 -> 3: individual accesses, next to the hourly aggregates.
    EVENT_SCHEMA,
];

/// Individual accesses. `access` answers "what did this app touch" cheaply;
/// `event` answers "when exactly". A row is one second in which an app
/// touched a path in one way; several accesses within the same second share
/// it and are counted. Paths are stored once, in `path`, and referenced by
/// id, since the same path recurs in many rows.
const EVENT_SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS path (
    id   INTEGER PRIMARY KEY,
    path TEXT NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS event (
    uid     INTEGER NOT NULL,
    path_id INTEGER NOT NULL REFERENCES path (id),
    mode    INTEGER NOT NULL,   -- MODE_* bits
    ts      INTEGER NOT NULL,   -- unix time
    ok      INTEGER NOT NULL,
    fail    INTEGER NOT NULL,
    PRIMARY KEY (uid, path_id, mode, ts)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS event_ts ON event (ts);
";

/// The schema at `SCHEMA_VERSION`, for new databases.
const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS access (
    hour     INTEGER NOT NULL,   -- unix time / 3600
    uid      INTEGER NOT NULL,
    pkg      TEXT    NOT NULL,   -- package name at record time ('' if unknown)
    cat      TEXT    NOT NULL,   -- Category::as_str()
    path     TEXT    NOT NULL,   -- what was accessed: a canonical path, a content
                                 -- URI, 'net://...' or 'exec:...', by category
    mode     INTEGER NOT NULL,   -- MODE_* bits
    ok       INTEGER NOT NULL,   -- opens that succeeded
    fail     INTEGER NOT NULL,   -- opens that failed (denied, missing...)
    first_ts INTEGER NOT NULL,
    last_ts  INTEGER NOT NULL,
    items    INTEGER NOT NULL DEFAULT 0,  -- most entries one provider query returned
    PRIMARY KEY (hour, uid, path, mode)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS access_uid_last ON access (uid, last_ts);
CREATE INDEX IF NOT EXISTS access_last ON access (last_ts);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL) WITHOUT ROWID;
";

#[derive(Hash, PartialEq, Eq)]
pub struct Key {
    pub hour: i64,
    pub uid: u32,
    pub path: String,
    pub mode: Mode,
}

pub struct Agg {
    pub pkg: String,
    pub cat: Category,
    pub ok: u32,
    pub fail: u32,
    pub first_ts: i64,
    pub last_ts: i64,
    pub items: u32,
}

/// Counters persisted in `meta` so `bbd stats` can report on a running daemon.
#[derive(Default)]
pub struct Counters {
    /// Events received from the kernel.
    pub seen: u64,
    /// Events that matched a category and were recorded.
    pub kept: u64,
    /// Events whose absolute path could not be determined.
    pub unresolved: u64,
    /// Events the kernel side lost because the ring buffer was full.
    pub dropped: u64,
}

/// Identifies an `event` row.
#[derive(Hash, PartialEq, Eq)]
struct EventKey {
    uid: u32,
    path: String,
    mode: Mode,
    ts: i64,
}

pub struct Writer {
    conn: Connection,
    pending: HashMap<Key, Agg>,
    /// Individual accesses not yet written: (allowed, refused) per key.
    events: HashMap<EventKey, (u32, u32)>,
}

impl Writer {
    pub fn open(path: &Path) -> anyhow::Result<Self> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir).with_context(|| format!("creating {}", dir.display()))?;
        }
        let mut conn =
            Connection::open(path).with_context(|| format!("opening {}", path.display()))?;
        // WAL lets `bbd` query commands read while the daemon writes.
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        migrate(&mut conn)?;
        Ok(Self {
            conn,
            pending: HashMap::new(),
            events: HashMap::new(),
        })
    }

    #[allow(clippy::too_many_arguments)]
    pub fn record(
        &mut self,
        uid: u32,
        pkg: &str,
        cat: Category,
        path: String,
        mode: Mode,
        ok: bool,
        ts: i64,
        items: u32,
    ) {
        let counts = self
            .events
            .entry(EventKey {
                uid,
                path: path.clone(),
                mode,
                ts,
            })
            .or_default();
        if ok {
            counts.0 += 1;
        } else {
            counts.1 += 1;
        }

        let key = Key {
            hour: ts / 3600,
            uid,
            path,
            mode,
        };
        let agg = self.pending.entry(key).or_insert_with(|| Agg {
            pkg: pkg.to_string(),
            cat,
            ok: 0,
            fail: 0,
            first_ts: ts,
            last_ts: ts,
            items: 0,
        });
        agg.items = agg.items.max(items);
        if ok {
            agg.ok += 1;
        } else {
            agg.fail += 1;
        }
        // Events from different sources are not strictly ordered in time.
        agg.first_ts = agg.first_ts.min(ts);
        agg.last_ts = agg.last_ts.max(ts);
    }

    /// Write buffered rows and counters in one transaction.
    pub fn flush(&mut self, counters: &Counters, now: i64) -> anyhow::Result<()> {
        let tx = self.conn.transaction()?;
        {
            let mut upsert = tx.prepare_cached(
                "INSERT INTO access
                     (hour, uid, pkg, cat, path, mode, ok, fail, first_ts, last_ts, items)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
                 ON CONFLICT (hour, uid, path, mode) DO UPDATE SET
                     ok = ok + excluded.ok,
                     fail = fail + excluded.fail,
                     first_ts = min(first_ts, excluded.first_ts),
                     last_ts = max(last_ts, excluded.last_ts),
                     items = max(items, excluded.items)",
            )?;
            for (k, a) in &self.pending {
                upsert.execute(params![
                    k.hour,
                    k.uid,
                    a.pkg,
                    a.cat.as_str(),
                    k.path,
                    k.mode,
                    a.ok,
                    a.fail,
                    a.first_ts,
                    a.last_ts,
                    a.items
                ])?;
            }
            let mut intern = tx.prepare_cached("INSERT OR IGNORE INTO path (path) VALUES (?1)")?;
            let mut path_id = tx.prepare_cached("SELECT id FROM path WHERE path = ?1")?;
            let mut insert_event = tx.prepare_cached(
                "INSERT INTO event (uid, path_id, mode, ts, ok, fail)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                 ON CONFLICT (uid, path_id, mode, ts) DO UPDATE SET
                     ok = ok + excluded.ok,
                     fail = fail + excluded.fail",
            )?;
            // Most events of a batch share a handful of paths.
            let mut ids: HashMap<&str, i64> = HashMap::new();
            for (k, (ok, fail)) in &self.events {
                let id = match ids.get(k.path.as_str()) {
                    Some(id) => *id,
                    None => {
                        intern.execute(params![k.path])?;
                        let id: i64 = path_id.query_row(params![k.path], |r| r.get(0))?;
                        ids.insert(&k.path, id);
                        id
                    }
                };
                insert_event.execute(params![k.uid, id, k.mode, k.ts, ok, fail])?;
            }

            let mut meta = tx.prepare_cached(
                "INSERT INTO meta (key, value) VALUES (?1, ?2)
                 ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            )?;
            for (key, value) in [
                ("events_seen", counters.seen as i64),
                ("events_kept", counters.kept as i64),
                ("events_unresolved", counters.unresolved as i64),
                ("events_dropped", counters.dropped as i64),
                ("last_flush", now),
            ] {
                meta.execute(params![key, value])?;
            }
        }
        tx.commit()?;
        // Only now: if anything above failed, the rows stay buffered and
        // are written by the next flush.
        self.pending.clear();
        self.events.clear();
        Ok(())
    }

    pub fn set_meta(&self, key: &str, value: i64) -> anyhow::Result<()> {
        self.conn.execute(
            "INSERT INTO meta (key, value) VALUES (?1, ?2)
             ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    /// Delete every row recorded for the given packages. Returns the number
    /// removed. Used to drop records that an earlier version kept but the
    /// current rules exclude.
    pub fn delete_packages(&self, packages: &[&str]) -> anyhow::Result<usize> {
        let mut removed = 0;
        for package in packages {
            self.conn.execute(
                "DELETE FROM event WHERE uid IN (SELECT uid FROM access WHERE pkg = ?1)",
                params![package],
            )?;
            removed += self
                .conn
                .execute("DELETE FROM access WHERE pkg = ?1", params![package])?;
        }
        Ok(removed)
    }

    /// Delete device, system and process rows whose path the current rules no
    /// longer consider worth recording. Returns the number removed.
    pub fn delete_untopical(&self) -> anyhow::Result<usize> {
        let stale: Vec<String> = self
            .conn
            .prepare(
                "SELECT DISTINCT path FROM access WHERE cat IN ('device', 'system', 'process')",
            )?
            .query_map([], |r| r.get::<_, String>(0))?
            .filter_map(Result::ok)
            .filter(|path| crate::rules::topic_of(path).is_none())
            .collect();
        let mut removed = 0;
        for path in &stale {
            removed += self.conn.execute(
                "DELETE FROM access WHERE path = ?1 AND cat IN ('device', 'system', 'process')",
                params![path],
            )?;
        }
        for path in &stale {
            self.conn.execute(
                "DELETE FROM event WHERE path_id IN (SELECT id FROM path WHERE path = ?1)",
                params![path],
            )?;
        }
        self.delete_unused_paths()?;
        Ok(removed)
    }

    /// Delete rows older than `days`. Returns the number removed.
    pub fn prune(&self, days: u32, now: i64) -> anyhow::Result<usize> {
        let cutoff = now - i64::from(days) * 86_400;
        self.conn
            .execute("DELETE FROM event WHERE ts < ?1", params![cutoff])?;
        self.delete_unused_paths()?;
        Ok(self
            .conn
            .execute("DELETE FROM access WHERE last_ts < ?1", params![cutoff])?)
    }

    /// Drop paths that no event refers to any more.
    fn delete_unused_paths(&self) -> anyhow::Result<()> {
        self.conn.execute(
            "DELETE FROM path WHERE id NOT IN (SELECT DISTINCT path_id FROM event)",
            [],
        )?;
        Ok(())
    }
}

/// Bring the database to `SCHEMA_VERSION`: create it if new, otherwise apply
/// the migration steps it is missing. Refuses a database written by a newer
/// build rather than guess at its layout.
fn migrate(conn: &mut Connection) -> anyhow::Result<()> {
    // One transaction for the schema change and the version that records it:
    // if the process dies half way, neither is applied and the next start
    // repeats the whole step.
    let conn = conn.transaction()?;
    let has_tables: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM sqlite_master WHERE name = 'access')",
        [],
        |r| r.get(0),
    )?;
    let mut version: i32 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
    if !has_tables {
        conn.execute_batch(SCHEMA)?;
        conn.execute_batch(EVENT_SCHEMA)?;
        version = SCHEMA_VERSION;
    } else if version == 0 {
        // Databases created before versioning existed have the first layout.
        version = 1;
    }
    anyhow::ensure!(
        version <= SCHEMA_VERSION,
        "database is version {version}, newer than this build supports ({SCHEMA_VERSION})"
    );
    for step in &MIGRATIONS[(version - 1) as usize..] {
        conn.execute_batch(step)?;
    }
    conn.pragma_update(None, "user_version", SCHEMA_VERSION)?;
    conn.commit()?;
    Ok(())
}

// ---- read side -------------------------------------------------------------

/// The top-level folder most of `paths` are in; a tie goes to the name that
/// sorts first, so the result is stable.
fn most_common_folder(paths: &std::collections::HashSet<String>) -> Option<String> {
    let mut counts: HashMap<&str, u32> = HashMap::new();
    for path in paths {
        if let Some(folder) = crate::paths::top_level_folder(path) {
            *counts.entry(folder).or_default() += 1;
        }
    }
    counts
        .into_iter()
        .max_by(|a, b| a.1.cmp(&b.1).then_with(|| b.0.cmp(a.0)))
        .map(|(folder, _)| folder.to_string())
}

#[derive(Serialize)]
pub struct AppRow {
    pub uid: u32,
    pub pkg: String,
    /// Distinct paths touched, per category.
    pub storage: i64,
    pub device: i64,
    pub system: i64,
    pub process: i64,
    pub provider: i64,
    pub network: i64,
    pub command: i64,
    pub personal: i64,
    pub service: i64,
    pub permission: i64,
    /// Files on shared storage the app opened successfully: what it actually
    /// read or wrote, as opposed to looked for or was refused.
    pub files_read: i64,
    /// Top-level shared-storage folders of the files it read (of everything
    /// it touched, if it read none), most-touched first, limited to
    /// `TOP_FOLDERS`.
    pub folders: Vec<String>,
    /// How many top-level folders it touched in total.
    pub folder_count: usize,
    /// Topics (see `rules::topic`) of everything it did other than touch
    /// user files, sorted.
    pub topics: Vec<&'static str>,
    pub ok: i64,
    pub fail: i64,
    pub last_ts: i64,
}

#[derive(Serialize)]
pub struct FileRow {
    pub uid: u32,
    pub pkg: String,
    pub cat: String,
    pub path: String,
    pub mode: Mode,
    pub ok: i64,
    pub fail: i64,
    pub first_ts: i64,
    pub last_ts: i64,
    /// Most entries a single provider query returned; 0 for everything else.
    pub items: i64,
    /// What the record means (see `rules::topic`); absent for user files and
    /// provider URIs.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub topic: Option<&'static str>,
}

/// One entry in a path's history: an access (or several in the same second),
/// or, for records made before individual accesses were kept, an hour's worth
/// of them with only the first and last time known.
#[derive(Serialize)]
pub struct HistoryRow {
    pub first_ts: i64,
    /// Equal to `first_ts` for an individual access.
    pub last_ts: i64,
    pub mode: Mode,
    pub ok: i64,
    pub fail: i64,
}

/// One app's activity of one kind within one hour: what the timeline lists.
#[derive(Serialize)]
pub struct TimelineRow {
    /// Start of the hour, as unix time.
    pub hour_ts: i64,
    pub uid: u32,
    pub pkg: String,
    pub cat: String,
    /// What the records mean (see `rules::topic`); absent for user files and
    /// provider URIs.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub topic: Option<&'static str>,
    /// Distinct paths touched.
    pub paths: i64,
    /// Distinct user files opened successfully; 0 for other categories.
    pub files_read: i64,
    pub ok: i64,
    pub fail: i64,
    /// The mode bits of every access, combined.
    pub modes: Mode,
    /// Most entries a single provider query returned.
    pub items: i64,
    /// For user files, the top-level folder most of them are in: of the
    /// files read, or of everything touched if none was.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub folder: Option<String>,
    pub first_ts: i64,
    pub last_ts: i64,
}

/// A page of the timeline.
#[derive(Serialize)]
pub struct Timeline {
    pub entries: Vec<TimelineRow>,
    /// Pass as `before` to read the next page; absent when there is no more.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_before: Option<i64>,
}

/// A timeline entry while it is being collected.
#[derive(Default)]
struct TimelineAcc {
    pkg: String,
    paths: std::collections::HashSet<String>,
    read: std::collections::HashSet<String>,
    ok: i64,
    fail: i64,
    modes: Mode,
    items: i64,
    first_ts: i64,
    last_ts: i64,
}

#[derive(Serialize)]
pub struct Stats {
    pub rows: i64,
    pub apps: i64,
    pub oldest_ts: Option<i64>,
    pub newest_ts: Option<i64>,
    pub meta: HashMap<String, i64>,
}

/// A list to match a column against with `instr(list, ',' || column || ',')`:
/// ",a,b,", or None, matching everything, when there is nothing in it.
fn any_of(values: impl Iterator<Item = String>) -> Option<String> {
    let values: Vec<String> = values.collect();
    (!values.is_empty()).then(|| format!(",{},", values.join(",")))
}

pub struct Reader {
    conn: Connection,
}

impl Reader {
    pub fn open(path: &Path) -> anyhow::Result<Self> {
        let conn = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)
            .with_context(|| format!("opening {} (has the daemon run yet?)", path.display()))?;
        Ok(Self { conn })
    }

    /// One row per uid, most recently active first.
    pub fn apps(&self, since: i64) -> anyhow::Result<Vec<AppRow>> {
        let mut stmt = self.conn.prepare(
            "SELECT uid, max(pkg),
                    count(DISTINCT CASE WHEN cat = 'storage' THEN path END),
                    count(DISTINCT CASE WHEN cat = 'device' THEN path END),
                    count(DISTINCT CASE WHEN cat = 'system' THEN path END),
                    count(DISTINCT CASE WHEN cat = 'process' THEN path END),
                    count(DISTINCT CASE WHEN cat = 'provider' THEN path END),
                    count(DISTINCT CASE WHEN cat = 'network' THEN path END),
                    count(DISTINCT CASE WHEN cat = 'command' THEN path END),
                    count(DISTINCT CASE WHEN cat = 'personal' THEN path END),
                    count(DISTINCT CASE WHEN cat = 'service' THEN path END),
                    count(DISTINCT CASE WHEN cat = 'permission' THEN path END),
                    count(DISTINCT CASE WHEN cat = 'storage' AND ok > 0 AND (mode & ?2) = 0
                                             AND (mode & ?3) != 0 THEN path END),
                    sum(ok), sum(fail), max(last_ts)
             FROM access WHERE last_ts >= ?1
             GROUP BY uid ORDER BY max(last_ts) DESC",
        )?;
        // A read is a successful open of a file: not a directory listing, a
        // stat-style probe or a watch.
        let rows = stmt.query_map(params![since, NOT_FILE_OPEN, FILE_OPEN], |r| {
            Ok(AppRow {
                uid: r.get(0)?,
                pkg: r.get(1)?,
                storage: r.get(2)?,
                device: r.get(3)?,
                system: r.get(4)?,
                process: r.get(5)?,
                provider: r.get(6)?,
                network: r.get(7)?,
                command: r.get(8)?,
                personal: r.get(9)?,
                service: r.get(10)?,
                permission: r.get(11)?,
                files_read: r.get(12)?,
                folders: Vec::new(),
                folder_count: 0,
                topics: Vec::new(),
                ok: r.get(13)?,
                fail: r.get(14)?,
                last_ts: r.get(15)?,
            })
        })?;
        let mut apps: Vec<AppRow> = rows.collect::<Result<_, _>>()?;

        // Which folders each app's files are in: distinct paths per top-level
        // folder, counted separately for files it read and for everything.
        let mut read: HashMap<u32, HashMap<String, u32>> = HashMap::new();
        let mut touched: HashMap<u32, HashMap<String, u32>> = HashMap::new();
        let mut stmt = self.conn.prepare(
            "SELECT uid, path, max(ok > 0 AND (mode & ?2) = 0 AND (mode & ?3) != 0)
             FROM access WHERE cat = 'storage' AND last_ts >= ?1
             GROUP BY uid, path",
        )?;
        let mut paths = stmt.query(params![since, NOT_FILE_OPEN, FILE_OPEN])?;
        while let Some(row) = paths.next()? {
            let (uid, path, was_read): (u32, String, bool) =
                (row.get(0)?, row.get(1)?, row.get(2)?);
            let Some(folder) = crate::paths::top_level_folder(&path) else {
                continue;
            };
            *touched
                .entry(uid)
                .or_default()
                .entry(folder.to_string())
                .or_default() += 1;
            if was_read {
                *read
                    .entry(uid)
                    .or_default()
                    .entry(folder.to_string())
                    .or_default() += 1;
            }
        }
        for app in &mut apps {
            // Name the folders of what was read; for an app that read nothing,
            // the folders it tried.
            let Some(folders) = read.remove(&app.uid).or_else(|| touched.remove(&app.uid)) else {
                continue;
            };
            app.folder_count = folders.len();
            let mut ranked: Vec<(String, u32)> = folders.into_iter().collect();
            // Most-touched first; ties alphabetically so the output is stable.
            ranked.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
            app.folders = ranked
                .into_iter()
                .take(TOP_FOLDERS)
                .map(|(name, _)| name)
                .collect();
        }

        // What kinds of information each app went after, besides user files.
        let mut topics: HashMap<u32, std::collections::BTreeSet<&'static str>> = HashMap::new();
        let mut stmt = self.conn.prepare(
            "SELECT DISTINCT uid, path FROM access
             WHERE cat NOT IN ('storage', 'provider') AND last_ts >= ?1",
        )?;
        let mut paths = stmt.query(params![since])?;
        while let Some(row) = paths.next()? {
            let (uid, path): (u32, String) = (row.get(0)?, row.get(1)?);
            if let Some(topic) = crate::rules::topic_of(&path) {
                topics.entry(uid).or_default().insert(topic);
            }
        }
        for app in &mut apps {
            if let Some(found) = topics.remove(&app.uid) {
                app.topics = found.into_iter().collect();
            }
        }
        Ok(apps)
    }

    /// Paths, aggregated across hours, most recent first. `uids` and `cats`
    /// (any of them), `prefix` and `contains` (a substring, ignoring ASCII
    /// case) narrow the result when given.
    #[allow(clippy::too_many_arguments)]
    pub fn files(
        &self,
        uids: &[u32],
        cats: &[Category],
        prefix: Option<&str>,
        contains: Option<&str>,
        since: i64,
        limit: u32,
        offset: u32,
    ) -> anyhow::Result<Vec<FileRow>> {
        let mut stmt = self.conn.prepare(
            "SELECT uid, max(pkg), cat, path, mode, sum(ok), sum(fail), min(first_ts), max(last_ts),
                    max(items)
             FROM access
             WHERE last_ts >= ?1
               AND (?2 IS NULL OR instr(?2, ',' || uid || ',') > 0)
               AND (?3 IS NULL OR substr(path, 1, length(?3)) = ?3)
               AND (?6 IS NULL OR instr(?6, ',' || cat || ',') > 0)
               AND (?7 IS NULL OR instr(lower(path), lower(?7)) > 0)
             GROUP BY uid, path, mode
             ORDER BY max(last_ts) DESC, path, uid, mode
             LIMIT ?4 OFFSET ?5",
        )?;
        let uids = any_of(uids.iter().map(u32::to_string));
        let cats = any_of(cats.iter().map(|c| c.as_str().to_string()));
        let rows = stmt.query_map(
            params![since, uids, prefix, limit, offset, cats, contains],
            |r| {
                let path: String = r.get(3)?;
                Ok(FileRow {
                    uid: r.get(0)?,
                    pkg: r.get(1)?,
                    cat: r.get(2)?,
                    topic: crate::rules::topic_of(&path),
                    path,
                    mode: r.get(4)?,
                    ok: r.get(5)?,
                    fail: r.get(6)?,
                    first_ts: r.get(7)?,
                    last_ts: r.get(8)?,
                    items: r.get(9)?,
                })
            },
        )?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// When one app touched one path, most recent first.
    ///
    /// Individual accesses come first. Hours recorded before those were kept
    /// only have aggregates, which follow as one entry per hour and mode.
    pub fn history(&self, uid: u32, path: &str, since: i64) -> anyhow::Result<Vec<HistoryRow>> {
        let mut stmt = self.conn.prepare(
            "SELECT e.ts, e.ts, e.mode, e.ok, e.fail
             FROM event e JOIN path p ON p.id = e.path_id
             WHERE e.uid = ?1 AND p.path = ?2 AND e.ts >= ?3
             ORDER BY e.ts DESC, e.mode
             LIMIT ?4",
        )?;
        let to_row = |r: &rusqlite::Row<'_>| {
            Ok(HistoryRow {
                first_ts: r.get(0)?,
                last_ts: r.get(1)?,
                mode: r.get(2)?,
                ok: r.get(3)?,
                fail: r.get(4)?,
            })
        };
        let mut rows: Vec<HistoryRow> = stmt
            .query_map(params![uid, path, since, HISTORY_LIMIT], to_row)?
            .collect::<Result<_, _>>()?;

        // Aggregates for the hours before the first individual access. The
        // hour that access falls in is covered by the events.
        let first_event_hour = rows.last().map_or(i64::MAX, |row| row.first_ts / 3600);
        let mut stmt = self.conn.prepare(
            "SELECT first_ts, last_ts, mode, ok, fail
             FROM access
             WHERE uid = ?1 AND path = ?2 AND last_ts >= ?3 AND hour < ?4
             ORDER BY hour DESC, mode
             LIMIT ?5",
        )?;
        let remaining = HISTORY_LIMIT.saturating_sub(rows.len() as u32);
        let older = stmt.query_map(
            params![uid, path, since, first_event_hour, remaining],
            to_row,
        )?;
        for row in older {
            rows.push(row?);
        }
        Ok(rows)
    }

    /// The histories of several paths of one app, merged, most recent first.
    pub fn history_of(
        &self,
        uid: u32,
        paths: &[String],
        since: i64,
    ) -> anyhow::Result<Vec<HistoryRow>> {
        let mut rows = Vec::new();
        for path in paths {
            rows.extend(self.history(uid, path, since)?);
        }
        rows.sort_by(|a, b| {
            b.last_ts
                .cmp(&a.last_ts)
                .then_with(|| b.first_ts.cmp(&a.first_ts))
        });
        rows.truncate(HISTORY_LIMIT as usize);
        Ok(rows)
    }

    /// Each app's activity per hour and kind (category and topic), most
    /// recent hour first, for hours that start before `before` and end after
    /// `since`. Records are read a day at a time; reading stops after the
    /// day in which `limit` entries are reached, so an hour is never split
    /// across pages.
    pub fn timeline(&self, since: i64, before: i64, limit: usize) -> anyhow::Result<Timeline> {
        let oldest: Option<i64> = self.conn.query_row(
            "SELECT min(hour) FROM access WHERE last_ts >= ?1",
            params![since],
            |r| r.get(0),
        )?;
        let Some(oldest) = oldest else {
            return Ok(Timeline {
                entries: Vec::new(),
                next_before: None,
            });
        };
        let floor = oldest.max(since.div_euclid(3600));
        // Hours whose start is before `before`.
        let mut end = (before - 1).div_euclid(3600) + 1;
        let mut entries = Vec::new();
        let mut stmt = self.conn.prepare(
            "SELECT hour, uid, pkg, cat, path, mode, ok, fail, first_ts, last_ts, items
             FROM access
             WHERE hour >= ?1 AND hour < ?2 AND last_ts >= ?3",
        )?;
        while end > floor {
            let start = (end - TIMELINE_WINDOW_HOURS).max(floor);
            let mut found: HashMap<(i64, u32, String, Option<&'static str>), TimelineAcc> =
                HashMap::new();
            let mut rows = stmt.query(params![start, end, since])?;
            while let Some(row) = rows.next()? {
                let (hour, uid, pkg, cat, path): (i64, u32, String, String, String) = (
                    row.get(0)?,
                    row.get(1)?,
                    row.get(2)?,
                    row.get(3)?,
                    row.get(4)?,
                );
                let (mode, ok, fail, first_ts, last_ts, items): (Mode, i64, i64, i64, i64, i64) = (
                    row.get(5)?,
                    row.get(6)?,
                    row.get(7)?,
                    row.get(8)?,
                    row.get(9)?,
                    row.get(10)?,
                );
                let topic = crate::rules::topic_of(&path);
                let is_storage = cat == Category::Storage.as_str();
                let acc = found
                    .entry((hour, uid, cat, topic))
                    .or_insert_with(|| TimelineAcc {
                        first_ts,
                        last_ts,
                        ..TimelineAcc::default()
                    });
                if acc.pkg.is_empty() {
                    acc.pkg = pkg;
                }
                if is_storage && ok > 0 && mode & NOT_FILE_OPEN == 0 && mode & FILE_OPEN != 0 {
                    acc.read.insert(path.clone());
                }
                acc.paths.insert(path);
                acc.ok += ok;
                acc.fail += fail;
                acc.modes |= mode;
                acc.items = acc.items.max(items);
                acc.first_ts = acc.first_ts.min(first_ts);
                acc.last_ts = acc.last_ts.max(last_ts);
            }
            let mut window: Vec<TimelineRow> = found
                .into_iter()
                .map(|((hour, uid, cat, topic), acc)| {
                    let folder = if cat == Category::Storage.as_str() {
                        most_common_folder(if acc.read.is_empty() {
                            &acc.paths
                        } else {
                            &acc.read
                        })
                    } else {
                        None
                    };
                    TimelineRow {
                        hour_ts: hour * 3600,
                        uid,
                        pkg: acc.pkg,
                        cat,
                        topic,
                        paths: acc.paths.len() as i64,
                        files_read: acc.read.len() as i64,
                        ok: acc.ok,
                        fail: acc.fail,
                        modes: acc.modes,
                        items: acc.items,
                        folder,
                        first_ts: acc.first_ts,
                        last_ts: acc.last_ts,
                    }
                })
                .collect();
            // Most recent hour first; within it, most recent activity first,
            // with a fixed order for ties so pages are stable.
            window.sort_by(|a, b| {
                b.hour_ts
                    .cmp(&a.hour_ts)
                    .then_with(|| b.last_ts.cmp(&a.last_ts))
                    .then_with(|| a.uid.cmp(&b.uid))
                    .then_with(|| a.cat.cmp(&b.cat))
                    .then_with(|| a.topic.cmp(&b.topic))
            });
            entries.extend(window);
            end = start;
            if entries.len() >= limit && end > floor {
                return Ok(Timeline {
                    entries,
                    next_before: Some(end * 3600),
                });
            }
        }
        Ok(Timeline {
            entries,
            next_before: None,
        })
    }

    pub fn stats(&self) -> anyhow::Result<Stats> {
        let (rows, apps, oldest_ts, newest_ts) = self.conn.query_row(
            "SELECT count(*), count(DISTINCT uid), min(first_ts), max(last_ts) FROM access",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
        )?;
        let mut stmt = self.conn.prepare("SELECT key, value FROM meta")?;
        let meta = stmt
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<Result<_, _>>()?;
        Ok(Stats {
            rows,
            apps,
            oldest_ts,
            newest_ts,
            meta,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aggregates_and_queries() {
        let dir = std::env::temp_dir().join(format!("bbd-test-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();

        let t = 1_000_000 * 3600; // start of an hour bucket
        let photo = "/storage/emulated/0/DCIM/a.jpg".to_string();
        let st = Category::Storage;
        w.record(10100, "com.a", st, photo.clone(), MODE_READ, true, t, 0);
        w.record(10100, "com.a", st, photo.clone(), MODE_READ, true, t + 5, 0);
        w.record(
            10100,
            "com.a",
            st,
            photo.clone(),
            MODE_READ,
            false,
            t + 9,
            0,
        );
        // Same path in the next hour: separate row, merged again at query time.
        w.record(
            10100,
            "com.a",
            st,
            photo.clone(),
            MODE_READ,
            true,
            t + 3600,
            0,
        );
        w.record(
            10100,
            "com.a",
            Category::System,
            "/proc/cpuinfo".into(),
            MODE_READ,
            true,
            t + 2,
            0,
        );
        w.record(
            10200,
            "com.b",
            st,
            "/storage/emulated/0/Download".into(),
            MODE_READ | MODE_DIR,
            true,
            t + 1,
            0,
        );
        w.flush(
            &Counters {
                seen: 9,
                kept: 5,
                unresolved: 1,
                dropped: 0,
            },
            t + 10,
        )
        .unwrap();
        // A second flush into an existing row must add, not replace.
        w.record(
            10100,
            "com.a",
            st,
            photo.clone(),
            MODE_READ,
            true,
            t + 20,
            0,
        );
        w.flush(
            &Counters {
                seen: 10,
                kept: 6,
                unresolved: 1,
                dropped: 2,
            },
            t + 30,
        )
        .unwrap();

        let r = Reader::open(&db).unwrap();
        let apps = r.apps(0).unwrap();
        assert_eq!(apps.len(), 2);
        assert_eq!(
            (apps[0].uid, apps[0].storage, apps[0].system),
            (10100, 1, 1)
        );
        assert_eq!((apps[0].device, apps[0].ok, apps[0].fail), (0, 5, 1));
        assert_eq!(
            (apps[0].folders.as_slice(), apps[0].folder_count),
            (&["DCIM".to_string()][..], 1)
        );
        // The photo was opened successfully; the Download folder was only listed.
        assert_eq!((apps[0].files_read, apps[1].files_read), (1, 0));
        assert_eq!(apps[1].folders, ["Download"]);
        assert_eq!(apps[0].topics, ["cpu_model"]);
        assert!(apps[1].topics.is_empty());

        assert_eq!(
            r.files(&[10100], &[], None, None, 0, 50, 0).unwrap().len(),
            2
        );
        let files = r.files(&[10100], &[st], None, None, 0, 50, 0).unwrap();
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].cat, "storage");
        assert_eq!(files[0].topic, None);
        let system = r
            .files(&[10100], &[Category::System], None, None, 0, 50, 0)
            .unwrap();
        assert_eq!(system[0].topic, Some("cpu_model"));
        assert_eq!((files[0].ok, files[0].fail), (4, 1));
        assert_eq!((files[0].first_ts, files[0].last_ts), (t, t + 3600));

        assert_eq!(
            r.files(
                &[],
                &[],
                Some("/storage/emulated/0/Download"),
                None,
                0,
                50,
                0
            )
            .unwrap()
            .len(),
            1
        );
        assert_eq!(
            r.files(&[], &[Category::System], None, None, 0, 50, 0)
                .unwrap()
                .len(),
            1
        );
        assert_eq!(
            r.files(&[], &[], None, None, t + 3600, 50, 0)
                .unwrap()
                .len(),
            1
        );

        // Every access to the photo, most recent first.
        let history = r.history(10100, &photo, 0).unwrap();
        assert_eq!(history[0].first_ts, t + 3600);
        assert_eq!(history.last().unwrap().first_ts, t);
        assert!(history.iter().all(|h| h.first_ts == h.last_ts));
        assert!(history.windows(2).all(|w| w[0].first_ts >= w[1].first_ts));
        let total = |f: fn(&HistoryRow) -> i64| history.iter().map(f).sum::<i64>();
        assert_eq!((total(|h| h.ok), total(|h| h.fail)), (4, 1));
        assert_eq!(r.history(10100, &photo, t + 3600).unwrap().len(), 1);
        assert!(r.history(10200, &photo, 0).unwrap().is_empty());

        let stats = r.stats().unwrap();
        assert_eq!((stats.rows, stats.apps), (4, 2));
        assert_eq!(stats.meta["events_seen"], 10);
        assert_eq!(stats.meta["events_dropped"], 2);

        assert_eq!(w.delete_packages(&["com.b", "com.none"]).unwrap(), 1);
        // A path the rules don't (or no longer) record is dropped; others stay.
        w.record(
            10100,
            "com.a",
            Category::System,
            "/proc/meminfo".into(),
            MODE_READ,
            true,
            t,
            0,
        );
        w.flush(&Counters::default(), t).unwrap();
        assert_eq!(w.delete_untopical().unwrap(), 1);
        assert_eq!(w.delete_untopical().unwrap(), 0);
        assert_eq!(w.prune(1, t + 3 * 86_400).unwrap(), 3);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_failed_flush_keeps_the_rows_for_the_next_one() {
        let dir = std::env::temp_dir().join(format!("bbd-test-flush-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        let t = 3_000_000 * 3600;
        w.record(
            10100,
            "com.a",
            Category::System,
            "/proc/cpuinfo".into(),
            MODE_READ,
            true,
            t,
            0,
        );

        // Make the write fail, as a full disk or a locked database would.
        w.conn
            .execute_batch("ALTER TABLE access RENAME TO access_gone")
            .unwrap();
        assert!(w.flush(&Counters::default(), t).is_err());
        w.conn
            .execute_batch("ALTER TABLE access_gone RENAME TO access")
            .unwrap();

        w.flush(&Counters::default(), t + 1).unwrap();
        let files = Reader::open(&db)
            .unwrap()
            .files(&[], &[], None, None, 0, 10, 0)
            .unwrap();
        assert_eq!((files.len(), files[0].ok), (1, 1));
        // And they are not written a second time.
        w.flush(&Counters::default(), t + 2).unwrap();
        let files = Reader::open(&db)
            .unwrap()
            .files(&[], &[], None, None, 0, 10, 0)
            .unwrap();
        assert_eq!(files[0].ok, 1);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn accesses_in_the_same_second_share_an_event() {
        let dir = std::env::temp_dir().join(format!("bbd-test-events-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        let t = 4_000_000 * 3600;
        let file = "/storage/emulated/0/Download/a.pdf".to_string();
        for _ in 0..500 {
            w.record(
                10100,
                "com.a",
                Category::Storage,
                file.clone(),
                MODE_READ,
                true,
                t,
                0,
            );
        }
        w.record(
            10100,
            "com.a",
            Category::Storage,
            file.clone(),
            MODE_READ,
            false,
            t,
            0,
        );
        w.record(
            10100,
            "com.a",
            Category::Storage,
            file.clone(),
            MODE_PROBE,
            true,
            t,
            0,
        );
        w.flush(&Counters::default(), t).unwrap();
        // The same second again, in a later flush: counted, not duplicated.
        w.record(
            10100,
            "com.a",
            Category::Storage,
            file.clone(),
            MODE_READ,
            true,
            t,
            0,
        );
        w.flush(&Counters::default(), t + 1).unwrap();

        let history = Reader::open(&db).unwrap().history(10100, &file, 0).unwrap();
        let rows: Vec<(Mode, i64, i64)> = history.iter().map(|h| (h.mode, h.ok, h.fail)).collect();
        assert_eq!(rows, [(MODE_READ, 501, 1), (MODE_PROBE, 1, 0)]);

        // Pruning removes old events and the paths nothing refers to any more.
        w.prune(1, t + 3 * 86_400).unwrap();
        let paths: i64 = w
            .conn
            .query_row("SELECT count(*) FROM path", [], |r| r.get(0))
            .unwrap();
        assert_eq!(paths, 0);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn provider_queries_keep_the_largest_result() {
        let dir = std::env::temp_dir().join(format!("bbd-test-items-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        let uri = "content://media/external/images/media".to_string();
        let t = 2_000_000 * 3600;
        w.record(
            10100,
            "com.a",
            Category::Provider,
            uri.clone(),
            MODE_QUERY,
            true,
            t,
            40,
        );
        w.record(
            10100,
            "com.a",
            Category::Provider,
            uri.clone(),
            MODE_QUERY,
            true,
            t + 1,
            3214,
        );
        w.flush(&Counters::default(), t + 2).unwrap();
        w.record(
            10100,
            "com.a",
            Category::Provider,
            uri,
            MODE_QUERY,
            true,
            t + 3,
            12,
        );
        w.flush(&Counters::default(), t + 4).unwrap();

        let r = Reader::open(&db).unwrap();
        let files = r
            .files(&[10100], &[Category::Provider], None, None, 0, 10, 0)
            .unwrap();
        assert_eq!((files.len(), files[0].ok, files[0].items), (1, 3, 3214));
        assert_eq!(r.apps(0).unwrap()[0].provider, 1);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn commands_and_destinations_are_counted_and_explained() {
        let dir = std::env::temp_dir().join(format!("bbd-test-kinds-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        let t = 5_000_000 * 3600;
        for (cat, subject, mode, ok) in [
            (Category::Command, "exec:su", MODE_EXEC, false),
            (
                Category::Command,
                "exec:getprop ro.serialno",
                MODE_EXEC,
                true,
            ),
            (
                Category::Network,
                "net://127.0.0.1:5555",
                MODE_CONNECT,
                true,
            ),
            (Category::Personal, "content://sms/inbox", MODE_QUERY, true),
            (
                Category::Service,
                "binder:android.content.IClipboard/getPrimaryClip",
                MODE_CALL,
                true,
            ),
        ] {
            w.record(10100, "com.a", cat, subject.into(), mode, ok, t, 0);
        }
        w.flush(&Counters::default(), t).unwrap();

        let r = Reader::open(&db).unwrap();
        let apps = r.apps(0).unwrap();
        assert_eq!(
            (
                apps[0].command,
                apps[0].network,
                apps[0].personal,
                apps[0].service
            ),
            (2, 1, 1, 1)
        );
        assert_eq!(
            apps[0].topics,
            [
                "clipboard",
                "local_ports",
                "messages",
                "root_check",
                "system_properties"
            ]
        );
        let commands = r
            .files(&[10100], &[Category::Command], None, None, 0, 10, 0)
            .unwrap();
        let su = commands.iter().find(|c| c.path == "exec:su").unwrap();
        assert_eq!((su.mode, su.ok, su.fail), (MODE_EXEC, 0, 1));
        assert_eq!(su.topic, Some("root_check"));
        // The cleanup of records the rules no longer keep leaves them alone.
        assert_eq!(w.delete_untopical().unwrap(), 0);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn upgrades_a_version_1_database() {
        let dir = std::env::temp_dir().join(format!("bbd-test-migrate-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = dir.join("bb.db");
        {
            // The layout before versioning: no `items` column, user_version 0.
            let conn = Connection::open(&db).unwrap();
            conn.execute_batch(
                "CREATE TABLE access (
                     hour INTEGER NOT NULL, uid INTEGER NOT NULL, pkg TEXT NOT NULL,
                     cat TEXT NOT NULL, path TEXT NOT NULL, mode INTEGER NOT NULL,
                     ok INTEGER NOT NULL, fail INTEGER NOT NULL,
                     first_ts INTEGER NOT NULL, last_ts INTEGER NOT NULL,
                     PRIMARY KEY (hour, uid, path, mode)) WITHOUT ROWID;
                 CREATE TABLE meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL) WITHOUT ROWID;
                 INSERT INTO access VALUES (1, 10100, 'com.a', 'system', '/proc/cpuinfo', 1, 2, 0, 3600, 3700);",
            )
            .unwrap();
        }
        // Opening migrates in place and keeps the existing rows.
        drop(Writer::open(&db).unwrap());
        let r = Reader::open(&db).unwrap();
        let files = r.files(&[], &[], None, None, 0, 10, 0).unwrap();
        assert_eq!((files.len(), files[0].ok, files[0].items), (1, 2, 0));
        // Records from before individual accesses were kept show up in the
        // history as the hour they were aggregated into.
        let history = r.history(10100, "/proc/cpuinfo", 0).unwrap();
        assert_eq!(history.len(), 1);
        assert_eq!(
            (history[0].first_ts, history[0].last_ts, history[0].ok),
            (3600, 3700, 2)
        );
        drop(r);
        // Opening again is a no-op.
        drop(Writer::open(&db).unwrap());

        // A database from a newer build is refused, not misread.
        Connection::open(&db)
            .unwrap()
            .pragma_update(None, "user_version", 99)
            .unwrap();
        assert!(Writer::open(&db).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn timeline_groups_by_hour_app_and_kind() {
        let dir = std::env::temp_dir().join(format!("bbd-timeline-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        let t = 1_000_000 * 3600;
        let st = Category::Storage;
        // Hour 0: two photos read, one Download file only probed, and a
        // look at the MAC address.
        for (path, mode, ts) in [
            ("/storage/emulated/0/DCIM/a.jpg", MODE_READ, t + 10),
            ("/storage/emulated/0/DCIM/b.jpg", MODE_READ, t + 20),
            ("/storage/emulated/0/Download/c.pdf", MODE_PROBE, t + 30),
        ] {
            w.record(10100, "com.a", st, path.into(), mode, true, ts, 0);
        }
        let mac = "/sys/class/net/wlan0/address".to_string();
        w.record(
            10100,
            "com.a",
            Category::Device,
            mac.clone(),
            MODE_READ,
            false,
            t + 40,
            0,
        );
        // Hour 1: another app's probe only.
        w.record(
            10200,
            "com.b",
            st,
            "/storage/emulated/0/Music".into(),
            MODE_PROBE,
            true,
            t + 3600 + 5,
            0,
        );
        w.flush(&Counters::default(), t + 3700).unwrap();
        let r = Reader::open(&db).unwrap();

        let page = r.timeline(0, t + 7200, 100).unwrap();
        assert!(page.next_before.is_none());
        let summary: Vec<_> = page
            .entries
            .iter()
            .map(|e| {
                (
                    e.hour_ts,
                    e.uid,
                    e.cat.as_str(),
                    e.topic,
                    e.paths,
                    e.files_read,
                )
            })
            .collect();
        assert_eq!(
            summary,
            vec![
                (t + 3600, 10200, "storage", None, 1, 0),
                // Within an hour, most recent activity first.
                (
                    t,
                    10100,
                    "device",
                    Some(crate::rules::topic::MAC_ADDRESS),
                    1,
                    0
                ),
                (t, 10100, "storage", None, 3, 2),
            ]
        );
        let files = &page.entries[2];
        // The folder of what was read, not of what was only probed.
        assert_eq!(files.folder.as_deref(), Some("DCIM"));
        assert_eq!(files.modes, MODE_READ | MODE_PROBE);
        assert_eq!((page.entries[1].ok, page.entries[1].fail), (0, 1));

        // `before` leaves out the hours that start at or after it.
        let earlier = r.timeline(0, t + 3600, 100).unwrap();
        assert_eq!(earlier.entries.len(), 2);
        // `since` leaves out the hours that ended before it.
        assert_eq!(
            r.timeline(t + 3600, t + 7200, 100).unwrap().entries.len(),
            1
        );

        // A page ends once the limit is reached, and says where to go on.
        let mut w = Writer::open(&db).unwrap();
        let old = t - 3 * 24 * 3600;
        w.record(
            10100,
            "com.a",
            st,
            "/storage/emulated/0/DCIM/old.jpg".into(),
            MODE_READ,
            true,
            old,
            0,
        );
        w.flush(&Counters::default(), t + 3700).unwrap();
        let first = r.timeline(0, t + 7200, 1).unwrap();
        assert_eq!(first.entries.len(), 3);
        let next = first.next_before.expect("an older page");
        let second = r.timeline(0, next, 1).unwrap();
        assert_eq!(second.entries.len(), 1);
        assert_eq!(second.entries[0].hour_ts, old - old.rem_euclid(3600));
        assert!(second.next_before.is_none());

        // An empty range is an empty page.
        assert!(
            r.timeline(t + 7200, t + 9000, 10)
                .unwrap()
                .entries
                .is_empty()
        );
        drop(r);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn files_are_filtered_by_text_category_and_app() {
        let dir = std::env::temp_dir().join(format!("bbd-contains-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        let t = 1_000_000 * 3600;
        let st = Category::Storage;
        w.record(
            10100,
            "com.a",
            st,
            "/storage/emulated/0/DCIM/IMG_1.jpg".into(),
            MODE_READ,
            true,
            t,
            0,
        );
        w.record(
            10200,
            "com.b",
            st,
            "/storage/emulated/0/DCIM/IMG_1.jpg".into(),
            MODE_READ,
            true,
            t,
            0,
        );
        w.record(
            10200,
            "com.b",
            st,
            "/storage/emulated/0/Music/a.mp3".into(),
            MODE_READ,
            true,
            t,
            0,
        );
        w.flush(&Counters::default(), t + 10).unwrap();
        let r = Reader::open(&db).unwrap();
        let found = r.files(&[], &[], None, Some("img_1"), 0, 10, 0).unwrap();
        let mut uids: Vec<u32> = found.iter().map(|f| f.uid).collect();
        uids.sort_unstable();
        assert_eq!(uids, vec![10100, 10200]);
        assert!(
            r.files(&[], &[], None, Some("nothing"), 0, 10, 0)
                .unwrap()
                .is_empty()
        );

        // Several categories at once, as the search asks for files and the
        // media library together.
        let mut w = Writer::open(&db).unwrap();
        let images = "content://media/external/images/media".to_string();
        w.record(
            10100,
            "com.a",
            Category::Provider,
            images,
            MODE_QUERY,
            true,
            t,
            40,
        );
        w.record(
            10100,
            "com.a",
            Category::System,
            "/proc/media".into(),
            MODE_READ,
            true,
            t,
            0,
        );
        w.flush(&Counters::default(), t + 20).unwrap();
        let both = r
            .files(
                &[],
                &[Category::Storage, Category::Provider],
                None,
                Some("media"),
                0,
                10,
                0,
            )
            .unwrap();
        assert_eq!(both.len(), 1);
        assert_eq!(both[0].cat, "provider");

        // Several apps at once, as the search asks for the apps it shows.
        let mine = r
            .files(&[10100, 10300], &[], None, Some("img_1"), 0, 10, 0)
            .unwrap();
        assert_eq!(mine.iter().map(|f| f.uid).collect::<Vec<_>>(), [10100]);
        assert!(
            r.files(&[10300], &[], None, Some("img_1"), 0, 10, 0)
                .unwrap()
                .is_empty()
        );
        drop(r);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn histories_of_several_paths_merge_most_recent_first() {
        let dir = std::env::temp_dir().join(format!("bbd-history-of-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        let t = 1_000_000 * 3600;
        let photo = "/storage/emulated/0/DCIM/IMG_1.jpg";
        let song = "/storage/emulated/0/Music/a.mp3";
        w.record(
            10200,
            "com.b",
            Category::Storage,
            photo.into(),
            MODE_READ,
            true,
            t,
            0,
        );
        w.flush(&Counters::default(), t + 10).unwrap();
        w.record(
            10200,
            "com.b",
            Category::Storage,
            song.into(),
            MODE_READ,
            true,
            t + 3600,
            0,
        );
        w.flush(&Counters::default(), t + 3610).unwrap();
        let r = Reader::open(&db).unwrap();
        let history = r
            .history_of(10200, &[photo.to_string(), song.to_string()], 0)
            .unwrap();
        assert_eq!(
            history.iter().map(|h| h.last_ts).collect::<Vec<_>>(),
            [t + 3600, t]
        );
        std::fs::remove_dir_all(dir).unwrap();
    }
}
