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

use super::*;
use crate::sandbox::items::Part;
use crate::{
    sandbox::tests::{Fixture, Recorder, enabled},
    sandbox_item::Item,
    test_sync::{Rc, RefCell},
};

#[test]
fn taking_every_app_out_answers_before_the_client_gives_up() {
    assert!(!DISABLE_ALL_WAIT.is_zero());
    assert!(DISABLE_ALL_WAIT + appops::ANSWER_WAIT < control::COMMAND_WAIT);
}

#[test]
fn packages_sharing_the_uid_are_sandboxed_with_it() {
    let mut f = Fixture::new("sandbox-shared");
    let mut sandbox = f.sandbox();
    let answer = sandbox
        .enable(&f.writer, &mut f.packages, "com.shared.two", 0, 1000)
        .unwrap();
    assert_eq!(
        answer,
        Enabled {
            uid: 10200,
            also: vec!["com.shared.one".into()]
        }
    );
    let alone = sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    assert!(alone.also.is_empty());
    assert_eq!(enabled(&sandbox), [10100, 10200]);
}

#[test]
fn the_uid_is_that_of_the_given_user() {
    let mut f = Fixture::new("sandbox-user");
    let mut sandbox = f.sandbox();
    let answer = sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 10, 1000)
        .unwrap();
    assert_eq!(answer.uid, 1_010_100);
    assert!(
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", u32::MAX, 1000)
            .is_err()
    );
}

#[test]
fn refuses_system_uids_itself_and_what_is_not_installed() {
    let mut f = Fixture::new("sandbox-refuse");
    let mut sandbox = f.sandbox();
    for package in ["com.android.phone", "li.mercury.bigbrother", "com.absent"] {
        assert!(
            sandbox
                .enable(&f.writer, &mut f.packages, package, 0, 1000)
                .is_err(),
            "{package}"
        );
    }
    assert!(f.writer.sandbox_entries().unwrap().is_empty());
    // The canary is how the sandbox is tested.
    sandbox
        .enable(
            &f.writer,
            &mut f.packages,
            "li.mercury.bigbrother.canary",
            0,
            1000,
        )
        .unwrap();
    assert_eq!(enabled(&sandbox), [10301]);
}

#[test]
fn disabling_keeps_the_android_id() {
    let mut f = Fixture::new("sandbox-identity");
    let mut sandbox = f.sandbox();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    let first = f.writer.sandbox_entry(10100).unwrap().unwrap();
    assert_eq!(first.android_id.len(), 16);
    assert!(
        first
            .android_id
            .chars()
            .all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c))
    );
    assert_eq!((first.enabled, first.created), (true, 1000));

    sandbox
        .disable(&f.writer, &mut f.packages, "com.app", 0)
        .unwrap();
    assert!(enabled(&sandbox).is_empty());
    let disabled = f.writer.sandbox_entry(10100).unwrap().unwrap();
    assert!(!disabled.enabled);
    assert_eq!(disabled.android_id, first.android_id);

    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 2000)
        .unwrap();
    assert_eq!(f.writer.sandbox_entry(10100).unwrap(), Some(first));
    // Another app gets an identity of its own.
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 10, 2000)
        .unwrap();
    let other = f.writer.sandbox_entry(1_010_100).unwrap().unwrap();
    assert_ne!(
        other.android_id,
        f.writer.sandbox_entry(10100).unwrap().unwrap().android_id
    );
}

#[test]
fn the_snapshot_is_loaded_from_the_enabled_entries() {
    let mut f = Fixture::new("sandbox-load");
    let mut sandbox = f.sandbox();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.shared.one", 0, 1000)
        .unwrap();
    sandbox
        .disable(&f.writer, &mut f.packages, "com.shared.one", 0)
        .unwrap();
    assert_eq!(enabled(&f.sandbox()), [10100]);
}

#[test]
fn enabling_twice_or_disabling_what_is_not_sandboxed_does_nothing() {
    let mut f = Fixture::new("sandbox-idempotent");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut sandbox = f.sandbox_with(vec![Recorder::new("gate", &log)]);
    sandbox
        .disable(&f.writer, &mut f.packages, "com.app", 0)
        .unwrap();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.shared.one", 0, 1000)
        .unwrap();
    // Through another package of the same uid.
    sandbox
        .enable(&f.writer, &mut f.packages, "com.shared.two", 0, 1000)
        .unwrap();
    assert_eq!(*log.borrow(), ["+gate", "+gate"]);
    assert!(
        sandbox
            .disable(&f.writer, &mut f.packages, "com.absent", 0)
            .is_err()
    );
}

#[test]
fn an_app_uninstalled_while_sandboxed_can_be_disabled() {
    let mut f = Fixture::new("sandbox-uninstalled");
    let mut sandbox = f.sandbox();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    let mut gone = Packages::new(f.dir.join("absent.list").to_str().unwrap());
    sandbox.disable(&f.writer, &mut gone, "com.app", 0).unwrap();
    assert!(enabled(&sandbox).is_empty());
    assert!(!f.writer.sandbox_entry(10100).unwrap().unwrap().enabled);
}

#[test]
fn what_the_effects_find_changed_is_kept_and_written() {
    let mut f = Fixture::new("sandbox-found");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut sandbox = f.sandbox_with(vec![Recorder::new("appops", &log)]);
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    sandbox.keep_found(&f.writer);
    assert_eq!(
        sandbox.snapshot_read()[&10100].saved_permissions,
        "appops=granted"
    );
    assert_eq!(
        f.writer
            .sandbox_entry(10100)
            .unwrap()
            .unwrap()
            .saved_permissions,
        "appops=granted"
    );
}

#[test]
fn a_permission_change_is_written_again_after_a_failed_write() {
    let mut f = Fixture::new("sandbox-found-retry");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut sandbox = f.sandbox_with(vec![Recorder::new("appops", &log)]);
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    let db = f.dir.join("bb.db");
    let connection = rusqlite::Connection::open(&db).unwrap();
    connection
        .execute_batch(
            "CREATE TRIGGER fail_saved_permission BEFORE UPDATE OF saved_permissions ON sandbox
                 BEGIN SELECT RAISE(FAIL, 'injected write failure'); END;",
        )
        .unwrap();
    sandbox.keep_found(&f.writer);
    assert_eq!(
        sandbox.snapshot_read()[&10100].saved_permissions,
        "appops=granted"
    );
    assert_eq!(
        f.writer
            .sandbox_entry(10100)
            .unwrap()
            .unwrap()
            .saved_permissions,
        ""
    );
    connection
        .execute_batch("DROP TRIGGER fail_saved_permission")
        .unwrap();
    sandbox.keep_found(&f.writer);
    let reopened = Writer::open(&db).unwrap();
    assert_eq!(
        reopened
            .sandbox_entry(10100)
            .unwrap()
            .unwrap()
            .saved_permissions,
        "appops=granted"
    );
}

#[test]
fn effects_run_in_order_and_what_they_change_is_kept() {
    let mut f = Fixture::new("sandbox-effects");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut sandbox = f.sandbox_with(vec![
        Recorder::new("gate", &log),
        Recorder::new("appops", &log),
    ]);
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    assert_eq!(
        f.writer.sandbox_entry(10100).unwrap().unwrap().saved_ops,
        "appops=1"
    );
    assert_eq!(sandbox.snapshot_read()[&10100].saved_ops, "appops=1");
    sandbox
        .disable(&f.writer, &mut f.packages, "com.app", 0)
        .unwrap();
    assert_eq!(*log.borrow(), ["+gate", "+appops", "-gate", "-appops"]);
    assert!(sandbox.status().gate);
}

#[test]
fn a_failed_enable_is_undone() {
    let mut f = Fixture::new("sandbox-enable-fails");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut failing = Recorder::new("identity", &log);
    failing.fail_enable = true;
    let mut sandbox = f.sandbox_with(vec![
        Recorder::new("gate", &log),
        Recorder::new("appops", &log),
        failing,
    ]);

    let e = sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap_err();
    assert_eq!(format!("{e:#}"), "identity: refused");
    assert_eq!(*log.borrow(), ["+gate", "+appops", "-appops", "-gate"]);
    assert!(enabled(&sandbox).is_empty());
    assert_eq!(f.writer.sandbox_entry(10100).unwrap(), None);
}

#[test]
fn a_failed_reenable_keeps_the_disabled_entry() {
    let mut f = Fixture::new("sandbox-reenable-fails");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut sandbox = f.sandbox();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    sandbox
        .disable(&f.writer, &mut f.packages, "com.app", 0)
        .unwrap();
    let disabled = f.writer.sandbox_entry(10100).unwrap();
    let mut failing = Recorder::new("gate", &log);
    failing.fail_enable = true;
    let mut sandbox = f.sandbox_with(vec![failing]);
    assert!(
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 2000)
            .is_err()
    );
    assert_eq!(f.writer.sandbox_entry(10100).unwrap(), disabled);
}

#[test]
fn a_failed_disable_is_undone() {
    let mut f = Fixture::new("sandbox-disable-fails");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut failing = Recorder::new("appops", &log);
    failing.fail_disable = true;
    let mut sandbox = f.sandbox_with(vec![Recorder::new("gate", &log), failing]);
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    log.borrow_mut().clear();

    assert!(
        sandbox
            .disable(&f.writer, &mut f.packages, "com.app", 0)
            .is_err()
    );
    assert_eq!(*log.borrow(), ["-gate", "+gate"]);
    assert_eq!(enabled(&sandbox), [10100]);
    assert!(f.writer.sandbox_entry(10100).unwrap().unwrap().enabled);
}

#[test]
fn gate_coverage_only_counts_for_registered_uids() {
    let mut f = Fixture::new("sandbox-per-uid-gate");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut sandbox = f.sandbox_with(vec![Recorder::new("gate", &log)]);
    for name in ["com.app", "com.shared.one"] {
        sandbox
            .enable(&f.writer, &mut f.packages, name, 0, 1000)
            .unwrap();
    }
    let list = sandbox.list(&f.writer).unwrap();
    for app in list.apps {
        let missing = app
            .unmet
            .get(&Item::Storage)
            .is_some_and(|parts| parts.contains(&Part::Mounts));
        assert_eq!(missing, app.uid != 10100);
    }
}

#[test]
fn a_disabled_sandbox_is_not_inherited_by_the_next_package_at_its_uid() {
    let mut f = Fixture::new("sandbox-new-install");
    let mut sandbox = f.sandbox();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    sandbox
        .disable(&f.writer, &mut f.packages, "com.app", 0)
        .unwrap();
    let mut old = f.writer.sandbox_entry(10100).unwrap().unwrap();
    old.saved_ops = "android:camera=0".into();
    old.settings = "camera=off".into();
    f.writer.put_sandbox(&old).unwrap();
    std::fs::write(f.dir.join("packages.list"), "com.successor 10100 0 /d x\n").unwrap();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.successor", 0, 2000)
        .unwrap();
    let next = f.writer.sandbox_entry(10100).unwrap().unwrap();
    assert_ne!(old.android_id, next.android_id);
    assert_ne!(old.install_id, next.install_id);
    assert_eq!(next.created, 2000);
    assert!(next.saved_ops.is_empty() && next.settings.is_empty());
}

#[test]
fn an_app_must_be_installed_for_the_user_named() {
    let mut f = Fixture::new("sandbox-user-data");
    let user_data = f.dir.join("user");
    std::fs::create_dir_all(user_data.join("0/com.app")).unwrap();
    let mut sandbox = Sandbox::load(&f.writer, Vec::new(), Some(&user_data)).unwrap();
    let e = sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 10, 1000)
        .unwrap_err();
    assert_eq!(e.to_string(), "cannot verify the installation for user 10");
    // A directory with a different owner cannot establish an installation.
    if unsafe { libc::geteuid() } != 10100 {
        assert!(
            sandbox
                .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
                .is_err()
        );
    }
    // A user whose uids Android could not hold.
    assert!(
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", LAST_USER + 1, 1000)
            .is_err()
    );
}

#[test]
fn every_app_can_be_taken_out_at_once() {
    let mut f = Fixture::new("sandbox-disable-all");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut sandbox = f.sandbox_with(vec![Recorder::new("gate", &log)]);
    for package in ["com.app", "com.shared.one"] {
        sandbox
            .enable(&f.writer, &mut f.packages, package, 0, 1000)
            .unwrap();
    }
    assert_eq!(
        sandbox.disable_all(&f.writer),
        DisabledAll {
            disabled: 2,
            failed: vec![]
        }
    );
    assert!(enabled(&sandbox).is_empty());
    assert!(
        f.writer
            .sandbox_entries()
            .unwrap()
            .iter()
            .all(|e| !e.enabled)
    );

    // One that cannot be taken out is reported, and stays.
    let mut failing = Recorder::new("appops", &log);
    failing.fail_disable = true;
    let mut sandbox = f.sandbox_with(vec![failing]);
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 2000)
        .unwrap();
    assert_eq!(
        sandbox.disable_all(&f.writer),
        DisabledAll {
            disabled: 0,
            failed: vec![10100]
        }
    );
    assert_eq!(enabled(&sandbox), [10100]);
}

#[test]
fn requests_are_answered_with_one_json_line() {
    let mut f = Fixture::new("sandbox-answer");
    let mut sandbox = f.sandbox();
    let mut ask = |line: &str| -> serde_json::Value {
        let answer = sandbox.answer(line, &f.writer, &mut f.packages);
        assert!(!answer.contains('\n'));
        serde_json::from_str(&answer).unwrap()
    };
    assert_eq!(
        ask(r#"{"cmd":"sandbox.enable","package":"com.shared.one","user":0}"#),
        serde_json::json!({"ok": true, "uid": 10200, "also": ["com.shared.two"]})
    );
    // The user may be left out.
    assert_eq!(
        ask(r#"{"cmd":"sandbox.enable","package":"com.app"}"#)["uid"],
        10100
    );
    assert_eq!(
        ask(r#"{"cmd":"sandbox.disable","package":"com.app","user":0}"#),
        serde_json::json!({"ok": true, "was_enabled": true})
    );
    assert_eq!(
        ask(r#"{"cmd":"sandbox.disable","package":"com.app","user":0}"#),
        serde_json::json!({"ok": true, "was_enabled": false})
    );
    let listed = ask(r#"{"cmd":"sandbox.list"}"#);
    let apps: Vec<_> = listed["apps"]
        .as_array()
        .unwrap()
        .iter()
        .map(|app| {
            (
                app["uid"].clone(),
                app["package"].clone(),
                app["enabled"].clone(),
            )
        })
        .collect();
    assert_eq!(
        apps,
        [
            (10100.into(), "com.app".into(), false.into()),
            (10200.into(), "com.shared.one".into(), true.into())
        ]
    );
    assert_eq!(listed["apps"][1]["settings"], serde_json::json!({}));
    assert_eq!(listed["apps"][1]["items"]["camera"], "on");
    check_item_answers(&mut ask);
    check_status_and_errors(&mut ask);
}

fn check_item_answers(ask: &mut impl FnMut(&str) -> serde_json::Value) {
    // Item by item, for one app and for all.
    assert_eq!(
        ask(
            r#"{"cmd":"sandbox.set","package":"com.shared.one","settings":{"camera":"off","location":"coarse"}}"#
        ),
        serde_json::json!({
            "ok": true, "uid": 10200,
            "settings": {"location": "coarse", "camera": "off"},
            "items": {
                "storage": "on", "cpu_info": "on", "contacts": "on", "messages": "on",
                "calendar": "on", "clipboard_accounts": "on", "location": "coarse", "camera": "off",
                "microphone": "on", "body_sensors": "on", "device_ids": "on",
                "installed_apps": "on", "nearby_devices": "on", "notification_content": "on",
                "screen_content": "on", "network": "off", "network_record": "off",
            },
        })
    );
    assert_eq!(
        ask(r#"{"cmd":"sandbox.set","package":"com.shared.one","settings":{"camera":null}}"#)["settings"],
        serde_json::json!({"location": "coarse"})
    );
    assert_eq!(
        ask(r#"{"cmd":"sandbox.set","package":"com.shared.one","settings":{"storage":"coarse"}}"#),
        serde_json::json!({"ok": false, "error": "storage cannot be coarse"})
    );
    assert_eq!(
        ask(
            r#"{"cmd":"sandbox.set","package":"com.shared.one","settings":{"contacts":"foreground"}}"#
        ),
        serde_json::json!({"ok": false, "error": "contacts cannot be foreground"})
    );
    assert_eq!(
        ask(
            r#"{"cmd":"sandbox.set","package":"com.shared.one","settings":{"camera":"foreground"}}"#
        )["items"]["camera"],
        "foreground"
    );
    assert_eq!(
        ask(r#"{"cmd":"sandbox.set","package":"com.shared.one","settings":{"camera":null}}"#)["items"]
            ["camera"],
        "on"
    );
    assert_eq!(
        ask(r#"{"cmd":"sandbox.global"}"#)["items"]["microphone"],
        "on"
    );
    assert_eq!(
        ask(r#"{"cmd":"sandbox.set_global","settings":{"microphone":"off"}}"#)["items"]["microphone"],
        "off"
    );
    assert_eq!(
        ask(r#"{"cmd":"sandbox.list"}"#)["apps"][1]["items"]["microphone"],
        "off"
    );
}

fn check_status_and_errors(ask: &mut impl FnMut(&str) -> serde_json::Value) {
    assert_eq!(
        ask(r#"{"cmd":"sandbox.status"}"#),
        serde_json::json!({
            "ok": true, "gate": false, "appops": false, "network": false,
            "net_record": false, "dns_names": false, "traffic": false,
            "hook": {"android_id": false, "app_list": false, "uri_grants": false, "ad_id": false},
            "admitted": 0, "failed": 0, "new_apps_stopped": 0, "new_apps_pending": 0,
            "new_apps_held": false, "new_apps_failed": 0, "hold_p95_us": 0,
        })
    );
    assert_eq!(
        ask(r#"{"cmd":"sandbox.enable","package":"com.absent"}"#),
        serde_json::json!({"ok": false, "error": "com.absent is not installed"})
    );
    assert_eq!(
        ask(r#"{"cmd":"sandbox.shred"}"#),
        serde_json::json!({"ok": false, "error": "unknown command"})
    );
    assert_eq!(ask("not json")["ok"], false);
    assert_eq!(ask(r#"{"cmd":"sandbox.enable"}"#)["ok"], false);
}
