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

//! What is stored of the network of sandboxed apps: where the apps the
//! user has the connections recorded of connected to, an hour at a time,
//! and the destinations each app is kept from.

use std::collections::HashMap;

use rusqlite::{Transaction, params};

use super::{Reader, Writer};

/// The tables of this module, in a new database and as the step from the
/// schema before them.
///
/// A `net_dest` row is one hour in which an app connected to a port of an
/// address, under the name it had looked up for the address ('' where it
/// had looked none up). `sent` and `recv` are the bytes of the connections
/// that ended in the hour and were counted, `counted` how many those were:
/// not every connection is. `refused` are the attempts the sandbox refused.
///
/// A `net_rule` row is a destination an app is kept from, as the user
/// named it (`netctl::Target`). `net_recording` says since when an app's
/// connections are recorded.
pub const NET_SCHEMA: &str = "
CREATE TABLE net_dest (
    uid      INTEGER NOT NULL,
    hour     INTEGER NOT NULL,   -- unix time / 3600
    host     TEXT    NOT NULL,
    addr     TEXT    NOT NULL,
    port     INTEGER NOT NULL,
    conn     INTEGER NOT NULL,
    refused  INTEGER NOT NULL,
    sent     INTEGER NOT NULL,
    recv     INTEGER NOT NULL,
    counted  INTEGER NOT NULL,
    first_ts INTEGER NOT NULL,
    last_ts  INTEGER NOT NULL,
    PRIMARY KEY (uid, hour, host, addr, port)
) WITHOUT ROWID;
CREATE TABLE net_rule (
    uid     INTEGER NOT NULL,
    target  TEXT    NOT NULL,
    created INTEGER NOT NULL,
    manual  INTEGER NOT NULL,
    PRIMARY KEY (uid, target)
) WITHOUT ROWID;
CREATE TABLE net_recording (
    uid   INTEGER PRIMARY KEY,
    since INTEGER NOT NULL
) WITHOUT ROWID;
";

/// What a `net_dest` row is about.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct NetKey {
    pub uid: u32,
    pub hour: i64,
    pub host: String,
    pub addr: String,
    pub port: u16,
}

/// What is added to a `net_dest` row.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct NetSeen {
    pub conn: u32,
    pub refused: u32,
    pub sent: u64,
    pub recv: u64,
    /// The connections `sent` and `recv` are of.
    pub counted: u32,
}

/// A `net_dest` row, as read.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NetRow {
    pub hour: i64,
    pub host: String,
    pub addr: String,
    pub port: u16,
    pub seen: NetSeen,
    pub first_ts: i64,
    pub last_ts: i64,
}

/// A destination an app is kept from.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NetRule {
    pub uid: u32,
    /// As `netctl::Target` writes it.
    pub target: String,
    pub created: i64,
    /// Whether the user typed it rather than chose it among the records.
    pub manual: bool,
}

pub enum RuleChange {
    Block { created: i64, manual: bool },
    Unblock,
}

/// Rows not yet written, with when each was first and last added to.
pub type Pending = HashMap<NetKey, (NetSeen, i64, i64)>;

impl Writer {
    /// Add `seen`, at `ts`, to what `key` holds. Buffered like the records.
    pub fn net_record(&mut self, key: NetKey, seen: NetSeen, ts: i64) {
        let entry = self
            .net
            .get_mut()
            .entry(key)
            .or_insert((NetSeen::default(), ts, ts));
        entry.0.conn += seen.conn;
        entry.0.refused += seen.refused;
        entry.0.sent += seen.sent;
        entry.0.recv += seen.recv;
        entry.0.counted += seen.counted;
        entry.1 = entry.1.min(ts);
        entry.2 = entry.2.max(ts);
    }

    /// Write the buffered rows in `tx`.
    pub(super) fn write_net(net: &Pending, tx: &Transaction<'_>) -> rusqlite::Result<()> {
        let mut upsert = tx.prepare_cached(
            "INSERT INTO net_dest
                 (uid, hour, host, addr, port, conn, refused, sent, recv, counted, first_ts, last_ts)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
             ON CONFLICT (uid, hour, host, addr, port) DO UPDATE SET
                 conn = conn + excluded.conn,
                 refused = refused + excluded.refused,
                 sent = sent + excluded.sent,
                 recv = recv + excluded.recv,
                 counted = counted + excluded.counted,
                 first_ts = min(first_ts, excluded.first_ts),
                 last_ts = max(last_ts, excluded.last_ts)",
        )?;
        for (key, (seen, first, last)) in net {
            upsert.execute(params![
                key.uid,
                key.hour,
                key.host,
                key.addr,
                key.port,
                seen.conn,
                seen.refused,
                seen.sent as i64,
                seen.recv as i64,
                seen.counted,
                first,
                last
            ])?;
        }
        Ok(())
    }

    /// Delete the rows of hours before `cutoff` (unix time).
    pub(super) fn prune_net(&self, cutoff: i64) -> rusqlite::Result<usize> {
        self.conn.execute(
            "DELETE FROM net_dest WHERE hour < ?1",
            params![cutoff / 3600],
        )
    }

    /// Delete what is recorded of the network of `uid`, written or not;
    /// how many rows. The destinations it is kept from stay.
    pub fn net_clear(&self, uid: u32) -> anyhow::Result<usize> {
        self.net.borrow_mut().retain(|key, _| key.uid != uid);
        Ok(self
            .conn
            .execute("DELETE FROM net_dest WHERE uid = ?1", params![uid])?)
    }

    /// Delete all that is kept of the network of `uid`: its records, its
    /// rules and that it is recorded. For a uid that has gone to another
    /// app, which none of them is about.
    pub fn net_forget(&self, uid: u32) -> anyhow::Result<()> {
        self.net_clear(uid)?;
        self.conn
            .execute("DELETE FROM net_rule WHERE uid = ?1", params![uid])?;
        self.conn
            .execute("DELETE FROM net_recording WHERE uid = ?1", params![uid])?;
        Ok(())
    }

    /// Every destination an app is kept from.
    pub fn net_rules(&self) -> anyhow::Result<Vec<NetRule>> {
        net_rules(&self.conn, None)
    }

    /// Commit the rule through to disk only if its effects could be applied.
    pub fn change_net_rule(
        &self,
        uid: u32,
        target: &str,
        change: RuleChange,
        apply: impl FnOnce() -> anyhow::Result<()>,
    ) -> anyhow::Result<()> {
        self.write_sandbox(|tx| {
            match change {
                RuleChange::Block { created, manual } => {
                    tx.execute(
                        "INSERT INTO net_rule (uid, target, created, manual) VALUES (?1, ?2, ?3, ?4)
                         ON CONFLICT (uid, target) DO NOTHING",
                        params![uid, target, created, manual],
                    )?;
                }
                RuleChange::Unblock => {
                    tx.execute(
                        "DELETE FROM net_rule WHERE uid = ?1 AND target = ?2",
                        params![uid, target],
                    )?;
                }
            }
            apply()
        })
    }

    /// How many destinations each uid is kept from.
    pub fn net_rule_counts(&self) -> anyhow::Result<HashMap<u32, u32>> {
        net_rule_counts(&self.conn)
    }

    /// Since when the connections of each uid are recorded.
    pub fn net_recording(&self) -> anyhow::Result<HashMap<u32, i64>> {
        net_recording(&self.conn)
    }

    /// Note that the connections of `uids`, and of no other, are recorded
    /// now: one recorded already keeps the time it has been since.
    pub fn set_net_recording(&self, uids: &[u32], now: i64) -> anyhow::Result<()> {
        let had = net_recording(&self.conn)?;
        for uid in uids.iter().filter(|uid| !had.contains_key(uid)) {
            self.conn.execute(
                "INSERT INTO net_recording (uid, since) VALUES (?1, ?2)",
                params![uid, now],
            )?;
        }
        for uid in had.keys().filter(|uid| !uids.contains(uid)) {
            self.conn
                .execute("DELETE FROM net_recording WHERE uid = ?1", params![uid])?;
        }
        Ok(())
    }
}

fn net_rule_counts(conn: &rusqlite::Connection) -> anyhow::Result<HashMap<u32, u32>> {
    let mut stmt = conn.prepare("SELECT uid, count(*) FROM net_rule GROUP BY uid")?;
    let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?;
    Ok(rows.collect::<Result<_, _>>()?)
}

fn net_recording(conn: &rusqlite::Connection) -> anyhow::Result<HashMap<u32, i64>> {
    let mut stmt = conn.prepare("SELECT uid, since FROM net_recording")?;
    let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?;
    Ok(rows.collect::<Result<_, _>>()?)
}

fn net_rules(conn: &rusqlite::Connection, uid: Option<u32>) -> anyhow::Result<Vec<NetRule>> {
    let mut stmt = conn.prepare(
        "SELECT uid, target, created, manual FROM net_rule
         WHERE ?1 IS NULL OR uid = ?1 ORDER BY uid, created, target",
    )?;
    let rows = stmt.query_map(params![uid], |r| {
        Ok(NetRule {
            uid: r.get(0)?,
            target: r.get(1)?,
            created: r.get(2)?,
            manual: r.get(3)?,
        })
    })?;
    Ok(rows.collect::<Result<_, _>>()?)
}

impl Reader {
    /// The destinations `uid` is kept from, oldest first.
    pub fn net_rules(&self, uid: u32) -> anyhow::Result<Vec<NetRule>> {
        net_rules(&self.conn, Some(uid))
    }

    /// Since when the connections of each uid are recorded.
    pub fn net_recording(&self) -> anyhow::Result<HashMap<u32, i64>> {
        net_recording(&self.conn)
    }

    /// What is recorded of the network of `uid` in the hours from `since`
    /// up to, not including, `until` (unix times).
    pub fn net_rows(&self, uid: u32, since: i64, until: i64) -> anyhow::Result<Vec<NetRow>> {
        let mut stmt = self.conn.prepare(
            "SELECT hour, host, addr, port, conn, refused, sent, recv, counted, first_ts, last_ts
             FROM net_dest WHERE uid = ?1 AND hour >= ?2 AND hour < ?3
             ORDER BY hour, host, addr, port",
        )?;
        let rows = stmt.query_map(
            params![uid, since.div_euclid(3600), (until + 3599).div_euclid(3600)],
            |r| {
                Ok(NetRow {
                    hour: r.get(0)?,
                    host: r.get(1)?,
                    addr: r.get(2)?,
                    port: r.get(3)?,
                    seen: NetSeen {
                        conn: r.get(4)?,
                        refused: r.get(5)?,
                        sent: r.get::<_, i64>(6)? as u64,
                        recv: r.get::<_, i64>(7)? as u64,
                        counted: r.get(8)?,
                    },
                    first_ts: r.get(9)?,
                    last_ts: r.get(10)?,
                })
            },
        )?;
        Ok(rows.collect::<Result<_, _>>()?)
    }
}
