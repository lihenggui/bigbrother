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
};

use anyhow::Context as _;
use rusqlite::{Connection, OpenFlags, OptionalExtension as _, Transaction, params};
use serde::Serialize;

use crate::record::{Category, Mode, SensorSession, is_high_rate};

pub mod net;

pub const DEFAULT_DB: &str = "/data/adb/big_brother/bb.db";

/// Schema version stored in SQLite's `user_version`. Bump it when the layout changes.
const SCHEMA_VERSION: i32 = 12;

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

/// Most entries returned for one path's history.
const HISTORY_LIMIT: u32 = 2000;

/// Most sensor sessions returned for one app.
const SENSOR_SESSIONS_LIMIT: u32 = 2000;

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
    install_id TEXT    NOT NULL
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
    /// Refused DNS lookups the resolver could not queue for recording.
    pub dns_lookups_dropped: u64,
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
    /// What sandboxed apps connected to, not yet written (see `net`).
    net: std::cell::RefCell<net::Pending>,
    /// The counters last written.
    written: Option<Counters>,
    /// Told the uids whose network records `net_clear` deletes, when
    /// another connection holds records not written yet.
    net_cleared: Option<std::sync::mpsc::Sender<u32>>,
}

impl Writer {
    /// Open the current database, creating it only when empty so a schema
    /// mismatch cannot discard the sandbox's saved grants.
    pub fn open(path: &Path) -> anyhow::Result<Self> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir).with_context(|| format!("creating {}", dir.display()))?;
        }
        let mut conn =
            Connection::open(path).with_context(|| format!("opening {}", path.display()))?;
        create_schema(&mut conn)?;
        // WAL lets `bbd` query commands read while the daemon writes.
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.pragma_update(None, "journal_size_limit", 8 * 1024 * 1024)?;
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
            net: std::cell::RefCell::new(HashMap::new()),
            written: None,
            net_cleared: None,
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
        !self.pending.is_empty()
            || !self.events.is_empty()
            || !self.sessions.is_empty()
            || !self.net.borrow().is_empty()
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
            self.net.get_mut().clear();
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
                ("dns_lookups_dropped", counters.dns_lookups_dropped as i64),
                ("last_flush", now),
            ] {
                meta.execute(params![key, value])?;
            }
        }
        Self::write_net(&self.net.borrow(), &tx)?;
        tx.commit()?;
        // Only now: if anything above failed, the rows stay buffered and
        // are written by the next flush.
        self.pending.clear();
        self.events.clear();
        self.sessions.clear();
        self.net.get_mut().clear();
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
        self.conn
            .execute("DELETE FROM event WHERE ts < ?1", params![cutoff])?;
        self.conn.execute(
            "DELETE FROM sensor_session WHERE end_ts < ?1",
            params![cutoff],
        )?;
        self.delete_unused_paths()?;
        self.prune_net(cutoff)?;
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
        self.write_sandbox(|tx| Self::write_sandbox_entry(tx, entry))
    }

    /// Take an old installation out and discard the network state tied to
    /// its UID together, so that either both are done or retirement can be
    /// tried again on the next check for a changed installation.
    pub fn retire_sandbox(&self, entry: &SandboxEntry) -> anyhow::Result<()> {
        let mut retired = entry.clone();
        retired.enabled = false;
        self.write_sandbox(|tx| {
            Self::write_sandbox_entry(tx, &retired)?;
            Self::delete_net_for_uid(tx, entry.uid)?;
            Ok(())
        })?;
        self.forget_pending_net(entry.uid);
        Ok(())
    }

    fn write_sandbox_entry(tx: &Transaction<'_>, entry: &SandboxEntry) -> anyhow::Result<()> {
        tx.execute(
            "INSERT INTO sandbox (uid, package, enabled, android_id, saved_ops, saved_permissions,
                                  created, settings, install_id)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
             ON CONFLICT (uid) DO UPDATE SET
                 package = excluded.package, enabled = excluded.enabled,
                 android_id = excluded.android_id, saved_ops = excluded.saved_ops,
                 saved_permissions = excluded.saved_permissions,
                 created = excluded.created, settings = excluded.settings, install_id = excluded.install_id",
            params![
                entry.uid, entry.package, entry.enabled, entry.android_id, entry.saved_ops,
                entry.saved_permissions, entry.created, entry.settings, entry.install_id,
            ],
        )?;
        Ok(())
    }

    /// Sync the WAL at commit, even when readers prevent a checkpoint.
    fn write_sandbox(
        &self,
        write: impl FnOnce(&Transaction<'_>) -> anyhow::Result<()>,
    ) -> anyhow::Result<()> {
        self.conn.pragma_update(None, "synchronous", "FULL")?;
        let result = (|| {
            let tx = self.conn.unchecked_transaction()?;
            write(&tx)?;
            tx.commit()?;
            Ok(())
        })();
        if let Err(e) = self.conn.pragma_update(None, "synchronous", "NORMAL") {
            // A commit may already have succeeded. Report the transaction result.
            eprintln!("bbd: could not restore the recording sync mode: {e}");
        }
        result
    }

    /// Forget the entry for `uid`. Only for undoing the first enable of an
    /// app when it failed: a disabled entry is otherwise kept.
    pub fn delete_sandbox(&self, uid: u32) -> anyhow::Result<()> {
        self.write_sandbox(|tx| {
            tx.execute("DELETE FROM sandbox WHERE uid = ?1", params![uid])?;
            tx.execute("DELETE FROM sandbox_packages WHERE uid = ?1", params![uid])?;
            Ok(())
        })
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
        self.write_sandbox(|tx| {
            tx.execute("DELETE FROM sandbox_packages WHERE uid = ?1", params![uid])?;
            for package in packages {
                tx.execute(
                    "INSERT OR IGNORE INTO sandbox_packages (uid, package) VALUES (?1, ?2)",
                    params![uid, package],
                )?;
            }
            Ok(())
        })
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
        self.write_sandbox(|tx| {
            tx.execute("DELETE FROM sandbox_global", [])?;
            for (item, mode) in pairs {
                tx.execute(
                    "INSERT INTO sandbox_global (item, mode) VALUES (?1, ?2)",
                    params![item, mode],
                )?;
            }
            Ok(())
        })
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
        self.write_sandbox(|tx| {
            tx.execute(
                "INSERT INTO meta (key, value) VALUES (?1, ?2)
                 ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                params![SANDBOX_AUTO, on],
            )?;
            tx.execute("DELETE FROM sandbox_known", [])?;
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
            Ok(())
        })
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

fn has_schema(conn: &Connection) -> anyhow::Result<bool> {
    let version = version_of(conn)?;
    if version == 0 && !has_tables(conn)? {
        return Ok(false);
    }
    anyhow::ensure!(
        version == SCHEMA_VERSION,
        "unsupported database schema {version} (expected {SCHEMA_VERSION}); use the previous module to uninstall before installing this build"
    );
    Ok(true)
}

/// Check a new or existing database before the installer replaces its daemon.
pub fn check_module_database(path: &Path) -> anyhow::Result<()> {
    if path.try_exists()? {
        let conn = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
        has_schema(&conn)?;
    }
    Ok(())
}

/// Create the schema in a new database; one that has it is left alone.
fn create_schema(conn: &mut Connection) -> anyhow::Result<()> {
    // One transaction for the tables and the version that records them: if
    // the process dies half way, the next start finds an empty database.
    let conn = conn.transaction()?;
    if has_schema(&conn)? {
        return Ok(());
    }
    conn.execute_batch(SCHEMA)?;
    conn.execute_batch(RETIRED_SANDBOX)?;
    conn.execute_batch(SANDBOX_AUTO_SCHEMA)?;
    conn.execute_batch(net::NET_SCHEMA)?;
    conn.pragma_update(None, "user_version", SCHEMA_VERSION)?;
    conn.commit()?;
    Ok(())
}

// ---- read side -------------------------------------------------------------

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

/// One period an app had a sensor on, as `bbd sensors` prints it.
#[derive(Serialize, Debug, PartialEq)]
pub struct SensorSessionRow {
    /// The sensor's type (`android.sensor.accelerometer`); empty if it could
    /// not be identified.
    pub sensor: String,
    /// Whether the rate asked for is high (see `crate::record::HIGH_RATE_PERIOD_NS`).
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

#[derive(Serialize)]
pub struct Stats {
    pub rows: i64,
    pub apps: i64,
    pub oldest_ts: Option<i64>,
    pub newest_ts: Option<i64>,
    pub meta: HashMap<String, i64>,
}

pub struct Reader {
    conn: Connection,
}

impl Reader {
    /// Query commands require the same database layout as the daemon.
    pub fn open(path: &Path) -> anyhow::Result<Self> {
        let conn = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)
            .with_context(|| format!("opening {} (has the daemon run yet?)", path.display()))?;
        if !has_schema(&conn)? {
            anyhow::bail!(
                "{} has no records yet (has the daemon run yet?)",
                path.display()
            );
        }
        Ok(Self { conn })
    }

    /// Reports use the same read-only, schema-checked connection.
    pub(crate) fn connection(&self) -> &Connection {
        &self.conn
    }

    /// Every sandbox entry, enabled or not, by uid.
    pub fn sandbox_entries(&self) -> anyhow::Result<Vec<SandboxEntry>> {
        sandbox_entries(&self.conn)
    }

    /// The global settings of the sandbox, as `item=mode,...`.
    pub fn sandbox_global(&self) -> anyhow::Result<String> {
        sandbox_global(&self.conn)
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

    /// The periods one app had sensors on that overlap the time from
    /// `since`, most recent start first.
    pub fn sensor_sessions(&self, uid: u32, since: i64) -> anyhow::Result<Vec<SensorSessionRow>> {
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
                high_rate: is_high_rate(continuous, period_ns),
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
    use std::time::Duration;

    use super::*;
    use crate::{record::*, report};

    #[test]
    fn sandbox_commits_are_durable_while_a_reader_holds_an_old_snapshot() {
        let dir = std::env::temp_dir().join(format!("bbd-sandbox-sync-{}", std::process::id()));
        let db = dir.join("bb.db");
        let writer = Writer::open(&db).unwrap();
        writer.conn.busy_timeout(Duration::ZERO).unwrap();
        writer
            .conn
            .execute_batch(
                "PRAGMA wal_autocheckpoint = 0;
                 PRAGMA wal_checkpoint(TRUNCATE);
                 CREATE TEMP TABLE sync_mode (value INTEGER NOT NULL);
                 CREATE TEMP TRIGGER observe_sync BEFORE INSERT ON main.sandbox BEGIN
                     INSERT INTO sync_mode SELECT synchronous FROM pragma_synchronous;
                 END;",
            )
            .unwrap();
        let reader = Connection::open_with_flags(&db, OpenFlags::SQLITE_OPEN_READ_ONLY).unwrap();
        reader.execute_batch("BEGIN").unwrap();
        let count = || {
            reader
                .query_row("SELECT count(*) FROM sandbox", [], |r| r.get::<_, u32>(0))
                .unwrap()
        };
        assert_eq!(count(), 0);
        writer.put_sandbox(&sandbox_entry_for(10100)).unwrap();
        let mode: u32 = writer
            .conn
            .query_row("SELECT value FROM sync_mode", [], |r| r.get(0))
            .unwrap();
        assert_eq!(mode, 2, "the sandbox write must use synchronous=FULL");
        let mode: u32 = writer
            .conn
            .pragma_query_value(None, "synchronous", |r| r.get(0))
            .unwrap();
        assert_eq!(mode, 1, "telemetry keeps synchronous=NORMAL");
        assert_eq!(count(), 0);
        reader.execute_batch("ROLLBACK").unwrap();
        assert_eq!(count(), 1);
        drop(reader);
        drop(writer);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn network_cleared_on_another_connection_is_not_written_from_what_was_held() {
        let dir = std::env::temp_dir().join(format!("bbd-net-cleared-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut recorder = Writer::open(&db).unwrap();
        let mut sandbox = Writer::open(&db).unwrap();
        let (cleared, told) = std::sync::mpsc::channel();
        sandbox.tell_net_cleared(cleared);
        let key = |uid| net::NetKey {
            uid,
            hour: 1,
            host: String::new(),
            addr: "203.0.113.1".into(),
            port: 443,
        };
        let seen = net::NetSeen {
            conn: 1,
            ..net::NetSeen::default()
        };
        recorder.net_record(key(10234), seen, 3600);
        recorder.net_record(key(10235), seen, 3600);

        sandbox.net_clear(10234).unwrap();
        // As the event loop does before it writes.
        for uid in told.try_iter() {
            recorder.net_clear(uid).unwrap();
        }
        recorder.flush(&Counters::default(), 3600).unwrap();

        let uids: Vec<u32> = recorder
            .conn
            .prepare("SELECT uid FROM net_dest")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        assert_eq!(uids, [10235]);
        drop((recorder, sandbox));
        std::fs::remove_dir_all(dir).unwrap();
    }

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
                ..Counters::default()
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
                dns_lookups_dropped: 3,
            },
            t + 30,
        )
        .unwrap();

        let r = Reader::open(&db).unwrap();
        let apps = report::apps(&r, 0).unwrap();
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
            report::files(&r, &[10100], &[], None, None, 0, 50, 0)
                .unwrap()
                .len(),
            2
        );
        let files = report::files(&r, &[10100], &[st], None, None, 0, 50, 0).unwrap();
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].cat, "storage");
        assert_eq!(files[0].topic, None);
        let system =
            report::files(&r, &[10100], &[Category::System], None, None, 0, 50, 0).unwrap();
        assert_eq!(system[0].topic, Some("cpu_model"));
        assert_eq!((files[0].ok, files[0].fail), (4, 1));
        assert_eq!((files[0].first_ts, files[0].last_ts), (t, t + 3600));

        assert_eq!(
            report::files(
                &r,
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
            report::files(&r, &[], &[Category::System], None, None, 0, 50, 0)
                .unwrap()
                .len(),
            1
        );
        assert_eq!(
            report::files(&r, &[], &[], None, None, t + 3600, 50, 0)
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
        assert_eq!(stats.meta["dns_lookups_dropped"], 3);

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
        let files =
            report::files(&Reader::open(&db).unwrap(), &[], &[], None, None, 0, 10, 0).unwrap();
        assert_eq!((files.len(), files[0].ok), (1, 1));
        // And they are not written a second time.
        w.flush(&Counters::default(), t + 2).unwrap();
        let files =
            report::files(&Reader::open(&db).unwrap(), &[], &[], None, None, 0, 10, 0).unwrap();
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
        let files =
            report::files(&r, &[10100], &[Category::Provider], None, None, 0, 10, 0).unwrap();
        assert_eq!((files.len(), files[0].ok, files[0].items), (1, 3, 3214));
        assert_eq!(report::apps(&r, 0).unwrap()[0].provider, 1);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn an_incompatible_database_is_preserved() {
        let dir = std::env::temp_dir().join(format!("bbd-test-version-{}", std::process::id()));
        let db = dir.join("bb.db");
        check_module_database(&db).unwrap();
        assert!(!db.exists());
        Writer::open(&db)
            .unwrap()
            .put_sandbox_global(&[("camera", "off")])
            .unwrap();
        assert!(Reader::open(&db).is_ok());
        check_module_database(&db).unwrap();

        for version in [0, SCHEMA_VERSION - 1, SCHEMA_VERSION + 1] {
            Connection::open(&db)
                .unwrap()
                .pragma_update(None, "user_version", version)
                .unwrap();
            let message = format!("unsupported database schema {version}");
            assert!(
                check_module_database(&db)
                    .unwrap_err()
                    .to_string()
                    .contains(&message)
            );
            assert!(
                Writer::open(&db)
                    .err()
                    .unwrap()
                    .to_string()
                    .contains(&message)
            );
            assert!(
                Reader::open(&db)
                    .err()
                    .unwrap()
                    .to_string()
                    .contains(&message)
            );

            let conn = Connection::open(&db).unwrap();
            assert_eq!(version_of(&conn).unwrap(), version);
            assert_eq!(sandbox_global(&conn).unwrap(), "camera=off");
        }
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
