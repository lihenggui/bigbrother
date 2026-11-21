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

//! Classify stored access records for the app, file and timeline reports.

use std::collections::HashMap;

use rusqlite::{Rows, Statement, params};
use serde::Serialize;

use crate::{
    paths,
    record::{Category, MODE_DIR, MODE_PROBE, MODE_READ, MODE_WATCH, MODE_WRITE, Mode},
    rules,
    store::Reader,
    topic,
};

/// How many of an app's folders the per-app summary names.
const TOP_FOLDERS: usize = 3;

/// Hours of records the timeline reads per query while it collects a page.
const TIMELINE_WINDOW_HOURS: i64 = 24;

/// Topics whose records each say something of their own (whether the
/// clipboard was read or written, which sensor was turned on); the timeline
/// lists them for these, where there are a handful per entry.
const SUBJECTS_LISTED: &[&str] = &[topic::CLIPBOARD, topic::SENSORS];

/// Mode bits of accesses that only looked for a file rather than opened it.
const NOT_FILE_OPEN: Mode = MODE_DIR | MODE_PROBE | MODE_WATCH;

/// Mode bits of accesses that opened a file.
const FILE_OPEN: Mode = MODE_READ | MODE_WRITE;

/// The top-level folder most of `paths` are in; a tie goes to the name that
/// sorts first, so the result is stable.
fn most_common_folder(paths: &std::collections::HashSet<String>) -> Option<String> {
    let mut counts: HashMap<&str, u32> = HashMap::new();
    for path in paths {
        if let Some(folder) = paths::top_level_folder(path) {
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
    /// Topics (see [`topic`]) of everything it did other than touch
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
    /// What the record means (see [`topic`]); absent for user files and
    /// provider URIs.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub topic: Option<&'static str>,
}

/// One app's activity of one kind within one hour: what the timeline lists.
#[derive(Serialize)]
pub struct TimelineRow {
    /// Start of the hour, as unix time.
    pub hour_ts: i64,
    pub uid: u32,
    pub pkg: String,
    pub cat: String,
    /// What the records mean (see [`topic`]); absent for user files and
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

/// A list to match a column against with `instr(list, ',' || column || ',')`:
/// ",a,b,", or None, matching everything, when there is nothing in it.
fn any_of(values: impl Iterator<Item = String>) -> Option<String> {
    let values: Vec<String> = values.collect();
    (!values.is_empty()).then(|| format!(",{},", values.join(",")))
}

/// One row per uid, most recently active first.
pub fn apps(reader: &Reader, since: i64) -> anyhow::Result<Vec<AppRow>> {
    let mut apps = app_rows(reader, since)?;
    attach_folders(reader, since, &mut apps)?;
    attach_topics(reader, since, &mut apps)?;
    Ok(apps)
}

fn app_rows(reader: &Reader, since: i64) -> anyhow::Result<Vec<AppRow>> {
    let mut stmt = reader.connection().prepare(
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
    Ok(rows.collect::<Result<_, _>>()?)
}

fn attach_folders(reader: &Reader, since: i64, apps: &mut [AppRow]) -> anyhow::Result<()> {
    // Which folders each app's files are in: distinct paths per top-level
    // folder, counted separately for files it read and for everything.
    let mut read: HashMap<u32, HashMap<String, u32>> = HashMap::new();
    let mut touched: HashMap<u32, HashMap<String, u32>> = HashMap::new();
    let mut stmt = reader.connection().prepare(
        "SELECT uid, path, max(ok > 0 AND (mode & ?2) = 0 AND (mode & ?3) != 0)
             FROM access WHERE cat = 'storage' AND last_ts >= ?1
             GROUP BY uid, path",
    )?;
    let mut paths = stmt.query(params![since, NOT_FILE_OPEN, FILE_OPEN])?;
    while let Some(row) = paths.next()? {
        let (uid, path, was_read): (u32, String, bool) = (row.get(0)?, row.get(1)?, row.get(2)?);
        let Some(folder) = paths::top_level_folder(&path) else {
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
    for app in apps {
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

    Ok(())
}

fn attach_topics(reader: &Reader, since: i64, apps: &mut [AppRow]) -> anyhow::Result<()> {
    // What kinds of information each app went after, besides user files.
    let mut topics: HashMap<u32, std::collections::BTreeSet<&'static str>> = HashMap::new();
    let mut stmt = reader.connection().prepare(
        "SELECT DISTINCT uid, path FROM access
             WHERE cat NOT IN ('storage', 'provider') AND last_ts >= ?1",
    )?;
    let mut paths = stmt.query(params![since])?;
    while let Some(row) = paths.next()? {
        let (uid, path): (u32, String) = (row.get(0)?, row.get(1)?);
        if let Some(topic) = rules::topic_of(&path) {
            topics.entry(uid).or_default().insert(topic);
        }
    }
    for app in apps {
        if let Some(found) = topics.remove(&app.uid) {
            app.topics = found.into_iter().collect();
        }
    }
    Ok(())
}

/// Paths, aggregated across hours, most recent first. `uids` and `cats`
/// (any of them), `prefix` and `contains` (a substring, ignoring ASCII
/// case) narrow the result when given.
#[allow(clippy::too_many_arguments)]
pub fn files(
    reader: &Reader,
    uids: &[u32],
    cats: &[Category],
    prefix: Option<&str>,
    contains: Option<&str>,
    since: i64,
    limit: u32,
    offset: u32,
) -> anyhow::Result<Vec<FileRow>> {
    let mut stmt = reader.connection().prepare(
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
                topic: rules::topic_of(&path),
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

/// Each app's activity per hour and kind (category and topic), most
/// recent hour first, for hours that start before `before` and end after
/// `since`. Records are read a day at a time; reading stops after the
/// day in which `limit` entries are reached, so an hour is never split
/// across pages.
pub fn timeline(
    reader: &Reader,
    since: i64,
    before: i64,
    limit: usize,
) -> anyhow::Result<Timeline> {
    let oldest: Option<i64> = reader.connection().query_row(
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
    let mut stmt = reader.connection().prepare(
        "SELECT hour, uid, pkg, cat, path, mode, ok, fail, first_ts, last_ts, items
             FROM access
             WHERE hour >= ?1 AND hour < ?2 AND last_ts >= ?3",
    )?;
    while end > floor {
        let start = (end - TIMELINE_WINDOW_HOURS).max(floor);
        entries.extend(timeline_rows(&mut stmt, start, end, since)?);
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

fn timeline_rows(
    stmt: &mut Statement<'_>,
    start: i64,
    end: i64,
    since: i64,
) -> anyhow::Result<Vec<TimelineRow>> {
    let mut rows = stmt.query(params![start, end, since])?;
    let mut window: Vec<TimelineRow> = group_hours(&mut rows)?
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
    Ok(window)
}

type HourGroups = HashMap<(i64, u32, String, Option<&'static str>), TimelineAcc>;

fn group_hours(rows: &mut Rows<'_>) -> rusqlite::Result<HourGroups> {
    let mut found: HourGroups = HashMap::new();
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
        let topic = rules::topic_of(&path);
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
    Ok(found)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        record::{MODE_CALL, MODE_CONNECT, MODE_EXEC, MODE_QUERY},
        store::{Counters, Writer},
    };

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
        let apps = apps(&r, 0).unwrap();
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
        let commands = files(&r, &[10100], &[Category::Command], None, None, 0, 10, 0).unwrap();
        let su = commands.iter().find(|c| c.path == "exec:su").unwrap();
        assert_eq!((su.mode, su.ok, su.fail), (MODE_EXEC, 0, 1));
        assert_eq!(su.topic, Some("root_check"));
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
        let page = timeline(&r, 0, t + 3600, 100).unwrap();
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
        record_timeline_window(&mut w, t);
        let r = Reader::open(&db).unwrap();

        let page = timeline(&r, 0, t + 7200, 100).unwrap();
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
                (t, 10100, "device", Some(crate::topic::MAC_ADDRESS), 1, 0),
                (t, 10100, "storage", None, 3, 2),
            ]
        );
        let files = &page.entries[2];
        // The folder of what was read, not of what was only probed.
        assert_eq!(files.folder.as_deref(), Some("DCIM"));
        assert_eq!(files.modes, MODE_READ | MODE_PROBE);
        assert_eq!((page.entries[1].ok, page.entries[1].fail), (0, 1));

        // `before` leaves out the hours that start at or after it.
        let earlier = timeline(&r, 0, t + 3600, 100).unwrap();
        assert_eq!(earlier.entries.len(), 2);
        // `since` leaves out the hours that ended before it.
        assert_eq!(
            timeline(&r, t + 3600, t + 7200, 100).unwrap().entries.len(),
            1
        );

        // A page ends once the limit is reached, and says where to go on.
        let mut w = Writer::open(&db).unwrap();
        let old = t - 3 * 24 * 3600;
        w.record(
            10100,
            "com.a",
            Category::Storage,
            "/storage/emulated/0/DCIM/old.jpg".into(),
            MODE_READ,
            true,
            old,
            0,
        );
        w.flush(&Counters::default(), t + 3700).unwrap();
        let first = timeline(&r, 0, t + 7200, 1).unwrap();
        assert_eq!(first.entries.len(), 3);
        let next = first.next_before.expect("an older page");
        let second = timeline(&r, 0, next, 1).unwrap();
        assert_eq!(second.entries.len(), 1);
        assert_eq!(second.entries[0].hour_ts, old - old.rem_euclid(3600));
        assert!(second.next_before.is_none());

        // An empty range is an empty page.
        assert!(
            timeline(&r, t + 7200, t + 9000, 10)
                .unwrap()
                .entries
                .is_empty()
        );
        drop(r);
        std::fs::remove_dir_all(dir).unwrap();
    }

    fn record_timeline_window(w: &mut Writer, t: i64) {
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
            mac,
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
    }

    #[test]
    fn files_are_filtered_by_text_category_and_app() {
        let dir = std::env::temp_dir().join(format!("bbd-contains-{}", std::process::id()));
        let db = dir.join("bb.db");
        let mut w = Writer::open(&db).unwrap();
        let t = 1_000_000 * 3600;
        for (uid, pkg, path) in [
            (10100, "com.a", "/storage/emulated/0/DCIM/IMG_1.jpg"),
            (10200, "com.b", "/storage/emulated/0/DCIM/IMG_1.jpg"),
            (10200, "com.b", "/storage/emulated/0/Music/a.mp3"),
        ] {
            w.record(
                uid,
                pkg,
                Category::Storage,
                path.into(),
                MODE_READ,
                true,
                t,
                0,
            );
        }
        w.flush(&Counters::default(), t + 10).unwrap();
        let r = Reader::open(&db).unwrap();
        let found = files(&r, &[], &[], None, Some("img_1"), 0, 10, 0).unwrap();
        let mut uids: Vec<u32> = found.iter().map(|f| f.uid).collect();
        uids.sort_unstable();
        assert_eq!(uids, vec![10100, 10200]);
        let absent = files(&r, &[], &[], None, Some("nothing"), 0, 10, 0).unwrap();
        assert!(absent.is_empty());

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
        let both = files(
            &r,
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
        let mine = files(&r, &[10100, 10300], &[], None, Some("img_1"), 0, 10, 0).unwrap();
        assert_eq!(mine.iter().map(|f| f.uid).collect::<Vec<_>>(), [10100]);
        let other = files(&r, &[10300], &[], None, Some("img_1"), 0, 10, 0).unwrap();
        assert!(other.is_empty());
        drop(r);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
