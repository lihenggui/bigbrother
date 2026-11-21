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

/// Takes the precise location permission as the app ops helper does,
/// finding it granted, and reads, as it does, what the app's row names
/// as perhaps taken.
struct Taker {
    db: PathBuf,
    seen: Rc<RefCell<Vec<String>>>,
}

impl Taker {
    fn take(&self, entry: &mut Entry, items: &Settings) -> anyhow::Result<()> {
        let row = Writer::open(&self.db)?
            .sandbox_entry(entry.uid)?
            .context("no row")?;
        self.seen.borrow_mut().push(row.saved_permissions);
        if items.taken_permissions().is_empty() {
            entry.saved_permissions.clear();
        } else if entry.saved_permissions.is_empty() {
            entry.saved_permissions = format!("{}=granted", items::PRECISE_LOCATION);
        }
        Ok(())
    }
}

impl Effect for Taker {
    fn name(&self) -> &'static str {
        "taker"
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        self.take(entry, items)
    }

    fn disable(&mut self, entry: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        entry.saved_permissions.clear();
        Ok(())
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        _: &[String],
        _: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        self.take(entry, after)
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
fn what_is_written_while_items_change_covers_them_before_and_after() {
    let mut f = Fixture::new("sandbox-items-covered");
    let seen = Rc::new(RefCell::new(Vec::new()));
    let witness = || {
        Box::new(Witness {
            db: f.dir.join("bb.db"),
            seen: seen.clone(),
        })
    };
    let mut sandbox = f.sandbox_with(vec![witness()]);
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.app",
            0,
            &camera(Some(Mode::Off)),
        )
        .unwrap();
    let held = |seen: &Settings| -> std::collections::BTreeSet<&str> {
        seen.op_targets().into_iter().map(|(op, _)| op).collect()
    };
    let both = ["android:camera", "android:record_audio"];

    // The camera held anew and the microphone let go: both stay named
    // until the change is carried out.
    let change = BTreeMap::from([
        (Item::Camera, Some(Mode::On)),
        (Item::Microphone, Some(Mode::Off)),
    ]);
    seen.borrow_mut().clear();
    sandbox
        .configure(&f.writer, &mut f.packages, "com.app", 0, &change)
        .unwrap();
    assert!(both.iter().all(|op| held(&seen.borrow()[0]).contains(op)));
    let row = || f.writer.sandbox_entry(10100).unwrap().unwrap().settings;
    assert_eq!(row(), "camera=on,microphone=off");

    // The same for the global settings, which the app follows again.
    let follow = BTreeMap::from([(Item::Camera, None), (Item::Microphone, None)]);
    sandbox
        .configure(&f.writer, &mut f.packages, "com.app", 0, &follow)
        .unwrap();
    let global = |item, mode| BTreeMap::from([(item, mode)]);
    sandbox
        .set_global(&f.writer, &mut f.packages, &global(Item::Camera, Mode::Off))
        .unwrap();
    seen.borrow_mut().clear();
    let change = BTreeMap::from([(Item::Camera, Mode::On), (Item::Microphone, Mode::Off)]);
    sandbox
        .set_global(&f.writer, &mut f.packages, &change)
        .unwrap();
    assert!(both.iter().all(|op| held(&seen.borrow()[0]).contains(op)));
    assert_eq!(
        f.writer.sandbox_global().unwrap(),
        "camera=on,microphone=off"
    );

    let witness = witness();
    check_failed_item_changes(&mut f, witness, &seen);
}

fn check_failed_item_changes(
    f: &mut Fixture,
    witness: Box<Witness>,
    seen: &RefCell<Vec<Settings>>,
) {
    let row = || f.writer.sandbox_entry(10100).unwrap().unwrap().settings;
    // A change that fails is written down as it was again.
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut failing = Recorder::new("gate", &log);
    failing.fail_update = true;
    let mut sandbox = f.sandbox_with(vec![witness, failing]);
    let microphone_on = BTreeMap::from([(Item::Microphone, Some(Mode::On))]);
    assert!(
        sandbox
            .configure(&f.writer, &mut f.packages, "com.app", 0, &microphone_on)
            .is_err()
    );
    assert_eq!(row(), "");
    assert!(
        sandbox
            .set_global(
                &f.writer,
                &mut f.packages,
                &BTreeMap::from([(Item::Microphone, Mode::On)])
            )
            .is_err()
    );
    assert_eq!(
        f.writer.sandbox_global().unwrap(),
        "camera=on,microphone=off"
    );
    // Both were seen covering the change while it ran.
    assert!(seen.borrow()[1..].iter().all(|seen| {
        seen.op_targets()
            .iter()
            .any(|(op, _)| *op == "android:record_audio")
    }));
}

#[test]
fn a_permission_is_named_in_the_row_before_it_is_taken() {
    let mut f = Fixture::new("sandbox-permission-named");
    let seen = Rc::new(RefCell::new(Vec::new()));
    let taker = || {
        Box::new(Taker {
            db: f.dir.join("bb.db"),
            seen: seen.clone(),
        })
    };
    let fine = items::PRECISE_LOCATION;
    let (unknown, granted) = (format!("{fine}=default"), format!("{fine}=granted"));
    let row = |uid| {
        f.writer
            .sandbox_entry(uid)
            .unwrap()
            .unwrap()
            .saved_permissions
    };
    let global = |mode| BTreeMap::from([(Item::Location, mode)]);
    let mut sandbox = f.sandbox_with(vec![taker()]);
    // Only location takes a permission here.
    let others_off = BTreeMap::from([
        (Item::NotificationContent, Mode::Off),
        (Item::ScreenContent, Mode::Off),
    ]);
    sandbox
        .set_global(&f.writer, &mut f.packages, &others_off)
        .unwrap();

    // Put in with location coarse: named before the state is known,
    // and written down with it after.
    sandbox
        .set_global(&f.writer, &mut f.packages, &global(Mode::Coarse))
        .unwrap();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    assert_eq!(*seen.borrow(), std::slice::from_ref(&unknown));
    assert_eq!(row(10100), granted);

    // Precise again gives it back. Returning to coarse names it again
    // before the effect runs, even though the covering mode takes nothing.
    for (mode, saved) in [(Mode::On, ""), (Mode::Coarse, granted.as_str())] {
        seen.borrow_mut().clear();
        let location = BTreeMap::from([(Item::Location, Some(mode))]);
        sandbox
            .configure(&f.writer, &mut f.packages, "com.app", 0, &location)
            .unwrap();
        assert_eq!(row(10100), saved);
    }
    assert_eq!(*seen.borrow(), std::slice::from_ref(&unknown));
    assert_eq!(row(10100), granted);

    // The same for an app made coarse by the global settings.
    sandbox
        .enable(&f.writer, &mut f.packages, "com.shared.one", 0, 1000)
        .unwrap();
    sandbox
        .set_global(&f.writer, &mut f.packages, &global(Mode::On))
        .unwrap();
    assert_eq!(row(10200), "");
    seen.borrow_mut().clear();
    sandbox
        .set_global(&f.writer, &mut f.packages, &global(Mode::Coarse))
        .unwrap();
    assert_eq!(*seen.borrow(), std::slice::from_ref(&unknown));
    assert_eq!(row(10200), granted);
    // The app with its own coarse was not changed, and still names its own.
    assert_eq!(row(10100), granted);

    let taker = taker();
    check_failed_permission_changes(&mut f, taker, &granted);
}

fn check_failed_permission_changes(f: &mut Fixture, taker: Box<Taker>, granted: &str) {
    let row = |uid| {
        f.writer
            .sandbox_entry(uid)
            .unwrap()
            .unwrap()
            .saved_permissions
    };
    let location = BTreeMap::from([(Item::Location, Some(Mode::On))]);
    let global = BTreeMap::from([(Item::Location, Mode::On)]);
    // A change that fails is written down as it was.
    let log = Rc::new(RefCell::new(Vec::new()));
    let mut failing = Recorder::new("gate", &log);
    failing.fail_update = true;
    let mut sandbox = f.sandbox_with(vec![taker, failing]);
    sandbox
        .configure(&f.writer, &mut f.packages, "com.app", 0, &location)
        .unwrap_err();
    assert_eq!(row(10100), granted);
    sandbox
        .set_global(&f.writer, &mut f.packages, &global)
        .unwrap_err();
    assert_eq!(row(10200), granted);
    sandbox
        .configure(&f.writer, &mut f.packages, "com.shared.one", 0, &location)
        .unwrap_err();
    assert_eq!(row(10200), granted);
    sandbox
        .disable(&f.writer, &mut f.packages, "com.app", 0)
        .unwrap();
    assert_eq!(row(10100), "");
}

#[test]
fn a_change_between_coarse_and_foreground_is_written_down_as_on() {
    let mut f = Fixture::new("sandbox-coarse-foreground");
    let (covered, named) = (
        Rc::new(RefCell::new(Vec::new())),
        Rc::new(RefCell::new(Vec::new())),
    );
    let mut sandbox = f.sandbox_with(vec![
        Box::new(Witness {
            db: f.dir.join("bb.db"),
            seen: covered.clone(),
        }),
        Box::new(Taker {
            db: f.dir.join("bb.db"),
            seen: named.clone(),
        }),
    ]);
    let fine = items::PRECISE_LOCATION;
    let location = |mode| BTreeMap::from([(Item::Location, Some(mode))]);
    let row = || f.writer.sandbox_entry(10100).unwrap().unwrap();
    // Only location takes a permission here.
    let others_off = BTreeMap::from([
        (Item::NotificationContent, Mode::Off),
        (Item::ScreenContent, Mode::Off),
    ]);
    sandbox
        .set_global(&f.writer, &mut f.packages, &others_off)
        .unwrap();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.app",
            0,
            &location(Mode::Coarse),
        )
        .unwrap();
    assert_eq!(row().saved_permissions, format!("{fine}=granted"));

    // While it runs, the app is held to on: neither keeps all the other
    // does. The permission taken stays named until it is given back.
    named.borrow_mut().clear();
    sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.app",
            0,
            &location(Mode::Foreground),
        )
        .unwrap();
    assert_eq!(covered.borrow()[0].mode(Item::Location), Mode::On);
    assert_eq!(*named.borrow(), [format!("{fine}=granted")]);
    assert_eq!(row().settings, "location=foreground");
    assert_eq!(row().saved_permissions, "");

    // And back: on while it runs, the permission named before it is
    // taken.
    covered.borrow_mut().clear();
    named.borrow_mut().clear();
    sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.app",
            0,
            &location(Mode::Coarse),
        )
        .unwrap();
    assert_eq!(covered.borrow()[0].mode(Item::Location), Mode::On);
    assert_eq!(*named.borrow(), [format!("{fine}=default")]);
    assert_eq!(row().settings, "location=coarse");
    assert_eq!(row().saved_permissions, format!("{fine}=granted"));
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
fn a_failed_app_write_undoes_effects_before_restoring_the_snapshot() {
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
        [(10200, Mode::On, Mode::On), (10200, Mode::On, Mode::Off)]
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

    assert!(format!("{error:#}").contains("uid 10200: cannot save camera mode"));
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
