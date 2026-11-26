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

use std::{sync::Barrier, thread};

use crate::record::{Category, MODE_READ};

use super::{Counters, Reader, SandboxEntry, Writer};

pub(super) fn sandbox_entry_for(uid: u32) -> SandboxEntry {
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

const FLUSHES_PER_WRITER: i64 = 30;
const ACCESS: &str = "/proc/cpuinfo";

#[test]
fn wal_keeps_flushes_and_sandbox_writes_consistent_while_a_reader_holds_a_snapshot() {
    let dir = std::env::temp_dir().join(format!("bbd-test-wal-{}", std::process::id()));
    let db = dir.join("bb.db");
    let sandbox = Writer::open(&db).unwrap();
    sandbox.put_sandbox(&sandbox_entry_for(10100)).unwrap();
    let writers = [Writer::open(&db).unwrap(), Writer::open(&db).unwrap()];
    let reader = Reader::open(&db).unwrap();
    let snapshot = reader.connection().unchecked_transaction().unwrap();
    assert_eq!(reader.stats().unwrap().rows, 0);
    let start = Barrier::new(4);
    thread::scope(|scope| {
        for mut writer in writers {
            let start = &start;
            scope.spawn(move || {
                start.wait();
                for at in 1..=FLUSHES_PER_WRITER {
                    writer.record(
                        10100,
                        "com.a",
                        Category::System,
                        ACCESS.into(),
                        MODE_READ,
                        true,
                        at,
                        0,
                    );
                    writer.flush(&Counters::default(), at).unwrap();
                }
            });
        }
        let start = &start;
        scope.spawn(move || {
            start.wait();
            for _ in 0..FLUSHES_PER_WRITER {
                sandbox
                    .write_sandbox(|tx| {
                        let created: i64 = tx.query_row(
                            "SELECT created FROM sandbox WHERE uid = 10100",
                            [],
                            |row| row.get(0),
                        )?;
                        tx.execute(
                            "UPDATE sandbox SET created = ?1 WHERE uid = 10100",
                            [created + 1],
                        )?;
                        Ok(())
                    })
                    .unwrap();
            }
        });
        start.wait();
        assert!(reader.history(10100, ACCESS, 0).unwrap().is_empty());
    });
    assert_eq!(reader.stats().unwrap().rows, 0);
    assert_eq!(reader.sandbox_entries().unwrap()[0].created, 1000);
    snapshot.commit().unwrap();
    assert_eq!(reader.stats().unwrap().rows, 1);
    let history = reader.history(10100, ACCESS, 0).unwrap();
    assert_eq!(history.len(), FLUSHES_PER_WRITER as usize);
    assert!(history.iter().all(|row| row.ok == 2 && row.fail == 0));
    assert_eq!(
        reader.sandbox_entries().unwrap()[0].created,
        1000 + FLUSHES_PER_WRITER
    );
    drop(reader);
    std::fs::remove_dir_all(dir).unwrap();
}
