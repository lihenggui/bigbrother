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

use std::path::Path;

use rusqlite::{Connection, OpenFlags};

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
/// A failed UID is left out of the start gate while awaiting a recovery event.
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
    library INTEGER NOT NULL,   -- 0: app, 1: library, 2: failed, 3: missing profile
    PRIMARY KEY (app_id, user, package)
) WITHOUT ROWID;
";

/// The tables for network records and destination rules.
///
/// A `net_dest` row is one hour in which an app connected to a port of an
/// address, under the name it had looked up for the address ('' where it
/// had looked none up). `sent` and `recv` are the bytes of the connections
/// that ended in the hour and were counted, `counted` how many those were:
/// not every connection is. `refused` are the attempts the sandbox refused.
///
/// A `net_rule` row is a destination an app is kept from, as the user
/// named it. `net_recording` says since when an app's
/// connections are recorded.
const NET_SCHEMA: &str = "
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

pub(super) fn has_schema(conn: &Connection) -> anyhow::Result<bool> {
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
pub(super) fn create_schema(conn: &mut Connection) -> anyhow::Result<()> {
    // One transaction for the tables and the version that records them: if
    // the process dies half way, the next start finds an empty database.
    let conn = conn.transaction()?;
    if has_schema(&conn)? {
        return Ok(());
    }
    conn.execute_batch(SCHEMA)?;
    conn.execute_batch(RETIRED_SANDBOX)?;
    conn.execute_batch(SANDBOX_AUTO_SCHEMA)?;
    conn.execute_batch(NET_SCHEMA)?;
    conn.pragma_update(None, "user_version", SCHEMA_VERSION)?;
    conn.commit()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::{Reader, Writer, sandbox::sandbox_global};

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
}
