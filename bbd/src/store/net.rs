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

use std::collections::{HashMap, HashSet};

use rusqlite::{Transaction, TransactionBehavior, params};

use super::{Reader, Writer};

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
    /// A stored target such as `domain:example.com` or `ip:203.0.113.7:443`.
    pub target: String,
    pub created: i64,
    /// Whether the user typed it rather than chose it among the records.
    pub manual: bool,
}

pub struct NameAddress {
    pub uid: u32,
    pub target: String,
    pub install_id: String,
    pub address: String,
    pub expires: i64,
}

pub enum RuleChange {
    Block { created: i64, manual: bool },
    Unblock,
}

/// Rows not yet written, with when each was first and last added to.
pub type Pending = HashMap<NetKey, (NetSeen, i64, i64)>;

impl NetKey {
    fn sample_bytes(&self) -> usize {
        // Include the key, counters, timestamps and hash table overhead.
        128 + self.host.capacity() + self.addr.capacity()
    }
}

impl Writer {
    /// Add `seen`, at `ts`, to what `key` holds. Buffered like the records.
    pub fn net_record(&mut self, key: NetKey, seen: NetSeen, ts: i64) {
        if !self.net.get_mut().contains_key(&key) && !self.reserve_sample(key.sample_bytes()) {
            return;
        }
        let entry = self
            .net
            .get_mut()
            .entry(key)
            .or_insert((NetSeen::default(), ts, ts));
        entry.0.conn = entry.0.conn.saturating_add(seen.conn);
        entry.0.refused = entry.0.refused.saturating_add(seen.refused);
        entry.0.sent = entry.0.sent.saturating_add(seen.sent);
        entry.0.recv = entry.0.recv.saturating_add(seen.recv);
        entry.0.counted = entry.0.counted.saturating_add(seen.counted);
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
    pub(super) fn prune_net(tx: &Transaction<'_>, cutoff: i64) -> rusqlite::Result<usize> {
        tx.execute(
            "DELETE FROM net_dest WHERE hour < ?1",
            params![cutoff / 3600],
        )
    }

    pub(super) fn prune_name_addresses(tx: &Transaction<'_>, now: i64) -> rusqlite::Result<()> {
        tx.execute("DELETE FROM net_name_address WHERE expires <= ?1", [now])?;
        Ok(())
    }

    /// Delete what is recorded of the network of `uid`, written or not;
    /// how many rows. The destinations it is kept from stay.
    pub fn net_clear(&self, uid: u32) -> anyhow::Result<usize> {
        self.forget_pending_net(uid);
        Ok(self
            .conn
            .execute("DELETE FROM net_dest WHERE uid = ?1", params![uid])?)
    }

    /// Tell `cleared` of every uid whose network records are cleared, for
    /// the writer that records connections to drop what it holds of them.
    pub fn tell_net_cleared(&mut self, cleared: std::sync::mpsc::Sender<u32>) {
        self.net_cleared = Some(cleared);
    }

    /// Drop what is held of the network of `uid`, here and, through
    /// [`Self::tell_net_cleared`], on the writer that records connections.
    pub(super) fn forget_pending_net(&self, uid: u32) {
        let mut released = 0;
        self.net.borrow_mut().retain(|key, _| {
            if key.uid != uid {
                return true;
            }
            released += key.sample_bytes();
            false
        });
        self.release_samples(released);
        if let Some(cleared) = &self.net_cleared {
            // A recorder that has exited has no buffered rows to clear.
            let _ = cleared.send(uid);
        }
    }

    /// Delete network state for a UID as part of retiring its old owner.
    pub(super) fn delete_net_for_uid(tx: &Transaction<'_>, uid: u32) -> rusqlite::Result<()> {
        tx.execute("DELETE FROM net_dest WHERE uid = ?1", params![uid])?;
        tx.execute("DELETE FROM net_rule WHERE uid = ?1", params![uid])?;
        tx.execute("DELETE FROM net_recording WHERE uid = ?1", params![uid])?;
        Ok(())
    }

    /// Every destination an app is kept from.
    pub fn net_rules(&self) -> anyhow::Result<Vec<NetRule>> {
        net_rules(&self.conn, None)
    }

    pub fn name_addresses(&self, now: i64) -> anyhow::Result<Vec<NameAddress>> {
        let mut stmt = self.conn.prepare(
            "SELECT a.uid, a.target, a.install_id, a.address, a.expires
             FROM net_name_address a JOIN sandbox s USING (uid, install_id)
             WHERE a.expires > ?1",
        )?;
        Ok(stmt
            .query_map([now], |r| {
                Ok(NameAddress {
                    uid: r.get(0)?,
                    target: r.get(1)?,
                    install_id: r.get(2)?,
                    address: r.get(3)?,
                    expires: r.get(4)?,
                })
            })?
            .collect::<rusqlite::Result<_>>()?)
    }

    /// Persist a successfully applied rule and its learned addresses together.
    pub fn change_net_rule(
        &self,
        uid: u32,
        target: &str,
        change: RuleChange,
        addresses: &[NameAddress],
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
            if !addresses.is_empty() {
                let mut put = tx.prepare_cached(
                    "INSERT INTO net_name_address (uid, target, install_id, address, expires)
                     SELECT ?1, ?2, ?3, ?4, ?5 WHERE EXISTS
                         (SELECT 1 FROM net_rule WHERE uid = ?1 AND target = ?2)
                     ON CONFLICT (uid, target, install_id, address) DO UPDATE SET expires = excluded.expires",
                )?;
                for a in addresses {
                    put.execute(params![a.uid, a.target, a.install_id, a.address, a.expires])?;
                }
            }
            Ok(())
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
        let tx = Transaction::new_unchecked(&self.conn, TransactionBehavior::Immediate)?;
        let had = net_recording(&tx)?;
        let uids: HashSet<_> = uids.iter().copied().collect();
        for uid in uids.iter().filter(|uid| !had.contains_key(uid)) {
            tx.execute(
                "INSERT INTO net_recording (uid, since) VALUES (?1, ?2)",
                params![uid, now],
            )?;
        }
        for uid in had.keys().filter(|uid| !uids.contains(uid)) {
            tx.execute("DELETE FROM net_recording WHERE uid = ?1", params![uid])?;
        }
        tx.commit()?;
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
        net_rules(self.connection(), Some(uid))
    }

    /// Preserve resolver covers for name rules and any future rule kind
    /// that an offline reader cannot classify as an address rule.
    pub fn net_uids_with_non_address_rules(&self) -> anyhow::Result<HashSet<u32>> {
        let mut stmt = self
            .connection()
            .prepare("SELECT DISTINCT uid FROM net_rule WHERE target NOT GLOB 'ip:*'")?;
        Ok(stmt
            .query_map([], |row| row.get(0))?
            .collect::<Result<_, _>>()?)
    }

    /// Since when the connections of each uid are recorded.
    pub fn net_recording(&self) -> anyhow::Result<HashMap<u32, i64>> {
        net_recording(self.connection())
    }

    /// What is recorded of the network of `uid` in the hours from `since`
    /// up to, not including, `until` (unix times).
    pub fn net_rows(&self, uid: u32, since: i64, until: i64) -> anyhow::Result<Vec<NetRow>> {
        let mut stmt = self.connection().prepare(
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

#[cfg(test)]
mod tests {
    use std::sync::mpsc::{TryRecvError, channel};

    use super::*;
    use crate::store::Counters;

    #[test]
    fn deleting_a_rule_cascades_to_its_learned_addresses() {
        let writer = Writer::open(std::path::Path::new(":memory:")).unwrap();
        writer
            .conn
            .execute_batch(
                "INSERT INTO net_rule VALUES
                (10100, 'domain:example.com', 100, 0),
                (10200, 'domain:example.com', 100, 0);
             INSERT INTO net_name_address VALUES
                (10100, 'domain:example.com', 'app1', '203.0.113.1', 200),
                (10200, 'domain:example.com', 'app2', '203.0.113.2', 200);",
            )
            .unwrap();
        writer
            .conn
            .execute("DELETE FROM net_rule WHERE uid = 10100", [])
            .unwrap();
        let remaining: i64 = writer
            .conn
            .query_row("SELECT uid FROM net_name_address", [], |row| row.get(0))
            .unwrap();
        let count: i64 = writer
            .conn
            .query_row("SELECT count(*) FROM net_name_address", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!((count, remaining), (1, 10200));
    }

    #[test]
    fn a_failed_recording_status_update_rolls_back_the_whole_set() {
        let writer = Writer::open(std::path::Path::new(":memory:")).unwrap();
        writer.set_net_recording(&[10100, 10200], 100).unwrap();
        writer
            .conn
            .execute_batch(
                "CREATE TRIGGER fail_recording_update BEFORE DELETE ON net_recording
             BEGIN SELECT RAISE(ABORT, 'cannot delete recording status'); END;",
            )
            .unwrap();
        assert!(writer.set_net_recording(&[10200, 10300], 200).is_err());
        assert_eq!(
            writer.net_recording().unwrap(),
            HashMap::from([(10100, 100), (10200, 100)])
        );
    }

    fn record_connection(writer: &mut Writer, uid: u32, hour: i64) {
        writer.net_record(
            NetKey {
                uid,
                hour,
                host: String::new(),
                addr: "203.0.113.1".into(),
                port: 443,
            },
            NetSeen {
                conn: 1,
                ..NetSeen::default()
            },
            hour * 3600,
        );
    }

    #[test]
    fn all_buffered_network_counters_saturate() {
        let dir = std::env::temp_dir().join(format!("bbd-net-saturate-{}", std::process::id()));
        let mut writer = Writer::open(&dir.join("bb.db")).unwrap();
        let key = NetKey {
            uid: 10100,
            hour: 0,
            host: "example.com".into(),
            addr: "203.0.113.1".into(),
            port: 443,
        };
        let maximum = NetSeen {
            conn: u32::MAX,
            refused: u32::MAX,
            sent: u64::MAX,
            recv: u64::MAX,
            counted: u32::MAX,
        };
        writer.net_record(key.clone(), maximum, 2);
        writer.net_record(
            key.clone(),
            NetSeen {
                conn: 1,
                refused: 1,
                sent: 1,
                recv: 1,
                counted: 1,
            },
            1,
        );
        assert_eq!(writer.net.get_mut()[&key], (maximum, 1, 2));
        drop(writer);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn clearing_network_drops_only_the_chosen_uids_written_and_buffered_rows() {
        let dir = std::env::temp_dir().join(format!("bbd-net-clear-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut writer = Writer::open(&db).unwrap();
        let reader = Reader::open(&db).unwrap();
        let counters = Counters::default();
        let (uid, other) = (10234, 10235);
        writer
            .change_net_rule(
                uid,
                "domain:example.com",
                RuleChange::Block {
                    created: 3600,
                    manual: true,
                },
                &[],
            )
            .unwrap();
        writer.set_net_recording(&[uid], 3600).unwrap();
        for uid in [uid, other] {
            record_connection(&mut writer, uid, 1);
        }
        writer.flush(&counters, 3600).unwrap();
        for uid in [uid, other] {
            record_connection(&mut writer, uid, 2);
        }

        assert_eq!(writer.net_clear(uid).unwrap(), 1);
        writer.flush(&counters, 7200).unwrap();
        assert!(reader.net_rows(uid, 0, 10_800).unwrap().is_empty());
        assert_eq!(reader.net_rows(other, 0, 10_800).unwrap().len(), 2);
        assert_eq!(reader.net_rules(uid).unwrap().len(), 1);
        assert_eq!(reader.net_recording().unwrap().get(&uid), Some(&3600));
        drop((reader, writer));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn tells_the_registered_receiver_which_uid_was_cleared() {
        let dir = std::env::temp_dir().join(format!("bbd-net-notice-{}", std::process::id()));
        let mut writer = Writer::open(&dir.join("bb.db")).unwrap();
        let (cleared, told) = channel();
        writer.tell_net_cleared(cleared);
        record_connection(&mut writer, 10234, 1);
        assert_eq!(told.try_recv(), Err(TryRecvError::Empty));

        assert_eq!(writer.net_clear(10234).unwrap(), 0);
        assert_eq!(told.try_recv().unwrap(), 10234);
        assert_eq!(told.try_recv(), Err(TryRecvError::Empty));
        drop(writer);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn network_cleared_on_another_connection_is_not_written_from_what_was_held() {
        let dir = std::env::temp_dir().join(format!("bbd-net-cleared-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut recorder = Writer::open(&db).unwrap();
        let mut sandbox = Writer::open(&db).unwrap();
        let (cleared, told) = channel();
        sandbox.tell_net_cleared(cleared);
        record_connection(&mut recorder, 10234, 1);
        record_connection(&mut recorder, 10235, 1);

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
}
