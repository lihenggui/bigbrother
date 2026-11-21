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
//! this is on, the daemon watches for both ([`watch::Watch`]) and looks at the list
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
    collections::{BTreeMap, BTreeSet},
    io,
    time::{Duration, Instant},
};

use anyhow::Context as _;
use aya::maps::{Array, HashMap as BpfHashMap, MapData};
use bb_common::{KNOWN_APP_WORDS, PER_USER_RANGE, known_app_bit};
use serde::{Deserialize, Serialize};

use super::{DISABLE_ALL_WAIT, Sandbox};
use crate::{
    backoff::Backoff,
    log::{Limited, Policy, SANDBOX_INTERVAL},
    packages::{Packages, app_id_of, user_of},
    store::{KnownApp, Writer},
};

mod scan;
pub mod watch;

/// How long after an app could not be put in it is tried again, at first
/// and at most. The gate holds its starts meanwhile, and each try can hold
/// up the main loop for as long as the app ops helper is waited for.
const RETRY_BACKOFF: Backoff = Backoff {
    first: Duration::from_secs(10),
    max: Duration::from_secs(300),
    healthy: None,
};

/// How soon an app a look had no time for is tried.
const LOOK_AGAIN: Duration = Duration::from_secs(2);

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
    /// Retry a package-list read or a write of the known set even when no
    /// particular app was waiting.
    rescan: Option<Retry>,
    known_dirty: bool,
    /// How many new apps the last look left out of the sandbox.
    pending: u32,
    /// What tells libraries from apps, where there is something to ask.
    libraries: Option<Box<dyn Libraries>>,
    /// Packages told to be apps, by app id, for as long as they are there
    /// under it. A library is known (`Known::libraries`).
    told_apps: BTreeSet<(u32, String)>,
    /// How long into a look apps are still put in.
    look_wait: Duration,
    failures: Limited,
    switch_errors: Limited,
    #[cfg(test)]
    user_data_fixture: Option<std::path::PathBuf>,
}

/// Tells libraries from apps.
///
/// A static shared library (Play installs a new one with each update of
/// Chrome or the WebView) or an SDK library has an app id, a line in the
/// package list and a data directory, but nothing runs as its uid, and it is
/// not put in the sandbox. What tells it is a flag the package manager keeps
/// for the package, which the package cannot set: never its name or any
/// text it controls.
pub trait Libraries: Send {
    /// Whether `package`, one of `uid`'s, is a library; `None` if that
    /// cannot be told by `until`.
    fn is_library(&self, uid: u32, package: &str, until: Instant) -> Option<bool>;

    /// Called after the questions of a look.
    fn done(&self) {}
}

/// When to try again to put in an app that could not be.
#[derive(Clone, Copy, Debug)]
struct Retry {
    at: Instant,
    /// Deferring an attempt leaves the failure streak alone.
    failures: u32,
}

impl Retry {
    /// After a try that failed, the one before having been `last`.
    fn after(last: Option<Retry>) -> Self {
        let failures = last.map_or(1, |last| last.failures.saturating_add(1));
        Self {
            at: Instant::now() + RETRY_BACKOFF.delay(failures),
            failures,
        }
    }

    /// Soon, for an app a look had no time for, with what was waited
    /// before kept.
    fn soon(last: Option<Retry>) -> Self {
        Self {
            at: Instant::now() + LOOK_AGAIN,
            failures: last.map_or(0, |last| last.failures),
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
            rescan: None,
            known_dirty: false,
            pending: 0,
            libraries: None,
            told_apps: BTreeSet::new(),
            look_wait: DISABLE_ALL_WAIT,
            failures: Limited::new(SANDBOX_INTERVAL, Policy::Shared),
            switch_errors: Limited::new(SANDBOX_INTERVAL, Policy::Shared),
            #[cfg(test)]
            user_data_fixture: None,
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
                match kernel.switch.set(0, &0, 0) {
                    Ok(()) => eprintln!("bbd: sandbox: starts of new apps are not held: {e:#}"),
                    Err(switch) => self.switch_errors.log(format_args!(
                        "bbd: sandbox: cannot stop holding starts of new apps after a map sync failure: {switch:?} ({e:#})"
                    )),
                }
                false
            }
        };
    }

    /// Whether `package`, one of `uid`'s, is a library, as the package
    /// manager says by `until`; `None` if it does not.
    fn kind_of(&mut self, uid: u32, package: &str, until: Instant) -> Option<bool> {
        let key = (app_id_of(uid), package.to_string());
        if self.told_apps.contains(&key) {
            return Some(false);
        }
        // With nothing to ask, every package counts as an app.
        let Some(libraries) = &self.libraries else {
            return Some(false);
        };
        let library = libraries.is_library(uid, package, until)?;
        if !library {
            self.told_apps.insert(key);
        }
        Some(library)
    }

    /// When a look is due without anything having changed: an app left
    /// out is to be tried again. `None` while none is, and while this is
    /// off.
    pub fn next_look(&self) -> Option<Instant> {
        if !self.on {
            return None;
        }
        self.retries
            .values()
            .chain(self.rescan.iter())
            .map(|retry| retry.at)
            .min()
    }

    /// Put off every try that is due, by as long as it waited before: a
    /// look could not be made.
    fn put_off(&mut self) {
        self.rescan = Some(Retry::after(self.rescan));
        let now = Instant::now();
        for retry in self.retries.values_mut().filter(|retry| retry.at <= now) {
            retry.at = now + RETRY_BACKOFF.delay(retry.failures);
        }
    }

    pub(super) fn forget_uid(&mut self, uid: u32) {
        if self.known.uids.remove(&uid).is_some() {
            self.known_dirty = true;
            self.rescan = Some(Retry {
                at: Instant::now(),
                failures: 0,
            });
        }
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
        for uid in removed {
            self.uids.remove(&uid).context("forgetting a known uid")?;
            self.held.remove(&uid);
        }
        for uid in added {
            self.uids
                .insert(&uid, &1, 0)
                .context("writing a known uid")?;
            self.held.insert(uid);
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
    /// turned on, the system's and the app's own.
    apps: BTreeMap<u32, BTreeSet<String>>,
    /// Uids of apps installed since that were dealt with.
    uids: BTreeMap<u32, BTreeSet<String>>,
    /// Packages the package manager has as libraries, by app id: each is
    /// left alone itself, while it is installed, and nothing else of its
    /// app id is.
    libraries: BTreeMap<u32, BTreeSet<String>>,
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
            libraries: BTreeMap::new(),
        }
    }

    fn from_rows(rows: Vec<KnownApp>) -> Self {
        let mut known = Self::default();
        for row in rows {
            let names = match row.user {
                None if row.library => known.libraries.entry(row.app_id),
                None => known.apps.entry(row.app_id),
                Some(user) => known.uids.entry(user * PER_USER_RANGE + row.app_id),
            };
            names.or_default().insert(row.package);
        }
        known
    }

    fn rows(&self) -> Vec<KnownApp> {
        let row = |app_id, user, package: &String, library| KnownApp {
            app_id,
            user,
            package: package.clone(),
            library,
        };
        let apps = self
            .apps
            .iter()
            .flat_map(|(app_id, names)| names.iter().map(|name| row(*app_id, None, name, false)));
        let uids = self.uids.iter().flat_map(|(&uid, names)| {
            let (user, app_id) = (user_of(uid), app_id_of(uid));
            names
                .iter()
                .map(move |name| row(app_id, Some(user), name, false))
        });
        // A library's app id known as an app's needs no row of its own.
        let libraries = self
            .libraries
            .iter()
            .filter(|(app_id, _)| !self.apps.contains_key(app_id))
            .flat_map(|(app_id, names)| names.iter().map(|name| row(*app_id, None, name, true)));
        apps.chain(uids).chain(libraries).collect()
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
            anyhow::ensure!(
                packages.refresh() && !packages.is_empty(),
                "the package list cannot be read"
            );
            Known::installed(packages)
        } else {
            Known::default()
        };
        writer.put_sandbox_auto(on, &known.rows())?;
        self.auto.on = on;
        self.auto.known = known;
        self.auto.known_dirty = false;
        self.auto.retries.clear();
        self.auto.rescan = None;
        self.auto.told_apps.clear();
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
        if self.auto.on {
            self.look(writer, packages, true);
        }
    }

    /// When a look is due without anything having changed (see
    /// [`Auto::next_look`]).
    pub fn auto_next_look(&self) -> Option<Instant> {
        self.auto.next_look()
    }

    /// Tell libraries from apps with `libraries` (see [`Libraries`]).
    pub fn tell_libraries_with(&mut self, libraries: Box<dyn Libraries>) {
        self.auto.libraries = Some(libraries);
    }

    /// Take up the putting of new apps in the sandbox in a daemon that just
    /// started, with the kernel side's maps where the gate is there. What
    /// needs no helper is done first: what is uninstalled is forgotten, and
    /// the system's new apps are known, so that their starts are not held.
    /// Apps installed while no daemon ran are held from then on, and put in
    /// by the main loop's first look, which waits for the helper no longer
    /// than any look does: it may still be waiting for the system to start.
    pub fn resume_auto(
        &mut self,
        kernel: Option<Kernel>,
        writer: &Writer,
        packages: &mut Packages,
    ) {
        self.auto.kernel = kernel;
        if self.auto.on {
            self.look(writer, packages, false);
        }
        self.auto.sync();
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

#[cfg(test)]
mod tests;
