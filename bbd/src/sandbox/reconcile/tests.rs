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

use std::collections::BTreeMap;

use super::*;
use crate::{
    sandbox::tests::{Fixture, Recorder, enabled},
    sandbox_item::{Item, Mode},
    test_sync::{Rc, RefCell},
};

#[test]
fn an_app_stays_in_while_any_package_of_its_uid_is_installed() {
    let mut f = Fixture::new("sandbox-shared-stays");
    let mut sandbox = f.sandbox();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.shared.one", 0, 1000)
        .unwrap();
    // The package it was put in by is uninstalled, the other is not.
    std::fs::write(f.dir.join("packages.list"), "com.shared.two 10200 0 /d x\n").unwrap();
    let mut restarted = f.sandbox();
    restarted.resume(&f.writer, &mut f.packages);
    assert_eq!(enabled(&restarted), [10200]);
    assert_eq!(
        f.writer.sandbox_entry(10200).unwrap().unwrap().package,
        "com.shared.two"
    );
    // Its settings are the other's to change.
    restarted
        .configure(
            &f.writer,
            &mut f.packages,
            "com.shared.two",
            0,
            &BTreeMap::from([(Item::Camera, Some(Mode::Off))]),
        )
        .unwrap();
}

#[test]
fn a_daemon_restart_does_not_stage_unknown_permission_preimages() {
    let mut f = Fixture::new("sandbox-resume-named");
    let mut sandbox = f.sandbox_with(Vec::new());
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    // As written before the items that take the access were there.
    let mut entry = f.writer.sandbox_entry(10100).unwrap().unwrap();
    entry.saved_permissions.clear();
    f.writer.put_sandbox(&entry).unwrap();

    let mut restarted = f.sandbox_with(Vec::new());
    restarted.resume(&f.writer, &mut f.packages);
    let named = f
        .writer
        .sandbox_entry(10100)
        .unwrap()
        .unwrap()
        .saved_permissions;
    assert!(named.is_empty());
    assert_eq!(restarted.snapshot_read()[&10100].saved_permissions, named);
}

#[test]
fn a_uid_handed_to_another_app_is_taken_out() {
    let mut f = Fixture::new("sandbox-reassigned");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut sandbox = f.sandbox_with(vec![Recorder::new("gate", &log)]);
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.shared.one", 0, 1000)
        .unwrap();
    log.borrow_mut().clear();
    // com.app was uninstalled, and its uid given to an app installed
    // after it. The shared uid keeps the package that was named.
    std::fs::write(
        f.dir.join("packages.list"),
        "com.newcomer 10100 0 /d x\ncom.shared.one 10200 0 /d x\n",
    )
    .unwrap();

    let mut restarted = f.sandbox_with(vec![Recorder::new("gate", &log)]);
    restarted.resume(&f.writer, &mut f.packages);
    assert_eq!(enabled(&restarted), [10200]);
    let released = f.writer.sandbox_entry(10100).unwrap().unwrap();
    assert!(!released.enabled);
    assert_eq!(released.package, "com.app");
    // Taken off the one, taken up again for the other.
    assert_eq!(*log.borrow(), ["~gate 10200"]);

    // Without a package list nothing is concluded.
    std::fs::remove_file(f.dir.join("packages.list")).unwrap();
    restarted.resume(&f.writer, &mut f.packages);
    assert_eq!(enabled(&restarted), [10200]);
}

#[test]
fn a_missing_user_directory_defers_resume_without_retiring_the_sandbox() {
    let mut f = Fixture::new("sandbox-deferred-user");
    f.sandbox()
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    let original = f.writer.sandbox_entry(10100).unwrap().unwrap();
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut sandbox = Sandbox::load(
        &f.writer,
        vec![Recorder::new("gate", &log)],
        Some(&f.dir.join("unavailable")),
    )
    .unwrap();
    sandbox.resume(&f.writer, &mut f.packages);
    sandbox.release_reassigned(&f.writer, &mut f.packages);
    assert!(log.borrow().is_empty());
    assert_eq!(f.writer.sandbox_entry(10100).unwrap().unwrap(), original);
    // Use the fixture's matching owner once the storage is available.
    sandbox.user_data = None;
    sandbox.release_reassigned(&f.writer, &mut f.packages);
    sandbox.release_reassigned(&f.writer, &mut f.packages);
    assert_eq!(*log.borrow(), ["~gate 10100"]);
}
