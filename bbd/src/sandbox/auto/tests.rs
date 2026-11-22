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

pub(super) struct Fixture {
    pub(super) dir: PathBuf,
    pub(super) writer: Writer,
    pub(super) packages: Packages,
}

impl Fixture {
    pub(super) fn new(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("bbd-test-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let packages = Packages::new(dir.join("packages.list").to_str().unwrap());
        let writer = Writer::open(&dir.join("bb.db")).unwrap();
        let mut fixture = Self {
            dir,
            writer,
            packages,
        };
        fixture.list(&[
            "com.android.chrome 10150 0 /d default:targetSdkVersion=35:partition=product 3003 0 1 1 @system",
            "com.example.old 10200 0 /d default:targetSdkVersion=34 none 0 1 1 com.android.vending",
        ]);
        fixture.install(0, "com.example.old");
        fixture.install(0, "com.android.chrome");
        fixture
    }

    /// Write the package list.
    pub(super) fn list(&mut self, lines: &[&str]) {
        std::fs::write(self.dir.join("packages.list"), lines.join("\n")).unwrap();
        self.packages.refresh();
    }

    pub(super) fn install(&self, user: u32, package: &str) {
        std::fs::create_dir_all(self.user_data().join(user.to_string()).join(package)).unwrap();
    }

    fn uninstall(&self, user: u32, package: &str) {
        std::fs::remove_dir(self.user_data().join(user.to_string()).join(package)).unwrap();
    }

    pub(super) fn user_data(&self) -> PathBuf {
        self.dir.join("user_de")
    }

    pub(super) fn sandbox(&self) -> Sandbox {
        self.sandbox_with(Vec::new())
    }

    fn sandbox_with(&self, effects: Vec<Box<dyn super::super::Effect>>) -> Sandbox {
        // The planner needs per-user directories. Verification of
        // trusted installation cookies needs a rooted Android device.
        let mut sandbox = Sandbox::load(&self.writer, effects, None).unwrap();
        sandbox.auto.user_data_fixture = Some(self.user_data());
        sandbox
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

pub(super) fn sandboxed(sandbox: &Sandbox) -> Vec<u32> {
    let mut uids: Vec<u32> = sandbox.snapshot_read().keys().copied().collect();
    uids.sort();
    uids
}

pub(super) const NEW: &str =
    "com.example.new 10230 0 /d default:targetSdkVersion=35 none 0 1 1 com.android.vending";

#[test]
fn nothing_installed_when_it_is_turned_on_is_put_in() {
    let mut f = Fixture::new("auto-on");
    let mut sandbox = f.sandbox();
    assert!(!sandbox.auto_on());
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());
    assert!(f.sandbox().auto_on());
}

#[test]
fn an_app_installed_afterwards_is_put_in_for_every_user_it_is_installed_for() {
    let mut f = Fixture::new("auto-new");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[
        "com.android.chrome 10150 0 /d default:partition=product none 0 1 1 @system",
        "com.example.old 10200 0 /d default none 0 1 1 com.android.vending",
        NEW,
    ]);
    f.install(0, "com.example.new");
    f.install(10, "com.example.new");
    f.install(11, "com.example.old");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10230, 1_010_230]);
    assert_eq!(f.writer.sandbox_entry(10230).unwrap().unwrap().settings, "");

    // Dealt with once: one the user takes out stays out, and a daemon
    // that starts again knows it.
    sandbox
        .disable(&f.writer, &mut f.packages, "com.example.new", 10)
        .unwrap();
    sandbox.isolate_new(&f.writer, &mut f.packages);
    let mut restarted = f.sandbox();
    restarted.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&restarted), [10230]);

    // Installed for another user later, it is put in there too.
    f.install(12, "com.example.new");
    restarted.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&restarted), [10230, 1_210_230]);
}

#[test]
fn an_update_is_not_new_and_a_reinstall_is() {
    let mut f = Fixture::new("auto-reinstall");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    // Updated: the same app id, another version.
    f.list(&[
        "com.android.chrome 10150 0 /d default:partition=product none 0 2 1 @system",
        "com.example.old 10200 0 /d default none 0 2 1 com.android.vending",
    ]);
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());

    // Uninstalled, then installed again under a new app id.
    f.list(&["com.android.chrome 10150 0 /d default:partition=product none 0 2 1 @system"]);
    f.uninstall(0, "com.example.old");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    f.list(&[
        "com.android.chrome 10150 0 /d default:partition=product none 0 2 1 @system",
        "com.example.old 10231 0 /d default none 0 2 1 com.android.vending",
    ]);
    f.install(0, "com.example.old");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10231]);
}

#[test]
fn an_app_id_given_to_another_app_is_new() {
    let mut f = Fixture::new("auto-reassigned");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    // Removed and its app id handed on before the list was looked at.
    f.list(&[
        "com.android.chrome 10150 0 /d default:partition=product none 0 1 1 @system",
        "com.example.other 10200 0 /d default none 0 1 1 com.android.vending",
    ]);
    f.install(0, "com.example.other");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10200]);
}

#[test]
fn the_systems_apps_and_its_own_are_never_put_in() {
    let mut f = Fixture::new("auto-system");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    // A system update brought a new app; the app was installed again.
    f.list(&[
        "com.android.chrome 10150 0 /d default:partition=product none 0 1 1 @system",
        "com.example.old 10200 0 /d default none 0 1 1 com.android.vending",
        "com.android.newer 10240 0 /d platform:privapp:partition=system none 0 1 1 @system",
        "li.mercury.bigbrother 10241 0 /d default none 0 1 1 @null",
    ]);
    f.install(0, "com.android.newer");
    f.install(0, "li.mercury.bigbrother");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());
    let (_, known) = f.writer.sandbox_auto().unwrap();
    let apps: Vec<u32> = known.iter().map(|row| row.app_id).collect();
    assert_eq!(apps, [10150, 10200, 10240, 10241]);
}

#[test]
fn nothing_is_done_while_it_is_off_or_without_the_package_list() {
    let mut f = Fixture::new("auto-off");
    let mut sandbox = f.sandbox();
    f.list(&[NEW]);
    f.install(0, "com.example.new");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());

    let mut absent = Packages::new(f.dir.join("absent.list").to_str().unwrap());
    let e = sandbox.set_auto(&f.writer, &mut absent, true).unwrap_err();
    assert_eq!(e.to_string(), "the package list cannot be read");
    assert!(!sandbox.auto_on());

    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    let known = f.writer.sandbox_auto().unwrap();
    // Nothing known is forgotten for want of the list.
    sandbox.isolate_new(&f.writer, &mut absent);
    assert_eq!(f.writer.sandbox_auto().unwrap(), known);

    sandbox.set_auto(&f.writer, &mut f.packages, false).unwrap();
    assert_eq!(f.writer.sandbox_auto().unwrap(), (false, vec![]));
}

#[test]
fn the_kernel_side_is_given_a_bit_per_known_app_id() {
    let known = Known {
        apps: [10_000, 10_063, 10_064, 19_999, 1001]
            .into_iter()
            .map(|app_id| (app_id, names(&["com.a"])))
            .collect(),
        uids: BTreeMap::from([(1_010_230, names(&["com.b"]))]),
        libraries: BTreeMap::from([(10_065, names(&["com.library_2"]))]),
        failed: BTreeMap::new(),
    };
    let words = known.words();
    assert_eq!(words.len(), 157);
    assert_eq!(words[0], 1 | 1 << 63);
    assert_eq!(words[1], 1);
    assert_eq!(words[156], 1 << 15);
    assert_eq!(words.iter().map(|w| w.count_ones()).sum::<u32>(), 4);
    // What no package can have has no bit.
    assert_eq!(known_app_bit(9_999), None);
    assert_eq!(known_app_bit(20_000), None);
    assert_eq!(known_app_bit(99_012), None);
}

pub(super) fn names(packages: &[&str]) -> BTreeSet<String> {
    packages.iter().map(|name| name.to_string()).collect()
}

#[test]
fn its_state_is_answered_as_json() {
    let mut f = Fixture::new("auto-answer");
    let mut sandbox = f.sandbox();
    let mut ask = |line: &str| -> serde_json::Value {
        serde_json::from_str(&sandbox.answer(line, &f.writer, &mut f.packages)).unwrap()
    };
    assert_eq!(
        ask(r#"{"cmd":"sandbox.auto"}"#),
        serde_json::json!({"ok": true, "auto": false})
    );
    assert_eq!(ask(r#"{"cmd":"sandbox.global"}"#)["auto"], false);
    assert_eq!(
        ask(r#"{"cmd":"sandbox.set_auto","on":true}"#),
        serde_json::json!({"ok": true, "auto": true})
    );
    assert_eq!(ask(r#"{"cmd":"sandbox.global"}"#)["auto"], true);
    assert_eq!(
        ask(r#"{"cmd":"sandbox.set_auto","on":false}"#),
        serde_json::json!({"ok": true, "auto": false})
    );

    let mut out = Vec::new();
    print(&mut out, true).unwrap();
    print(&mut out, false).unwrap();
    assert_eq!(
        String::from_utf8(out).unwrap(),
        "Newly installed apps are put in the sandbox.\n\
         Newly installed apps are not put in the sandbox.\n"
    );
}

/// Refuses to put an app in while `refusing` is set, as when the app ops
/// helper does not answer, and counts the tries.
struct Refusing {
    refusing: crate::test_sync::Rc<crate::test_sync::Cell<bool>>,
    tries: crate::test_sync::Rc<crate::test_sync::Cell<u32>>,
}

impl crate::sandbox::Effect for Refusing {
    fn name(&self) -> &'static str {
        "app ops"
    }

    fn enable(
        &mut self,
        _: &mut crate::sandbox::Entry,
        packages: &[String],
        _: &crate::sandbox::items::Settings,
    ) -> anyhow::Result<()> {
        self.tries.set(self.tries.get() + 1);
        if self.refusing.get() {
            return Err(Transient(anyhow::anyhow!("{} was refused", packages[0])).into());
        }
        Ok(())
    }

    fn disable(
        &mut self,
        _: &mut crate::sandbox::Entry,
        _: &[String],
        _: &crate::sandbox::items::Settings,
    ) -> anyhow::Result<()> {
        Ok(())
    }

    fn update(
        &mut self,
        _: &mut crate::sandbox::Entry,
        _: &[String],
        _: &crate::sandbox::items::Settings,
        _: &crate::sandbox::items::Settings,
    ) -> anyhow::Result<()> {
        Ok(())
    }
}

#[test]
fn an_app_that_cannot_be_put_in_is_tried_again_later_and_reported() {
    let mut f = Fixture::new("auto-retry");
    let refusing = crate::test_sync::Rc::new(crate::test_sync::Cell::new(true));
    let tries = crate::test_sync::Rc::new(crate::test_sync::Cell::new(0));
    let effect = Refusing {
        refusing: refusing.clone(),
        tries: tries.clone(),
    };
    let mut sandbox = f.sandbox_with(vec![Box::new(effect)]);
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");

    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());
    assert_eq!(tries.get(), 1);
    assert_eq!(sandbox.status().new_apps_pending, 1);
    // Not tried again at every look: each try can wait for the helper.
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(tries.get(), 1);
    assert_eq!(sandbox.status().new_apps_pending, 1);
    assert!(sandbox.auto_admission().holds(10230));

    // Its time come, it is tried again.
    refusing.set(false);
    let retry = sandbox.auto.retries.get_mut(&10230).unwrap();
    assert_eq!(RETRY_BACKOFF.delay(retry.failures), Duration::from_secs(10));
    retry.at = Instant::now();
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(tries.get(), 2);
    assert_eq!(sandboxed(&sandbox), [10230]);
    assert_eq!(sandbox.status().new_apps_pending, 0);
    assert!(sandbox.auto.retries.is_empty());
    // Later tries wait longer, up to a bound.
    let first = Retry::after(None);
    let second = Retry::after(Some(first));
    assert_eq!(
        RETRY_BACKOFF.delay(second.failures),
        Duration::from_secs(20)
    );
    let mut last = first;
    for _ in 0..20 {
        last = Retry::after(Some(last));
    }
    assert_eq!(RETRY_BACKOFF.delay(last.failures), Duration::from_secs(300));
}

#[test]
fn persistent_enable_failure_releases_starts_until_a_recovery_event() {
    let mut f = Fixture::new("auto-release");
    let refusing = crate::test_sync::Rc::new(crate::test_sync::Cell::new(true));
    let tries = crate::test_sync::Rc::new(crate::test_sync::Cell::new(0));
    let mut sandbox = f.sandbox_with(vec![Box::new(Refusing {
        refusing: refusing.clone(),
        tries: tries.clone(),
    })]);
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");

    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandbox.auto_admission().holds(10230));
    sandbox.auto.retries.get_mut(&10230).unwrap().at = Instant::now();
    sandbox.isolate_new(&f.writer, &mut f.packages);

    assert_eq!(tries.get(), 2);
    assert!(!sandbox.auto_admission().holds(10230));
    assert!(sandbox.auto_admission().holds(10231));
    assert!(sandbox.auto_admission().holds(1_010_230));
    assert_eq!(sandbox.status().new_apps_failed, 1);
    assert_eq!(sandbox.status().new_apps_pending, 0);
    assert_eq!(sandbox.auto_next_look(), None);

    sandbox.auto_started(&f.writer, &mut f.packages, BTreeSet::from([10230]));
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(tries.get(), 2);

    let mut restarted = f.sandbox();
    restarted.resume_auto(None, &f.writer, &mut f.packages);
    assert!(!restarted.auto_admission().holds(10230));
    assert_eq!(restarted.status().new_apps_failed, 1);
    assert_eq!(restarted.auto_next_look(), None);

    sandbox.auto_helper_restarted(&f.writer, &mut f.packages);
    assert_eq!(tries.get(), 3);
    assert!(!sandbox.auto_admission().holds(10230));
    assert_eq!(sandbox.auto_next_look(), None);
    refusing.set(false);
    sandbox.auto_packages_changed(&f.writer, &mut f.packages);
    assert_eq!(tries.get(), 3);
    f.list(&[&NEW.replace("none 0 1 1", "none 0 2 1")]);
    sandbox.auto_packages_changed(&f.writer, &mut f.packages);
    assert_eq!(tries.get(), 4);
    assert_eq!(sandboxed(&sandbox), [10230]);
    assert_eq!(sandbox.status().new_apps_failed, 0);
}

#[test]
fn an_unsupported_restriction_releases_starts_without_a_retry_timer() {
    use crate::sandbox_item::{Item, Mode};

    let mut f = Fixture::new("auto-unsupported");
    let mut sandbox = f.sandbox_with(vec![Box::new(crate::sandbox::network::Unavailable::new())]);
    sandbox
        .set_global(
            &f.writer,
            &mut f.packages,
            &BTreeMap::from([(Item::Network, Mode::On)]),
        )
        .unwrap();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");

    sandbox.isolate_new(&f.writer, &mut f.packages);

    assert!(!sandbox.auto_admission().holds(10230));
    assert_eq!(sandbox.status().new_apps_failed, 1);
    assert_eq!(sandbox.auto_next_look(), None);

    sandbox
        .set_global(
            &f.writer,
            &mut f.packages,
            &BTreeMap::from([(Item::Network, Mode::Off)]),
        )
        .unwrap();
    assert!(!sandbox.auto_admission().holds(10230));
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10230]);
    assert_eq!(sandbox.status().new_apps_failed, 0);
}

#[test]
fn all_global_items_off_leave_new_starts_alone() {
    use crate::sandbox_item::{Item, Mode};

    let mut f = Fixture::new("auto-all-off");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    assert!(sandbox.auto_admission().holds(10230));
    let off = Item::ALL
        .into_iter()
        .map(|item| (item, Mode::Off))
        .collect();
    sandbox
        .set_global(&f.writer, &mut f.packages, &off)
        .unwrap();

    assert!(!sandbox.auto_admission().holds(10230));
    f.list(&[NEW]);
    f.install(0, "com.example.new");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10230]);
    assert!(!sandbox.auto_admission().holds(10231));

    sandbox
        .set_global(
            &f.writer,
            &mut f.packages,
            &BTreeMap::from([(Item::Camera, Mode::On)]),
        )
        .unwrap();
    assert!(sandbox.auto_admission().holds(10231));
    assert!(!sandbox.auto_admission().holds(10230));
}

#[test]
fn a_started_app_with_no_profile_directory_is_released_until_it_appears() {
    let mut f = Fixture::new("auto-no-profile");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");

    sandbox.auto_started(&f.writer, &mut f.packages, BTreeSet::from([1_010_230]));

    assert_eq!(sandboxed(&sandbox), [10230]);
    assert!(!sandbox.auto_admission().holds(1_010_230));
    assert_eq!(sandbox.status().new_apps_failed, 1);
    assert_eq!(sandbox.auto_next_look(), None);

    f.install(10, "com.example.new");
    sandbox.auto_packages_changed(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10230, 1_010_230]);
    assert_eq!(sandbox.status().new_apps_failed, 0);
}

#[test]
fn a_deferred_attempt_releases_starts_at_the_deadline() {
    let mut f = Fixture::new("auto-hold-timeout");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");
    sandbox.auto.look_wait = Duration::ZERO;

    sandbox.isolate_new(&f.writer, &mut f.packages);
    sandbox.auto.retries.get_mut(&10230).unwrap().since = Instant::now() - HOLD_LIMIT;
    sandbox.isolate_new(&f.writer, &mut f.packages);

    assert!(!sandbox.auto_admission().holds(10230));
    assert_eq!(sandbox.status().new_apps_failed, 1);
    assert_eq!(sandbox.auto_next_look(), None);
}

#[test]
fn a_look_puts_apps_in_only_for_so_long() {
    let mut f = Fixture::new("auto-bound");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");
    f.install(10, "com.example.new");
    assert_eq!(sandbox.auto_next_look(), None);
    // A look that has taken its time already leaves the rest, for a
    // look soon after.
    sandbox.auto.look_wait = Duration::ZERO;
    let looked = Instant::now();
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());
    assert_eq!(sandbox.status().new_apps_pending, 2);
    let next = sandbox.auto_next_look().unwrap();
    assert!(next >= looked + LOOK_AGAIN && next <= Instant::now() + LOOK_AGAIN);
    // Not before.
    sandbox.auto.look_wait = DISABLE_ALL_WAIT;
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());
    // Then.
    for retry in sandbox.auto.retries.values_mut() {
        retry.at = Instant::now();
    }
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10230, 1_010_230]);
    assert_eq!(sandbox.status().new_apps_pending, 0);
    assert_eq!(sandbox.auto_next_look(), None);
}

/// The package manager as the helper reaches it: the packages it keeps
/// flagged as libraries, and the questions it was asked.
struct PackageManager {
    libraries: Vec<&'static str>,
    /// None of its answers arrive.
    silent: crate::test_sync::Rc<crate::test_sync::Cell<bool>>,
    asked: crate::test_sync::Rc<crate::test_sync::RefCell<Vec<String>>>,
    /// By when each question was to be answered.
    deadlines: crate::test_sync::Rc<crate::test_sync::RefCell<Vec<Instant>>>,
}

impl Libraries for PackageManager {
    fn is_library(&self, _: u32, package: &str, until: Instant) -> Option<bool> {
        self.asked.borrow_mut().push(package.to_string());
        self.deadlines.borrow_mut().push(until);
        (!self.silent.get()).then(|| self.libraries.contains(&package))
    }
}

fn package_manager(
    libraries: Vec<&'static str>,
    silent: bool,
) -> (
    Box<PackageManager>,
    crate::test_sync::Rc<crate::test_sync::RefCell<Vec<String>>>,
) {
    let asked = crate::test_sync::Rc::new(crate::test_sync::RefCell::new(Vec::new()));
    let manager = PackageManager {
        libraries,
        silent: crate::test_sync::Rc::new(crate::test_sync::Cell::new(silent)),
        asked: asked.clone(),
        deadlines: crate::test_sync::Rc::default(),
    };
    (Box::new(manager), asked)
}

/// A version of Chrome's library, as Play installs it with an update of
/// Chrome: a package of its own, with an app id and a data directory.
const LIBRARY: &str = "com.google.android.trichromelibrary_700000000";

fn library_line(package: &str, app_id: u32) -> String {
    format!(
        "{package} {app_id} 0 /d default:targetSdkVersion=34 none 0 700000000 1 com.android.vending"
    )
}

#[test]
fn a_library_is_left_alone_and_its_app_id_is_not() {
    let mut f = Fixture::new("auto-library");
    let mut sandbox = f.sandbox();
    let (manager, asked) = package_manager(vec![LIBRARY], false);
    sandbox.tell_libraries_with(manager);
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[&library_line(LIBRARY, 10240)]);
    f.install(0, LIBRARY);
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());
    assert_eq!(sandbox.status().new_apps_pending, 0);
    // That package alone is known, as a library, and its app id is not.
    let (_, known) = f.writer.sandbox_auto().unwrap();
    let rows: Vec<_> = known
        .iter()
        .filter(|row| row.app_id == 10240)
        .map(|row| (row.app_id, row.kind))
        .collect();
    assert_eq!(rows, [(10240, KnownKind::Library)]);
    assert_eq!(
        sandbox.auto.known.words(),
        Known::default().words(),
        "the kernel side would let an app of the app id start"
    );
    // Asked once, also by a daemon started again.
    sandbox.isolate_new(&f.writer, &mut f.packages);
    let mut restarted = f.sandbox();
    let (again, asked_again) = package_manager(vec![LIBRARY], false);
    restarted.tell_libraries_with(again);
    restarted.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(*asked.borrow(), [LIBRARY]);
    assert!(asked_again.borrow().is_empty());

    // Uninstalled, and its app id given to an app: that app is new.
    f.list(&[NEW.replace("10230", "10240").as_str()]);
    f.uninstall(0, LIBRARY);
    f.install(0, "com.example.new");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10240]);
}

#[test]
fn an_app_is_told_from_a_library_by_the_package_manager_alone() {
    let mut f = Fixture::new("auto-library-name");
    let mut sandbox = f.sandbox();
    // Named as a library is, and with a library's version in its line:
    // the package manager does not have it as one.
    let lookalike = "com.google.android.trichromelibrary_700000001";
    let (manager, asked) = package_manager(vec![LIBRARY], false);
    sandbox.tell_libraries_with(manager);
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[&library_line(lookalike, 10241)]);
    f.install(0, lookalike);
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10241]);
    assert_eq!(*asked.borrow(), [lookalike]);
}

#[test]
fn a_package_not_told_about_in_time_is_asked_about_again_later() {
    let mut f = Fixture::new("auto-library-silent");
    let mut sandbox = f.sandbox();
    let (manager, asked) = package_manager(vec![LIBRARY], true);
    let silent = manager.silent.clone();
    sandbox.tell_libraries_with(manager);
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[&library_line(LIBRARY, 10240)]);
    f.install(0, LIBRARY);
    // As at boot, while the helper waits for the system: neither a
    // library nor an app yet.
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());
    assert_eq!(sandbox.status().new_apps_pending, 1);
    assert!(sandbox.auto.known.libraries.is_empty());
    // Asked again when its time comes.
    silent.set(false);
    sandbox.auto.retries.get_mut(&10240).unwrap().at = Instant::now();
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());
    assert_eq!(sandbox.status().new_apps_pending, 0);
    assert_eq!(asked.borrow().len(), 2);
}

#[test]
fn an_app_id_stays_known_while_any_of_its_packages_is_installed() {
    let mut f = Fixture::new("auto-shared");
    let shared = [
        "com.shared.one 10220 0 /d default none 0 1 1 @null",
        "com.shared.two 10220 0 /d default none 0 1 1 @null",
    ];
    f.list(&shared);
    f.install(0, "com.shared.one");
    f.install(0, "com.shared.two");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    // One of the pair uninstalled: the other is still there.
    f.list(&shared[1..]);
    f.uninstall(0, "com.shared.one");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());

    // The same for a pair installed since, taken out by the user.
    let pair = [
        "com.pair.one 10230 0 /d default none 0 1 1 @null",
        "com.pair.two 10230 0 /d default none 0 1 1 @null",
    ];
    f.list(&[shared[1], pair[0], pair[1]]);
    f.install(0, "com.pair.one");
    f.install(0, "com.pair.two");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10230]);
    sandbox
        .disable(&f.writer, &mut f.packages, "com.pair.one", 0)
        .unwrap();
    f.list(&[shared[1], pair[1]]);
    f.uninstall(0, "com.pair.one");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());
}

#[test]
fn an_app_given_the_uid_of_one_in_the_sandbox_gets_an_entry_of_its_own() {
    let mut f = Fixture::new("auto-reused");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    let camera_off = BTreeMap::from([(
        crate::sandbox_item::Item::Camera,
        Some(crate::sandbox_item::Mode::Off),
    )]);
    sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.example.new",
            0,
            &camera_off,
        )
        .unwrap();
    let first = f.writer.sandbox_entry(10230).unwrap().unwrap();
    // Uninstalled, and its uid given to another app, before a look.
    f.list(&["com.example.other 10230 0 /d default none 0 1 1 @null"]);
    f.uninstall(0, "com.example.new");
    f.install(0, "com.example.other");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10230]);
    let other = f.writer.sandbox_entry(10230).unwrap().unwrap();
    assert_eq!(other.package, "com.example.other");
    assert_eq!(other.settings, "");
    assert_ne!(other.android_id, first.android_id);
}

#[test]
fn an_app_of_two_packages_stays_in_while_either_is_installed() {
    let mut f = Fixture::new("auto-pair-stays");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    let pair = [
        "com.pair.one 10230 0 /d default none 0 1 1 @null",
        "com.pair.two 10230 0 /d default none 0 1 1 @null",
    ];
    f.list(&pair);
    f.install(0, "com.pair.one");
    f.install(0, "com.pair.two");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10230]);
    let entry = f.writer.sandbox_entry(10230).unwrap().unwrap();
    assert_eq!(entry.package, "com.pair.one");

    // The one it is named after is uninstalled: it stays in, named
    // after the other, with what it had.
    f.list(&pair[1..]);
    f.uninstall(0, "com.pair.one");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10230]);
    let kept = f.writer.sandbox_entry(10230).unwrap().unwrap();
    assert_eq!(kept.package, "com.pair.two");
    assert_eq!(kept.android_id, entry.android_id);
    assert!(kept.enabled);
    // A daemon started again knows it so.
    let mut restarted = f.sandbox();
    restarted.resume(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&restarted), [10230]);
}

#[test]
fn an_app_given_a_uid_still_held_for_another_is_put_in_once_that_is_out() {
    let mut f = Fixture::new("auto-release-fails");
    let conn = rusqlite::Connection::open(f.dir.join("bb.db")).unwrap();
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    let first = f.writer.sandbox_entry(10230).unwrap().unwrap();

    // Uninstalled, its uid given to another app, and it cannot be taken
    // out: the other is not in the sandbox under its entry.
    conn.execute_batch(
        "CREATE TRIGGER refuse_retirement BEFORE UPDATE OF enabled ON sandbox
         WHEN NEW.enabled = 0
         BEGIN SELECT RAISE(ABORT, 'refuse retirement'); END;",
    )
    .unwrap();
    f.list(&["com.example.other 10230 0 /d default none 0 1 1 @null"]);
    f.uninstall(0, "com.example.new");
    f.install(0, "com.example.other");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    let (_, known) = f.writer.sandbox_auto().unwrap();
    assert!(known.iter().all(|row| row.app_id != 10230));
    assert_eq!(sandbox.status().new_apps_pending, 1);
    assert_eq!(
        sandbox.snapshot_read()[&10230].package,
        "com.example.new",
        "held for the one before, as it was"
    );

    // Taken out at last: the other is put in, with an entry of its own.
    conn.execute_batch("DROP TRIGGER refuse_retirement")
        .unwrap();
    sandbox.auto.retries.get_mut(&10230).unwrap().at = Instant::now();
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10230]);
    let other = f.writer.sandbox_entry(10230).unwrap().unwrap();
    assert_eq!(other.package, "com.example.other");
    assert_ne!(other.android_id, first.android_id);
}

#[test]
fn an_app_installed_again_under_the_same_uid_keeps_its_entry() {
    let mut f = Fixture::new("auto-reinstall-uid");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    let old = "com.example.old 10200 0 /d default none 0 1 1 com.android.vending";
    f.list(&[old, NEW]);
    f.install(0, "com.example.new");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    let camera_off = BTreeMap::from([(
        crate::sandbox_item::Item::Camera,
        Some(crate::sandbox_item::Mode::Off),
    )]);
    sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.example.new",
            0,
            &camera_off,
        )
        .unwrap();
    let first = f.writer.sandbox_entry(10230).unwrap().unwrap();
    // Uninstalled, and installed again under the same uid, which
    // Android may give it after a restart: new, and put in again with
    // the settings and identity it had, as Android keeps its own
    // ANDROID_ID for it.
    f.list(&[old]);
    f.uninstall(0, "com.example.new");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());
    f.list(&[old, NEW]);
    f.install(0, "com.example.new");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10230]);
    let again = f.writer.sandbox_entry(10230).unwrap().unwrap();
    assert_eq!(
        (again.settings.as_str(), again.android_id.as_str()),
        (first.settings.as_str(), first.android_id.as_str())
    );
}

#[test]
fn a_daemon_starting_asks_the_helper_nothing() {
    let mut f = Fixture::new("auto-resume");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    // Installed while no daemon ran, with a system app a system
    // update brought.
    f.list(&[
        NEW,
        "com.android.newer 10240 0 /d platform:partition=system none 0 1 1 @system",
    ]);
    f.install(0, "com.example.new");
    let mut started = f.sandbox();
    let (manager, asked) = package_manager(vec![], false);
    started.tell_libraries_with(manager);
    started.resume_auto(None, &f.writer, &mut f.packages);
    assert!(asked.borrow().is_empty());
    assert!(sandboxed(&started).is_empty());
    // The system's app is known before starts are held; the new one is
    // left for the main loop's first look, soon.
    assert!(started.auto.known.apps.contains_key(&10240));
    assert_eq!(started.status().new_apps_pending, 1);
    assert!(started.auto_next_look().unwrap() <= Instant::now() + LOOK_AGAIN);
    started.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&started), [10230]);
    assert_eq!(*asked.borrow(), ["com.example.new"]);
}

#[test]
fn questions_are_given_no_more_time_than_the_look_has() {
    let mut f = Fixture::new("auto-question-time");
    let mut sandbox = f.sandbox();
    let (manager, _) = package_manager(vec![], false);
    let deadlines = manager.deadlines.clone();
    sandbox.tell_libraries_with(manager);
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");
    let looked = Instant::now();
    sandbox.isolate_new(&f.writer, &mut f.packages);
    let deadlines = deadlines.borrow();
    assert_eq!(deadlines.len(), 1);
    assert!(deadlines[0] <= looked + sandbox.auto.look_wait + Duration::from_millis(50));
}

#[test]
fn a_retry_that_cannot_be_made_is_put_off() {
    let mut f = Fixture::new("auto-put-off");
    let refusing = crate::test_sync::Rc::new(crate::test_sync::Cell::new(true));
    let tries = crate::test_sync::Rc::new(crate::test_sync::Cell::new(0));
    let effect = Refusing {
        refusing: refusing.clone(),
        tries,
    };
    let mut sandbox = f.sandbox_with(vec![Box::new(effect)]);
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");
    sandbox.isolate_new(&f.writer, &mut f.packages);
    // Due, and the package list cannot be read: the next look is not
    // due at once, again and again.
    sandbox.auto.retries.get_mut(&10230).unwrap().at = Instant::now();
    let mut absent = Packages::new(f.dir.join("absent.list").to_str().unwrap());
    sandbox.isolate_new(&f.writer, &mut absent);
    assert!(sandbox.auto_next_look().unwrap() >= Instant::now() + Duration::from_secs(5));
    // Nothing left: no look is due.
    refusing.set(false);
    sandbox.auto.retries.get_mut(&10230).unwrap().at = Instant::now();
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandbox.auto_next_look(), None);
    // Nor while it is off.
    sandbox.set_auto(&f.writer, &mut f.packages, false).unwrap();
    assert_eq!(sandbox.auto_next_look(), None);
}

#[test]
fn an_install_event_is_retried_when_the_package_list_cannot_be_read() {
    let mut f = Fixture::new("auto-list-unavailable");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    std::fs::remove_file(f.dir.join("packages.list")).unwrap();

    sandbox.isolate_new(&f.writer, &mut f.packages);

    let retry = sandbox.auto_next_look().unwrap();
    assert!(retry > Instant::now());
    assert!(sandboxed(&sandbox).is_empty());
}

#[test]
fn a_known_app_is_written_again_after_a_failed_write() {
    let mut f = Fixture::new("auto-known-write");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");
    let conn = rusqlite::Connection::open(f.dir.join("bb.db")).unwrap();
    conn.execute_batch(
        "CREATE TRIGGER refuse_known BEFORE INSERT ON sandbox_known
         BEGIN SELECT RAISE(ABORT, 'refuse known app'); END;",
    )
    .unwrap();

    sandbox.isolate_new(&f.writer, &mut f.packages);

    assert_eq!(sandboxed(&sandbox), [10230]);
    assert!(
        f.writer
            .sandbox_auto()
            .unwrap()
            .1
            .iter()
            .all(|row| row.app_id != 10230)
    );
    conn.execute_batch("DROP TRIGGER refuse_known").unwrap();
    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(
        f.writer
            .sandbox_auto()
            .unwrap()
            .1
            .iter()
            .any(|row| row.app_id == 10230)
    );
}

#[test]
fn a_package_that_moves_during_classification_is_left_for_the_next_scan() {
    struct Moved(PathBuf);

    impl Libraries for Moved {
        fn is_library(&self, _: u32, _: &str, _: Instant) -> Option<bool> {
            std::fs::write(&self.0, NEW.replace("10230", "10231")).unwrap();
            Some(false)
        }
    }

    let mut f = Fixture::new("auto-moved-during-scan");
    let mut sandbox = f.sandbox();
    sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
    f.list(&[NEW]);
    f.install(0, "com.example.new");
    sandbox.tell_libraries_with(Box::new(Moved(f.dir.join("packages.list"))));

    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert!(sandboxed(&sandbox).is_empty());

    sandbox.isolate_new(&f.writer, &mut f.packages);
    assert_eq!(sandboxed(&sandbox), [10231]);
}
