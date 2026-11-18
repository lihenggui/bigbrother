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

//! Putting newly installed apps in the sandbox.
//!
//! While it is on (`bbd sandbox auto on`), every app installed afterwards is
//! put in the sandbox with the global settings, as `bbd sandbox enable`
//! would. Apps installed before it was turned on, and those that came with
//! the system, are never put in by it, and an app the user takes out stays
//! out.
//!
//! What is new is told by what is known ([`Known`]):
//!
//! - Turning it on takes the app id of every package installed then as
//!   known, for every Android user: such an app is left alone wherever it is
//!   installed, then or later. So are app ids that come with the system
//!   later (a system update brings new ones), and the app's own.
//! - Any other app id is an app installed since. It is put in the sandbox
//!   for each user it is installed for, and each of those uids is known from
//!   then on: it is dealt with once.
//! - An update keeps the app id. A package installed again after it was
//!   removed gets a new one, and is new. What is uninstalled is forgotten, so
//!   that an app id or uid Android gives to another app later is new.
//!
//! Android writes its package list (`/data/system/packages.list`) again on
//! every install, update and removal. While this is on, the daemon watches
//! for that ([`Watch`]) and looks at the list each time; while it is off,
//! nothing is watched.

use std::{
    collections::BTreeMap,
    ffi::CString,
    io,
    os::{
        fd::{AsRawFd, FromRawFd, OwnedFd},
        unix::ffi::OsStrExt as _,
    },
    path::Path,
    time::{Duration, Instant},
};

use serde::{Deserialize, Serialize};
use tokio::io::{Interest, unix::AsyncFd};

use super::{FIRST_APPLICATION_UID, LAST_USER, OWN_PACKAGES, Sandbox};
use crate::{
    packages::{PER_USER_RANGE, Packages},
    store::{KnownApp, Writer},
};

/// The directory of Android's package list, which is replaced by a new file
/// on every change.
pub const PACKAGE_DIR: &str = "/data/system";

/// The package list's name in [`PACKAGE_DIR`].
const PACKAGE_LIST: &[u8] = b"packages.list";

/// App ids of installed apps end below this (Android's
/// `LAST_APPLICATION_UID` is 19999). Above are the SDK sandboxes of apps and
/// isolated processes, which are not packages.
const APP_ID_END: u32 = 20_000;

/// Least time between two log lines about apps that could not be put in.
/// One that cannot be is tried again with every change of the package list.
const FAILURE_LOG_INTERVAL: Duration = Duration::from_secs(60);

/// The answer to `sandbox.auto` and `sandbox.set_auto`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Switch {
    /// Whether apps installed from now on are put in the sandbox.
    pub auto: bool,
}

/// Whether newly installed apps are put in the sandbox, and what is known
/// of the installed ones.
pub struct Auto {
    on: bool,
    known: Known,
    failures: FailureLog,
}

impl Auto {
    /// As last written.
    pub fn load(writer: &Writer) -> anyhow::Result<Self> {
        let (on, rows) = writer.sandbox_auto()?;
        Ok(Self {
            on,
            known: Known::from_rows(rows),
            failures: FailureLog::default(),
        })
    }
}

/// What is known of the installed apps: an app that is not is new.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct Known {
    /// App ids left alone for every user, each with one of its packages:
    /// those installed when this was turned on, the system's, the app's own.
    apps: BTreeMap<u32, String>,
    /// Uids of apps installed since that were dealt with, each with the
    /// package it was dealt with as.
    uids: BTreeMap<u32, String>,
}

impl Known {
    /// Every app in `packages`.
    fn installed(packages: &Packages) -> Self {
        Self {
            apps: packages
                .app_ids()
                .filter(|(app_id, _)| is_app(*app_id))
                .filter_map(|(app_id, names)| Some((app_id, names.first()?.clone())))
                .collect(),
            uids: BTreeMap::new(),
        }
    }

    fn from_rows(rows: Vec<KnownApp>) -> Self {
        let mut known = Self::default();
        for row in rows {
            match row.user {
                None => known.apps.insert(row.app_id, row.package),
                Some(user) => known
                    .uids
                    .insert(user * PER_USER_RANGE + row.app_id, row.package),
            };
        }
        known
    }

    fn rows(&self) -> Vec<KnownApp> {
        let apps = self.apps.iter().map(|(app_id, package)| KnownApp {
            app_id: *app_id,
            user: None,
            package: package.clone(),
        });
        let uids = self.uids.iter().map(|(uid, package)| KnownApp {
            app_id: uid % PER_USER_RANGE,
            user: Some(uid / PER_USER_RANGE),
            package: package.clone(),
        });
        apps.chain(uids).collect()
    }
}

/// Whether `app_id` can be an installed app's.
fn is_app(app_id: u32) -> bool {
    (FIRST_APPLICATION_UID..APP_ID_END).contains(&app_id)
}

/// What a look at the package list finds.
#[derive(Debug, Default, PartialEq, Eq)]
struct Plan {
    /// Apps installed since and not dealt with, to put in the sandbox: the
    /// uid, and the package to name.
    isolate: Vec<(u32, String)>,
    /// What is known from now on, those apps aside.
    known: Known,
}

/// What is new in `packages` against `known`, for `users`; `installed` says
/// whether a package is installed for a user.
fn plan(
    known: &Known,
    packages: &Packages,
    users: &[u32],
    installed: impl Fn(u32, &str) -> bool,
) -> Plan {
    // What is no longer installed is forgotten. An app id that now has
    // other packages was given to another app.
    let mut next = Known {
        apps: known
            .apps
            .iter()
            .filter(|(app_id, package)| packages.packages_of(**app_id).contains(package))
            .map(|(app_id, package)| (*app_id, package.clone()))
            .collect(),
        uids: known
            .uids
            .iter()
            .filter(|(uid, package)| {
                packages
                    .packages_of(*uid % PER_USER_RANGE)
                    .contains(package)
                    && installed(*uid / PER_USER_RANGE, package)
            })
            .map(|(uid, package)| (*uid, package.clone()))
            .collect(),
    };

    let mut app_ids: Vec<(u32, &[String])> = packages
        .app_ids()
        .filter(|(app_id, _)| is_app(*app_id) && !next.apps.contains_key(app_id))
        .collect();
    app_ids.sort_unstable();
    let mut isolate = Vec::new();
    for (app_id, names) in app_ids {
        let Some(first) = names.first() else {
            continue;
        };
        if packages.is_system(app_id) || names.iter().any(|n| OWN_PACKAGES.contains(&n.as_str())) {
            next.apps.insert(app_id, first.clone());
            continue;
        }
        for &user in users {
            let uid = user * PER_USER_RANGE + app_id;
            if next.uids.contains_key(&uid) {
                continue;
            }
            if let Some(package) = names.iter().find(|name| installed(user, name)) {
                isolate.push((uid, package.clone()));
            }
        }
    }
    // An app id the system took over (an app the user installed that a
    // system update then brought) needs no uids of its own.
    next.uids
        .retain(|uid, _| !next.apps.contains_key(&(uid % PER_USER_RANGE)));
    Plan {
        isolate,
        known: next,
    }
}

impl Sandbox {
    /// Whether newly installed apps are put in the sandbox.
    pub fn auto_on(&self) -> bool {
        self.auto.on
    }

    pub(super) fn auto_switch(&self) -> Switch {
        Switch { auto: self.auto.on }
    }

    /// Turn the putting of newly installed apps in the sandbox on or off.
    /// Turning it on takes every app installed now as known: none of them
    /// is put in.
    pub(super) fn set_auto(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        on: bool,
    ) -> anyhow::Result<Switch> {
        if on == self.auto.on {
            return Ok(self.auto_switch());
        }
        let known = if on {
            packages.refresh();
            anyhow::ensure!(!packages.is_empty(), "the package list cannot be read");
            Known::installed(packages)
        } else {
            Known::default()
        };
        writer.put_sandbox_auto(on, &known.rows())?;
        self.auto.on = on;
        self.auto.known = known;
        eprintln!(
            "bbd: sandbox: newly installed apps are {}put in the sandbox",
            if on { "" } else { "no longer " }
        );
        Ok(self.auto_switch())
    }

    /// Put the apps installed since this was turned on, and not dealt with
    /// yet, in the sandbox, and forget those uninstalled. Nothing is done
    /// while it is off.
    pub fn isolate_new(&mut self, writer: &Writer, packages: &mut Packages) {
        if !self.auto.on {
            return;
        }
        packages.refresh();
        let users = self.users();
        // Without the package list, or the users, nothing can be told, and
        // nothing known is forgotten.
        if packages.is_empty() || users.is_empty() {
            return;
        }
        let plan = plan(&self.auto.known, packages, &users, |user, package| {
            self.installed_for(user, package)
        });
        let mut known = plan.known;
        let mut isolated = Vec::new();
        for (uid, package) in plan.isolate {
            let user = uid / PER_USER_RANGE;
            match self.enable(writer, packages, &package, user, crate::now()) {
                Ok(enabled) => {
                    isolated.push(enabled.uid);
                    known.uids.insert(enabled.uid, package);
                }
                Err(e) => {
                    // Package names are not logged; the error may name it.
                    let e = format!("{e:#}").replace(package.as_str(), "the app");
                    self.auto.failures.log(uid, &e);
                }
            }
        }
        if !isolated.is_empty() {
            let uids: Vec<String> = isolated.iter().map(u32::to_string).collect();
            eprintln!(
                "bbd: sandbox: newly installed, put in the sandbox: uid {}",
                uids.join(", ")
            );
        }
        if known != self.auto.known {
            // What was done stands even if it cannot be written: it is
            // known, and what the database still lacks is looked at again
            // when the daemon next starts.
            if let Err(e) = writer.put_sandbox_auto(true, &known.rows()) {
                eprintln!("bbd: sandbox: cannot write the apps known to be installed: {e:#}");
            }
            self.auto.known = known;
        }
    }

    /// The Android users, by their directories in `user_data`; user 0 alone
    /// where that is not checked.
    fn users(&self) -> Vec<u32> {
        let Some(dir) = &self.user_data else {
            return vec![0];
        };
        let mut users: Vec<u32> = std::fs::read_dir(dir)
            .into_iter()
            .flatten()
            .flatten()
            .filter_map(|entry| entry.file_name().to_str()?.parse().ok())
            .filter(|user| *user <= LAST_USER)
            .collect();
        users.sort_unstable();
        users
    }

    /// Whether `package` is installed for Android user `user`.
    fn installed_for(&self, user: u32, package: &str) -> bool {
        self.user_data
            .as_ref()
            .is_none_or(|dir| dir.join(user.to_string()).join(package).exists())
    }
}

/// Print whether newly installed apps are put in the sandbox, for a person.
pub fn print(out: &mut impl io::Write, on: bool) -> io::Result<()> {
    if on {
        writeln!(out, "Newly installed apps are put in the sandbox.")
    } else {
        writeln!(out, "Newly installed apps are not put in the sandbox.")
    }
}

/// Logs apps that could not be put in, at most one line per interval.
#[derive(Default)]
struct FailureLog {
    logged: Option<Instant>,
    unlogged: u32,
}

impl FailureLog {
    fn log(&mut self, uid: u32, e: &str) {
        if self
            .logged
            .is_some_and(|at| at.elapsed() < FAILURE_LOG_INTERVAL)
        {
            self.unlogged += 1;
            return;
        }
        let more = match self.unlogged {
            0 => String::new(),
            n => format!(" ({n} more since the last)"),
        };
        eprintln!("bbd: sandbox: uid {uid}, newly installed, is not in the sandbox: {e}{more}");
        self.logged = Some(Instant::now());
        self.unlogged = 0;
    }
}

// ---- the watch -------------------------------------------------------------

/// Bytes read from the watch at a time: room for a dozen events.
const EVENTS_SIZE: usize = 4096;

/// Watches for the package list being written, while newly installed apps
/// are put in the sandbox.
///
/// Android writes the list to a new file and renames that over the old one,
/// so it is the directory that is watched: every file renamed or written into
/// it is reported, and only the package list counts. The others there change
/// now and then, and each such change costs a read of the event.
#[derive(Default)]
pub struct Watch {
    inotify: Option<AsyncFd<OwnedFd>>,
    /// Watching failed, which was logged: it is not tried again until this
    /// is turned off and on, or the daemon starts again.
    failed: bool,
}

impl Watch {
    /// Watch `dir` while `on`, and stop when not. True when the watch has
    /// just begun: what changed before it did was not seen, and the list is
    /// to be looked at.
    pub fn arm(&mut self, on: bool, dir: &Path) -> bool {
        if !on {
            self.inotify = None;
            self.failed = false;
            return false;
        }
        if self.inotify.is_some() || self.failed {
            return false;
        }
        match watch(dir) {
            Ok(inotify) => {
                self.inotify = Some(inotify);
                true
            }
            Err(e) => {
                self.give_up(&e);
                false
            }
        }
    }

    pub fn armed(&self) -> bool {
        self.inotify.is_some()
    }

    /// Wait until the package list has been written. Never, while not
    /// armed. Every event there is is read, so that one change is reported
    /// once.
    pub async fn changed(&self) -> io::Result<()> {
        let Some(inotify) = &self.inotify else {
            return std::future::pending().await;
        };
        let mut events = [0; EVENTS_SIZE];
        let mut written = false;
        loop {
            let mut ready = inotify.readable().await?;
            loop {
                match ready.try_io(|fd| read(fd.get_ref(), &mut events)) {
                    Ok(Ok(n)) => written |= names_package_list(&events[..n]),
                    Ok(Err(e)) if e.kind() == io::ErrorKind::Interrupted => {}
                    Ok(Err(e)) => return Err(e),
                    // Nothing more to read.
                    Err(_) => break,
                }
            }
            if written {
                return Ok(());
            }
        }
    }

    /// Stop watching, after `e`.
    pub fn give_up(&mut self, e: &io::Error) {
        eprintln!(
            "bbd: sandbox: cannot watch for installed apps ({e}); they are looked for when the daemon next starts"
        );
        self.inotify = None;
        self.failed = true;
    }
}

fn watch(dir: &Path) -> io::Result<AsyncFd<OwnedFd>> {
    let fd = unsafe { libc::inotify_init1(libc::IN_NONBLOCK | libc::IN_CLOEXEC) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: just opened, and owned by nothing else.
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
    let dir = CString::new(dir.as_os_str().as_bytes())?;
    // Renamed into it, which is how Android writes the list; written in
    // place, which it may do too.
    let mask = libc::IN_MOVED_TO | libc::IN_CLOSE_WRITE;
    if unsafe { libc::inotify_add_watch(fd.as_raw_fd(), dir.as_ptr(), mask) } < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: the OwnedFd owns its fd, which stays open and unchanged for as
    // long as the AsyncFd owns the OwnedFd.
    unsafe { AsyncFd::register_with_interest(fd, Interest::READABLE) }.map_err(|e| e.into_parts().1)
}

fn read(fd: &OwnedFd, into: &mut [u8]) -> io::Result<usize> {
    let n = unsafe { libc::read(fd.as_raw_fd(), into.as_mut_ptr().cast(), into.len()) };
    usize::try_from(n).map_err(|_| io::Error::last_os_error())
}

/// Whether `events`, as read from an inotify descriptor, name the package
/// list, or say that events were lost, any of which may have.
fn names_package_list(events: &[u8]) -> bool {
    let header = size_of::<libc::inotify_event>();
    let mut rest = events;
    while rest.len() >= header {
        // SAFETY: a header's bytes, read unaligned; any bytes are one.
        let event = unsafe { rest.as_ptr().cast::<libc::inotify_event>().read_unaligned() };
        let end = rest.len().min(header + event.len as usize);
        // The name is padded with NULs.
        let name = rest[header..end].split(|&b| b == 0).next().unwrap_or(&[]);
        if event.mask & libc::IN_Q_OVERFLOW != 0 || name == PACKAGE_LIST {
            return true;
        }
        rest = &rest[end..];
    }
    false
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::*;

    struct Fixture {
        dir: PathBuf,
        writer: Writer,
        packages: Packages,
    }

    impl Fixture {
        fn new(name: &str) -> Self {
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
        fn list(&mut self, lines: &[&str]) {
            std::fs::write(self.dir.join("packages.list"), lines.join("\n")).unwrap();
            self.packages.refresh();
        }

        fn install(&self, user: u32, package: &str) {
            std::fs::create_dir_all(self.user_data().join(user.to_string()).join(package)).unwrap();
        }

        fn uninstall(&self, user: u32, package: &str) {
            std::fs::remove_dir(self.user_data().join(user.to_string()).join(package)).unwrap();
        }

        fn user_data(&self) -> PathBuf {
            self.dir.join("user_de")
        }

        fn sandbox(&self) -> Sandbox {
            Sandbox::load(&self.writer, Vec::new(), Some(&self.user_data())).unwrap()
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    fn sandboxed(sandbox: &Sandbox) -> Vec<u32> {
        let mut uids: Vec<u32> = sandbox.snapshot_read().keys().copied().collect();
        uids.sort();
        uids
    }

    const NEW: &str =
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
    fn what_is_new_is_worked_out_per_app_id_and_user() {
        let mut f = Fixture::new("auto-plan");
        f.list(&[
            "com.android.chrome 10150 0 /d default:partition=product none 0 1 1 @system",
            "com.shared.one 10220 0 /d default none 0 1 1 @null",
            "com.shared.two 10220 0 /d default none 0 1 1 @null",
            "com.example.gone 10221 0 /d default none 0 1 1 @null",
            "com.android.phone 1001 0 /d platform:partition=system none 0 1 1 @system",
        ]);
        let known = Known {
            apps: BTreeMap::from([(10150, "com.android.chrome".into())]),
            uids: BTreeMap::from([
                (10221, "com.example.gone".into()),
                (1_010_221, "com.example.gone".into()),
            ]),
        };
        // User 10 has removed it, and has only the second of the pair.
        let installed = |user: u32, package: &str| {
            !(user == 10 && matches!(package, "com.example.gone" | "com.shared.one"))
        };
        let plan = plan(&known, &f.packages, &[0, 10], installed);
        assert_eq!(
            plan.isolate,
            [
                (10220, "com.shared.one".to_string()),
                (1_010_220, "com.shared.two".to_string())
            ]
        );
        assert_eq!(
            plan.known,
            Known {
                apps: known.apps.clone(),
                uids: BTreeMap::from([(10221, "com.example.gone".into())]),
            }
        );
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

    fn event(mask: u32, name: &str) -> Vec<u8> {
        // Names are padded to a multiple of the header's alignment.
        let len = (name.len() + 1).next_multiple_of(4);
        let header = libc::inotify_event {
            wd: 1,
            mask,
            cookie: 0,
            len: len as u32,
        };
        let mut bytes = unsafe {
            std::slice::from_raw_parts(
                (&raw const header).cast::<u8>(),
                size_of::<libc::inotify_event>(),
            )
        }
        .to_vec();
        bytes.extend(name.bytes());
        bytes.resize(bytes.len() + len - name.len(), 0);
        bytes
    }

    #[test]
    fn only_events_naming_the_package_list_count() {
        let tmp = event(libc::IN_CLOSE_WRITE, "packages.list.tmp");
        let other = event(libc::IN_MOVED_TO, "appops.xml");
        let list = event(libc::IN_MOVED_TO, "packages.list");
        assert!(!names_package_list(&[tmp.clone(), other.clone()].concat()));
        assert!(names_package_list(&[tmp, other, list].concat()));
        // Events were lost: any of them may have been the list.
        assert!(names_package_list(&event(libc::IN_Q_OVERFLOW, "")));
        assert!(!names_package_list(&[]));
        assert!(!names_package_list(&[0; 5]));
    }

    #[tokio::test]
    async fn the_list_replaced_is_seen_and_other_files_are_not() {
        let f = Fixture::new("auto-watch");
        let dir = f.dir.join("system");
        std::fs::create_dir_all(&dir).unwrap();
        let mut watch = Watch::default();
        assert!(!watch.arm(false, &dir));
        assert!(watch.arm(true, &dir));
        assert!(!watch.arm(true, &dir));
        assert!(watch.armed());

        let soon = Duration::from_millis(200);
        std::fs::write(dir.join("appops.xml"), "x").unwrap();
        assert!(tokio::time::timeout(soon, watch.changed()).await.is_err());
        // As Android writes it: a new file renamed over the old one.
        std::fs::write(dir.join("packages.list.tmp"), "x").unwrap();
        std::fs::rename(dir.join("packages.list.tmp"), dir.join("packages.list")).unwrap();
        tokio::time::timeout(soon, watch.changed())
            .await
            .unwrap()
            .unwrap();
        // Reported once.
        assert!(tokio::time::timeout(soon, watch.changed()).await.is_err());

        assert!(!watch.arm(false, &dir));
        assert!(!watch.armed());
        // A directory that cannot be watched is given up on until turned
        // off and on.
        assert!(!watch.arm(true, &f.dir.join("absent")));
        assert!(!watch.arm(true, &dir));
        watch.arm(false, &dir);
        assert!(watch.arm(true, &dir));
    }
}
