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

use std::{
    collections::{BTreeSet, HashMap},
    path::{Path, PathBuf},
    time::{Duration, SystemTime},
};

use anyhow::Context as _;
use rusqlite::{Connection, OpenFlags, OptionalExtension as _, params};
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

/// Schema version stored in SQLite's `user_version`. Bump it whenever
/// `SCHEMA` changes, and add the step from the previous version to
/// `MIGRATIONS`.
const SCHEMA_VERSION: i32 = 10;

/// How a database written by a released build is brought to
/// `SCHEMA_VERSION`: (from, to, statements), applied one after another.
/// Versions that only development builds wrote have no step; such a
/// database is set aside. v0.2.0 wrote version 3.
const MIGRATIONS: &[(i32, i32, &str)] = &[
    (3, 7, MIGRATE_3_TO_7),
    (
        7,
        8,
        "ALTER TABLE sandbox ADD COLUMN install_id TEXT NOT NULL DEFAULT '';",
    ),
    (8, 9, RETIRED_SANDBOX),
    (9, 10, SANDBOX_AUTO_SCHEMA),
];

// The old install's settings and saved grants remain available to the owner
// for recovery. Only the live sandbox table is ever used to authorize work.
const RETIRED_SANDBOX: &str = "
CREATE TABLE sandbox_retired AS
    SELECT uid, package, enabled, android_id, saved_ops, saved_permissions, created, settings, install_id
    FROM sandbox WHERE 0;
CREATE UNIQUE INDEX retired_install ON sandbox_retired(uid, package, install_id, android_id);
CREATE TRIGGER retain_replaced_install BEFORE UPDATE OF install_id ON sandbox
WHEN OLD.install_id != NEW.install_id
BEGIN
    INSERT OR REPLACE INTO sandbox_retired
    VALUES(OLD.uid, OLD.package, OLD.enabled, OLD.android_id, OLD.saved_ops,
           OLD.saved_permissions, OLD.created, OLD.settings, OLD.install_id);
END;
";

/// v0.2.0 had the accesses and individual accesses: the sensor sessions and
/// the sandbox, added since, start empty.
const MIGRATE_3_TO_7: &str = "
CREATE TABLE sensor_session (
    uid        INTEGER NOT NULL,
    pid        INTEGER NOT NULL,
    proc_start INTEGER NOT NULL,
    connection INTEGER NOT NULL,
    handle     INTEGER NOT NULL,
    sensor     TEXT    NOT NULL,
    continuous INTEGER NOT NULL,
    period_ns  INTEGER NOT NULL,
    start_ts   INTEGER NOT NULL,
    end_ts     INTEGER,
    PRIMARY KEY (uid, pid, connection, handle, start_ts)
) WITHOUT ROWID;
CREATE INDEX sensor_session_end ON sensor_session (end_ts);
CREATE TABLE sandbox (
    uid        INTEGER PRIMARY KEY,
    package    TEXT    NOT NULL,
    enabled    INTEGER NOT NULL,
    android_id TEXT    NOT NULL,
    saved_ops  TEXT    NOT NULL,
    saved_permissions TEXT NOT NULL,
    created    INTEGER NOT NULL,
    settings   TEXT    NOT NULL
) WITHOUT ROWID;
CREATE TABLE sandbox_global (
    item TEXT PRIMARY KEY,
    mode TEXT NOT NULL
) WITHOUT ROWID;
";

/// Most entries returned for one path's history.
const HISTORY_LIMIT: u32 = 2000;

/// How many of an app's folders the per-app summary names.
const TOP_FOLDERS: usize = 3;

/// Hours of records the timeline reads per query while it collects a page.
const TIMELINE_WINDOW_HOURS: i64 = 24;

/// Topics whose records each say something of their own (whether the
/// clipboard was read or written, which sensor was turned on); the timeline
/// lists them for these, where there are a handful per entry.
const SUBJECTS_LISTED: &[&str] = &[crate::rules::topic::CLIPBOARD, crate::rules::topic::SENSORS];

/// Most sensor sessions returned for one app.
const SENSOR_SESSIONS_LIMIT: u32 = 2000;

/// Mode bits of accesses that only looked for a file rather than opened it.
const NOT_FILE_OPEN: Mode = MODE_DIR | MODE_PROBE | MODE_WATCH;

/// Mode bits of accesses that opened a file.
const FILE_OPEN: Mode = MODE_READ | MODE_WRITE;

/// The schema at `SCHEMA_VERSION`, created in a new database.
///
/// `access` answers "what did this app touch" cheaply, an hour at a time;
/// `event` answers "when exactly": a row is one second in which an app
/// touched a path in one way, and several accesses within the same second
/// share it and are counted. Paths of events are stored once, in `path`, and
/// referenced by id, since the same path recurs in many rows.
///
/// A `sensor_session` row is one period during which an app had a sensor on:
/// one sensor on one connection of one process, at one rate (see `sensors`).
///
/// `sandbox` holds the apps the user put in the sandbox (see `sandbox`). It
/// applies to a uid, not a package: every package sharing the uid is in it.
/// A disabled entry is kept rather than deleted, so that the app gets the
/// same identity back when it is enabled again. Its `settings` are the items
/// the app has a mode of its own for; `sandbox_global` holds the settings
/// every other item follows (see `sandbox::items`). An item named in neither
/// is on.
///
/// `sandbox_packages` holds the packages that ran under an entry's uid when
/// it was last looked at: the entry stays that app's while any of them is
/// installed, and is not another app's that is given the uid later.
///
/// `sandbox_known` is what the isolation of newly installed apps knows of
/// the installed ones (see `sandbox::auto`); it is empty while that is off,
/// which the `sandbox_auto` key of `meta` says. Rows with user -1 are an app
/// id left alone for every Android user; rows with a user are a uid of an
/// app installed since, already dealt with. There is a row for each package
/// of the app id or uid. A library row is one package of an app id that the
/// package manager has as a library, left alone while it is installed.
const SCHEMA: &str = "
CREATE TABLE access (
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
    items    INTEGER NOT NULL,   -- most entries one provider query returned
    PRIMARY KEY (hour, uid, path, mode)
) WITHOUT ROWID;
CREATE INDEX access_uid_last ON access (uid, last_ts);
CREATE INDEX access_last ON access (last_ts);
CREATE TABLE meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL) WITHOUT ROWID;
CREATE TABLE path (
    id   INTEGER PRIMARY KEY,
    path TEXT NOT NULL UNIQUE
);
CREATE TABLE event (
    uid     INTEGER NOT NULL,
    path_id INTEGER NOT NULL REFERENCES path (id),
    mode    INTEGER NOT NULL,   -- MODE_* bits
    ts      INTEGER NOT NULL,   -- unix time
    ok      INTEGER NOT NULL,
    fail    INTEGER NOT NULL,
    PRIMARY KEY (uid, path_id, mode, ts)
) WITHOUT ROWID;
CREATE INDEX event_ts ON event (ts);
CREATE TABLE sensor_session (
    uid        INTEGER NOT NULL,
    pid        INTEGER NOT NULL,
    proc_start INTEGER NOT NULL,  -- the process's start, in clock ticks after boot:
                                  -- tells a reused pid apart
    connection INTEGER NOT NULL,  -- the process's binder handle for the connection
    handle     INTEGER NOT NULL,  -- the sensor's handle on this device
    sensor     TEXT    NOT NULL,  -- its type, e.g. 'android.sensor.accelerometer';
                                  -- '' if it could not be identified
    continuous INTEGER NOT NULL,  -- 1 if it reports at a steady rate
    period_ns  INTEGER NOT NULL,  -- sampling period asked for; 0 for the fastest
    start_ts   INTEGER NOT NULL,
    end_ts     INTEGER,           -- NULL while it is on
    PRIMARY KEY (uid, pid, connection, handle, start_ts)
) WITHOUT ROWID;
CREATE INDEX sensor_session_end ON sensor_session (end_ts);
CREATE TABLE sandbox (
    uid        INTEGER PRIMARY KEY,   -- user * 100000 + app id
    package    TEXT    NOT NULL,      -- the package the user named, for display only
    enabled    INTEGER NOT NULL,
    android_id TEXT    NOT NULL,      -- 16 hex digits, drawn on the first enable
    saved_ops  TEXT    NOT NULL,      -- the uid's app-op modes before it was enabled,
                                      -- 'op=mode[/grant],...', restored when it is disabled
    saved_permissions TEXT NOT NULL,  -- the runtime permissions the sandbox may have
                                      -- taken, each with its state before, or 'default'
                                      -- where that is not known: 'permission=state,...'
    created    INTEGER NOT NULL,
    settings   TEXT    NOT NULL,      -- 'item=mode,...'
    install_id TEXT NOT NULL DEFAULT ''
) WITHOUT ROWID;
CREATE TABLE sandbox_global (
    item TEXT PRIMARY KEY,
    mode TEXT NOT NULL
) WITHOUT ROWID;
";

const SANDBOX_AUTO_SCHEMA: &str = "
CREATE TABLE sandbox_packages (
    uid     INTEGER NOT NULL,
    package TEXT    NOT NULL,
    PRIMARY KEY (uid, package)
) WITHOUT ROWID;
CREATE TABLE sandbox_known (
    app_id  INTEGER NOT NULL,
    user    INTEGER NOT NULL,   -- the Android user; -1 for every user
    package TEXT    NOT NULL,   -- known while one of its packages is installed: tells it
                                -- apart from an app given the app id after this one
    library INTEGER NOT NULL,   -- 1: this package alone, a library
    PRIMARY KEY (app_id, user, package)
) WITHOUT ROWID;
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
#[derive(Clone, Default, PartialEq)]
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

/// One period an app had a sensor on: a `sensor_session` row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SensorSession {
    pub uid: u32,
    pub pid: u32,
    pub proc_start: u64,
    pub connection: u32,
    pub handle: i32,
    pub sensor: String,
    pub continuous: bool,
    pub period_ns: i64,
    pub start_ts: i64,
    pub end_ts: Option<i64>,
}

/// Identifies an `event` row.
#[derive(Hash, PartialEq, Eq)]
struct EventKey {
    uid: u32,
    path: String,
    mode: Mode,
    ts: i64,
}

// Bound telemetry independently of the state required to undo sandbox changes.
const BUFFER_BYTES: usize = 4 * 1024 * 1024;
const DB_BYTES: u64 = 256 * 1024 * 1024;
const FREE_RESERVE: u64 = 64 * 1024 * 1024;

pub struct Writer {
    recording_pages: u32,
    recovery_pages: u32,
    buffered_bytes: usize,
    dropped: u64,
    conn: Connection,
    /// Where the database is, to find the one set aside next to it.
    path: PathBuf,
    pending: HashMap<Key, Agg>,
    /// Individual accesses not yet written: (allowed, refused) per key.
    events: HashMap<EventKey, (u32, u32)>,
    /// Sensor sessions started, changed or ended, not yet written.
    sessions: Vec<SensorSession>,
    /// The counters last written.
    written: Option<Counters>,
}

impl Writer {
    /// Open the database at `path`, creating it if new. One that a
    /// released build wrote at an earlier schema version is migrated,
    /// keeping the records and the sandbox. One at a version with no
    /// migration (a development build's, or a newer build's) is not read
    /// with the wrong layout: it is set aside as `<path>.old`, over the one
    /// set aside before, and recording starts over in a new one.
    pub fn open(path: &Path) -> anyhow::Result<Self> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir).with_context(|| format!("creating {}", dir.display()))?;
        }
        let open = || Connection::open(path).with_context(|| format!("opening {}", path.display()));
        let mut conn = open()?;
        if let Some(version) = other_version(&conn)? {
            if migrate(&mut conn, version)? {
                eprintln!("bbd: the database was version {version}: migrated to {SCHEMA_VERSION}");
            } else {
                drop(conn);
                set_aside(path)?;
                eprintln!(
                    "bbd: the database was version {version}, not {SCHEMA_VERSION}: set aside, starting a new one"
                );
                conn = open()?;
            }
        }
        // WAL lets `bbd` query commands read while the daemon writes.
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.pragma_update(None, "journal_size_limit", 8 * 1024 * 1024)?;
        create_schema(&mut conn)?;
        let page_size: u32 = conn.pragma_query_value(None, "page_size", |r| r.get(0))?;
        let pages: u32 = conn.pragma_query_value(None, "page_count", |r| r.get(0))?;
        // A transaction can overshoot the telemetry watermark. Preserve room
        // for sandbox recovery on devices that already have a larger database.
        let limit = u64::from(pages).max(DB_BYTES / u64::from(page_size))
            + FREE_RESERVE / u64::from(page_size);
        conn.pragma_update(None, "max_page_count", limit as i64)?;
        Ok(Self {
            conn,
            path: path.to_owned(),
            recording_pages: (DB_BYTES / u64::from(page_size)) as u32,
            recovery_pages: limit.min(u64::from(u32::MAX)) as u32,
            buffered_bytes: 0,
            dropped: 0,
            pending: HashMap::new(),
            events: HashMap::new(),
            sessions: Vec::new(),
            written: None,
        })
    }

    /// Write a sensor session with the next flush, over what was written of
    /// it before.
    pub fn sensor_session(&mut self, session: SensorSession) {
        if self.reserve_sample(session.sensor.len() + 128) {
            self.sessions.push(session);
        }
    }

    fn reserve_sample(&mut self, bytes: usize) -> bool {
        if bytes > BUFFER_BYTES - self.buffered_bytes {
            self.dropped = self.dropped.saturating_add(1);
            return false;
        }
        self.buffered_bytes += bytes;
        true
    }

    /// The sensor sessions not ended: those a previous run left open.
    pub fn open_sensor_sessions(&self) -> anyhow::Result<Vec<SensorSession>> {
        let mut stmt = self.conn.prepare(
            "SELECT uid, pid, proc_start, connection, handle, sensor, continuous, period_ns,
                    start_ts
             FROM sensor_session WHERE end_ts IS NULL",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(SensorSession {
                uid: r.get(0)?,
                pid: r.get(1)?,
                proc_start: r.get::<_, i64>(2)? as u64,
                connection: r.get(3)?,
                handle: r.get(4)?,
                sensor: r.get(5)?,
                continuous: r.get(6)?,
                period_ns: r.get(7)?,
                start_ts: r.get(8)?,
                end_ts: None,
            })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// A value from `meta`.
    pub fn meta(&self, key: &str) -> anyhow::Result<Option<i64>> {
        Ok(self
            .conn
            .query_row("SELECT value FROM meta WHERE key = ?1", params![key], |r| {
                r.get(0)
            })
            .optional()?)
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
        if path.len() > BUFFER_BYTES {
            self.dropped = self.dropped.saturating_add(1);
            return;
        }
        let event = EventKey {
            uid,
            path: path.clone(),
            mode,
            ts,
        };
        let key = Key {
            hour: ts / 3600,
            uid,
            path,
            mode,
        };
        let mut bytes = 0;
        if !self.events.contains_key(&event) {
            bytes += 128 + event.path.capacity();
        }
        if !self.pending.contains_key(&key) {
            bytes += 128 + key.path.capacity() + pkg.len();
        }
        if !self.reserve_sample(bytes) {
            return;
        }
        let counts = self.events.entry(event).or_default();
        if ok {
            counts.0 = counts.0.saturating_add(1);
        } else {
            counts.1 = counts.1.saturating_add(1);
        }
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
            agg.ok = agg.ok.saturating_add(1);
        } else {
            agg.fail = agg.fail.saturating_add(1);
        }
        // Events from different sources are not strictly ordered in time.
        agg.first_ts = agg.first_ts.min(ts);
        agg.last_ts = agg.last_ts.max(ts);
    }

    fn has_rows(&self) -> bool {
        !self.pending.is_empty() || !self.events.is_empty() || !self.sessions.is_empty()
    }

    /// Write buffered rows, with the counters, in one transaction. Without
    /// rows nothing is written: a device on which nothing is recorded is
    /// not written to.
    pub fn flush(&mut self, counters: &Counters, now: i64) -> anyhow::Result<()> {
        if !self.has_rows() {
            return Ok(());
        }
        self.write(counters, now)
    }

    /// Flush for a query that is about to read: the counters are written
    /// too if they changed.
    pub fn flush_for_query(&mut self, counters: &Counters, now: i64) -> anyhow::Result<()> {
        if self.written.as_ref() == Some(counters) && !self.has_rows() {
            return Ok(());
        }
        self.write(counters, now)
    }

    fn write(&mut self, counters: &Counters, now: i64) -> anyhow::Result<()> {
        if !self.room_for_records()? {
            self.dropped = self.dropped.saturating_add(
                self.events
                    .values()
                    .map(|(ok, fail)| u64::from(*ok) + u64::from(*fail))
                    .sum::<u64>()
                    + self.sessions.len() as u64,
            );
            self.pending.clear();
            self.events.clear();
            self.sessions.clear();
            self.buffered_bytes = 0;
            return Ok(());
        }
        self.conn
            .pragma_update(None, "max_page_count", self.recording_pages)?;
        let result = self.write_batch(counters, now);
        self.conn
            .pragma_update(None, "max_page_count", self.recovery_pages)?;
        result
    }

    fn write_batch(&mut self, counters: &Counters, now: i64) -> anyhow::Result<()> {
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
            let mut session = tx.prepare_cached(
                "INSERT INTO sensor_session
                     (uid, pid, proc_start, connection, handle, sensor, continuous, period_ns,
                      start_ts, end_ts)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
                 ON CONFLICT (uid, pid, connection, handle, start_ts) DO UPDATE SET
                     sensor = excluded.sensor,
                     continuous = excluded.continuous,
                     period_ns = excluded.period_ns,
                     end_ts = excluded.end_ts",
            )?;
            // In the order they changed: the last change of a session wins.
            for s in &self.sessions {
                session.execute(params![
                    s.uid,
                    s.pid,
                    s.proc_start as i64,
                    s.connection,
                    s.handle,
                    s.sensor,
                    s.continuous,
                    s.period_ns,
                    s.start_ts,
                    s.end_ts
                ])?;
            }

            let mut meta = tx.prepare_cached(
                "INSERT INTO meta (key, value) VALUES (?1, ?2)
                 ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            )?;
            for (key, value) in [
                ("events_seen", counters.seen as i64),
                ("events_kept", counters.kept as i64),
                ("events_unresolved", counters.unresolved as i64),
                (
                    "events_dropped",
                    counters.dropped.saturating_add(self.dropped) as i64,
                ),
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
        self.sessions.clear();
        self.buffered_bytes = 0;
        self.written = Some(counters.clone());
        Ok(())
    }

    fn room_for_records(&self) -> anyhow::Result<bool> {
        let page_size: u64 = self
            .conn
            .pragma_query_value(None, "page_size", |r| r.get::<_, u32>(0).map(u64::from))?;
        let pages: u64 = self
            .conn
            .pragma_query_value(None, "page_count", |r| r.get::<_, u32>(0).map(u64::from))?;
        let free: u64 = self.conn.pragma_query_value(None, "freelist_count", |r| {
            r.get::<_, u32>(0).map(u64::from)
        })?;
        let mut wal = self.path.as_os_str().to_owned();
        wal.push("-wal");
        let wal_bytes = std::fs::metadata(wal).map_or(0, |m| m.len());
        if pages.saturating_sub(free).saturating_mul(page_size) + wal_bytes >= DB_BYTES {
            return Ok(false);
        }
        let path = std::ffi::CString::new(self.path.as_os_str().as_encoded_bytes())?;
        let mut space = std::mem::MaybeUninit::uninit();
        if unsafe { libc::statvfs(path.as_ptr(), space.as_mut_ptr()) } != 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let space = unsafe { space.assume_init() };
        Ok((space.f_bavail as u128) * (space.f_frsize as u128) >= u128::from(FREE_RESERVE))
    }

    pub fn set_meta(&self, key: &str, value: i64) -> anyhow::Result<()> {
        self.conn.execute(
            "INSERT INTO meta (key, value) VALUES (?1, ?2)
             ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    /// Delete rows older than `days`. Returns the number removed.
    pub fn prune(&self, days: u32, now: i64) -> anyhow::Result<usize> {
        let cutoff = now - i64::from(days) * 86_400;
        // Not removing it is no reason to keep the records here past their time.
        if let Err(e) = remove_set_aside_before(&self.path, cutoff) {
            eprintln!("bbd: could not remove the database set aside: {e:#}");
        }
        self.conn
            .execute("DELETE FROM event WHERE ts < ?1", params![cutoff])?;
        self.conn.execute(
            "DELETE FROM sensor_session WHERE end_ts < ?1",
            params![cutoff],
        )?;
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

    /// Write a sandbox entry over whatever was stored for its uid: enabling
    /// and disabling both write the whole entry, since what the sandbox
    /// does to the app may change more of it than `enabled`. Unlike the
    /// records, it is written at once, and through to the disk: the sandbox
    /// changes rarely, and the entry holds what the app's app ops were
    /// before, which is nowhere else once they are changed. It must survive
    /// the daemon stopping, and the power going, right after.
    pub fn put_sandbox(&self, entry: &SandboxEntry) -> anyhow::Result<()> {
        self.conn.execute(
            "INSERT INTO sandbox (uid, package, enabled, android_id, saved_ops, saved_permissions,
                                  created, settings, install_id)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
             ON CONFLICT (uid) DO UPDATE SET
                 package = excluded.package, enabled = excluded.enabled,
                 android_id = excluded.android_id, saved_ops = excluded.saved_ops,
                 saved_permissions = excluded.saved_permissions,
                 created = excluded.created, settings = excluded.settings, install_id = excluded.install_id",
            params![
                entry.uid,
                entry.package,
                entry.enabled,
                entry.android_id,
                entry.saved_ops,
                entry.saved_permissions,
                entry.created,
                entry.settings, entry.install_id
            ],
        )?;
        self.sync()
    }

    /// Have what was written so far reach the disk now. The database
    /// otherwise leaves that to the system (`synchronous = NORMAL`), which
    /// is right for the records and not for the sandbox.
    fn sync(&self) -> anyhow::Result<()> {
        self.conn
            .query_row("PRAGMA wal_checkpoint(FULL)", [], |_| Ok(()))?;
        Ok(())
    }

    /// Forget the entry for `uid`. Only for undoing the first enable of an
    /// app when it failed: a disabled entry is otherwise kept.
    pub fn delete_sandbox(&self, uid: u32) -> anyhow::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        tx.execute("DELETE FROM sandbox WHERE uid = ?1", params![uid])?;
        tx.execute("DELETE FROM sandbox_packages WHERE uid = ?1", params![uid])?;
        tx.commit()?;
        self.sync()
    }

    /// The packages that ran under `uid` when its entry was last looked at.
    pub fn sandbox_packages(&self, uid: u32) -> anyhow::Result<BTreeSet<String>> {
        let mut stmt = self
            .conn
            .prepare("SELECT package FROM sandbox_packages WHERE uid = ?1")?;
        let rows = stmt.query_map(params![uid], |r| r.get(0))?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Replace the packages that run under `uid`, written through to the
    /// disk with the entries.
    pub fn put_sandbox_packages(&self, uid: u32, packages: &[String]) -> anyhow::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        tx.execute("DELETE FROM sandbox_packages WHERE uid = ?1", params![uid])?;
        for package in packages {
            tx.execute(
                "INSERT OR IGNORE INTO sandbox_packages (uid, package) VALUES (?1, ?2)",
                params![uid, package],
            )?;
        }
        tx.commit()?;
        self.sync()
    }

    /// The entry for `uid`, enabled or not.
    pub fn sandbox_entry(&self, uid: u32) -> anyhow::Result<Option<SandboxEntry>> {
        Ok(self
            .conn
            .query_row(
                &format!("{SANDBOX_SELECT} WHERE uid = ?1"),
                params![uid],
                sandbox_entry,
            )
            .optional()?)
    }

    /// Every entry, enabled or not, by uid.
    pub fn sandbox_entries(&self) -> anyhow::Result<Vec<SandboxEntry>> {
        sandbox_entries(&self.conn)
    }

    /// The global settings of the sandbox, as `item=mode,...`.
    pub fn sandbox_global(&self) -> anyhow::Result<String> {
        sandbox_global(&self.conn)
    }

    /// Replace the global settings of the sandbox with `pairs` of item and
    /// mode. Written through to the disk, as the entries are.
    pub fn put_sandbox_global(&self, pairs: &[(&str, &str)]) -> anyhow::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        tx.execute("DELETE FROM sandbox_global", [])?;
        for (item, mode) in pairs {
            tx.execute(
                "INSERT INTO sandbox_global (item, mode) VALUES (?1, ?2)",
                params![item, mode],
            )?;
        }
        tx.commit()?;
        self.sync()
    }
}

/// One app in the sandbox: a `sandbox` row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SandboxEntry {
    pub install_id: String,
    pub uid: u32,
    pub package: String,
    pub enabled: bool,
    pub android_id: String,
    pub saved_ops: String,
    /// The runtime permissions the sandbox may have taken from the app,
    /// with the state each had before (see `sandbox::appops`).
    pub saved_permissions: String,
    pub created: i64,
    /// The items the app has a mode of its own for, as `item=mode,...`.
    pub settings: String,
}

const SANDBOX_SELECT: &str = "SELECT uid, package, enabled, android_id, saved_ops, \
     saved_permissions, created, settings, install_id FROM sandbox";

fn sandbox_entry(r: &rusqlite::Row<'_>) -> rusqlite::Result<SandboxEntry> {
    Ok(SandboxEntry {
        uid: r.get(0)?,
        package: r.get(1)?,
        enabled: r.get(2)?,
        android_id: r.get(3)?,
        saved_ops: r.get(4)?,
        saved_permissions: r.get(5)?,
        created: r.get(6)?,
        settings: r.get(7)?,
        install_id: r.get(8)?,
    })
}

fn sandbox_entries(conn: &Connection) -> anyhow::Result<Vec<SandboxEntry>> {
    let mut stmt = conn.prepare(&format!("{SANDBOX_SELECT} ORDER BY uid"))?;
    let rows = stmt.query_map([], sandbox_entry)?;
    Ok(rows.collect::<Result<_, _>>()?)
}

fn sandbox_global(conn: &Connection) -> anyhow::Result<String> {
    let mut stmt = conn.prepare("SELECT item, mode FROM sandbox_global ORDER BY item")?;
    let pairs = stmt
        .query_map([], |r| {
            Ok(format!(
                "{}={}",
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?
            ))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(pairs.join(","))
}

/// What the isolation of newly installed apps knows of one installed app
/// (see `sandbox::auto`): a `sandbox_known` row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KnownApp {
    pub app_id: u32,
    /// The Android user it was dealt with for; `None` for every user.
    pub user: Option<u32>,
    pub package: String,
    /// The package is a library, left alone itself.
    pub library: bool,
}

/// The `meta` key that says whether newly installed apps are isolated.
const SANDBOX_AUTO: &str = "sandbox_auto";

impl Writer {
    /// Whether newly installed apps are isolated, and what is known of the
    /// installed ones.
    pub fn sandbox_auto(&self) -> anyhow::Result<(bool, Vec<KnownApp>)> {
        let on = self.meta(SANDBOX_AUTO)?.is_some_and(|on| on != 0);
        let mut stmt = self.conn.prepare(
            "SELECT app_id, user, package, library FROM sandbox_known
             ORDER BY app_id, user, package",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(KnownApp {
                app_id: r.get(0)?,
                user: u32::try_from(r.get::<_, i64>(1)?).ok(),
                package: r.get(2)?,
                library: r.get(3)?,
            })
        })?;
        Ok((on, rows.collect::<Result<_, _>>()?))
    }

    /// Replace both at once, written through to the disk as the sandbox's
    /// entries are: what is known decides which apps are put in the
    /// sandbox.
    pub fn put_sandbox_auto(&self, on: bool, known: &[KnownApp]) -> anyhow::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        tx.execute(
            "INSERT INTO meta (key, value) VALUES (?1, ?2)
             ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            params![SANDBOX_AUTO, on],
        )?;
        tx.execute("DELETE FROM sandbox_known", [])?;
        {
            let mut insert = tx.prepare(
                "INSERT INTO sandbox_known (app_id, user, package, library)
                 VALUES (?1, ?2, ?3, ?4)",
            )?;
            for app in known {
                insert.execute(params![
                    app.app_id,
                    app.user.map_or(-1, i64::from),
                    app.package,
                    app.library
                ])?;
            }
        }
        tx.commit()?;
        self.sync()
    }
}

fn has_tables(conn: &Connection) -> rusqlite::Result<bool> {
    conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'table')",
        [],
        |r| r.get(0),
    )
}

fn version_of(conn: &Connection) -> rusqlite::Result<i32> {
    conn.pragma_query_value(None, "user_version", |r| r.get(0))
}

/// The version of a database that has tables but another schema version
/// than this build's; `None` for a new database or one at this version.
fn other_version(conn: &Connection) -> anyhow::Result<Option<i32>> {
    if !has_tables(conn)? {
        return Ok(None);
    }
    let version = version_of(conn)?;
    Ok((version != SCHEMA_VERSION).then_some(version))
}

/// Move the database at `path`, with its write-ahead log, to `<path>.old`,
/// over what is there. SQLite finds a database's log by its name, so the
/// set-aside one stays whole.
fn set_aside(path: &Path) -> anyhow::Result<()> {
    for suffix in ["", "-wal", "-shm"] {
        let mut from = path.as_os_str().to_owned();
        from.push(suffix);
        let mut to = path.as_os_str().to_owned();
        to.push(".old");
        to.push(suffix);
        match std::fs::rename(&from, &to) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound && !suffix.is_empty() => {
                // A log left over from the one set aside before is not this one's.
                let _ = std::fs::remove_file(&to);
            }
            result => result.with_context(|| format!("setting {} aside", path.display()))?,
        }
    }
    Ok(())
}

/// Bring a database at `from` to `SCHEMA_VERSION` with the steps of
/// `MIGRATIONS`, in one transaction. False, with nothing changed, if there
/// is no way from `from`.
fn migrate(conn: &mut Connection, from: i32) -> anyhow::Result<bool> {
    let mut steps = Vec::new();
    let mut version = from;
    while version != SCHEMA_VERSION {
        let Some(&(_, to, statements)) = MIGRATIONS.iter().find(|(f, _, _)| *f == version) else {
            return Ok(false);
        };
        steps.push(statements);
        version = to;
    }
    // user_version is in the database header and written with the
    // transaction: a migration stopped half way leaves the old version.
    let tx = conn.transaction()?;
    for statements in steps {
        tx.execute_batch(statements)
            .with_context(|| format!("migrating the database from version {from}"))?;
    }
    tx.pragma_update(None, "user_version", SCHEMA_VERSION)?;
    tx.commit()?;
    Ok(true)
}

/// Remove the database set aside next to `path` once all of it is older
/// than `cutoff` (unix time). Nothing writes to it after it is set aside,
/// so its last change, in the file or in its log (which holds the newest
/// writes until they are copied into the file), is when its newest record
/// was.
fn remove_set_aside_before(path: &Path, cutoff: i64) -> anyhow::Result<()> {
    let mut old = path.as_os_str().to_owned();
    old.push(".old");
    let modified = |suffix: &str| {
        let mut file = old.clone();
        file.push(suffix);
        std::fs::metadata(&file).and_then(|m| m.modified())
    };
    let mut newest = match modified("") {
        Ok(modified) => modified,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(e).context("reading the database set aside"),
    };
    if let Ok(log) = modified("-wal") {
        newest = newest.max(log);
    }
    let cutoff = SystemTime::UNIX_EPOCH + Duration::from_secs(cutoff.max(0).unsigned_abs());
    if newest >= cutoff {
        return Ok(());
    }
    // The file last: what is left after a failure is found and removed again.
    for suffix in ["-wal", "-shm", ""] {
        let mut file = old.clone();
        file.push(suffix);
        match std::fs::remove_file(&file) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => {
                return Err(e).context("removing the database set aside");
            }
            _ => {}
        }
    }
    Ok(())
}

/// Create the schema in a new database; one that has it is left alone.
fn create_schema(conn: &mut Connection) -> anyhow::Result<()> {
    // One transaction for the tables and the version that records them: if
    // the process dies half way, the next start finds an empty database.
    let conn = conn.transaction()?;
    if has_tables(&conn)? {
        return Ok(());
    }
    conn.execute_batch(SCHEMA)?;
    conn.execute_batch(RETIRED_SANDBOX)?;
    conn.execute_batch(SANDBOX_AUTO_SCHEMA)?;
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

/// One entry in a path's history: an access, or several in the same second.
#[derive(Serialize)]
pub struct HistoryRow {
    pub ts: i64,
    pub mode: Mode,
    pub ok: i64,
    pub fail: i64,
    /// Which path it is of, when the histories of several are merged.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
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
    /// For the topics in `SUBJECTS_LISTED`, each path and its counts, in
    /// path order.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub subjects: Option<Vec<SubjectCount>>,
}

/// One path of a timeline entry, and how often it was allowed and refused.
#[derive(Serialize, Debug, PartialEq, Eq)]
pub struct SubjectCount {
    pub path: String,
    pub ok: i64,
    pub fail: i64,
}

/// One period an app had a sensor on, as `bbd sensors` prints it.
#[derive(Serialize, Debug, PartialEq)]
pub struct SensorSessionRow {
    /// The sensor's type (`android.sensor.accelerometer`); empty if it could
    /// not be identified.
    pub sensor: String,
    /// Whether the rate asked for is high (see `sensors::HIGH_RATE_PERIOD_NS`).
    pub high_rate: bool,
    /// The sampling period asked for, 0 for the fastest the sensor has.
    /// Absent for sensors that report on change or once, or that could not
    /// be identified, for which a rate means nothing.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub period_ns: Option<i64>,
    /// The same as a rate; absent with `period_ns`, or when it is 0.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rate_hz: Option<f64>,
    pub start_ts: i64,
    /// `null` while the sensor is on.
    pub end_ts: Option<i64>,
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
    /// Path -> (ok, fail), for the topics in `SUBJECTS_LISTED`.
    subjects: std::collections::BTreeMap<String, (i64, i64)>,
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
    /// Open the database at `path` to read. One at another schema version
    /// is not read: its layout is not this build's, and the daemon migrates
    /// it or sets it aside when it starts.
    pub fn open(path: &Path) -> anyhow::Result<Self> {
        let conn = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)
            .with_context(|| format!("opening {} (has the daemon run yet?)", path.display()))?;
        match version_of(&conn)? {
            SCHEMA_VERSION => Ok(Self { conn }),
            0 => anyhow::bail!(
                "{} has no records yet (has the daemon run yet?)",
                path.display()
            ),
            version => anyhow::bail!(
                "{} is version {version}, not {SCHEMA_VERSION}: the daemon brings it up to date when it starts",
                path.display()
            ),
        }
    }

    /// Every sandbox entry, enabled or not, by uid.
    pub fn sandbox_entries(&self) -> anyhow::Result<Vec<SandboxEntry>> {
        sandbox_entries(&self.conn)
    }

    /// The global settings of the sandbox, as `item=mode,...`.
    pub fn sandbox_global(&self) -> anyhow::Result<String> {
        sandbox_global(&self.conn)
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
    pub fn history(&self, uid: u32, path: &str, since: i64) -> anyhow::Result<Vec<HistoryRow>> {
        let mut stmt = self.conn.prepare(
            "SELECT e.ts, e.mode, e.ok, e.fail
             FROM event e JOIN path p ON p.id = e.path_id
             WHERE e.uid = ?1 AND p.path = ?2 AND e.ts >= ?3
             ORDER BY e.ts DESC, e.mode
             LIMIT ?4",
        )?;
        let rows = stmt.query_map(params![uid, path, since, HISTORY_LIMIT], |r| {
            Ok(HistoryRow {
                ts: r.get(0)?,
                mode: r.get(1)?,
                ok: r.get(2)?,
                fail: r.get(3)?,
                path: None,
            })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
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
            let named = (paths.len() > 1).then_some(path);
            rows.extend(
                self.history(uid, path, since)?
                    .into_iter()
                    .map(|row| HistoryRow {
                        path: named.cloned(),
                        ..row
                    }),
            );
        }
        rows.sort_by_key(|row| std::cmp::Reverse(row.ts));
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
                if topic.is_some_and(|topic| SUBJECTS_LISTED.contains(&topic)) {
                    let counts = acc.subjects.entry(path.clone()).or_default();
                    counts.0 += ok;
                    counts.1 += fail;
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
                        subjects: topic
                            .is_some_and(|topic| SUBJECTS_LISTED.contains(&topic))
                            .then(|| {
                                acc.subjects
                                    .into_iter()
                                    .map(|(path, (ok, fail))| SubjectCount { path, ok, fail })
                                    .collect()
                            }),
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

    /// The periods one app had sensors on that overlap the time from
    /// `since`, most recent start first. Empty for a database the daemon has
    /// not brought to this version yet.
    pub fn sensor_sessions(&self, uid: u32, since: i64) -> anyhow::Result<Vec<SensorSessionRow>> {
        let exists: bool = self.conn.query_row(
            "SELECT EXISTS (SELECT 1 FROM sqlite_master WHERE name = 'sensor_session')",
            [],
            |r| r.get(0),
        )?;
        if !exists {
            return Ok(Vec::new());
        }
        let mut stmt = self.conn.prepare(
            "SELECT sensor, continuous, period_ns, start_ts, end_ts FROM sensor_session
             WHERE uid = ?1 AND (end_ts IS NULL OR end_ts >= ?2)
             ORDER BY start_ts DESC, sensor
             LIMIT ?3",
        )?;
        let rows = stmt.query_map(params![uid, since, SENSOR_SESSIONS_LIMIT], |r| {
            let continuous: bool = r.get(1)?;
            let period_ns: i64 = r.get(2)?;
            let period = continuous.then_some(period_ns);
            Ok(SensorSessionRow {
                sensor: r.get(0)?,
                high_rate: crate::sensors::is_high_rate(continuous, period_ns),
                period_ns: period,
                rate_hz: period.filter(|p| *p > 0).map(|p| 1e9 / p as f64),
                start_ts: r.get(3)?,
                end_ts: r.get(4)?,
            })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
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
    fn telemetry_over_budget_leaves_room_for_sandbox_recovery() {
        let dir = std::env::temp_dir().join(format!("bbd-buffer-limit-{}", std::process::id()));
        let mut w = Writer::open(&dir.join("bb.db")).unwrap();
        w.record(
            10100,
            "example",
            Category::Storage,
            "x".repeat(BUFFER_BYTES),
            MODE_READ,
            true,
            1,
            0,
        );
        assert!(w.events.is_empty() && w.pending.is_empty());
        assert_eq!(w.dropped, 1);
        w.put_sandbox(&sandbox_entry_for(10100)).unwrap();
        assert!(w.sandbox_entry(10100).unwrap().is_some());
        drop(w);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_full_telemetry_transaction_preserves_the_sandbox_write_allowance() {
        let dir = std::env::temp_dir().join(format!("bbd-recording-pages-{}", std::process::id()));
        let mut w = Writer::open(&dir.join("bb.db")).unwrap();
        let normal = w.recording_pages;
        w.recording_pages = w
            .conn
            .pragma_query_value(None, "page_count", |row| row.get(0))
            .unwrap();
        w.record(
            10100,
            "example",
            Category::Storage,
            "a".repeat(64 * 1024),
            MODE_READ,
            true,
            1,
            0,
        );
        assert!(w.flush(&Counters::default(), 1).is_err());
        assert_eq!(w.events.len(), 1);
        w.put_sandbox(&sandbox_entry_for(10100)).unwrap();
        w.recording_pages = normal;
        w.flush(&Counters::default(), 2).unwrap();
        assert!(w.events.is_empty());
        assert!(w.sandbox_entry(10100).unwrap().is_some());
        drop(w);
        std::fs::remove_dir_all(dir).unwrap();
    }

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
        assert_eq!(history[0].ts, t + 3600);
        assert_eq!(history.last().unwrap().ts, t);
        assert!(history.windows(2).all(|w| w[0].ts >= w[1].ts));
        let total = |f: fn(&HistoryRow) -> i64| history.iter().map(f).sum::<i64>();
        assert_eq!((total(|h| h.ok), total(|h| h.fail)), (4, 1));
        assert_eq!(r.history(10100, &photo, t + 3600).unwrap().len(), 1);
        assert!(r.history(10200, &photo, 0).unwrap().is_empty());

        let stats = r.stats().unwrap();
        assert_eq!((stats.rows, stats.apps), (4, 2));
        assert_eq!(stats.meta["events_seen"], 10);
        assert_eq!(stats.meta["events_dropped"], 2);

        assert_eq!(w.prune(1, t + 3 * 86_400).unwrap(), 4);
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
    fn nothing_is_written_while_nothing_is_recorded() {
        let dir = std::env::temp_dir().join(format!("bbd-test-idle-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        let last_flush = || {
            Reader::open(&db)
                .unwrap()
                .stats()
                .unwrap()
                .meta
                .get("last_flush")
                .copied()
        };
        let t = 3_000_000 * 3600;
        w.flush(&Counters::default(), t).unwrap();
        assert_eq!(last_flush(), None);

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
        w.flush(&Counters::default(), t + 1).unwrap();
        assert_eq!(last_flush(), Some(t + 1));
        w.flush(&Counters::default(), t + 31).unwrap();
        assert_eq!(last_flush(), Some(t + 1));

        // A query writes only counters that changed.
        w.flush_for_query(&Counters::default(), t + 32).unwrap();
        assert_eq!(last_flush(), Some(t + 1));
        let counters = Counters {
            seen: 1,
            ..Counters::default()
        };
        w.flush_for_query(&counters, t + 34).unwrap();
        assert_eq!(last_flush(), Some(t + 34));
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
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_database_at_another_version_is_set_aside_and_a_new_one_started() {
        let dir = std::env::temp_dir().join(format!("bbd-test-version-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let db = dir.join("bb.db");
        let old = dir.join("bb.db.old");
        let version = |path: &Path| -> i32 {
            Connection::open(path)
                .unwrap()
                .pragma_query_value(None, "user_version", |r| r.get(0))
                .unwrap()
        };
        // A new database is created at the current version; opening it again
        // keeps it, with what was written.
        Writer::open(&db)
            .unwrap()
            .put_sandbox_global(&[("camera", "off")])
            .unwrap();
        let w = Writer::open(&db).unwrap();
        assert_eq!(version(&db), SCHEMA_VERSION);
        assert_eq!(w.sandbox_global().unwrap(), "camera=off");
        drop(w);
        assert!(!old.exists());

        // Neither upgraded nor misread: a database at a version no released
        // build wrote, and a newer one, are each set aside whole, and
        // recording starts over.
        for other in [6, SCHEMA_VERSION + 1] {
            let w = Writer::open(&db).unwrap();
            w.put_sandbox_global(&[("camera", "off")]).unwrap();
            w.conn.pragma_update(None, "user_version", other).unwrap();
            drop(w);
            let w = Writer::open(&db).unwrap();
            assert_eq!(version(&db), SCHEMA_VERSION);
            assert_eq!(w.sandbox_global().unwrap(), "");
            assert_eq!(version(&old), other);
            let kept: String = Connection::open(&old)
                .unwrap()
                .query_row("SELECT mode FROM sandbox_global", [], |r| r.get(0))
                .unwrap();
            assert_eq!(kept, "off");
        }
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_database_at_another_version_is_not_read() {
        let dir = std::env::temp_dir().join(format!("bbd-test-read-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let db = dir.join("bb.db");
        let w = Writer::open(&db).unwrap();
        assert!(Reader::open(&db).is_ok());
        w.conn.pragma_update(None, "user_version", 6).unwrap();
        assert!(Reader::open(&db).is_err());
        drop(w);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn the_global_settings_are_written_over() {
        let dir = std::env::temp_dir().join(format!("bbd-test-global-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let w = Writer::open(&dir.join("bb.db")).unwrap();
        assert_eq!(w.sandbox_global().unwrap(), "");
        w.put_sandbox_global(&[("camera", "off"), ("location", "coarse")])
            .unwrap();
        assert_eq!(w.sandbox_global().unwrap(), "camera=off,location=coarse");
        w.put_sandbox_global(&[("contacts", "off")]).unwrap();
        assert_eq!(w.sandbox_global().unwrap(), "contacts=off");
        drop(w);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn what_auto_isolation_knows_is_written_over_and_survives_reopening() {
        let dir = std::env::temp_dir().join(format!("bbd-test-known-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let db = dir.join("bb.db");
        let w = Writer::open(&db).unwrap();
        assert_eq!(w.sandbox_auto().unwrap(), (false, vec![]));
        let known = |app_id, user, package: &str| KnownApp {
            app_id,
            user,
            package: package.into(),
            library: false,
        };
        let rows = [
            known(10100, None, "com.a"),
            known(10100, None, "com.b"),
            known(10230, Some(0), "com.new"),
            known(10230, Some(10), "com.new"),
            KnownApp {
                library: true,
                ..known(10240, None, "com.library_2")
            },
        ];
        w.put_sandbox_auto(true, &rows).unwrap();
        drop(w);
        let w = Writer::open(&db).unwrap();
        assert_eq!(w.sandbox_auto().unwrap(), (true, rows.to_vec()));
        // Turned off, nothing is known.
        w.put_sandbox_auto(false, &[]).unwrap();
        assert_eq!(w.sandbox_auto().unwrap(), (false, vec![]));
    }

    /// Every column of every table, and every index, of the database at
    /// `path`, in an order that does not depend on how they were created.
    fn layout(path: &Path) -> Vec<String> {
        let conn = Connection::open(path).unwrap();
        let mut layout: Vec<String> = conn
            .prepare(
                "SELECT m.type || ' ' || m.name || ' ' || coalesce(p.name || ' ' || p.type || ' ' \
                 || p.\"notnull\" || ' ' || p.pk, '') \
                 FROM sqlite_master m LEFT JOIN pragma_table_info(m.name) p ON m.type = 'table' \
                 WHERE m.name NOT LIKE 'sqlite_%'",
            )
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .map(Result::unwrap)
            .collect();
        layout.sort();
        layout
    }

    /// A released version, the schema version of the database it wrote, and
    /// a database it could have written: the statements of `bbd/testdata/db/`.
    struct Release {
        version: &'static str,
        schema: i32,
        fixture: &'static str,
    }

    /// Every release, oldest first. Each release adds itself here, with a
    /// fixture (CONTRIBUTING.md), so that every later schema change has to
    /// bring its database along.
    const RELEASES: &[Release] = &[Release {
        version: "v0.2.0",
        schema: 3,
        fixture: include_str!("../testdata/db/v0.2.0.sql"),
    }];

    /// The tables of the database, by name, each with its columns.
    fn tables(conn: &Connection) -> Vec<(String, Vec<String>)> {
        let names: Vec<String> = conn
            .prepare(
                "SELECT name FROM sqlite_master WHERE type = 'table' \
                 AND name NOT LIKE 'sqlite_%' ORDER BY name",
            )
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .map(Result::unwrap)
            .collect();
        names
            .into_iter()
            .map(|table| {
                let columns = conn
                    .prepare("SELECT name FROM pragma_table_info(?1) ORDER BY cid")
                    .unwrap()
                    .query_map([&table], |r| r.get(0))
                    .unwrap()
                    .map(Result::unwrap)
                    .collect();
                (table, columns)
            })
            .collect()
    }

    /// Every row of `table`, of the given columns only, sorted.
    fn rows(conn: &Connection, table: &str, columns: &[String]) -> Vec<String> {
        let mut stmt = conn
            .prepare(&format!("SELECT {} FROM \"{table}\"", columns.join(", ")))
            .unwrap();
        let mut rows: Vec<String> = stmt
            .query_map([], |r| {
                (0..columns.len())
                    .map(|i| r.get::<_, rusqlite::types::Value>(i))
                    .collect::<Result<Vec<_>, _>>()
                    .map(|values| format!("{values:?}"))
            })
            .unwrap()
            .map(Result::unwrap)
            .collect();
        rows.sort();
        rows
    }

    #[test]
    fn the_database_of_every_release_is_migrated_with_everything_in_it() {
        for release in RELEASES {
            let version = release.version;
            let dir = std::env::temp_dir()
                .join(format!("bbd-test-release-{version}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();
            let db = dir.join("bb.db");
            let fresh = dir.join("fresh.db");

            let conn = Connection::open(&db).unwrap();
            conn.execute_batch(release.fixture).unwrap();
            assert_eq!(
                version_of(&conn).unwrap(),
                release.schema,
                "the fixture of {version} is at another schema version"
            );
            let before: Vec<(String, Vec<String>, Vec<String>)> = tables(&conn)
                .into_iter()
                .map(|(table, columns)| {
                    let rows = rows(&conn, &table, &columns);
                    (table, columns, rows)
                })
                .collect();
            for (table, _, rows) in &before {
                assert!(
                    !rows.is_empty(),
                    "the fixture of {version} has no rows in {table}"
                );
            }
            drop(conn);

            let w = Writer::open(&db).unwrap();
            assert!(
                !dir.join("bb.db.old").exists(),
                "the database of {version} was set aside"
            );
            assert_eq!(version_of(&w.conn).unwrap(), SCHEMA_VERSION);
            // Every row of every table it had, with every value, and nothing
            // in the tables added since.
            for (table, columns, rows_before) in &before {
                assert_eq!(
                    &rows(&w.conn, table, columns),
                    rows_before,
                    "{table} of {version} changed"
                );
            }
            for (table, columns) in tables(&w.conn) {
                if !before.iter().any(|(t, _, _)| *t == table) {
                    assert!(
                        rows(&w.conn, &table, &columns).is_empty(),
                        "{table} has rows the database of {version} did not"
                    );
                }
            }
            drop(w);
            drop(Writer::open(&fresh).unwrap());
            assert_eq!(layout(&db), layout(&fresh), "{version}");

            // Opened again, it is left as it is, and the sandbox works in it
            // as in a new one.
            let w = Writer::open(&db).unwrap();
            for (table, columns, rows_before) in &before {
                assert_eq!(&rows(&w.conn, table, columns), rows_before, "{version}");
            }
            w.put_sandbox(&sandbox_entry_for(10999)).unwrap();
            assert_eq!(
                w.sandbox_entry(10999).unwrap(),
                Some(sandbox_entry_for(10999))
            );
            drop(w);
            std::fs::remove_dir_all(dir).unwrap();
        }
    }

    #[test]
    fn the_records_of_v0_2_0_read_as_they_were_recorded() {
        let dir = std::env::temp_dir().join(format!("bbd-test-v0.2.0-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let db = dir.join("bb.db");
        Connection::open(&db)
            .unwrap()
            .execute_batch(RELEASES[0].fixture)
            .unwrap();
        drop(Writer::open(&db).unwrap());
        let r = Reader::open(&db).unwrap();

        let stats = r.stats().unwrap();
        assert_eq!((stats.rows, stats.apps), (14, 4));
        assert_eq!(
            (stats.oldest_ts, stats.newest_ts),
            (Some(1_760_004_000), Some(1_760_007_705))
        );
        assert_eq!(stats.meta.len(), 13);
        assert_eq!(stats.meta["events_seen"], 1_234_567);
        assert_eq!(stats.meta["last_flush"], 1_760_007_710);
        assert_eq!(stats.meta["coverage_netlink"], 0);
        assert_eq!(stats.meta["coverage_binder"], -1);

        let apps = r.apps(0).unwrap();
        let app = |uid: u32| apps.iter().find(|a| a.uid == uid).unwrap();
        assert_eq!(apps.len(), 4);
        let gallery = app(10123);
        assert_eq!(gallery.pkg, "com.example.gallery");
        assert_eq!((gallery.storage, gallery.provider), (2, 1));
        assert_eq!(gallery.last_ts, 1_760_007_705);
        let tracker = app(10124);
        assert_eq!(
            [
                tracker.device,
                tracker.system,
                tracker.process,
                tracker.network,
                tracker.command,
                tracker.personal,
                tracker.service,
                tracker.permission,
            ],
            [1; 8]
        );
        assert_eq!(app(10200).pkg, "");
        assert_eq!(app(1_010_123).storage, 1);

        let photo = "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg";
        let files = r
            .files(&[10123], &[Category::Storage], None, None, 0, 50, 0)
            .unwrap();
        assert_eq!(files.len(), 2);
        let merged = files.iter().find(|f| f.path == photo).unwrap();
        assert_eq!((merged.mode, merged.ok, merged.fail), (MODE_READ, 4, 1));
        assert_eq!(
            (merged.first_ts, merged.last_ts),
            (1_760_004_010, 1_760_007_705)
        );
        let document = files.iter().find(|f| f.path != photo).unwrap();
        assert_eq!(
            document.path,
            "/storage/emulated/0/Documents/O'Brien résumé.pdf"
        );
        assert_eq!(document.mode, MODE_READ | MODE_VIA_PROVIDER);
        let queried = r
            .files(&[], &[Category::Personal], None, None, 0, 50, 0)
            .unwrap();
        assert_eq!(queried.len(), 1);
        assert_eq!((queried[0].mode, queried[0].items), (MODE_QUERY, 120));

        let history = r.history(10123, photo, 0).unwrap();
        assert_eq!(
            history
                .iter()
                .map(|h| (h.ts, h.ok, h.fail))
                .collect::<Vec<_>>(),
            [
                (1_760_007_705, 0, 1),
                (1_760_007_700, 1, 0),
                (1_760_004_050, 1, 0),
                (1_760_004_010, 2, 0),
            ]
        );
        assert_eq!(
            r.history(1_010_123, "/storage/emulated/10/Pictures/a.png", 0)
                .unwrap()[0]
                .mode,
            MODE_WRITE
        );
        assert!(
            !r.timeline(0, 1_760_010_000, 100)
                .unwrap()
                .entries
                .is_empty()
        );
        assert!(r.sandbox_entries().unwrap().is_empty());
        assert_eq!(r.sandbox_global().unwrap(), "");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn the_current_version_and_every_release_schema_have_a_way_to_this_one() {
        // The version is raised when a release is made: the release has to
        // add itself to RELEASES, with the database it writes.
        let prop = include_str!("../../module/module.prop");
        let current = prop
            .lines()
            .find_map(|line| line.strip_prefix("version="))
            .unwrap()
            .trim();
        assert!(
            RELEASES.iter().any(|r| r.version == current),
            "{current} has no fixture in RELEASES (see CONTRIBUTING.md)"
        );
        for pair in RELEASES.windows(2) {
            assert!(pair[0].schema <= pair[1].schema, "RELEASES is not in order");
        }
        // The steps from each release's schema, followed as `migrate` does,
        // reach this one.
        for release in RELEASES {
            let mut version = release.schema;
            for _ in 0..=MIGRATIONS.len() {
                if version == SCHEMA_VERSION {
                    break;
                }
                let Some(&(_, to, _)) = MIGRATIONS.iter().find(|(from, _, _)| *from == version)
                else {
                    break;
                };
                version = to;
            }
            assert_eq!(
                version, SCHEMA_VERSION,
                "the steps in MIGRATIONS from schema {} of {} end at {version}, not {SCHEMA_VERSION}",
                release.schema, release.version
            );
        }
    }

    #[test]
    fn the_database_set_aside_is_removed_once_its_records_are_out_of_date() {
        let dir = std::env::temp_dir().join(format!("bbd-test-old-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let db = dir.join("bb.db");
        let old = dir.join("bb.db.old");
        let w = Writer::open(&db).unwrap();
        std::fs::write(&old, "").unwrap();
        std::fs::write(dir.join("bb.db.old-wal"), "").unwrap();
        let day = 86_400;
        let set_aside_at = 100 * day;
        let touch = |path: &Path, at: i64| {
            std::fs::File::options()
                .write(true)
                .open(path)
                .unwrap()
                .set_modified(SystemTime::UNIX_EPOCH + Duration::from_secs(at as u64))
                .unwrap();
        };
        touch(&old, set_aside_at - 10 * day);
        // The newest records are in the log, not yet in the file.
        touch(&dir.join("bb.db.old-wal"), set_aside_at);

        w.prune(30, set_aside_at + 29 * day).unwrap();
        assert!(old.exists());
        w.prune(30, set_aside_at + 31 * day).unwrap();
        assert!(!old.exists());
        assert!(!dir.join("bb.db.old-wal").exists());
        // Nothing set aside: nothing to do.
        w.prune(30, set_aside_at + 31 * day).unwrap();
        drop(w);
        std::fs::remove_dir_all(dir).unwrap();
    }

    fn sandbox_entry_for(uid: u32) -> SandboxEntry {
        SandboxEntry {
            uid,
            package: "com.a".into(),
            enabled: true,
            android_id: "0123456789abcdef".into(),
            saved_ops: "android:camera=default".into(),
            install_id: "test".into(),
            saved_permissions: "android.permission.ACCESS_FINE_LOCATION=granted+user_set".into(),
            created: 1000,
            settings: String::new(),
        }
    }

    #[test]
    fn replacing_an_installation_archives_its_previous_recovery_state() {
        let dir = std::env::temp_dir().join(format!("bbd-retired-install-{}", std::process::id()));
        let w = Writer::open(&dir.join("bb.db")).unwrap();
        let old = sandbox_entry_for(10100);
        let mut new = old.clone();
        new.install_id = "replacement".into();
        new.saved_permissions.clear();
        w.put_sandbox(&old).unwrap();
        w.put_sandbox(&new).unwrap();
        let kept: String = w
            .conn
            .query_row(
                "SELECT saved_permissions FROM sandbox_retired WHERE uid=?1 AND install_id=?2",
                params![old.uid, old.install_id],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(kept, old.saved_permissions);
        assert_eq!(w.sandbox_entries().unwrap(), [new]);
        drop(w);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_sandbox_entry_is_written_over_and_survives_reopening() {
        let dir = std::env::temp_dir().join(format!("bbd-sandbox-{}", std::process::id()));
        let db = dir.join("bb.db");
        let w = Writer::open(&db).unwrap();
        assert_eq!(w.sandbox_entry(10100).unwrap(), None);
        w.put_sandbox(&sandbox_entry_for(10100)).unwrap();
        w.put_sandbox(&sandbox_entry_for(1_010_100)).unwrap();
        let disabled = SandboxEntry {
            enabled: false,
            ..sandbox_entry_for(10100)
        };
        w.put_sandbox(&disabled).unwrap();
        assert_eq!(w.sandbox_entry(10100).unwrap(), Some(disabled));
        // Enabling again writes over the same row.
        w.put_sandbox(&sandbox_entry_for(10100)).unwrap();
        assert_eq!(
            w.sandbox_entries().unwrap(),
            [sandbox_entry_for(10100), sandbox_entry_for(1_010_100)]
        );
        // The packages of a uid are written over, and go with its entry.
        let pair = ["com.a".to_string(), "com.b".to_string()];
        w.put_sandbox_packages(1_010_100, &pair).unwrap();
        assert_eq!(
            w.sandbox_packages(1_010_100).unwrap(),
            pair.iter().cloned().collect()
        );
        w.put_sandbox_packages(1_010_100, &pair[1..]).unwrap();
        assert_eq!(w.sandbox_packages(1_010_100).unwrap().len(), 1);
        w.delete_sandbox(1_010_100).unwrap();
        assert_eq!(w.sandbox_entries().unwrap(), [sandbox_entry_for(10100)]);
        assert!(w.sandbox_packages(1_010_100).unwrap().is_empty());
        drop(w);
        // It survives the daemon starting again.
        assert_eq!(
            Writer::open(&db).unwrap().sandbox_entry(10100).unwrap(),
            Some(sandbox_entry_for(10100))
        );
        std::fs::remove_dir_all(dir).unwrap();
    }

    fn session(start_ts: i64, end_ts: Option<i64>) -> SensorSession {
        SensorSession {
            uid: 10100,
            pid: 4242,
            proc_start: 777,
            connection: 9,
            handle: 1,
            sensor: "android.sensor.gyroscope".into(),
            continuous: true,
            period_ns: 0,
            start_ts,
            end_ts,
        }
    }

    #[test]
    fn sensor_sessions_are_written_over_and_read_by_overlap() {
        let dir = std::env::temp_dir().join(format!("bbd-sensors-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        // Written while on, then again when it ended.
        w.sensor_session(session(1000, None));
        w.flush(&Counters::default(), 1001).unwrap();
        assert_eq!(w.open_sensor_sessions().unwrap(), [session(1000, None)]);
        w.sensor_session(session(1000, Some(1600)));
        // At 20 ms, and a light sensor, which has no rate to speak of.
        w.sensor_session(SensorSession {
            period_ns: 20_000_000,
            ..session(2000, Some(2300))
        });
        w.sensor_session(SensorSession {
            handle: 5,
            sensor: "android.sensor.light".into(),
            continuous: false,
            ..session(3000, None)
        });
        // Another app's.
        w.sensor_session(SensorSession {
            uid: 10200,
            ..session(3000, None)
        });
        w.flush(&Counters::default(), 3001).unwrap();
        let r = Reader::open(&db).unwrap();
        let rows = r.sensor_sessions(10100, 0).unwrap();
        assert_eq!(
            rows,
            [
                SensorSessionRow {
                    sensor: "android.sensor.light".into(),
                    high_rate: false,
                    period_ns: None,
                    rate_hz: None,
                    start_ts: 3000,
                    end_ts: None,
                },
                SensorSessionRow {
                    sensor: "android.sensor.gyroscope".into(),
                    high_rate: false,
                    period_ns: Some(20_000_000),
                    rate_hz: Some(50.0),
                    start_ts: 2000,
                    end_ts: Some(2300),
                },
                SensorSessionRow {
                    sensor: "android.sensor.gyroscope".into(),
                    high_rate: true,
                    period_ns: Some(0),
                    rate_hz: None,
                    start_ts: 1000,
                    end_ts: Some(1600),
                },
            ]
        );
        assert_eq!(
            serde_json::to_string(&rows[0]).unwrap(),
            r#"{"sensor":"android.sensor.light","high_rate":false,"start_ts":3000,"end_ts":null}"#
        );
        // Those that ended before the range starts are left out; those still
        // on are not.
        let recent = r.sensor_sessions(10100, 2400).unwrap();
        assert_eq!(recent.len(), 1);
        assert_eq!(recent[0].end_ts, None);
        // Kept as long as the other records, and while they are on.
        w.prune(1, 1600 + 86_400 + 1).unwrap();
        assert_eq!(r.sensor_sessions(10100, 0).unwrap().len(), 2);
        drop(r);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn the_timeline_lists_what_clipboard_and_sensor_entries_hold() {
        let dir =
            std::env::temp_dir().join(format!("bbd-timeline-subjects-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        let t = 1_000_000 * 3600;
        let read = "binder:android.content.IClipboard/getPrimaryClip".to_string();
        let write = "binder:android.content.IClipboard/setPrimaryClip".to_string();
        let sv = Category::Service;
        for (path, ts) in [(&read, t + 1), (&read, t + 2), (&write, t + 3)] {
            w.record(10100, "com.a", sv, path.clone(), MODE_CALL, true, ts, 0);
        }
        let refused = "appop:android:read_clipboard".to_string();
        w.record(
            10100,
            "com.a",
            Category::Permission,
            refused.clone(),
            MODE_READ,
            false,
            t + 4,
            0,
        );
        let mac = "/sys/class/net/wlan0/address".to_string();
        w.record(
            10100,
            "com.a",
            Category::Device,
            mac,
            MODE_READ,
            true,
            t + 5,
            0,
        );
        w.flush(&Counters::default(), t + 10).unwrap();
        let r = Reader::open(&db).unwrap();
        let page = r.timeline(0, t + 3600, 100).unwrap();
        let subjects = |cat: &str| {
            page.entries
                .iter()
                .find(|e| e.cat == cat)
                .unwrap()
                .subjects
                .as_ref()
                .map(|s| {
                    s.iter()
                        .map(|s| (s.path.as_str(), s.ok, s.fail))
                        .collect::<Vec<_>>()
                })
        };
        assert_eq!(
            subjects("service"),
            Some(vec![(read.as_str(), 2, 0), (write.as_str(), 1, 0)])
        );
        assert_eq!(subjects("permission"), Some(vec![(refused.as_str(), 0, 1)]));
        // Not for other kinds, whose records the title already describes.
        assert_eq!(subjects("device"), None);
        drop(r);
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
            history.iter().map(|h| h.ts).collect::<Vec<_>>(),
            [t + 3600, t]
        );
        // Each says which path it is of; one path's alone need not.
        assert_eq!(
            history
                .iter()
                .map(|h| h.path.as_deref())
                .collect::<Vec<_>>(),
            [Some(song), Some(photo)]
        );
        let one = r.history_of(10200, &[photo.to_string()], 0).unwrap();
        assert_eq!(one[0].path, None);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
