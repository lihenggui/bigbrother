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

use std::collections::BTreeSet;

use rusqlite::{Connection, OptionalExtension as _, Transaction, params};

use super::{Reader, Writer};

impl Writer {
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
    pub(super) fn write_sandbox(
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

pub(super) fn sandbox_global(conn: &Connection) -> anyhow::Result<String> {
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
    pub kind: KnownKind,
}

/// The values 0 and 1 are the app and library rows written by v0.3.0.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KnownKind {
    App = 0,
    Library = 1,
    Failed = 2,
    MissingProfile = 3,
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
                kind: match r.get::<_, i64>(3)? {
                    0 => KnownKind::App,
                    1 => KnownKind::Library,
                    2 => KnownKind::Failed,
                    3 => KnownKind::MissingProfile,
                    _ => return Err(rusqlite::Error::InvalidQuery),
                },
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
                    app.kind as i64
                ])?;
            }
            Ok(())
        })
    }
}

impl Reader {
    /// Every sandbox entry, enabled or not, by uid.
    pub fn sandbox_entries(&self) -> anyhow::Result<Vec<SandboxEntry>> {
        sandbox_entries(self.connection())
    }

    /// The global settings of the sandbox, as `item=mode,...`.
    pub fn sandbox_global(&self) -> anyhow::Result<String> {
        sandbox_global(self.connection())
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use rusqlite::OpenFlags;

    use super::*;
    use crate::store::tests::sandbox_entry_for;

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
            kind: KnownKind::App,
        };
        let rows = [
            known(10100, None, "com.a"),
            known(10100, None, "com.b"),
            known(10230, Some(0), "com.new"),
            known(10230, Some(10), "com.new"),
            KnownApp {
                kind: KnownKind::Library,
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
}
