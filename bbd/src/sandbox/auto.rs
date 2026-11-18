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
//! every install, update and removal, and makes an app's data directory for
//! each user it is installed for (`/data/user_de/<user>/<package>`). While
//! this is on, the daemon watches for both ([`Watch`]) and looks at the list
//! each time; while it is off, nothing is watched.
//!
//! An app can start before the daemon has looked: an installer may open it
//! at once. So that none of its code runs outside the sandbox, the kernel
//! side of the gate is given what is known ([`Kernel`]) and stops a start of
//! any other app id or uid, as it stops a sandboxed app's. The gate thread
//! ends that start, as it ends any start it cannot give a sandbox, and tells
//! the main loop, which looks at the list and puts the app in: its next
//! start is held as a sandboxed one. While this is off, the kernel side
//! reads only the switch.

use std::{
    collections::{BTreeMap, BTreeSet, HashMap},
    ffi::CString,
    io,
    os::{
        fd::{AsRawFd, FromRawFd, OwnedFd},
        unix::ffi::OsStrExt as _,
    },
    path::{Path, PathBuf},
    time::{Duration, Instant},
};

use anyhow::Context as _;
use aya::maps::{Array, HashMap as BpfHashMap, MapData};
use bb_common::{KNOWN_APP_WORDS, known_app_bit};
use serde::{Deserialize, Serialize};
use tokio::io::{Interest, unix::AsyncFd};

use super::{DISABLE_ALL_WAIT, LAST_USER, OWN_PACKAGES, Sandbox};
use crate::{
    packages::{PER_USER_RANGE, Packages},
    store::{KnownApp, Writer},
};

/// The directory of Android's package list, which is replaced by a new file
/// on every change.
pub const PACKAGE_DIR: &str = "/data/system";

/// The package list's name in [`PACKAGE_DIR`].
const PACKAGE_LIST: &[u8] = b"packages.list";

/// Least time between two log lines about apps that could not be put in.
const FAILURE_LOG_INTERVAL: Duration = Duration::from_secs(60);

/// How long after an app could not be put in it is tried again, at first
/// and at most. The gate holds its starts meanwhile, and each try can hold
/// up the main loop for as long as the app ops helper is waited for.
const RETRY_FIRST: Duration = Duration::from_secs(10);
const RETRY_MAX: Duration = Duration::from_secs(300);

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
    /// The kernel side's maps, where the gate is there.
    kernel: Option<Kernel>,
    /// Whether starts of new apps are held now.
    held: bool,
    /// New apps that could not be put in the sandbox, by uid, with when to
    /// try again.
    retries: BTreeMap<u32, Retry>,
    /// How many new apps the last look left out of the sandbox.
    pending: u32,
    /// What tells libraries from apps, where there is something to ask.
    libraries: Option<Box<dyn Libraries>>,
    /// What it told of packages, by app id and package, for as long as the
    /// package is there under that app id.
    kinds: HashMap<(u32, String), bool>,
    /// How long into a look apps are still put in.
    look_wait: Duration,
    failures: FailureLog,
}

/// Tells libraries from apps.
///
/// A static shared library (Play installs a new one with each update of
/// Chrome or the WebView) or an SDK library has an app id, a line in the
/// package list and a data directory, but nothing runs as its uid, and it is
/// not put in the sandbox. What tells it is a flag the package manager keeps
/// for the package, which the package cannot set: never its name or any
/// text it controls.
pub trait Libraries {
    /// Whether `package`, one of `uid`'s, is a library; `None` if that
    /// cannot be told, and it counts as an app.
    fn is_library(&self, uid: u32, package: &str) -> Option<bool>;

    /// Called after the questions of a look.
    fn done(&self) {}
}

/// When to try again to put in an app that could not be.
#[derive(Clone, Copy, Debug)]
struct Retry {
    at: Instant,
    /// How long was waited since the last try.
    waited: Duration,
}

impl Retry {
    /// After a try that failed, the one before having been `last`.
    fn after(last: Option<Retry>) -> Self {
        let waited = last.map_or(RETRY_FIRST, |last| (last.waited * 2).min(RETRY_MAX));
        Self {
            at: Instant::now() + waited,
            waited,
        }
    }
}

impl Auto {
    /// As last written.
    pub fn load(writer: &Writer) -> anyhow::Result<Self> {
        let (on, rows) = writer.sandbox_auto()?;
        Ok(Self {
            on,
            known: Known::from_rows(rows),
            kernel: None,
            held: false,
            retries: BTreeMap::new(),
            pending: 0,
            libraries: None,
            kinds: HashMap::new(),
            look_wait: DISABLE_ALL_WAIT,
            failures: FailureLog::default(),
        })
    }

    /// Have the kernel side hold starts of new apps while this is on,
    /// against what is known now.
    fn sync(&mut self) {
        let Some(kernel) = &mut self.kernel else {
            self.held = false;
            return;
        };
        self.held = match kernel.sync(self.on, &self.known) {
            Ok(()) => self.on,
            Err(e) => {
                // A known app missing from the maps could never start.
                // Better hold none: new apps are still found by the watch.
                let _ = kernel.switch.set(0, &0, 0);
                eprintln!("bbd: sandbox: starts of new apps are not held: {e:#}");
                false
            }
        };
    }

    /// Whether `package`, one of `uid`'s, is a library, as told once for as
    /// long as it is there. One that cannot be told about counts as an app.
    fn is_library(&mut self, uid: u32, package: &str) -> bool {
        let key = (uid % PER_USER_RANGE, package.to_string());
        if let Some(library) = self.kinds.get(&key) {
            return *library;
        }
        let Some(told) = self
            .libraries
            .as_ref()
            .and_then(|libraries| libraries.is_library(uid, package))
        else {
            return false;
        };
        self.kinds.insert(key, told);
        told
    }

    pub(super) fn report(&self, status: &mut super::Status) {
        status.new_apps_pending = self.pending;
        status.new_apps_held = self.held;
    }
}

/// The kernel side's maps for new apps (see `gate_setresuid` in bb-ebpf).
pub struct Kernel {
    /// Whether starts of new apps are held.
    switch: Array<MapData, u32>,
    /// A bit per known app id.
    apps: Array<MapData, u64>,
    /// The known uids of apps installed since.
    uids: BpfHashMap<MapData, u32, u32>,
    /// What `apps` and `uids` hold, so that only changes are written.
    words: Vec<u64>,
    held: BTreeSet<u32>,
}

impl Kernel {
    /// The maps of the loaded gate program.
    pub fn take(ebpf: &mut aya::Ebpf) -> anyhow::Result<Self> {
        let mut map = |name: &str| {
            ebpf.take_map(name)
                .with_context(|| format!("{name} map missing"))
        };
        Ok(Self {
            switch: Array::try_from(map("AUTO_ISOLATE")?)?,
            apps: Array::try_from(map("KNOWN_APP_IDS")?)?,
            uids: BpfHashMap::try_from(map("KNOWN_UIDS")?)?,
            words: vec![0; KNOWN_APP_WORDS as usize],
            held: BTreeSet::new(),
        })
    }

    /// Have the maps hold `known`, and starts of new apps held while `on`.
    /// What is known is written before starts are held and after they no
    /// longer are: a known app is never held for not being written yet.
    fn sync(&mut self, on: bool, known: &Known) -> anyhow::Result<()> {
        if !on {
            self.switch.set(0, &0, 0).context("turning the hold off")?;
        }
        for (index, word) in known.words().into_iter().enumerate() {
            if self.words[index] != word {
                self.apps
                    .set(index as u32, &word, 0)
                    .context("writing the known app ids")?;
                self.words[index] = word;
            }
        }
        let uids: BTreeSet<u32> = known.uids.keys().copied().collect();
        let added: Vec<u32> = uids.difference(&self.held).copied().collect();
        let removed: Vec<u32> = self.held.difference(&uids).copied().collect();
        for uid in added {
            self.uids
                .insert(&uid, &1, 0)
                .context("writing a known uid")?;
            self.held.insert(uid);
        }
        for uid in removed {
            let _ = self.uids.remove(&uid);
            self.held.remove(&uid);
        }
        if on {
            self.switch.set(0, &1, 0).context("turning the hold on")?;
        }
        Ok(())
    }
}

/// What is known of the installed apps: an app that is not is new.
///
/// Each app id and uid is known with its packages. Several packages can
/// share one (sharedUserId), and it stays known for as long as any of them
/// is installed.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct Known {
    /// App ids left alone for every user: those installed when this was
    /// turned on, the system's, the app's own, and libraries.
    apps: BTreeMap<u32, BTreeSet<String>>,
    /// Uids of apps installed since that were dealt with.
    uids: BTreeMap<u32, BTreeSet<String>>,
}

impl Known {
    /// Every app in `packages`.
    fn installed(packages: &Packages) -> Self {
        Self {
            apps: packages
                .app_ids()
                .filter(|(app_id, _)| is_app(*app_id))
                .map(|(app_id, names)| (app_id, names.iter().cloned().collect()))
                .collect(),
            uids: BTreeMap::new(),
        }
    }

    fn from_rows(rows: Vec<KnownApp>) -> Self {
        let mut known = Self::default();
        for row in rows {
            let names = match row.user {
                None => known.apps.entry(row.app_id),
                Some(user) => known.uids.entry(user * PER_USER_RANGE + row.app_id),
            };
            names.or_default().insert(row.package);
        }
        known
    }

    fn rows(&self) -> Vec<KnownApp> {
        let row = |app_id, user, package: &String| KnownApp {
            app_id,
            user,
            package: package.clone(),
        };
        let apps = self
            .apps
            .iter()
            .flat_map(|(app_id, names)| names.iter().map(|name| row(*app_id, None, name)));
        let uids = self.uids.iter().flat_map(|(uid, names)| {
            let (user, app_id) = (uid / PER_USER_RANGE, uid % PER_USER_RANGE);
            names.iter().map(move |name| row(app_id, Some(user), name))
        });
        apps.chain(uids).collect()
    }

    /// The known app ids as the kernel side has them: a bit per app id.
    fn words(&self) -> Vec<u64> {
        let mut words = vec![0; KNOWN_APP_WORDS as usize];
        for (word, bit) in self.apps.keys().filter_map(|app_id| known_app_bit(*app_id)) {
            words[word as usize] |= bit;
        }
        words
    }
}

/// Whether `app_id` can be an installed app's.
fn is_app(app_id: u32) -> bool {
    known_app_bit(app_id).is_some()
}

/// What a look at the package list finds.
#[derive(Debug, Default, PartialEq, Eq)]
struct Plan {
    /// Apps installed since and not dealt with, to put in the sandbox: the
    /// uid, and its packages installed for the user (the first is named).
    isolate: Vec<(u32, BTreeSet<String>)>,
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
    let names_of =
        |app_id| -> BTreeSet<String> { packages.packages_of(app_id).iter().cloned().collect() };
    // An app id or uid stays known, with the packages it has now, while any
    // package it was known with is installed. One whose packages are all
    // gone was uninstalled, or given to another app.
    let mut next = Known::default();
    for (app_id, was) in &known.apps {
        let now = names_of(*app_id);
        if !now.is_disjoint(was) {
            next.apps.insert(*app_id, now);
        }
    }
    for (uid, was) in &known.uids {
        let user = uid / PER_USER_RANGE;
        let mut now = names_of(uid % PER_USER_RANGE);
        now.retain(|name| installed(user, name));
        if !now.is_disjoint(was) {
            next.uids.insert(*uid, now);
        }
    }

    let mut app_ids: Vec<(u32, &[String])> = packages
        .app_ids()
        .filter(|(app_id, _)| is_app(*app_id) && !next.apps.contains_key(app_id))
        .collect();
    app_ids.sort_unstable();
    let mut isolate = Vec::new();
    for (app_id, names) in app_ids {
        if packages.is_system(app_id) || names.iter().any(|n| OWN_PACKAGES.contains(&n.as_str())) {
            next.apps.insert(app_id, names_of(app_id));
            continue;
        }
        for &user in users {
            let uid = user * PER_USER_RANGE + app_id;
            if next.uids.contains_key(&uid) {
                continue;
            }
            let here: BTreeSet<String> = names
                .iter()
                .filter(|name| installed(user, name))
                .cloned()
                .collect();
            if !here.is_empty() {
                isolate.push((uid, here));
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
        self.auto.retries.clear();
        self.auto.pending = 0;
        self.auto.sync();
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
        let started = Instant::now();
        // An app uninstalled while in the sandbox is taken out first, as
        // before a command: an app given its uid since is new, and must
        // not be found in the sandbox under the other's entry. This reads
        // the package list again.
        self.release_reassigned(writer, packages);
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
        // What was told of packages that are gone, or under another app id
        // now, is told again if they come back.
        self.auto
            .kinds
            .retain(|(app_id, package), _| packages.packages_of(*app_id).contains(package));
        // The new apps this look leaves out, and when to try them again.
        let mut left = 0;
        let mut retries = BTreeMap::new();
        // Each app may wait for the app ops helper, on the loop that also
        // records: as when taking every app out, the rest are left for the
        // next look past a point. Their starts are held meanwhile.
        let until = started + self.auto.look_wait;
        for (uid, mut names) in plan.isolate {
            let retry = self.auto.retries.get(&uid).copied();
            if retry.is_some_and(|retry| retry.at > Instant::now()) || Instant::now() > until {
                retries.extend(retry.map(|retry| (uid, retry)));
                left += 1;
                continue;
            }
            // A library is left alone, and not known: what else is ever
            // given its app id is new.
            names.retain(|name| !self.auto.is_library(uid, name));
            let Some(package) = names.first().cloned() else {
                continue;
            };
            match self.enable(
                writer,
                packages,
                &package,
                uid / PER_USER_RANGE,
                crate::now(),
            ) {
                Ok(enabled) => {
                    isolated.push(enabled.uid);
                    known.uids.insert(enabled.uid, names);
                }
                Err(e) => {
                    retries.insert(uid, Retry::after(retry));
                    left += 1;
                    // Package names are not logged; the error may name it.
                    let e = format!("{e:#}").replace(package.as_str(), "the app");
                    self.auto.failures.log(uid, &e);
                }
            }
        }
        if let Some(libraries) = &self.auto.libraries {
            libraries.done();
        }
        self.auto.pending = left;
        self.auto.retries = retries;
        if !isolated.is_empty() {
            // How long it took: putting an app in can wait for the app ops
            // helper to start, and for the database to reach the disk.
            let uids: Vec<String> = isolated.iter().map(u32::to_string).collect();
            eprintln!(
                "bbd: sandbox: newly installed, put in the sandbox: uid {} ({} ms)",
                uids.join(", "),
                started.elapsed().as_millis()
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
            self.auto.sync();
        }
    }

    /// Tell libraries from apps with `libraries` (see [`Libraries`]).
    pub fn tell_libraries_with(&mut self, libraries: Box<dyn Libraries>) {
        self.auto.libraries = Some(libraries);
    }

    /// Take up the putting of new apps in the sandbox in a daemon that just
    /// started, with the kernel side's maps where the gate is there. What
    /// was installed while no daemon ran is put in first; starts of new apps
    /// are held from then on.
    pub fn resume_auto(
        &mut self,
        kernel: Option<Kernel>,
        writer: &Writer,
        packages: &mut Packages,
    ) {
        self.auto.kernel = kernel;
        self.isolate_new(writer, packages);
        self.auto.sync();
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

/// What is watched in the package list's directory: renamed into it, which
/// is how Android writes the list, and written in place, which it may do.
const LIST_MASK: u32 = libc::IN_MOVED_TO | libc::IN_CLOSE_WRITE;
/// What is watched in the directory of all users' data: a user added.
const USERS_MASK: u32 = libc::IN_CREATE | libc::IN_MOVED_TO | libc::IN_ONLYDIR;
/// What is watched in a user's data directory: an app's directory made, or
/// given its owner, which installd does right after.
const USER_MASK: u32 = libc::IN_CREATE | libc::IN_MOVED_TO | libc::IN_ATTRIB | libc::IN_ONLYDIR;

/// Watches for apps being installed, while newly installed apps are put in
/// the sandbox.
///
/// Android writes its package list to a new file and renames that over the
/// old one, so the list's directory is watched, and only the list counts
/// there; the other files in it change now and then, and each change costs
/// a read of the event. The list is written before the app's data
/// directories are made, which is how an app is known to be installed for a
/// user, and need not be written again after. So each user's data directory
/// is watched for new entries too, and the directory of all users for new
/// users. Those change only when apps are installed and users added.
#[derive(Default)]
pub struct Watch {
    inotify: Option<Inotify>,
    /// Watching failed, which was logged: it is not tried again until this
    /// is turned off and on, or the daemon starts again.
    failed: bool,
}

struct Inotify {
    fd: AsyncFd<OwnedFd>,
    /// The watch of the package list's directory.
    list: i32,
    /// The watch of the directory of all users' data, and that directory.
    users: i32,
    users_dir: PathBuf,
    /// The watches of each user's data directory.
    user_dirs: BTreeSet<i32>,
}

impl Watch {
    /// Watch `list_dir`, the package list's directory, and `users_dir`, the
    /// users' data directories, while `on`, and stop when not. True when the
    /// watch has just begun: what changed before it did was not seen, and is
    /// to be looked at.
    pub fn arm(&mut self, on: bool, list_dir: &Path, users_dir: &Path) -> bool {
        if !on {
            self.inotify = None;
            self.failed = false;
            return false;
        }
        if self.inotify.is_some() || self.failed {
            return false;
        }
        match Inotify::new(list_dir, users_dir) {
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

    /// Wait until an app may have been installed. Never, while not armed.
    /// Every event there is is read, so that one change is reported once.
    pub async fn changed(&mut self) -> io::Result<()> {
        let Some(inotify) = &mut self.inotify else {
            return std::future::pending().await;
        };
        let mut buffer = [0; EVENTS_SIZE];
        loop {
            let mut events = Vec::new();
            {
                let mut ready = inotify.fd.readable().await?;
                loop {
                    match ready.try_io(|fd| read(fd.get_ref(), &mut buffer)) {
                        Ok(Ok(n)) => events.extend(parse_events(&buffer[..n])),
                        Ok(Err(e)) if e.kind() == io::ErrorKind::Interrupted => {}
                        Ok(Err(e)) => return Err(e),
                        // Nothing more to read.
                        Err(_) => break,
                    }
                }
            }
            if inotify.note(&events) {
                return Ok(());
            }
        }
    }

    /// Stop watching, after `e`.
    pub fn give_up(&mut self, e: &io::Error) {
        eprintln!(
            "bbd: sandbox: cannot watch for installed apps ({e}); new apps are found as they start, or when the daemon next starts"
        );
        self.inotify = None;
        self.failed = true;
    }
}

impl Inotify {
    fn new(list_dir: &Path, users_dir: &Path) -> io::Result<Self> {
        let fd = unsafe { libc::inotify_init1(libc::IN_NONBLOCK | libc::IN_CLOEXEC) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: just opened, and owned by nothing else.
        let fd = unsafe { OwnedFd::from_raw_fd(fd) };
        let list = add_watch(&fd, list_dir, LIST_MASK)?;
        let users = add_watch(&fd, users_dir, USERS_MASK)?;
        // SAFETY: the OwnedFd owns its fd, which stays open and unchanged
        // for as long as the AsyncFd owns the OwnedFd.
        let fd = unsafe { AsyncFd::register_with_interest(fd, Interest::READABLE) }
            .map_err(|e| e.into_parts().1)?;
        let mut inotify = Self {
            fd,
            list,
            users,
            users_dir: users_dir.to_path_buf(),
            user_dirs: BTreeSet::new(),
        };
        inotify.watch_users();
        Ok(inotify)
    }

    /// Watch the data directory of every user there is. One that cannot be
    /// watched is still covered by the package list and the gate.
    fn watch_users(&mut self) {
        let users: Vec<Vec<u8>> = std::fs::read_dir(&self.users_dir)
            .into_iter()
            .flatten()
            .flatten()
            .map(|entry| entry.file_name().as_bytes().to_vec())
            .collect();
        for user in users {
            self.watch_user(&user);
        }
    }

    fn watch_user(&mut self, name: &[u8]) {
        let is_user = !name.is_empty() && name.iter().all(u8::is_ascii_digit);
        if !is_user {
            return;
        }
        let dir = self.users_dir.join(std::ffi::OsStr::from_bytes(name));
        if let Ok(wd) = add_watch(self.fd.get_ref(), &dir, USER_MASK) {
            self.user_dirs.insert(wd);
        }
    }

    /// Take in `events`, and say whether an app may have been installed.
    fn note(&mut self, events: &[Event]) -> bool {
        let mut installed = false;
        for event in events {
            if event.mask & libc::IN_Q_OVERFLOW != 0 {
                // Any of what was lost may have been an install, or a new
                // user.
                self.watch_users();
                installed = true;
            } else if event.mask & libc::IN_IGNORED != 0 {
                // A user removed: the kernel dropped the watch.
                self.user_dirs.remove(&event.wd);
            } else if event.wd == self.list {
                installed |= event.name == PACKAGE_LIST;
            } else if event.wd == self.users {
                self.watch_user(&event.name);
                installed = true;
            } else if self.user_dirs.contains(&event.wd) {
                // The directory itself changing says nothing.
                installed |= !event.name.is_empty();
            }
        }
        installed
    }
}

fn add_watch(fd: &OwnedFd, dir: &Path, mask: u32) -> io::Result<i32> {
    let dir = CString::new(dir.as_os_str().as_bytes())?;
    let wd = unsafe { libc::inotify_add_watch(fd.as_raw_fd(), dir.as_ptr(), mask) };
    if wd < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(wd)
}

fn read(fd: &OwnedFd, into: &mut [u8]) -> io::Result<usize> {
    let n = unsafe { libc::read(fd.as_raw_fd(), into.as_mut_ptr().cast(), into.len()) };
    usize::try_from(n).map_err(|_| io::Error::last_os_error())
}

/// An event read from the watch.
#[derive(Debug, PartialEq, Eq)]
struct Event {
    wd: i32,
    mask: u32,
    /// The entry of the watched directory it is about; empty for the
    /// directory itself.
    name: Vec<u8>,
}

/// The events in `bytes`, as read from an inotify descriptor.
fn parse_events(bytes: &[u8]) -> Vec<Event> {
    let header = size_of::<libc::inotify_event>();
    let mut events = Vec::new();
    let mut rest = bytes;
    while rest.len() >= header {
        // SAFETY: a header's bytes, read unaligned; any bytes are one.
        let event = unsafe { rest.as_ptr().cast::<libc::inotify_event>().read_unaligned() };
        let end = rest.len().min(header + event.len as usize);
        // The name is padded with NULs.
        let name = rest[header..end].split(|&b| b == 0).next().unwrap_or(&[]);
        events.push(Event {
            wd: event.wd,
            mask: event.mask,
            name: name.to_vec(),
        });
        rest = &rest[end..];
    }
    events
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
    fn the_kernel_side_is_given_a_bit_per_known_app_id() {
        let known = Known {
            apps: [10_000, 10_063, 10_064, 19_999, 1001]
                .into_iter()
                .map(|app_id| (app_id, names(&["com.a"])))
                .collect(),
            uids: BTreeMap::from([(1_010_230, names(&["com.b"]))]),
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
            apps: BTreeMap::from([(10150, names(&["com.android.chrome"]))]),
            uids: BTreeMap::from([
                (10221, names(&["com.example.gone"])),
                (1_010_221, names(&["com.example.gone"])),
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
                (10220, names(&["com.shared.one", "com.shared.two"])),
                (1_010_220, names(&["com.shared.two"]))
            ]
        );
        assert_eq!(
            plan.known,
            Known {
                apps: known.apps.clone(),
                uids: BTreeMap::from([(10221, names(&["com.example.gone"]))]),
            }
        );
    }

    fn names(packages: &[&str]) -> BTreeSet<String> {
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

    /// An event as the kernel writes it.
    fn event(wd: i32, mask: u32, name: &str) -> Vec<u8> {
        // Names are padded to a multiple of the header's alignment.
        let len = if name.is_empty() {
            0
        } else {
            (name.len() + 1).next_multiple_of(4)
        };
        let header = libc::inotify_event {
            wd,
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
    fn events_are_read_with_their_names() {
        let bytes = [
            event(1, libc::IN_MOVED_TO, "packages.list"),
            event(3, libc::IN_ATTRIB, ""),
            event(3, libc::IN_CREATE | libc::IN_ISDIR, "com.example.new"),
        ]
        .concat();
        assert_eq!(
            parse_events(&bytes),
            [
                Event {
                    wd: 1,
                    mask: libc::IN_MOVED_TO,
                    name: b"packages.list".to_vec()
                },
                Event {
                    wd: 3,
                    mask: libc::IN_ATTRIB,
                    name: vec![]
                },
                Event {
                    wd: 3,
                    mask: libc::IN_CREATE | libc::IN_ISDIR,
                    name: b"com.example.new".to_vec()
                },
            ]
        );
        assert!(parse_events(&[]).is_empty());
        assert!(parse_events(&[0; 5]).is_empty());
    }

    /// A package list's directory and a users' data directory with user 0,
    /// as Android has them.
    fn layout(f: &Fixture) -> (PathBuf, PathBuf) {
        let system = f.dir.join("system");
        let users = f.dir.join("user_de");
        std::fs::create_dir_all(&system).unwrap();
        std::fs::create_dir_all(users.join("0")).unwrap();
        (system, users)
    }

    /// Whether the watch reports a change within a short while.
    async fn reports(watch: &mut Watch) -> bool {
        match tokio::time::timeout(Duration::from_millis(200), watch.changed()).await {
            Ok(changed) => {
                changed.unwrap();
                true
            }
            Err(_) => false,
        }
    }

    #[tokio::test]
    async fn installs_are_seen_in_the_list_and_the_data_directories() {
        let f = Fixture::new("auto-watch");
        let (system, users) = layout(&f);
        let mut watch = Watch::default();
        assert!(!watch.arm(false, &system, &users));
        assert!(watch.arm(true, &system, &users));
        assert!(!watch.arm(true, &system, &users));
        assert!(watch.armed());

        // As Android writes the list: a new file renamed over the old one.
        std::fs::write(system.join("appops.xml"), "x").unwrap();
        assert!(!reports(&mut watch).await);
        std::fs::write(system.join("packages.list.tmp"), "x").unwrap();
        std::fs::rename(
            system.join("packages.list.tmp"),
            system.join("packages.list"),
        )
        .unwrap();
        assert!(reports(&mut watch).await);
        // Reported once.
        assert!(!reports(&mut watch).await);

        // An app's data directory made, then given its owner.
        let app = users.join("0/com.example.new");
        std::fs::create_dir(&app).unwrap();
        assert!(reports(&mut watch).await);
        std::fs::set_permissions(&app, std::os::unix::fs::PermissionsExt::from_mode(0o700))
            .unwrap();
        assert!(reports(&mut watch).await);
        // What the app keeps there is its own business.
        std::fs::write(app.join("prefs.xml"), "x").unwrap();
        assert!(!reports(&mut watch).await);

        // A user added, and an app installed for it.
        std::fs::create_dir(users.join("10")).unwrap();
        assert!(reports(&mut watch).await);
        std::fs::create_dir(users.join("10/com.example.new")).unwrap();
        assert!(reports(&mut watch).await);
        // And removed again.
        std::fs::remove_dir_all(users.join("10")).unwrap();
        assert!(!reports(&mut watch).await);

        assert!(!watch.arm(false, &system, &users));
        assert!(!watch.armed());
        // What cannot be watched is given up on until turned off and on.
        assert!(!watch.arm(true, &f.dir.join("absent"), &users));
        assert!(!watch.arm(true, &system, &users));
        watch.arm(false, &system, &users);
        assert!(watch.arm(true, &system, &users));
    }

    #[test]
    fn an_idle_loop_is_woken_by_each_change() {
        let f = Fixture::new("auto-wake");
        let (system, users) = layout(&f);
        // As the daemon runs its main loop.
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let (renamed, seen) = runtime.block_on(async {
            let mut watch = Watch::default();
            assert!(watch.arm(true, &system, &users));
            // Two writes of the list, the second while the first is being
            // looked at.
            let writer = {
                let system = system.clone();
                std::thread::spawn(move || {
                    let mut renamed = Vec::new();
                    for pause in [100, 10] {
                        std::thread::sleep(Duration::from_millis(pause));
                        renamed.push(Instant::now());
                        std::fs::write(system.join("packages.list.tmp"), "x").unwrap();
                        std::fs::rename(
                            system.join("packages.list.tmp"),
                            system.join("packages.list"),
                        )
                        .unwrap();
                    }
                    renamed
                })
            };
            let (_commands, mut command) = tokio::sync::mpsc::channel::<()>(1);
            let mut flush = tokio::time::interval(Duration::from_secs(30));
            flush.tick().await;
            let until = tokio::time::Instant::now() + Duration::from_secs(2);
            let mut seen = Vec::new();
            while seen.len() < 2 {
                tokio::select! {
                    _ = flush.tick() => {}
                    _ = command.recv() => {}
                    changed = watch.changed(), if watch.armed() => {
                        changed.unwrap();
                        seen.push(Instant::now());
                        // A look at the list takes a while.
                        std::thread::sleep(Duration::from_millis(30));
                    }
                    _ = tokio::time::sleep_until(until) => break,
                }
            }
            (writer.join().unwrap(), seen)
        });
        // Each write is seen soon after it was made.
        for at in renamed {
            assert!(
                seen.iter()
                    .any(|seen| *seen >= at && *seen - at < Duration::from_millis(200)),
                "a write was not seen in time"
            );
        }
    }

    #[tokio::test]
    async fn an_app_whose_data_directory_comes_after_the_list_is_put_in() {
        let mut f = Fixture::new("auto-late-data");
        let mut sandbox = f.sandbox();
        sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
        let mut watch = Watch::default();
        assert!(watch.arm(true, &f.dir, &f.user_data()));
        let new_two = "com.example.two 10231 0 /d default:targetSdkVersion=35 none 0 1 1 @null";

        // The list first: the app is installed for no user yet.
        f.list(&[
            "com.android.chrome 10150 0 /d default:partition=product none 0 1 1 @system",
            "com.example.old 10200 0 /d default none 0 1 1 com.android.vending",
            NEW,
            new_two,
        ]);
        assert!(reports(&mut watch).await);
        sandbox.isolate_new(&f.writer, &mut f.packages);
        assert!(sandboxed(&sandbox).is_empty());

        // Then its data directory, and the list is not written again.
        f.install(0, "com.example.new");
        assert!(reports(&mut watch).await);
        sandbox.isolate_new(&f.writer, &mut f.packages);
        assert_eq!(sandboxed(&sandbox), [10230]);

        // A user added later, and an app installed for it.
        std::fs::create_dir(f.user_data().join("10")).unwrap();
        assert!(reports(&mut watch).await);
        sandbox.isolate_new(&f.writer, &mut f.packages);
        f.install(10, "com.example.two");
        assert!(reports(&mut watch).await);
        sandbox.isolate_new(&f.writer, &mut f.packages);
        assert_eq!(sandboxed(&sandbox), [10230, 1_010_231]);
    }

    /// Refuses to put an app in while `refusing` is set, as when the app ops
    /// helper does not answer, and counts the tries.
    struct Refusing {
        refusing: std::rc::Rc<std::cell::Cell<bool>>,
        tries: std::rc::Rc<std::cell::Cell<u32>>,
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
            anyhow::ensure!(!self.refusing.get(), "{} was refused", packages[0]);
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
        let refusing = std::rc::Rc::new(std::cell::Cell::new(true));
        let tries = std::rc::Rc::new(std::cell::Cell::new(0));
        let effect = Refusing {
            refusing: refusing.clone(),
            tries: tries.clone(),
        };
        let mut sandbox =
            Sandbox::load(&f.writer, vec![Box::new(effect)], Some(&f.user_data())).unwrap();
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

        // Its time come, it is tried again.
        refusing.set(false);
        let retry = sandbox.auto.retries.get_mut(&10230).unwrap();
        assert_eq!(retry.waited, RETRY_FIRST);
        retry.at = Instant::now();
        sandbox.isolate_new(&f.writer, &mut f.packages);
        assert_eq!(tries.get(), 2);
        assert_eq!(sandboxed(&sandbox), [10230]);
        assert_eq!(sandbox.status().new_apps_pending, 0);
        assert!(sandbox.auto.retries.is_empty());
        // Later tries wait longer, up to a bound.
        let first = Retry::after(None);
        assert_eq!(Retry::after(Some(first)).waited, RETRY_FIRST * 2);
        let mut last = first;
        for _ in 0..20 {
            last = Retry::after(Some(last));
        }
        assert_eq!(last.waited, RETRY_MAX);
    }

    #[test]
    fn a_look_puts_apps_in_only_for_so_long() {
        let mut f = Fixture::new("auto-bound");
        let mut sandbox = f.sandbox();
        sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
        f.list(&[NEW]);
        f.install(0, "com.example.new");
        f.install(10, "com.example.new");
        // A look that has taken its time already leaves the rest.
        sandbox.auto.look_wait = Duration::ZERO;
        sandbox.isolate_new(&f.writer, &mut f.packages);
        assert!(sandboxed(&sandbox).is_empty());
        assert_eq!(sandbox.status().new_apps_pending, 2);
        // The next one takes them up.
        sandbox.auto.look_wait = DISABLE_ALL_WAIT;
        sandbox.isolate_new(&f.writer, &mut f.packages);
        assert_eq!(sandboxed(&sandbox), [10230, 1_010_230]);
        assert_eq!(sandbox.status().new_apps_pending, 0);
    }

    /// The package manager as the helper reaches it: the packages it keeps
    /// flagged as libraries, and the questions it was asked.
    struct PackageManager {
        libraries: Vec<&'static str>,
        /// None of its answers arrive.
        silent: bool,
        asked: std::rc::Rc<std::cell::RefCell<Vec<String>>>,
    }

    impl Libraries for PackageManager {
        fn is_library(&self, _: u32, package: &str) -> Option<bool> {
            self.asked.borrow_mut().push(package.to_string());
            (!self.silent).then(|| self.libraries.contains(&package))
        }
    }

    fn package_manager(
        libraries: Vec<&'static str>,
        silent: bool,
    ) -> (
        Box<PackageManager>,
        std::rc::Rc<std::cell::RefCell<Vec<String>>>,
    ) {
        let asked = std::rc::Rc::new(std::cell::RefCell::new(Vec::new()));
        let manager = PackageManager {
            libraries,
            silent,
            asked: asked.clone(),
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
        // Not known: nothing is written of it.
        let (_, known) = f.writer.sandbox_auto().unwrap();
        assert!(known.iter().all(|row| row.app_id != 10240));
        // Asked once for as long as it is there.
        sandbox.isolate_new(&f.writer, &mut f.packages);
        assert_eq!(*asked.borrow(), [LIBRARY]);

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
    fn a_package_that_cannot_be_told_about_counts_as_an_app() {
        let mut f = Fixture::new("auto-library-silent");
        let mut sandbox = f.sandbox();
        let (manager, asked) = package_manager(vec![LIBRARY], true);
        sandbox.tell_libraries_with(manager);
        sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
        f.list(&[&library_line(LIBRARY, 10240)]);
        f.install(0, LIBRARY);
        sandbox.isolate_new(&f.writer, &mut f.packages);
        assert_eq!(sandboxed(&sandbox), [10240]);
        assert_eq!(asked.borrow().len(), 1);
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
            crate::sandbox::items::Item::Camera,
            Some(crate::sandbox::items::Mode::Off),
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
}
