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

use std::path::PathBuf;

use super::*;
use crate::{
    sandbox::{
        Effect, identity,
        tests::{Fixture, Recorder},
    },
    test_sync::{Rc, RefCell},
};

/// Reads, while the effects of a change of items run, what a daemon
/// that started then would take the app up with: its row over the
/// global settings, as written.
struct Witness {
    db: PathBuf,
    seen: Rc<RefCell<Vec<Settings>>>,
}

impl Effect for Witness {
    fn name(&self) -> &'static str {
        "witness"
    }

    fn enable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        Ok(())
    }

    fn disable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        Ok(())
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        _: &[String],
        _: &Settings,
        _: &Settings,
    ) -> anyhow::Result<()> {
        let written = Writer::open(&self.db)?;
        let row = written.sandbox_entry(entry.uid)?.context("no row")?;
        let global = Settings::parse(&written.sandbox_global()?);
        self.seen.borrow_mut().push(items_of(&row, &global));
        Ok(())
    }
}

fn camera(mode: Option<Mode>) -> BTreeMap<Item, Option<Mode>> {
    BTreeMap::from([(Item::Camera, mode)])
}

#[test]
fn changing_items_updates_an_app_in_the_sandbox_only_if_what_applies_changes() {
    let mut f = Fixture::new("sandbox-items");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut sandbox = f.sandbox_with(vec![Recorder::new("a", &log), Recorder::new("b", &log)]);
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    log.borrow_mut().clear();

    let configured = sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.app",
            0,
            &camera(Some(Mode::Off)),
        )
        .unwrap();
    assert_eq!(configured.items.mode(Item::Camera), Mode::Off);
    assert_eq!(*log.borrow(), ["*a", "*b"]);
    assert_eq!(
        f.writer.sandbox_entry(10100).unwrap().unwrap().settings,
        "camera=off"
    );
    // The same again changes nothing, and nothing is done.
    sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.app",
            0,
            &camera(Some(Mode::Off)),
        )
        .unwrap();
    assert_eq!(log.borrow().len(), 2);

    // The global settings change the apps that follow them.
    let global = |item, mode| BTreeMap::from([(item, mode)]);
    sandbox
        .set_global(&f.writer, &mut f.packages, &global(Item::Camera, Mode::Off))
        .unwrap();
    assert_eq!(log.borrow().len(), 2);
    sandbox
        .set_global(
            &f.writer,
            &mut f.packages,
            &global(Item::Microphone, Mode::Off),
        )
        .unwrap();
    assert_eq!(log.borrow().len(), 4);
    assert_eq!(
        f.writer.sandbox_global().unwrap(),
        "camera=off,microphone=off"
    );
    // Following them again for the camera: it was off, and stays so.
    sandbox
        .configure(&f.writer, &mut f.packages, "com.app", 0, &camera(None))
        .unwrap();
    assert_eq!(log.borrow().len(), 4);

    check_disabled_settings(&mut f, &mut sandbox, &log);
}

fn check_disabled_settings(f: &mut Fixture, sandbox: &mut Sandbox, log: &RefCell<Vec<String>>) {
    // An app taken out keeps its settings for when it is put back.
    sandbox
        .disable(&f.writer, &mut f.packages, "com.app", 0)
        .unwrap();
    log.borrow_mut().clear();
    sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.app",
            0,
            &camera(Some(Mode::On)),
        )
        .unwrap();
    assert!(log.borrow().is_empty());
    assert_eq!(
        f.writer.sandbox_entry(10100).unwrap().unwrap().settings,
        "camera=on"
    );
    // One that never was in it has none.
    assert!(
        sandbox
            .configure(
                &f.writer,
                &mut f.packages,
                "com.shared.one",
                0,
                &camera(None)
            )
            .is_err()
    );
}

#[test]
fn a_change_of_items_that_fails_is_undone() {
    let mut f = Fixture::new("sandbox-items-failed");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut failing = Recorder::new("b", &log);
    failing.fail_update = true;
    let mut sandbox = f.sandbox_with(vec![Recorder::new("a", &log), failing]);
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    log.borrow_mut().clear();

    assert!(
        sandbox
            .configure(
                &f.writer,
                &mut f.packages,
                "com.app",
                0,
                &camera(Some(Mode::Off))
            )
            .is_err()
    );
    // `a` was changed, and changed back.
    assert_eq!(*log.borrow(), ["*a", "*a"]);
    assert_eq!(f.writer.sandbox_entry(10100).unwrap().unwrap().settings, "");
    assert_eq!(sandbox.snapshot_read()[&10100].settings, "");

    let off = BTreeMap::from([(Item::Camera, Mode::Off)]);
    assert!(
        sandbox
            .set_global(&f.writer, &mut f.packages, &off)
            .is_err()
    );
    assert_eq!(f.writer.sandbox_global().unwrap(), "");
    assert_eq!(sandbox.global_items().items.mode(Item::Camera), Mode::On);
}

#[test]
fn item_changes_keep_the_committed_policy_visible_until_commit() {
    let mut f = Fixture::new("sandbox-items-committed");
    let seen = Rc::new(RefCell::new(Vec::new()));
    f.writer
        .put_sandbox_global(&[("location", "foreground")])
        .unwrap();
    let mut sandbox = f.sandbox_with(vec![Box::new(Witness {
        db: f.dir.join("bb.db"),
        seen: seen.clone(),
    })]);
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    sandbox
        .set_global(
            &f.writer,
            &mut f.packages,
            &BTreeMap::from([(Item::Location, Mode::Coarse)]),
        )
        .unwrap();
    sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.app",
            0,
            &BTreeMap::from([(Item::Location, Some(Mode::Off))]),
        )
        .unwrap();
    let modes: Vec<_> = seen
        .borrow()
        .iter()
        .map(|settings| settings.mode(Item::Location))
        .collect();
    assert_eq!(modes, [Mode::Foreground, Mode::Coarse]);
    assert_eq!(
        f.writer.sandbox_entry(10100).unwrap().unwrap().settings,
        "location=off"
    );
}

#[test]
fn a_failed_change_of_the_global_settings_leaves_the_hook_list_as_it_was() {
    let mut f = Fixture::new("sandbox-global-list");
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut failing = Recorder::new("gate", &log);
    failing.fail_update = true;
    let mut sandbox = f.sandbox();
    let identity = identity::Identity::new(&sandbox.snapshot(), &sandbox.global());
    sandbox.add_effect(Box::new(identity.at(&f.dir)));
    sandbox.add_effect(failing);
    for package in ["com.app", "com.shared.one"] {
        sandbox
            .enable(&f.writer, &mut f.packages, package, 0, 1000)
            .unwrap();
    }
    let list = || {
        std::fs::read_to_string(f.dir.join("list"))
            .unwrap()
            .split_once('\n')
            .unwrap()
            .1
            .to_owned()
    };
    let listed = list();

    // The first app's change fails after its line was written.
    let off = BTreeMap::from([(Item::InstalledApps, Mode::Off)]);
    assert!(
        sandbox
            .set_global(&f.writer, &mut f.packages, &off)
            .is_err()
    );
    assert_eq!(list(), listed);
}

type CameraUpdate = (u32, Mode, Mode);

struct CameraWitness {
    snapshot: crate::sandbox::Snapshot,
    global: crate::sandbox::Global,
    seen: Rc<RefCell<Vec<CameraUpdate>>>,
}

impl Effect for CameraWitness {
    fn name(&self) -> &'static str {
        "camera witness"
    }

    fn enable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        Ok(())
    }

    fn disable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        Ok(())
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        _: &[String],
        _: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        let snapshot = crate::sync::read(&self.snapshot);
        let global = crate::sync::read(&self.global);
        let visible = items_of(&snapshot[&entry.uid], &global).mode(Item::Camera);
        let requested = after.mode(Item::Camera);
        self.seen.borrow_mut().push((entry.uid, visible, requested));
        entry.saved_ops = match requested {
            Mode::On => "android:camera=1".into(),
            _ => String::new(),
        };
        Ok(())
    }
}

fn watch_camera_updates(f: &mut Fixture) -> (Sandbox, Rc<RefCell<Vec<CameraUpdate>>>) {
    f.writer.put_sandbox_global(&[("camera", "off")]).unwrap();
    let mut sandbox = f.sandbox();
    let seen = Rc::new(RefCell::new(Vec::new()));
    sandbox.add_effect(Box::new(CameraWitness {
        snapshot: sandbox.snapshot(),
        global: sandbox.global(),
        seen: seen.clone(),
    }));
    for package in ["com.app", "com.shared.one"] {
        sandbox
            .enable(&f.writer, &mut f.packages, package, 0, 1000)
            .unwrap();
    }
    rusqlite::Connection::open(f.dir.join("bb.db"))
        .unwrap()
        .execute_batch(
            "CREATE TRIGGER fail_saving_camera BEFORE UPDATE OF saved_ops ON sandbox
             WHEN NEW.uid = 10200 AND NEW.saved_ops = 'android:camera=1'
             BEGIN SELECT RAISE(ABORT, 'cannot save camera mode'); END;",
        )
        .unwrap();
    (sandbox, seen)
}

#[test]
fn a_failed_app_write_restores_the_snapshot_before_undoing_effects() {
    let mut f = Fixture::new("sandbox-app-write-failed");
    let (mut sandbox, seen) = watch_camera_updates(&mut f);
    let previous = f.writer.sandbox_entry(10200).unwrap().unwrap();

    let error = sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.shared.one",
            0,
            &camera(Some(Mode::On)),
        )
        .unwrap_err();

    assert!(format!("{error:#}").contains("cannot save camera mode"));
    assert_eq!(
        *seen.borrow(),
        [(10200, Mode::On, Mode::On), (10200, Mode::Off, Mode::Off)]
    );
    assert_eq!(sandbox.snapshot_read()[&10200], previous);
    assert_eq!(f.writer.sandbox_entry(10200).unwrap(), Some(previous));
}

#[test]
fn a_failed_global_write_restores_global_settings_before_undoing_each_app() {
    let mut f = Fixture::new("sandbox-global-write-failed");
    let (mut sandbox, seen) = watch_camera_updates(&mut f);
    let previous = f.writer.sandbox_entries().unwrap();

    let error = sandbox
        .set_global(
            &f.writer,
            &mut f.packages,
            &BTreeMap::from([(Item::Camera, Mode::On)]),
        )
        .unwrap_err();

    assert!(format!("{error:#}").contains("cannot save camera mode"));
    assert_eq!(
        *seen.borrow(),
        [
            (10100, Mode::On, Mode::On),
            (10200, Mode::On, Mode::On),
            (10200, Mode::Off, Mode::Off),
            (10100, Mode::Off, Mode::Off),
        ]
    );
    assert_eq!(f.writer.sandbox_global().unwrap(), "camera=off");
    assert_eq!(sandbox.global_items().items.mode(Item::Camera), Mode::Off);
    assert_eq!(f.writer.sandbox_entries().unwrap(), previous);
    for entry in previous {
        assert_eq!(sandbox.snapshot_read()[&entry.uid], entry);
    }
}

#[test]
fn a_failed_global_commit_preserves_every_installs_undo_for_restart() {
    use std::sync::{Arc, Mutex};

    use crate::sandbox::appops::tests::{FINE, recording_helper};

    let mut f = Fixture::new("sandbox-global-undo");
    f.writer
        .put_sandbox_global(&[("location", "foreground")])
        .unwrap();
    let mut sandbox = f.sandbox();
    for name in ["com.app", "com.shared.one"] {
        sandbox
            .enable(&f.writer, &mut f.packages, name, 0, 0)
            .unwrap();
    }
    let before = f.writer.sandbox_entries().unwrap();
    let changed = Arc::new(Mutex::new(std::collections::BTreeSet::new()));
    let (effect, applied) = recording_helper({
        let changed = changed.clone();
        move |uid, command| {
            if command.starts_with("apply ") {
                changed.lock().unwrap().insert(uid);
            }
        }
    });
    sandbox.add_effect(effect);
    sandbox.resume(&f.writer, &mut f.packages);

    let fault = rusqlite::Connection::open(f.dir.join("bb.db")).unwrap();
    fault
        .execute_batch(
            "CREATE TRIGGER fail_global BEFORE INSERT ON sandbox_global
         BEGIN SELECT RAISE(ABORT, 'injected global commit failure'); END;",
        )
        .unwrap();
    assert!(
        sandbox
            .set_global(
                &f.writer,
                &mut f.packages,
                &BTreeMap::from([(Item::Location, Mode::Coarse)]),
            )
            .is_err()
    );
    assert_eq!(f.writer.sandbox_global().unwrap(), "location=foreground");
    assert_eq!(f.writer.sandbox_entries().unwrap(), before);
    let pending = f.writer.appops_undo().unwrap();
    assert_eq!(pending.len(), 2);
    assert!(
        pending
            .iter()
            .all(|undo| undo.preimage.ends_with(&format!("{FINE}=granted+user_set")))
    );
    assert_eq!(changed.lock().unwrap().len(), 2);
    drop(sandbox);
    assert_eq!(
        applied
            .join()
            .unwrap()
            .iter()
            .filter(|line| line.starts_with("apply "))
            .count(),
        2
    );
    fault.execute_batch("DROP TRIGGER fail_global").unwrap();

    let (effect, recovered) = recording_helper({
        let changed = changed.clone();
        move |uid, command| {
            assert!(command.starts_with("undo "));
            assert!(command.ends_with(&format!("{FINE}=granted+user_set")));
            changed.lock().unwrap().remove(&uid);
        }
    });
    let mut sandbox = f.sandbox_with(vec![effect]);
    sandbox.resume(&f.writer, &mut f.packages);
    sandbox.recover_appops(&f.writer, &mut f.packages);
    assert!(changed.lock().unwrap().is_empty());
    assert!(f.writer.appops_undo().unwrap().is_empty());
    assert_eq!(f.writer.sandbox_entries().unwrap(), before);
    assert_eq!(f.writer.sandbox_global().unwrap(), "location=foreground");
    drop(sandbox);
    assert_eq!(
        recovered
            .join()
            .unwrap()
            .iter()
            .filter(|line| line.starts_with("undo "))
            .count(),
        2
    );
}
