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

use std::{fs, net::UdpSocket, path::PathBuf};

use bb_common::{
    ABI_NATIVE, AF_INET, BINDER_ARGS_CAP, BinderEvent, ConnectEvent, EXEC_ARG_CAP, EXEC_ARGS,
    EXEC_PATH_CAP, EventData, ExecEvent, FileEvent, OP_OPEN, PATH_CAP, PROC_SUPER_MAGIC,
    PROCESS_READ, PathEvent, ProcessEvent, ResultEvent, TrafficEvent,
};

use super::{netrec::NET_DESTINATIONS_PER_HOUR, *};
use crate::{
    binder,
    packages::FIRST_ISOLATED_APP_ID,
    proc::uid_of_pid,
    record::MODE_ISOLATED,
    report::{self, FileRow},
    sandbox::destinations::View,
    store::{Reader, SandboxEntry, net::NetSeen},
};

const APP_UID: u32 = 10100;
const FIXTURE_TS: i64 = 5;
const EVENT_CLOCK: EventClock = EventClock {
    boot_unix_ns: FIXTURE_TS * 1_000_000_000,
};

struct Fixture {
    tracer: Tracer,
    dir: PathBuf,
}

impl Fixture {
    fn new(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("bbd-tracer-{name}-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let list = dir.join("packages.list");
        fs::write(
            &list,
            "com.example.reader 10100 0 /data/user/0/com.example.reader default none\n\
             com.example.installed 10101 0 /data/user/0/com.example.installed default none\n",
        )
        .unwrap();
        let tracer = Tracer::new(
            list.to_str().unwrap(),
            Writer::open(&dir.join("bb.db")).unwrap(),
            sensors::Sensors::new(|| Ok(String::new())),
            false,
            false,
            false,
        );
        Self { tracer, dir }
    }

    fn event(&mut self, event: &impl EventData) {
        self.tracer
            .on_event(selftest::event_bytes(event), &EVENT_CLOCK);
    }

    fn flush(&mut self) -> Reader {
        self.tracer
            .writer
            .flush(&self.tracer.counters, FIXTURE_TS)
            .unwrap();
        Reader::open(&self.dir.join("bb.db")).unwrap()
    }

    fn rows(&mut self, category: Category) -> Vec<FileRow> {
        report::files(&self.flush(), &[], &[category], None, None, 0, 10, 0).unwrap()
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.dir);
    }
}

fn header(kind: u32) -> EventHeader {
    EventHeader {
        kind,
        uid: APP_UID,
        pid: 123,
        tid: 123,
        boot_ns_low: 0,
        boot_ns_high: 0,
    }
}

#[test]
fn complete_path_events_are_recorded_and_other_bytes_are_ignored() {
    let mut fixture = Fixture::new("path");
    let path = b"/sdcard/Documents/example.txt\0";
    let mut event = PathEvent {
        hdr: header(EV_PATH),
        op: OP_OPEN,
        ret: 3,
        dfd: libc::AT_FDCWD,
        flags: libc::O_RDONLY as u32,
        abi: ABI_NATIVE,
        path_len: path.len() as u32,
        path: [0; PATH_CAP],
    };
    event.path[..path.len()].copy_from_slice(path);
    let bytes = selftest::event_bytes(&event);
    fixture
        .tracer
        .on_event(&bytes[..size_of::<EventHeader>() - 1], &EVENT_CLOCK);
    fixture
        .tracer
        .on_event(&bytes[..bytes.len() - 1], &EVENT_CLOCK);
    let mut unknown = event;
    unknown.hdr.kind = u32::MAX;
    fixture.event(&unknown);
    let mut unaligned = vec![0];
    unaligned.extend_from_slice(bytes);
    fixture.tracer.on_event(&unaligned[1..], &EVENT_CLOCK);
    let rows = fixture.rows(Category::Storage);
    assert_eq!(rows.len(), 1);
    let row = &rows[0];
    assert_eq!(row.pkg, "com.example.reader");
    assert_eq!(row.path, "/storage/emulated/0/Documents/example.txt");
    assert_eq!(
        (row.ok, row.fail, row.mode, row.last_ts),
        (1, 0, MODE_READ, FIXTURE_TS)
    );
    assert_eq!(fixture.tracer.counters.seen, 1);
}

#[test]
fn truncated_file_paths_are_unresolved() {
    let mut fixture = Fixture::new("file");
    let components = b"cpuinfo\0";
    let mut event = FileEvent {
        hdr: header(EV_FILE),
        flags: libc::O_RDONLY as u32,
        magic: PROC_SUPER_MAGIC,
        dev: 0,
        mode: libc::S_IFREG | 0o444,
        in_syscall: 0,
        truncated: 1,
        data_len: components.len() as u32,
    };
    let mut bytes = selftest::event_bytes(&event).to_vec();
    bytes.extend_from_slice(components);
    fixture.tracer.on_event(&bytes, &EVENT_CLOCK);
    assert_eq!(fixture.tracer.counters.seen, 1);
    assert_eq!(fixture.tracer.counters.unresolved, 1);
    assert_eq!(fixture.tracer.counters.kept, 0);

    event.truncated = 0;
    bytes[..size_of::<FileEvent>()].copy_from_slice(selftest::event_bytes(&event));
    fixture.tracer.on_event(&bytes, &EVENT_CLOCK);
    assert_eq!(fixture.tracer.counters.unresolved, 1);
    assert_eq!(fixture.tracer.counters.kept, 1);
}

#[test]
fn exec_arguments_are_recorded_with_the_syscall_result() {
    let mut fixture = Fixture::new("exec");
    let path = b"/system/bin/getprop\0";
    let argument = b"ro.build.version.release\0";
    let mut event = ExecEvent {
        hdr: header(EV_EXEC),
        path_len: path.len() as u32,
        argc: 1,
        path: [0; EXEC_PATH_CAP],
        args: [[0; EXEC_ARG_CAP]; EXEC_ARGS],
    };
    event.path[..path.len()].copy_from_slice(path);
    event.args[0][..argument.len()].copy_from_slice(argument);
    fixture.event(&event);
    assert_eq!(fixture.tracer.counters.kept, 0);
    fixture.event(&ResultEvent {
        hdr: header(EV_RESULT),
        ret: 0,
    });

    let rows = fixture.rows(Category::Command);
    assert_eq!(rows.len(), 1);
    let row = &rows[0];
    assert_eq!(row.path, "exec:getprop ro.build.version.release");
    assert_eq!(
        (row.mode, row.ok, row.fail, row.last_ts),
        (MODE_EXEC, 1, 0, FIXTURE_TS)
    );
    assert_eq!(fixture.tracer.counters.seen, 1);
}

#[test]
fn process_events_ignore_the_callers_own_uid() {
    let mut fixture = Fixture::new("process");
    let pid = std::process::id();
    let own_uid = uid_of_pid(pid).unwrap();
    let mut event = ProcessEvent {
        hdr: EventHeader {
            pid,
            uid: own_uid,
            ..header(EV_PROCESS)
        },
        how: PROCESS_READ,
        target: pid,
    };
    fixture.event(&event);
    assert!(fixture.tracer.awaiting_result.is_empty());

    event.hdr.uid = if own_uid == APP_UID {
        APP_UID + 1
    } else {
        APP_UID
    };
    fixture.event(&event);
    fixture.event(&ResultEvent {
        hdr: header(EV_RESULT),
        ret: -libc::EPERM,
    });

    let rows = fixture.rows(Category::Process);
    assert_eq!(rows.len(), 1);
    let row = &rows[0];
    assert_eq!(row.path, "process:read-memory");
    assert_eq!(
        (row.uid, row.mode, row.ok, row.fail),
        (event.hdr.uid, MODE_READ, 0, 1)
    );
    assert_eq!(fixture.tracer.counters.seen, 2);
}

fn binder_event(catalog: &Catalog, interface: &str, code: u32, args: &[u8]) -> BinderEvent {
    let key = binder::key(interface).unwrap();
    let (_, value) = catalog
        .map_entries()
        .into_iter()
        .find(|(entry, _)| *entry == key)
        .unwrap();
    let mut event = BinderEvent {
        hdr: header(EV_BINDER),
        interface: value[0] as u32,
        code,
        target: 7,
        args_len: args.len() as u32,
        args: [0; BINDER_ARGS_CAP],
    };
    event.args[..args.len()].copy_from_slice(args);
    event
}

#[test]
fn binder_sensor_calls_open_and_close_a_session() {
    let mut fixture = Fixture::new("sensor");
    let catalog = Catalog::parse("");
    let mut args = [0; 16];
    args[..4].copy_from_slice(&42_i32.to_le_bytes());
    args[4..8].copy_from_slice(&1_i32.to_le_bytes());
    args[8..].copy_from_slice(&20_000_000_i64.to_le_bytes());
    let mut event = binder_event(&catalog, binder::CONNECTION, 2, &args);
    event.hdr.pid = std::process::id();
    fixture.tracer.catalog = Some(catalog);

    fixture.event(&event);
    assert!(fixture.tracer.sensors.any_on());
    event.args[4..8].fill(0);
    fixture.event(&event);
    assert!(!fixture.tracer.sensors.any_on());
    assert_eq!(fixture.tracer.counters.seen, 2);
}

#[test]
fn binder_package_queries_mark_only_absent_packages() {
    let mut fixture = Fixture::new("binder");
    const INTERFACE: &str = "android.content.pm.IPackageManager";
    fixture.tracer.catalog = Some(Catalog::parse(
        "android.content.pm.IPackageManager\t3\tgetPackageInfo\n",
    ));
    for package in ["com.example.missing", "com.example.installed"] {
        let units: Vec<u16> = package.encode_utf16().collect();
        let mut args = (units.len() as i32).to_le_bytes().to_vec();
        args.extend(units.into_iter().flat_map(u16::to_le_bytes));
        args.extend([0, 0]);
        let event = binder_event(
            fixture.tracer.catalog.as_ref().unwrap(),
            INTERFACE,
            3,
            &args,
        );
        fixture.event(&event);
    }
    let rows = fixture.rows(Category::Service);
    assert_eq!(rows.len(), 2);
    assert_eq!(
        rows[0].path,
        "binder:android.content.pm.IPackageManager/getPackageInfo#!com.example.missing"
    );
    assert_eq!(
        rows[1].path,
        "binder:android.content.pm.IPackageManager/getPackageInfo#com.example.installed"
    );
    assert!(rows.iter().all(|row| row.mode == MODE_CALL && row.ok == 1));
}

#[test]
fn loopback_to_the_callers_own_listener_is_ignored() {
    let mut fixture = Fixture::new("loopback");
    let listener = UdpSocket::bind((std::net::Ipv4Addr::LOCALHOST, 0)).unwrap();
    let uid = uid_of_pid(std::process::id()).unwrap();
    let port = listener.local_addr().unwrap().port();
    assert!(fixture.tracer.listeners.owned_by(port, uid));

    fixture.event(&ConnectEvent {
        hdr: EventHeader {
            uid,
            ..header(EV_CONNECT)
        },
        family: AF_INET,
        port: u32::from(port),
        addr: ipv4([127, 0, 0, 1]),
    });
    assert_eq!(fixture.tracer.counters.seen, 1);
    assert!(fixture.rows(Category::Network).is_empty());
    assert!(fixture.flush().net_rows(uid, 0, 3600).unwrap().is_empty());
}

#[test]
fn a_refused_internet_connection_is_counted_as_refused() {
    let mut fixture = Fixture::new("refused");
    let snapshot = sandbox::Snapshot::default();
    snapshot.write().unwrap().insert(
        APP_UID,
        SandboxEntry {
            install_id: String::new(),
            uid: APP_UID,
            package: "com.example.reader".into(),
            enabled: true,
            android_id: String::new(),
            saved_ops: String::new(),
            saved_permissions: String::new(),
            created: 0,
            settings: "network=on".into(),
        },
    );
    fixture.tracer.net = Some(View {
        snapshot,
        global: Default::default(),
        book: Default::default(),
        names: None,
    });
    fixture.event(&ConnectEvent {
        hdr: header(EV_CONNECT),
        family: AF_INET,
        port: 443,
        addr: ipv4([203, 0, 113, 7]),
    });

    let rows = fixture.flush().net_rows(APP_UID, 0, 3600).unwrap();
    assert_eq!(rows.len(), 1);
    assert_eq!((rows[0].addr.as_str(), rows[0].port), ("203.0.113.7", 443));
    assert_eq!(
        rows[0].seen,
        NetSeen {
            refused: 1,
            ..NetSeen::default()
        }
    );
}

#[test]
fn traffic_records_both_halves_of_the_socket_counters() {
    let mut fixture = Fixture::new("traffic");
    fixture.event(&TrafficEvent {
        hdr: header(EV_TRAFFIC),
        family: AF_INET,
        port: 443,
        addr: ipv4([203, 0, 113, 7]),
        sent_low: 4,
        sent_high: 2,
        recv_low: 7,
        recv_high: 3,
    });
    let rows = fixture.flush().net_rows(APP_UID, 0, 3600).unwrap();
    assert_eq!(rows.len(), 1);
    assert_eq!(
        (rows[0].first_ts, rows[0].last_ts),
        (FIXTURE_TS, FIXTURE_TS)
    );
    assert_eq!(
        rows[0].seen,
        NetSeen {
            sent: (2 << 32) + 4,
            recv: (3 << 32) + 7,
            counted: 1,
            ..NetSeen::default()
        }
    );
    assert_eq!(fixture.tracer.counters.seen, 1);
}

fn ipv4(octets: [u8; 4]) -> [u8; 16] {
    let mut addr = [0; 16];
    addr[..4].copy_from_slice(&octets);
    addr
}

#[test]
fn an_exited_isolated_process_uses_the_most_recent_owner_of_its_uid() {
    let mut fixture = Fixture::new("isolated");
    let tracer = &mut fixture.tracer;
    let uid = FIRST_ISOLATED_APP_ID;
    let missing_pid = u32::MAX;
    assert_eq!(tracer.owner_uid(uid, missing_pid), (uid, 0));

    let seen = Instant::now();
    tracer
        .isolated_owners
        .insert((1, uid), (10101, seen - Duration::from_secs(1)));
    tracer.isolated_owners.insert((2, uid), (APP_UID, seen));
    tracer
        .isolated_owners
        .insert((3, uid + 1), (10102, seen + Duration::from_secs(1)));
    assert_eq!(tracer.owner_uid(uid, missing_pid), (APP_UID, MODE_ISOLATED));
}

#[test]
fn destinations_past_the_per_app_hourly_cap_are_aggregated() {
    let mut fixture = Fixture::new("net-cap");
    let tracer = &mut fixture.tracer;
    let cap = u16::try_from(NET_DESTINATIONS_PER_HOUR).unwrap();
    let mut key_for =
        |uid, ts, port| tracer.net_key(uid, ts, "example.com".into(), "203.0.113.7".into(), port);
    for port in 1..=cap {
        assert_eq!(key_for(APP_UID, 5, port).port, port);
    }
    let overflow = key_for(APP_UID, 5, cap + 1);
    assert_eq!(
        (
            overflow.host.as_str(),
            overflow.addr.as_str(),
            overflow.port
        ),
        ("", "", 0)
    );
    assert_eq!(key_for(APP_UID, 5, 1).port, 1);
    assert_eq!(key_for(APP_UID + 1, 5, cap + 1).port, cap + 1);
    assert_eq!(key_for(APP_UID, 3600, cap + 1).port, cap + 1);
}

#[test]
fn provider_ingestion_records_collections_and_shared_files() {
    let mut fixture = Fixture::new("providers");
    fixture
        .tracer
        .ingest_provider_events(|_| panic!("ingestion is disabled"));
    fixture.tracer.ingest_provider_events = true;

    let lines = [
        ("1\t1\t1000\tq\t\t2\tcontent://sms/inbox/32", false),
        (
            "1\t2\t10100\tq\t\t4\tcontent://call_log/calls/14?limit=4",
            false,
        ),
        ("1\t3\t10100\tq\t\t6\tcontent://dictionary/words", false),
        (
            "1\t4\t10100\to\trw\t0\tcontent://media/files/2\t/sdcard/Documents/example.txt",
            true,
        ),
        (
            "1\t5\t10100\to\tr\t0\tcontent://media/files/3\t/sdcard/Android/data/com.example.reader/file",
            true,
        ),
        ("1\t6\t10100\tq\t\t2\tcontent://media/files/4?limit=2", true),
    ];
    fixture.tracer.ingest_provider_events(|_| {
        lines
            .into_iter()
            .map(|(line, storage)| spool::parse_line(line, storage).unwrap())
            .collect()
    });

    assert_eq!(fixture.tracer.counters.seen, 6);
    assert_eq!(fixture.tracer.counters.kept, 3);
    let personal = fixture.rows(Category::Personal);
    assert_eq!(personal.len(), 1);
    assert_eq!(
        (
            personal[0].path.as_str(),
            personal[0].mode,
            personal[0].items
        ),
        ("content://call_log/calls", MODE_QUERY, 4)
    );
    let storage = fixture.rows(Category::Storage);
    assert_eq!(storage.len(), 1);
    assert_eq!(
        (storage[0].path.as_str(), storage[0].mode),
        (
            "/storage/emulated/0/Documents/example.txt",
            MODE_READ | MODE_WRITE | MODE_VIA_PROVIDER
        )
    );
    let provider = fixture.rows(Category::Provider);
    assert_eq!(provider.len(), 1);
    assert_eq!(
        (
            provider[0].path.as_str(),
            provider[0].mode,
            provider[0].items
        ),
        ("content://media/files/[id]", MODE_QUERY, 2)
    );
}

#[test]
fn a_failed_coverage_write_is_logged_and_can_be_retried() {
    let mut fixture = Fixture::new("coverage");
    let key = "coverage_appops";
    assert!(fixture.tracer.write_coverage(key, -1));
    let conn = rusqlite::Connection::open(fixture.dir.join("bb.db")).unwrap();
    conn.execute_batch(
        "CREATE TRIGGER reject_coverage BEFORE INSERT ON meta
         BEGIN SELECT RAISE(ABORT, 'injected write failure'); END;",
    )
    .unwrap();

    assert!(!fixture.tracer.write_coverage(key, 1));
    assert_eq!(fixture.tracer.writer.meta(key).unwrap(), Some(-1));
    assert!(
        fixture
            .tracer
            .write_errors
            .last_message()
            .is_some_and(|line| {
                line.starts_with("bbd: could not write coverage_appops:")
                    && line.contains("injected write failure")
            })
    );

    conn.execute_batch("DROP TRIGGER reject_coverage").unwrap();
    assert!(fixture.tracer.write_coverage(key, 1));
    assert_eq!(fixture.tracer.writer.meta(key).unwrap(), Some(1));
}

#[test]
fn labels_describe_recorded_modes() {
    assert_eq!(mode_label(MODE_READ | MODE_DIR), "list");
    assert_eq!(mode_label(MODE_READ | MODE_WRITE), "read+write");
    assert_eq!(mode_label(MODE_PROBE), "probe");
    assert_eq!(mode_label(MODE_WATCH), "watch");
    assert_eq!(mode_label(MODE_QUERY), "query");
    assert_eq!(mode_label(MODE_CONNECT), "connect");
    assert_eq!(mode_label(MODE_EXEC), "exec");
    assert_eq!(
        mode_label(MODE_READ | MODE_VIA_PROVIDER),
        "read-via-provider"
    );
    assert_eq!(
        mode_label(MODE_READ | MODE_WRITE | MODE_VIA_PROVIDER),
        "write-via-provider"
    );
}
#[test]
fn events_are_dated_by_when_the_kernel_saw_them() {
    let clock = EventClock {
        boot_unix_ns: 1_700_000_000 * 1_000_000_000,
    };
    assert_eq!(clock.unix_secs(5_999_999_999), 1_700_000_005);
    assert_eq!(clock.unix_secs(0), 1_700_000_000);

    // An event from a moment ago, read now.
    let boot_ns = clock_ns(libc::CLOCK_BOOTTIME) as u64 - 20_000_000_000;
    let ts = EventClock::now().unix_secs(boot_ns);
    assert!((now() - 21..=now() - 19).contains(&ts), "{ts}");
}

#[test]
fn isolated_owner_cache_rejects_new_processes_when_full_and_prunes_expired_ones() {
    use super::attribution::MAX_ISOLATED_OWNERS;

    let mut fixture = Fixture::new("isolated-cap");
    let tracer = &mut fixture.tracer;
    let uid = FIRST_ISOLATED_APP_ID;
    let owner = FIRST_APPLICATION_UID;
    let old = Instant::now() - Duration::from_secs(120);
    for pid in 1..=MAX_ISOLATED_OWNERS as u32 * 2 {
        tracer.remember_isolated_owner(pid, uid, owner, old);
    }
    assert_eq!(tracer.isolated_owners.len(), MAX_ISOLATED_OWNERS);
    assert_eq!(tracer.owner_uid(uid, 1), (owner, MODE_ISOLATED));
    tracer.prune_isolated_owners();
    assert_eq!(tracer.isolated_owners.len(), 1);
    tracer.remember_isolated_owner(u32::MAX, uid, owner, Instant::now());
    assert_eq!(tracer.owner_uid(uid, u32::MAX), (owner, MODE_ISOLATED));
    assert_eq!(tracer.isolated_owners.len(), 2);
}
