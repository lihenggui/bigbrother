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

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
};

use anyhow::Context as _;
use rusqlite::{Connection, OptionalExtension as _, Statement, params};

use crate::record::{Category, Mode, SensorSession};

use super::{net, schema::create_schema};

#[derive(Hash, PartialEq, Eq)]
struct Key {
    hour: i64,
    uid: u32,
    path: String,
    mode: Mode,
}

struct Agg {
    pkg: String,
    cat: Category,
    ok: u32,
    fail: u32,
    first_ts: i64,
    last_ts: i64,
    items: u32,
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
    buffered_bytes: std::cell::Cell<usize>,
    dropped: u64,
    pub(super) conn: Connection,
    /// Where the database is, to find the one set aside next to it.
    path: PathBuf,
    pending: HashMap<Key, Agg>,
    /// Individual accesses not yet written: (allowed, refused) per key.
    events: HashMap<EventKey, (u32, u32)>,
    /// Sensor sessions started, changed or ended, not yet written.
    sessions: Vec<SensorSession>,
    /// What sandboxed apps connected to, not yet written (see `net`).
    pub(super) net: std::cell::RefCell<net::Pending>,
    /// The counters last written.
    written: Option<Counters>,
    /// Told the uids whose network records `net_clear` deletes, when
    /// another connection holds records not written yet.
    pub(super) net_cleared: Option<std::sync::mpsc::Sender<u32>>,
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
            buffered_bytes: std::cell::Cell::new(0),
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

    pub(super) fn reserve_sample(&mut self, bytes: usize) -> bool {
        let buffered = self.buffered_bytes.get();
        if bytes > BUFFER_BYTES - buffered {
            self.dropped = self.dropped.saturating_add(1);
            return false;
        }
        self.buffered_bytes.set(buffered + bytes);
        true
    }

    pub(super) fn release_samples(&self, bytes: usize) {
        self.buffered_bytes.set(self.buffered_bytes.get() - bytes);
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
            self.buffered_bytes.set(0);
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
            upsert_aggregates(&mut upsert, &self.pending)?;
            let mut intern = tx.prepare_cached("INSERT OR IGNORE INTO path (path) VALUES (?1)")?;
            let mut path_id = tx.prepare_cached("SELECT id FROM path WHERE path = ?1")?;
            let mut insert_event = tx.prepare_cached(
                "INSERT INTO event (uid, path_id, mode, ts, ok, fail)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                 ON CONFLICT (uid, path_id, mode, ts) DO UPDATE SET
                     ok = ok + excluded.ok,
                     fail = fail + excluded.fail",
            )?;
            insert_events(&mut intern, &mut path_id, &mut insert_event, &self.events)?;
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
            write_sessions(&mut session, &self.sessions)?;

            let mut meta = tx.prepare_cached(
                "INSERT INTO meta (key, value) VALUES (?1, ?2)
                 ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            )?;
            write_meta(&mut meta, counters, self.dropped, now)?;
        }
        Self::write_net(&self.net.borrow(), &tx)?;
        tx.commit()?;
        // Only now: if anything above failed, the rows stay buffered and
        // are written by the next flush.
        self.pending.clear();
        self.events.clear();
        self.sessions.clear();
        self.net.get_mut().clear();
        self.buffered_bytes.set(0);
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
        self.prune_name_addresses(now)?;
        Ok(self
            .conn
            .execute("DELETE FROM access WHERE last_ts < ?1", params![cutoff])?)
    }

    /// Delete what was recorded of system paths that `recorded` no longer
    /// accepts: records made under a rule that has since been dropped, which
    /// would otherwise stay on every app's page until they age out. Returns
    /// the number of paths forgotten.
    pub fn forget_system_paths(&self, recorded: impl Fn(&str) -> bool) -> anyhow::Result<usize> {
        let dropped: Vec<String> = self
            .conn
            .prepare("SELECT DISTINCT path FROM access WHERE cat IN ('device', 'system', 'process') AND path LIKE '/%'")?
            .query_map([], |row| row.get::<_, String>(0))?
            .filter(|path| !path.as_ref().is_ok_and(|path| recorded(path)))
            .collect::<Result<_, _>>()?;
        for path in &dropped {
            self.conn
                .execute("DELETE FROM access WHERE path = ?1", params![path])?;
            self.conn.execute(
                "DELETE FROM event WHERE path_id IN (SELECT id FROM path WHERE path = ?1)",
                params![path],
            )?;
        }
        if !dropped.is_empty() {
            self.delete_unused_paths()?;
        }
        Ok(dropped.len())
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

fn upsert_aggregates(
    upsert: &mut Statement<'_>,
    pending: &HashMap<Key, Agg>,
) -> rusqlite::Result<()> {
    for (k, a) in pending {
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
    Ok(())
}

fn insert_events(
    intern: &mut Statement<'_>,
    path_id: &mut Statement<'_>,
    insert_event: &mut Statement<'_>,
    events: &HashMap<EventKey, (u32, u32)>,
) -> rusqlite::Result<()> {
    // Most events of a batch share a handful of paths.
    let mut ids: HashMap<&str, i64> = HashMap::new();
    for (k, (ok, fail)) in events {
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
    Ok(())
}

fn write_sessions(session: &mut Statement<'_>, sessions: &[SensorSession]) -> rusqlite::Result<()> {
    // In the order they changed: the last change of a session wins.
    for s in sessions {
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

    Ok(())
}

fn write_meta(
    meta: &mut Statement<'_>,
    counters: &Counters,
    dropped: u64,
    now: i64,
) -> rusqlite::Result<()> {
    for (key, value) in [
        ("events_seen", counters.seen as i64),
        ("events_kept", counters.kept as i64),
        ("events_unresolved", counters.unresolved as i64),
        (
            "events_dropped",
            counters.dropped.saturating_add(dropped) as i64,
        ),
        ("dns_lookups_dropped", counters.dns_lookups_dropped as i64),
        ("last_flush", now),
    ] {
        meta.execute(params![key, value])?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        record::*,
        report,
        store::{Reader, reader::HistoryRow, tests::sandbox_entry_for},
    };

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
    fn network_rows_share_the_telemetry_budget_and_existing_rows_still_update() {
        let dir = std::env::temp_dir().join(format!("bbd-net-limit-{}", std::process::id()));
        let mut writer = Writer::open(&dir.join("bb.db")).unwrap();
        let large = net::NetKey {
            uid: 10100,
            hour: 0,
            host: String::with_capacity(BUFFER_BYTES - 160),
            addr: "203.0.113.1".into(),
            port: 443,
        };
        let excess = net::NetKey {
            uid: 10200,
            host: "example.com".into(),
            ..large.clone()
        };
        let seen = net::NetSeen {
            conn: 1,
            ..Default::default()
        };
        let existing = large.clone();
        writer.net_record(large, seen, 2);
        assert!(writer.buffered_bytes.get() >= BUFFER_BYTES - 160);
        assert!(writer.buffered_bytes.get() <= BUFFER_BYTES);
        writer.net_record(excess.clone(), seen, 4);
        assert_eq!(writer.net.get_mut().len(), 1);
        assert_eq!(writer.dropped, 1);
        writer.net_record(existing.clone(), seen, 5);
        assert_eq!(
            writer.net.get_mut()[&existing],
            (net::NetSeen { conn: 2, ..seen }, 2, 5)
        );
        assert_eq!(writer.dropped, 1);
        writer.net_clear(existing.uid).unwrap();
        assert_eq!(writer.buffered_bytes.get(), 0);
        writer.net_record(excess.clone(), seen, 6);
        assert!(writer.net.get_mut().contains_key(&excess));
        drop(writer);
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
        record_accesses(&mut w, t, &photo);

        let r = Reader::open(&db).unwrap();
        check_access_reports(&r, t);
        check_access_history_and_stats(&r, t, &photo);

        assert_eq!(w.prune(1, t + 3 * 86_400).unwrap(), 4);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn forgets_system_paths_no_longer_recorded() {
        let dir = std::env::temp_dir().join(format!("bbd-test-forget-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        let t = 1_000_000 * 3600;
        let photo = "/storage/emulated/0/DCIM/a.jpg".to_string();
        record_accesses(&mut w, t, &photo);
        let boot_id = "/proc/sys/kernel/random/boot_id";
        w.record(
            10100,
            "com.a",
            Category::System,
            boot_id.into(),
            MODE_READ,
            true,
            t + 3,
            0,
        );
        w.flush(&Counters::default(), t + 10).unwrap();

        let forgotten = w
            .forget_system_paths(|path| path == "/proc/cpuinfo")
            .unwrap();

        assert_eq!(forgotten, 1);
        let r = Reader::open(&db).unwrap();
        assert!(r.history(10100, boot_id, 0).unwrap().is_empty());
        assert_eq!(r.history(10100, "/proc/cpuinfo", 0).unwrap().len(), 1);
        assert!(!r.history(10100, &photo, 0).unwrap().is_empty());
        std::fs::remove_dir_all(dir).unwrap();
    }

    fn record_accesses(w: &mut Writer, t: i64, photo: &str) {
        let st = Category::Storage;
        // The last access falls in the next hour and merges only at query time.
        for (ts, ok) in [(t, true), (t + 5, true), (t + 9, false), (t + 3600, true)] {
            w.record(10100, "com.a", st, photo.to_owned(), MODE_READ, ok, ts, 0);
        }
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
            photo.to_owned(),
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
    }

    fn check_access_reports(r: &Reader, t: i64) {
        let apps = report::apps(r, 0).unwrap();
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
            report::files(r, &[10100], &[], None, None, 0, 50, 0)
                .unwrap()
                .len(),
            2
        );
        let files = report::files(r, &[10100], &[Category::Storage], None, None, 0, 50, 0).unwrap();
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].cat, "storage");
        assert_eq!(files[0].topic, None);
        let system = report::files(r, &[10100], &[Category::System], None, None, 0, 50, 0).unwrap();
        assert_eq!(system[0].topic, Some("cpu_model"));
        assert_eq!((files[0].ok, files[0].fail), (4, 1));
        assert_eq!((files[0].first_ts, files[0].last_ts), (t, t + 3600));

        assert_eq!(
            report::files(
                r,
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
            report::files(r, &[], &[Category::System], None, None, 0, 50, 0)
                .unwrap()
                .len(),
            1
        );
        assert_eq!(
            report::files(r, &[], &[], None, None, t + 3600, 50, 0)
                .unwrap()
                .len(),
            1
        );
    }

    fn check_access_history_and_stats(r: &Reader, t: i64, photo: &str) {
        // Every access to the photo, most recent first.
        let history = r.history(10100, photo, 0).unwrap();
        assert_eq!(history[0].ts, t + 3600);
        assert_eq!(history.last().unwrap().ts, t);
        assert!(history.windows(2).all(|w| w[0].ts >= w[1].ts));
        let total = |f: fn(&HistoryRow) -> i64| history.iter().map(f).sum::<i64>();
        assert_eq!((total(|h| h.ok), total(|h| h.fail)), (4, 1));
        assert_eq!(r.history(10100, photo, t + 3600).unwrap().len(), 1);
        assert!(r.history(10200, photo, 0).unwrap().is_empty());

        let stats = r.stats().unwrap();
        assert_eq!((stats.rows, stats.apps), (4, 2));
        assert_eq!(stats.meta["events_seen"], 10);
        assert_eq!(stats.meta["events_dropped"], 2);
        assert_eq!(stats.meta["dns_lookups_dropped"], 3);
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
}
