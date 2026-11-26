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

use std::{fs, path::PathBuf, sync::mpsc};

use bb_common::{EV_FILE, EventHeader, FileEvent, PROC_SUPER_MAGIC};

use super::*;
use crate::{
    sensors::Sensors,
    store::{
        Reader,
        net::{NetKey, NetSeen},
    },
};

const APP: u32 = 10100;
const OTHER_APP: u32 = 10200;

struct Fixture {
    dir: PathBuf,
    tracer: Tracer,
    reader: Reader,
}

impl Fixture {
    fn new(test: &str) -> Self {
        let dir =
            std::env::temp_dir().join(format!("bbd-event-loop-{test}-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let list = dir.join("packages.list");
        fs::write(
            &list,
            "com.example.app 10100 0 /data/user/0/com.example.app default none\n",
        )
        .unwrap();
        let db = dir.join("bb.db");
        let tracer = Tracer::new(
            list.to_str().unwrap(),
            Writer::open(&db).unwrap(),
            Sensors::new(|| Ok(String::new())),
            false,
            false,
            false,
        );
        Self {
            dir,
            tracer,
            reader: Reader::open(&db).unwrap(),
        }
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.dir).unwrap();
    }
}

fn permission_uses() -> PermissionUses {
    let (_, initial) = tokio::sync::mpsc::channel(1);
    PermissionUses {
        bridge: Bridge::new(),
        initial,
        tracker: appops::Tracker::new(100),
        coverage: COVERAGE_PENDING,
        schedule: PollSchedule::default(),
    }
}

#[test]
fn usage_waits_for_activity_or_dirty_and_always_reads_on_query_and_shutdown() {
    let start = Instant::now();
    let later = start + APPOPS_POLL_MIN_INTERVAL;
    let mut schedule = PollSchedule::default();
    let mut count = 0;
    let mut flush = |kind, now, dirty, activity| {
        let wait = schedule.wait(kind, now, dirty, activity);
        count += usize::from(wait.is_some());
        wait
    };
    assert_eq!(flush(FlushKind::Periodic, start, false, 0), None);
    assert_eq!(flush(FlushKind::Periodic, later, false, 0), None);
    assert_eq!(
        flush(FlushKind::Query, later, false, 0),
        Some(USAGE_QUERY_WAIT)
    );
    assert_eq!(flush(FlushKind::Periodic, later, false, 1), None);
    assert_eq!(
        flush(FlushKind::Periodic, later, true, 1),
        Some(USAGE_QUERY_WAIT)
    );
    assert_eq!(
        flush(
            FlushKind::Periodic,
            later + APPOPS_POLL_MIN_INTERVAL,
            false,
            2
        ),
        Some(USAGE_QUERY_WAIT)
    );
    assert_eq!(
        flush(
            FlushKind::Shutdown,
            later + APPOPS_POLL_MIN_INTERVAL,
            false,
            2
        ),
        Some(USAGE_SHUTDOWN_WAIT)
    );
    assert_eq!(count, 4);
}

#[test]
fn permission_readings_update_coverage_and_record_each_new_use_once() {
    let mut f = Fixture::new("permissions");
    let mut permissions = permission_uses();
    f.tracer
        .writer
        .set_meta("coverage_appops", COVERAGE_PENDING)
        .unwrap();

    permissions.ingest(&mut f.tracer, None);
    assert_eq!(f.tracer.writer.meta("coverage_appops").unwrap(), Some(0));

    let reading = "10100\tcom.example.app\tandroid:camera\t101000\t102000";
    for _ in 0..2 {
        permissions.ingest(&mut f.tracer, Some(appops::parse(reading)));
    }
    f.tracer.writer.flush(&f.tracer.counters, 103).unwrap();
    assert_eq!(f.tracer.writer.meta("coverage_appops").unwrap(), Some(1));
    let rows = f.reader.history(APP, "appop:android:camera", 0).unwrap();
    assert_eq!(rows.len(), 2);
    assert_eq!((rows[0].ts, rows[0].ok, rows[0].fail), (102, 0, 1));
    assert_eq!((rows[1].ts, rows[1].ok, rows[1].fail), (101, 1, 0));
}

fn record_connection(writer: &mut Writer, uid: u32, at: i64) {
    writer.net_record(
        NetKey {
            uid,
            hour: 0,
            host: String::new(),
            addr: "203.0.113.7".into(),
            port: 443,
        },
        NetSeen {
            conn: 1,
            ..Default::default()
        },
        at,
    );
}

#[test]
fn clear_notifications_drop_only_the_named_apps_pending_and_written_network_rows() {
    let mut f = Fixture::new("clear-net");
    let (send, receive) = mpsc::channel();
    record_connection(&mut f.tracer.writer, APP, 100);
    f.tracer.writer.flush(&f.tracer.counters, 100).unwrap();
    record_connection(&mut f.tracer.writer, APP, 101);
    record_connection(&mut f.tracer.writer, OTHER_APP, 101);

    send.send(APP).unwrap();
    send.send(APP).unwrap();
    drop_cleared_net(&mut f.tracer, &receive);
    f.tracer.writer.flush(&f.tracer.counters, 102).unwrap();

    assert!(f.reader.net_rows(APP, 0, 3600).unwrap().is_empty());
    let other = f.reader.net_rows(OTHER_APP, 0, 3600).unwrap();
    assert_eq!(other.len(), 1);
    assert_eq!(other[0].seen.conn, 1);
}

fn stage_open(tracer: &mut Tracer, at: i64) {
    let components = b"cpuinfo\0";
    let file = FileEvent {
        hdr: EventHeader {
            kind: EV_FILE,
            uid: APP,
            pid: 12,
            tid: 12,
            boot_ns_low: 0,
            boot_ns_high: 0,
        },
        flags: libc::O_RDONLY as u32,
        magic: PROC_SUPER_MAGIC,
        dev: 0,
        mode: libc::S_IFREG | 0o444,
        in_syscall: 1,
        truncated: 0,
        data_len: components.len() as u32,
    };
    let mut bytes = crate::selftest::event_bytes(&file).to_vec();
    bytes.extend_from_slice(components);
    tracer.on_test_event(&bytes, at);
}

#[tokio::test]
async fn flush_expires_missing_syscall_results_at_the_deadline_and_persists_them_once() {
    let mut f = Fixture::new("expire");
    let mut permissions = permission_uses();
    let (_clear, net) = mpsc::channel();
    stage_open(&mut f.tracer, 100);

    flush_records(&mut f.tracer, &mut permissions, &net, false, || 109).await;
    assert!(
        f.reader
            .history(APP, "/proc/cpuinfo", 0)
            .unwrap()
            .is_empty()
    );
    assert_eq!(f.tracer.writer.meta("last_flush").unwrap(), None);

    flush_records(&mut f.tracer, &mut permissions, &net, true, || 110).await;
    let rows = f.reader.history(APP, "/proc/cpuinfo", 0).unwrap();
    assert_eq!(rows.len(), 1);
    assert_eq!((rows[0].ts, rows[0].ok, rows[0].fail), (100, 1, 0));
    assert_eq!(f.tracer.writer.meta("last_flush").unwrap(), Some(110));

    flush_records(&mut f.tracer, &mut permissions, &net, true, || 120).await;
    assert_eq!(f.tracer.writer.meta("last_flush").unwrap(), Some(110));
    assert_eq!(f.reader.history(APP, "/proc/cpuinfo", 0).unwrap()[0].ok, 1);
}
