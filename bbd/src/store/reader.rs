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

use std::{collections::HashMap, path::Path};

use anyhow::Context as _;
use rusqlite::{Connection, OpenFlags, params};
use serde::Serialize;

use crate::record::{Mode, is_high_rate};

use super::schema::has_schema;

/// Most entries returned for one path's history.
const HISTORY_LIMIT: u32 = 2000;

/// Most sensor sessions returned for one app.
const SENSOR_SESSIONS_LIMIT: u32 = 2000;

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
    use super::*;
    use crate::{
        record::{Category, MODE_READ, SensorSession},
        store::{Counters, Writer},
    };

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
