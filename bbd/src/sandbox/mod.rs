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

//! The sandbox: which apps the user put in it, and turning it on and off.
//!
//! The sandbox applies to a uid, not a package. Android runs every package
//! that shares a uid as the same user, with the same files and permissions,
//! so one of them cannot be held apart from the others; enabling it for one
//! puts them all in, and says so.
//!
//! What it does to an app is a list of [`Effect`]s, carried out in order
//! when it is enabled and disabled. Two records say what holds:
//!
//! - The `sandbox` table says what may be in effect. It is written before
//!   the effects of an enable and after those of a disable, so that whenever
//!   any effect may be in place the row says the app is sandboxed, and a
//!   daemon that stopped half way can finish or undo the change when it
//!   starts again. A change of items is written before its effects as
//!   items that keep from the app all that the old or the new ones keep
//!   (`items::Settings::covering`), and after them as the new ones, and so
//!   are the global settings: an app op held anew is named before it is
//!   held, and one let go stays named until it is. A runtime permission
//!   taken anew is named in the row before it is taken
//!   (`appops::naming_taken`), as no mode of the items covers it together
//!   with the ops of another.
//! - The snapshot, in memory, says what is meant to be in effect. It changes
//!   before the effects run, which may read it, and is shared with whatever
//!   needs to look up a uid without waiting for the daemon's main loop.
//!
//! The effects, in the order they are carried out:
//!
//! - [`appops`]: personal data is refused through app ops, and location
//!   made approximate through its permission.
//! - [`identity`]: the system server hook is told which uids to answer
//!   differently.
//! - [`network`]: the packet filter refuses what the app sends.
//! - [`gate`]: starting apps are held until [`mounts`] has replaced the
//!   storage they see.
//! - [`sweep`]: the app's running processes are ended, so that it starts
//!   again as it should. The system ends them itself when [`appops`] takes
//!   a permission the app had: Android does whenever a runtime permission
//!   is revoked.
//!
//! Taking an app out begins with the one that can fail, restoring its app
//! ops, so that a failure leaves the app as sandboxed as it was.
//!
//! What each effect does depends on the app's [`items`]: the items the app
//! has a mode of its own for, over the global settings. An item is on unless
//! set otherwise, except the network, and an app with every item off has
//! nothing done to it.
//!
//! Apps can also be put in as they are installed ([`auto`]).

pub mod appops;
pub mod auto;
pub mod destinations;
pub(crate) mod dirs;
pub mod gate;
pub mod identity;
pub mod items;
mod mounts;
pub mod network;
pub mod resolver;
pub mod sweep;

use std::{
    collections::{BTreeMap, BTreeSet, HashMap},
    fs::File,
    io::{Read as _, Write as _},
    path::{Path, PathBuf},
    sync::{Arc, RwLock},
};

use anyhow::Context as _;
use serde::{Deserialize, Serialize};

use crate::{
    control::{self, Request},
    packages::{PER_USER_RANGE, Packages},
    store::Writer,
};

pub use crate::store::SandboxEntry as Entry;
use items::{InPlace, Item, Mode, Part, Settings};

use crate::netctl::Target;

/// App ids from here up belong to installed apps. Below are the system's
/// own uids, which are shared by many components and cannot be sandboxed.
const FIRST_APPLICATION_UID: u32 = 10_000;

/// Packages that are never sandboxed: the app, whose own reads of the
/// device would be cut off from what it reports on. The canary test app
/// (`li.mercury.bigbrother.canary`) is not among them; it is what the
/// sandbox is tested with.
const OWN_PACKAGES: &[&str] = &["li.mercury.bigbrother"];

/// The highest Android user whose uids still fit the 31 bits Android keeps
/// them in.
const LAST_USER: u32 = (i32::MAX as u32 - (PER_USER_RANGE - 1)) / PER_USER_RANGE;

/// How long after it began taking every app out (or carrying out a change
/// of the global settings) an app may still be started on. It is checked
/// before each app, and one app can then wait for the app ops helper for
/// up to `appops::ANSWER_WAIT`: with a second to spare, the answer still
/// reaches the client before it gives up (`control::COMMAND_WAIT`).
const DISABLE_ALL_WAIT: std::time::Duration = control::COMMAND_WAIT
    .saturating_sub(appops::ANSWER_WAIT)
    .saturating_sub(std::time::Duration::from_secs(1));

/// Least time between two log lines about entries that cannot be taken out:
/// each is tried again at every command and every look for new apps.
const RELEASE_LOG_INTERVAL: std::time::Duration = std::time::Duration::from_secs(60);

/// The most destinations one app can be kept from: what bounds the list
/// the kernel side looks packets up in.
const RULES_PER_APP: usize = 1024;

/// The sandboxed uids and their entries, enabled ones only.
pub type Snapshot = Arc<RwLock<HashMap<u32, Entry>>>;

/// The global settings: the mode of each item of an app that has none of
/// its own.
pub type Global = Arc<RwLock<Settings>>;

/// What applies to `entry`: its own settings over the global ones.
pub fn items_of(entry: &Entry, global: &Settings) -> Settings {
    Settings::parse(&entry.settings).over(global)
}

/// One thing the sandbox does to an app (say, the storage it sees, or the
/// app ops it is refused).
///
/// Both [`Effect::enable`] and [`Effect::disable`] run the effects in the
/// order they were added, since a step such as stopping the app has to come
/// last either way. When one fails, those that already ran are undone in
/// reverse order and the command fails, so that an app is never left half
/// sandboxed.
pub trait Effect {
    /// Names the effect in an error.
    fn name(&self) -> &'static str;

    /// Apply the effect to `entry.uid`, under which run `packages`, as
    /// `items` say. It may change the entry, for instance to keep what it
    /// replaced; the entry is written after the last effect.
    fn enable(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        items: &Settings,
    ) -> anyhow::Result<()>;

    /// Take the effect off again, from what `entry` keeps. `items` are the
    /// ones that applied.
    fn disable(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        items: &Settings,
    ) -> anyhow::Result<()>;

    /// Change what the effect does to an app in the sandbox from what
    /// `before` says to what `after` says.
    fn update(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        before: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()>;

    /// Take up again, in a daemon that just started, the apps that were
    /// sandboxed when the last one stopped. Unlike [`Effect::enable`] this
    /// cannot fail the whole: an effect reports what it could not do and
    /// carries on.
    fn resume(&mut self, _apps: &[Resumed]) {}

    /// Drop bookkeeping without changing permissions or signalling the successor.
    fn forget(&mut self, _uid: u32) {}

    /// Carry out what changed for an app in the sandbox other than its
    /// items: the destinations it is kept from. `items` are those that
    /// apply to it.
    fn refresh(&mut self, _entry: &Entry, _items: &Settings) -> anyhow::Result<()> {
        Ok(())
    }

    /// Fill in what the effect knows about its own state.
    fn report(&self, _status: &mut Status) {}

    /// Bring the enabled `entries` up to date with what the effect found
    /// changed by the user while their apps were sandboxed, since it was
    /// last asked; the uids of the entries it changed, which are written.
    fn found(&mut self, _entries: &mut HashMap<u32, Entry>) -> Vec<u32> {
        Vec::new()
    }
}

/// A sandboxed app taken up again by a daemon that just started.
#[derive(Clone, Debug)]
pub struct Resumed {
    pub entry: Entry,
    /// The packages that run under its uid.
    pub packages: Vec<String>,
    /// What applies to it.
    pub items: Settings,
}

/// What is done to an app's effects.
#[derive(Clone, Copy)]
enum Change<'a> {
    Enable(&'a Settings),
    Disable(&'a Settings),
    Update(&'a Settings, &'a Settings),
}

impl Change<'_> {
    /// What undoes it.
    fn reversed(self) -> Self {
        match self {
            Change::Enable(items) => Change::Disable(items),
            Change::Disable(items) => Change::Enable(items),
            Change::Update(before, after) => Change::Update(after, before),
        }
    }

    fn apply(
        self,
        effect: &mut dyn Effect,
        entry: &mut Entry,
        packages: &[String],
    ) -> anyhow::Result<()> {
        match self {
            Change::Enable(items) => effect.enable(entry, packages, items),
            Change::Disable(items) => effect.disable(entry, packages, items),
            Change::Update(before, after) => effect.update(entry, packages, before, after),
        }
    }
}

/// The answer to `sandbox.enable`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Enabled {
    pub uid: u32,
    /// The other packages that share the uid, and are sandboxed with it.
    pub also: Vec<String>,
}

/// The answer to `sandbox.disable`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Disabled {
    /// False if the app was not in the sandbox, and nothing was done.
    pub was_enabled: bool,
}

/// The answer to `sandbox.disable_all`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DisabledAll {
    /// How many apps were taken out.
    pub disabled: u32,
    /// The uids that could not be, and are sandboxed still.
    pub failed: Vec<u32>,
}

/// The answer to `sandbox.list`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Listed {
    pub apps: Vec<ListedApp>,
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ListedApp {
    pub uid: u32,
    pub package: String,
    pub enabled: bool,
    /// When it was first put in the sandbox, in seconds since the epoch.
    pub created: i64,
    /// The items it has a mode of its own for.
    pub settings: Settings,
    /// What applies to it: its own settings over the global ones.
    pub items: Settings,
    /// For an app in the sandbox, the items that are on but not in effect,
    /// each with the parts that are not in place.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub unmet: BTreeMap<Item, Vec<Part>>,
    /// How many destinations it is kept from.
    #[serde(default)]
    pub network_rules: u32,
    /// Since when its connections are recorded, in seconds since the
    /// epoch, if they are.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recording_since: Option<i64>,
}

/// The answer to `sandbox.global` and `sandbox.set_global`: every item,
/// with the mode it has for an app that has none of its own.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct GlobalItems {
    pub items: Settings,
    /// Whether apps installed from now on are put in the sandbox with them
    /// (see [`auto`]).
    #[serde(default)]
    pub auto: bool,
}

/// The answer to `sandbox.set`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Configured {
    pub uid: u32,
    /// The items the app now has a mode of its own for.
    pub settings: Settings,
    /// What applies to it now.
    pub items: Settings,
}

/// The answer to `sandbox.status`: whether each part of the sandbox works on
/// this device, and how the starts of sandboxed apps went. What no effect
/// reports on stays false or zero.
#[derive(Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Status {
    /// Starting apps are held until their storage is replaced.
    pub gate: bool,
    /// Personal data is refused through app ops.
    pub appops: bool,
    /// The packet filter refuses the network to the apps kept from it.
    #[serde(default)]
    pub network: bool,
    /// The connections of the apps the user chose can be recorded.
    #[serde(default)]
    pub net_record: bool,
    /// The names apps look up are known, and can be refused.
    #[serde(default)]
    pub dns_names: bool,
    /// The bytes of connections are counted.
    #[serde(default)]
    pub traffic: bool,
    pub hook: HookStatus,
    /// Starts of sandboxed apps let through, and those that were stopped.
    pub admitted: u64,
    pub failed: u64,
    /// Starts of newly installed apps ended before they were in the
    /// sandbox (see [`auto`]).
    pub new_apps_stopped: u64,
    /// Newly installed apps not in the sandbox yet: not put in by the last
    /// look, or failed to be and waiting to be tried again.
    pub new_apps_pending: u32,
    /// Whether starts of newly installed apps not in the sandbox yet are
    /// held.
    pub new_apps_held: bool,
    /// How long starts were held, 95th percentile, in microseconds.
    pub hold_p95_us: u64,
    /// The ops each sandboxed uid is held to and the system keeps, by uid:
    /// what [`Sandbox::list`] reports per app.
    #[serde(skip)]
    pub gated_uids: BTreeSet<u32>,
    #[serde(skip)]
    pub held_ops: BTreeMap<u32, BTreeSet<String>>,
}

/// What the system server hook reports it has in place.
#[derive(Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct HookStatus {
    pub android_id: bool,
    pub app_list: bool,
    /// Whether file grants an app kept from before its storage was
    /// isolated are withheld from it.
    #[serde(default)]
    pub uri_grants: bool,
    /// Whether the advertising ID is withheld from the apps whose device
    /// identifiers are.
    #[serde(default)]
    pub ad_id: bool,
}

pub struct Sandbox {
    awaiting_storage: BTreeSet<u32>,
    snapshot: Snapshot,
    global: Global,
    effects: Vec<Box<dyn Effect>>,
    /// Where each Android user's apps keep their data, a directory per user
    /// and package (`/data/user_de`, which can be read before the user
    /// unlocks the device): how an app is known to be installed for a user.
    /// Not checked when `None`.
    user_data: Option<PathBuf>,
    auto: auto::Auto,
    /// When an entry that could not be taken out was last logged.
    release_logged: Option<std::time::Instant>,
    /// The destinations apps are kept from, as written.
    book: destinations::Book,
}

impl Sandbox {
    /// The sandbox as last written, doing `effects`, in this order, to the
    /// apps enabled and disabled from now on. The snapshot holds the enabled
    /// entries.
    pub fn load(
        writer: &Writer,
        effects: Vec<Box<dyn Effect>>,
        user_data: Option<&Path>,
    ) -> anyhow::Result<Self> {
        let enabled = writer
            .sandbox_entries()?
            .into_iter()
            .filter(|entry| entry.enabled)
            .map(|entry| (entry.uid, entry))
            .collect();
        let global = Settings::parse(&writer.sandbox_global()?);
        let book = destinations::Book::default();
        {
            // A rule this build cannot read was written by a later one,
            // and is left where it is.
            let mut targets = book.lock().unwrap_or_else(|e| e.into_inner());
            for rule in writer.net_rules()? {
                if let Some(target) = Target::parse(&rule.target) {
                    targets.entry(rule.uid).or_default().insert(target);
                }
            }
        }
        Ok(Self {
            book,
            snapshot: Arc::new(RwLock::new(enabled)),
            global: Arc::new(RwLock::new(global)),
            effects,
            user_data: user_data.map(Path::to_path_buf),
            auto: auto::Auto::load(writer)?,
            release_logged: None,
            awaiting_storage: BTreeSet::new(),
        })
    }

    /// The destinations apps are kept from, for what carries them out.
    pub fn book(&self) -> destinations::Book {
        self.book.clone()
    }

    /// The enabled entries, for an effect that needs them all.
    pub fn snapshot(&self) -> Snapshot {
        self.snapshot.clone()
    }

    /// The global settings, for an effect that works out what applies to
    /// the apps in the snapshot.
    pub fn global(&self) -> Global {
        self.global.clone()
    }

    fn global_read(&self) -> std::sync::RwLockReadGuard<'_, Settings> {
        self.global.read().unwrap_or_else(|e| e.into_inner())
    }

    /// What applies to `entry`.
    fn items(&self, entry: &Entry) -> Settings {
        items_of(entry, &self.global_read())
    }

    /// Add an effect, after those there are.
    pub fn add_effect(&mut self, effect: Box<dyn Effect>) {
        self.effects.push(effect);
    }

    /// Put the apps that were sandboxed when the daemon last stopped back
    /// in. To be called once, when the daemon starts.
    pub fn resume(&mut self, writer: &Writer, packages: &mut Packages) {
        if packages.refresh() && !packages.is_empty() {
            self.reconcile_entries(writer, packages, None);
        }
        self.resume_verified(writer, packages);
    }

    fn resume_verified(&mut self, writer: &Writer, packages: &mut Packages) {
        let mut owned = Vec::new();
        let mut waiting = BTreeSet::new();
        for entry in self.snapshot_read().values() {
            if self.owns(entry) {
                owned.push(entry.clone());
            } else {
                waiting.insert(entry.uid);
            }
        }
        self.awaiting_storage = waiting;
        let mut apps = Vec::new();
        for entry in owned {
            let entry = self.naming_taken_on_resume(writer, entry);
            apps.push(Resumed {
                packages: packages.for_uid(entry.uid).to_vec(),
                items: self.items(&entry),
                entry,
            });
        }
        apps.sort_by_key(|app| app.entry.uid);
        for effect in &mut self.effects {
            effect.resume(&apps);
        }
    }

    /// `entry`, taken up again, naming the permissions its items take that
    /// it does not name yet, as enabling and changing items do before they
    /// are taken: an item that takes one, new since the entry was last
    /// written and on by the global settings, is taken as the helper
    /// starts, and what it finds is kept only for what the entry names.
    fn naming_taken_on_resume(&mut self, writer: &Writer, entry: Entry) -> Entry {
        let named = appops::naming_taken(&entry, &self.items(&entry));
        if named == entry {
            return entry;
        }
        match writer.put_sandbox(&named) {
            Ok(()) => {
                self.snapshot_write().insert(named.uid, named.clone());
                named
            }
            Err(e) => {
                eprintln!(
                    "bbd: sandbox: naming what uid {} is held to: {e:#}",
                    entry.uid
                );
                entry
            }
        }
    }

    /// Read the current package list and retire entries whose installation
    /// is gone. A verified shared UID member can continue to own the sandbox.
    pub(crate) fn release_reassigned(&mut self, writer: &Writer, packages: &mut Packages) {
        if !self.snapshot_read().is_empty() && packages.refresh() && !packages.is_empty() {
            self.reconcile(writer, packages, None);
        }
    }

    fn reconcile(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        until: Option<std::time::Instant>,
    ) {
        let changed = self.reconcile_entries(writer, packages, until);
        let available = self.awaiting_storage.iter().any(|uid| {
            self.snapshot_read()
                .get(uid)
                .is_some_and(|entry| self.owns(entry))
        });
        if changed || available {
            self.resume_verified(writer, packages);
        }
    }

    fn reconcile_entries(
        &mut self,
        writer: &Writer,
        packages: &Packages,
        until: Option<std::time::Instant>,
    ) -> bool {
        let mut entries: Vec<Entry> = self.snapshot_read().values().cloned().collect();
        entries.sort_by_key(|entry| entry.uid);
        let mut changed = false;
        for mut entry in entries {
            if until.is_some_and(|until| std::time::Instant::now() >= until) {
                break;
            }
            let uid = entry.uid;
            let current = packages.packages_of(uid % PER_USER_RANGE);
            let recorded = match writer.sandbox_packages(uid) {
                Ok(recorded) => recorded,
                Err(e) => {
                    self.log_reconcile_error(uid, &e);
                    continue;
                }
            };
            match self.existing_owner(&entry, current, &recorded) {
                Ok(Some(owner)) => {
                    if entry.package != owner {
                        entry.package = owner;
                        if let Err(e) = writer.put_sandbox(&entry) {
                            self.log_reconcile_error(uid, &e);
                            continue;
                        }
                        self.snapshot_write().insert(uid, entry.clone());
                        changed = true;
                    }
                    match self.remember_packages(writer, &entry, current, &recorded) {
                        Ok(updated) => changed |= updated,
                        Err(e) => self.log_reconcile_error(uid, &e),
                    }
                }
                Ok(None) => {
                    entry.enabled = false;
                    if let Err(e) = writer.put_sandbox(&entry) {
                        self.log_reconcile_error(uid, &e);
                        continue;
                    }
                    self.snapshot_write().remove(&uid);
                    self.awaiting_storage.remove(&uid);
                    self.auto.forget_uid(uid);
                    // Nothing saved for the old installation may be applied
                    // to the one the uid belongs to now.
                    for effect in &mut self.effects {
                        effect.forget(uid);
                    }
                }
                Err(_) => {
                    // A directory may be unavailable while the user starts.
                }
            }
        }
        changed
    }

    fn log_reconcile_error(&mut self, uid: u32, e: &anyhow::Error) {
        if self
            .release_logged
            .is_none_or(|at| at.elapsed() >= RELEASE_LOG_INTERVAL)
        {
            eprintln!("bbd: sandbox: cannot reconcile uid {uid}: {e:#}");
            self.release_logged = Some(std::time::Instant::now());
        }
    }

    fn owns(&self, entry: &Entry) -> bool {
        self.matches_package(entry, &entry.package).unwrap_or(false)
    }

    fn matches_package(&self, entry: &Entry, package: &str) -> std::io::Result<bool> {
        match &self.user_data {
            Some(root) => dirs::matches_install(root, package, entry.uid, &entry.install_id),
            None => Ok(true),
        }
    }

    fn existing_owner(
        &self,
        entry: &Entry,
        current: &[String],
        recorded: &BTreeSet<String>,
    ) -> std::io::Result<Option<String>> {
        let mut unavailable = None;
        let siblings = current
            .iter()
            .filter(|pkg| *pkg != &entry.package && recorded.contains(*pkg));
        for package in std::iter::once(&entry.package)
            .filter(|pkg| current.contains(pkg))
            .chain(siblings)
        {
            match self.matches_package(entry, package) {
                Ok(true) => return Ok(Some(package.clone())),
                Ok(false) => {}
                Err(e) => unavailable = Some(e),
            }
        }
        unavailable.map_or(Ok(None), Err)
    }

    fn remember_packages(
        &self,
        writer: &Writer,
        entry: &Entry,
        current: &[String],
        recorded: &BTreeSet<String>,
    ) -> anyhow::Result<bool> {
        let verified: BTreeSet<String> = current
            .iter()
            .filter(|package| {
                recorded.contains(*package)
                    || *package == &entry.package
                    || self.user_data.as_deref().is_none_or(|root| {
                        dirs::share_install(root, package, entry.uid, &entry.install_id)
                            .unwrap_or(false)
                    })
            })
            .cloned()
            .collect();
        if verified == *recorded {
            return Ok(false);
        }
        let names: Vec<String> = verified.into_iter().collect();
        writer.put_sandbox_packages(entry.uid, &names)?;
        Ok(true)
    }

    fn snapshot_read(&self) -> std::sync::RwLockReadGuard<'_, HashMap<u32, Entry>> {
        // A reader that panicked cannot have left the map half changed.
        self.snapshot.read().unwrap_or_else(|e| e.into_inner())
    }

    fn snapshot_write(&self) -> std::sync::RwLockWriteGuard<'_, HashMap<u32, Entry>> {
        self.snapshot.write().unwrap_or_else(|e| e.into_inner())
    }

    /// Keep in the entries, and write, what the effects found changed by
    /// the user while the apps were sandboxed (`Effect::found`): called
    /// with every write of the records, and before every command, which
    /// may give back what the entries hold.
    pub fn keep_found(&mut self, writer: &Writer) {
        let snapshot = self.snapshot.clone();
        let mut entries = snapshot.write().unwrap_or_else(|e| e.into_inner());
        let mut changed: Vec<u32> = Vec::new();
        for effect in &mut self.effects {
            changed.extend(effect.found(&mut entries));
        }
        changed.sort_unstable();
        changed.dedup();
        for uid in changed {
            if let Some(entry) = entries.get(&uid)
                && let Err(e) = writer.put_sandbox(entry)
            {
                // Kept here all the same, and written with the next change.
                eprintln!("bbd: sandbox: cannot write what was found for uid {uid}: {e:#}");
            }
        }
    }

    /// Answer a request line from `bbd.ctl`.
    pub fn answer(&mut self, line: &str, writer: &Writer, packages: &mut Packages) -> String {
        let request = match Request::parse(line) {
            Ok(request) => request,
            Err(e) => return control::failure(&e),
        };
        self.keep_found(writer);
        if !matches!(request, Request::SandboxStatus) {
            self.release_reassigned(writer, packages);
        }
        let answer = match request {
            Request::SandboxEnable { package, user } => self
                .enable(writer, packages, &package, user, crate::now())
                .map(control::success),
            Request::SandboxDisable { package, user } => self
                .disable(writer, packages, &package, user)
                .map(control::success),
            Request::SandboxDisableAll => Ok(control::success(self.disable_all(writer))),
            Request::SandboxList => self.list(writer).map(control::success),
            Request::SandboxStatus => Ok(control::success(self.status())),
            Request::SandboxSet {
                package,
                user,
                settings,
            } => self
                .configure(writer, packages, &package, user, &settings)
                .map(control::success),
            Request::SandboxGlobal => Ok(control::success(self.global_items())),
            Request::SandboxSetGlobal { settings } => self
                .set_global(writer, packages, &settings)
                .map(control::success),
            Request::SandboxAuto => Ok(control::success(self.auto_switch())),
            Request::SandboxSetAuto { on } => {
                self.set_auto(writer, packages, on).map(control::success)
            }
            Request::NetBlock {
                package,
                user,
                target,
                manual,
            } => self
                .net_block(writer, packages, &package, user, &target, manual)
                .map(control::success),
            Request::NetUnblock {
                package,
                user,
                target,
            } => self
                .net_unblock(writer, packages, &package, user, &target)
                .map(control::success),
            Request::NetClear { package, user } => uid_of(packages, &package, user)
                .and_then(|uid| writer.net_clear(uid))
                .map(|deleted| control::success(serde_json::json!({"deleted": deleted}))),
        };
        self.note_recording(writer);
        answer.unwrap_or_else(|e| control::failure(&format!("{e:#}")))
    }

    /// Write down which apps have their connections recorded now, with
    /// since when: those in the sandbox with the item on.
    pub fn note_recording(&self, writer: &Writer) {
        let recorded: Vec<u32> = {
            let global = self.global_read();
            self.snapshot_read()
                .values()
                .filter(|entry| items_of(entry, &global).network_recorded())
                .map(|entry| entry.uid)
                .collect()
        };
        if let Err(e) = writer.set_net_recording(&recorded, crate::now()) {
            eprintln!("bbd: could not write which apps are recorded: {e:#}");
        }
    }

    /// Keep `package` from the destination `target`. The rule is the
    /// app's whether it is in the sandbox or not, and is carried out
    /// while it is.
    fn net_block(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        package: &str,
        user: u32,
        target: &str,
        manual: bool,
    ) -> anyhow::Result<serde_json::Value> {
        let uid = uid_of(packages, package, user)?;
        let parsed = Target::parse(target).context("not a domain or an address")?;
        let status = self.status();
        match parsed {
            Target::Ip(..) => anyhow::ensure!(
                status.network,
                "the packet filter is not available; destinations cannot be refused"
            ),
            Target::Domain(_) | Target::Host(_) => anyhow::ensure!(
                status.dns_names,
                "the names apps look up cannot be refused on this device"
            ),
        }
        {
            let mut book = self.book.lock().unwrap_or_else(|e| e.into_inner());
            let targets = book.entry(uid).or_default();
            anyhow::ensure!(
                targets.len() < RULES_PER_APP || targets.contains(&parsed),
                "an app can be kept from {RULES_PER_APP} destinations at most"
            );
            targets.insert(parsed.clone());
        }
        let rule = crate::store::net::NetRule {
            uid,
            target: parsed.to_text(),
            created: crate::now(),
            manual,
        };
        let done = writer.put_net_rule(&rule).and_then(|()| self.refresh(uid));
        if let Err(e) = done {
            // Not written, or not carried out: not a rule.
            self.forget_rule(writer, uid, &parsed);
            return Err(e);
        }
        Ok(serde_json::json!({}))
    }

    fn net_unblock(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        package: &str,
        user: u32,
        target: &str,
    ) -> anyhow::Result<serde_json::Value> {
        let uid = uid_of(packages, package, user)?;
        let parsed = Target::parse(target).context("not a domain or an address")?;
        let had = self
            .book
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get_mut(&uid)
            .is_some_and(|targets| targets.remove(&parsed));
        if let Err(e) = self.refresh(uid) {
            // Still refused: still a rule.
            if had {
                self.book
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .entry(uid)
                    .or_default()
                    .insert(parsed);
            }
            return Err(e);
        }
        writer.delete_net_rule(uid, &parsed.to_text())?;
        Ok(serde_json::json!({}))
    }

    /// Take a rule that could not be made out of the book and the
    /// database, and out of the kernel as far as it got there.
    fn forget_rule(&mut self, writer: &Writer, uid: u32, target: &Target) {
        if let Some(targets) = self
            .book
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get_mut(&uid)
        {
            targets.remove(target);
        }
        let _ = writer.delete_net_rule(uid, &target.to_text());
        let _ = self.refresh(uid);
    }

    /// Put `package`, as installed for Android user `user`, in the sandbox,
    /// with every package that shares its uid.
    pub fn enable(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        package: &str,
        user: u32,
        now: i64,
    ) -> anyhow::Result<Enabled> {
        let uid = uid_of(packages, package, user)?;
        self.enable_uid(writer, packages, package, uid, now)
    }

    fn enable_uid(
        &mut self,
        writer: &Writer,
        packages: &Packages,
        package: &str,
        uid: u32,
        now: i64,
    ) -> anyhow::Result<Enabled> {
        let user = uid / PER_USER_RANGE;
        let sharing = packages.packages_of(uid % PER_USER_RANGE).to_vec();
        anyhow::ensure!(
            !sharing.iter().any(|p| OWN_PACKAGES.contains(&p.as_str())),
            "Big Brother cannot sandbox itself"
        );
        let also = sharing.iter().filter(|p| *p != package).cloned().collect();
        let before = writer.sandbox_entry(uid)?;
        let recorded = writer.sandbox_packages(uid)?;
        let owner = match &before {
            Some(entry) => self
                .existing_owner(entry, &sharing, &recorded)
                .with_context(|| format!("cannot verify the installation for user {user}"))?,
            None => None,
        };
        // Even when another package owns the sandbox, the requested package
        // must belong to this UID and be installed for the requested user.
        let install_id = match &self.user_data {
            Some(root) => {
                if let (Some(old), Some(_)) = (&before, &owner) {
                    dirs::share_install(root, package, uid, &old.install_id).with_context(
                        || format!("cannot verify the installation for user {user}"),
                    )?;
                    old.install_id.clone()
                } else {
                    dirs::install_id(root, package, uid, true).with_context(|| {
                        format!("cannot verify the installation for user {user}")
                    })?
                }
            }
            None => package.to_owned(),
        };
        if let Some(held) = self.snapshot_read().get(&uid) {
            anyhow::ensure!(
                owner.as_ref().is_some_and(|owner| owner == &held.package) && self.owns(held),
                "the uid still has an unverified sandbox entry"
            );
            return Ok(Enabled { uid, also });
        }

        let mut entry = match (&before, owner) {
            (Some(old), Some(owner)) => Entry {
                package: owner,
                enabled: true,
                ..old.clone()
            },
            _ => Entry {
                install_id,
                uid,
                package: package.to_string(),
                enabled: true,
                android_id: new_android_id()?,
                saved_ops: String::new(),
                saved_permissions: String::new(),
                created: now,
                settings: String::new(),
            },
        };
        let items = self.items(&entry);
        let written = appops::naming_taken(&entry, &items);
        writer.put_sandbox(&written)?;
        self.snapshot_write().insert(uid, entry.clone());

        let mut applied = self.run(&mut entry, &sharing, Change::Enable(&items));
        if applied.is_ok() && entry != written {
            applied = writer.put_sandbox(&entry);
            if applied.is_err() {
                // What the effects changed is lost: they cannot stay.
                if let Err(e) = self.run(&mut entry.clone(), &sharing, Change::Disable(&items)) {
                    eprintln!("bbd: sandbox: cannot take uid {uid}'s effects off: {e:#}");
                }
            }
        }
        if let Err(e) = applied {
            self.snapshot_write().remove(&uid);
            let restored = match &before {
                Some(old) => writer.put_sandbox(old),
                None => writer.delete_sandbox(uid),
            };
            if let Err(restore) = restored {
                eprintln!("bbd: sandbox: cannot restore uid {uid}'s entry: {restore:#}");
            }
            return Err(e);
        }
        let previous_packages = if before
            .as_ref()
            .is_some_and(|old| old.install_id == entry.install_id)
        {
            recorded
        } else {
            BTreeSet::new()
        };
        if let Err(e) = self.remember_packages(writer, &entry, &sharing, &previous_packages) {
            self.log_reconcile_error(uid, &e);
        }
        self.snapshot_write().insert(uid, entry);
        Ok(Enabled { uid, also })
    }

    /// Take `package`'s uid, as installed for Android user `user`, out of
    /// the sandbox. Its entry is kept, disabled, so that enabling it again
    /// gives the app the identity it had. Nothing is done for an app that is
    /// not sandboxed, and the answer says so.
    pub fn disable(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        package: &str,
        user: u32,
    ) -> anyhow::Result<Disabled> {
        // An app uninstalled while sandboxed is found by its entry.
        let uid = self.uid_of_entry(writer, packages, package, user)?;
        let sharing = packages.for_uid(uid).to_vec();
        self.disable_uid(writer, uid, &sharing)
    }

    fn disable_uid(
        &mut self,
        writer: &Writer,
        uid: u32,
        sharing: &[String],
    ) -> anyhow::Result<Disabled> {
        let Some(mut entry) = self.snapshot_write().remove(&uid) else {
            return Ok(Disabled { was_enabled: false });
        };
        let items = self.items(&entry);
        let enabled = entry.clone();
        entry.enabled = false;
        if let Err(e) = self.run(&mut entry, sharing, Change::Disable(&items)) {
            self.snapshot_write().insert(uid, enabled);
            return Err(e);
        }
        if let Err(e) = writer.put_sandbox(&entry) {
            // The row still says the app is sandboxed: so it stays.
            if let Err(e) = self.run(&mut enabled.clone(), sharing, Change::Enable(&items)) {
                eprintln!("bbd: sandbox: cannot put uid {uid}'s effects back: {e:#}");
            }
            self.snapshot_write().insert(uid, enabled);
            return Err(e);
        }
        Ok(Disabled { was_enabled: true })
    }

    /// Take every app out of the sandbox, going on past one that cannot be.
    pub fn disable_all(&mut self, writer: &Writer) -> DisabledAll {
        let mut uids: Vec<u32> = self.snapshot_read().keys().copied().collect();
        uids.sort_unstable();
        let mut answer = DisabledAll {
            disabled: 0,
            failed: Vec::new(),
        };
        // Each app may wait for the app ops helper, on the loop that also
        // records. With a helper that does not answer, the rest are not
        // waited for one by one.
        let until = std::time::Instant::now() + DISABLE_ALL_WAIT;
        for uid in uids {
            if std::time::Instant::now() > until {
                answer.failed.push(uid);
                continue;
            }
            match self.disable_uid(writer, uid, &[]) {
                Ok(_) => answer.disabled += 1,
                Err(e) => {
                    eprintln!("bbd: sandbox: cannot take uid {uid} out: {e:#}");
                    answer.failed.push(uid);
                }
            }
        }
        answer
    }

    /// Carry out every effect. When one fails, those that already ran are
    /// reversed, last first.
    fn run(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        change: Change,
    ) -> anyhow::Result<()> {
        for done in 0..self.effects.len() {
            anyhow::ensure!(self.owns(entry), "the installation has changed");
            let effect = &mut self.effects[done];
            if let Err(e) = change.apply(effect.as_mut(), entry, packages) {
                let failed = effect.name();
                for effect in self.effects[..done].iter_mut().rev() {
                    if let Err(undo) = change.reversed().apply(effect.as_mut(), entry, packages) {
                        eprintln!(
                            "bbd: sandbox: cannot undo {} for uid {}: {undo:#}",
                            effect.name(),
                            entry.uid
                        );
                    }
                }
                return Err(e.context(failed));
            }
        }
        Ok(())
    }

    /// Carry out, for the app of `uid` if it is in the sandbox, what
    /// changed for it other than its items ([`Effect::refresh`]).
    pub fn refresh(&mut self, uid: u32) -> anyhow::Result<()> {
        let Some(entry) = self.snapshot_read().get(&uid).cloned() else {
            return Ok(());
        };
        let items = self.items(&entry);
        for effect in &mut self.effects {
            effect
                .refresh(&entry, &items)
                .with_context(|| effect.name().to_string())?;
        }
        Ok(())
    }

    pub fn status(&self) -> Status {
        let mut status = Status::default();
        for effect in &self.effects {
            effect.report(&mut status);
        }
        self.auto.report(&mut status);
        status
    }

    /// Every entry, enabled or not.
    fn list(&self, writer: &Writer) -> anyhow::Result<Listed> {
        let global = self.global_read();
        let status = self.status();
        let (rules, recording) = (writer.net_rule_counts()?, writer.net_recording()?);
        let apps = writer
            .sandbox_entries()?
            .into_iter()
            .map(|entry| {
                let in_place = InPlace {
                    mounts: status.gate && status.gated_uids.contains(&entry.uid),
                    android_id_hook: status.hook.android_id,
                    app_list_hook: status.hook.app_list,
                    uri_grants_hook: status.hook.uri_grants,
                    ad_id_hook: status.hook.ad_id,
                    packet_filter: status.network,
                };
                let settings = Settings::parse(&entry.settings);
                let items = settings.over(&global);
                let unmet = if entry.enabled {
                    let held = status.held_ops.get(&entry.uid);
                    items.unmet(&in_place, |op| held.is_some_and(|held| held.contains(op)))
                } else {
                    BTreeMap::new()
                };
                ListedApp {
                    unmet,
                    items,
                    network_rules: rules.get(&entry.uid).copied().unwrap_or(0),
                    recording_since: recording.get(&entry.uid).copied(),
                    uid: entry.uid,
                    package: entry.package,
                    enabled: entry.enabled,
                    created: entry.created,
                    settings,
                }
            })
            .collect();
        Ok(Listed { apps })
    }

    /// Every item, with its global mode.
    fn global_items(&self) -> GlobalItems {
        GlobalItems {
            items: self.global_read().over(&Settings::default()),
            auto: self.auto_switch().auto,
        }
    }

    /// Set what the sandbox does to `package`, as installed for Android user
    /// `user`, item by item: to a mode, or with `None` back to following the
    /// global settings. Items not named keep what they have. The app may be
    /// in the sandbox or have been in it; for an app in it, what changes is
    /// carried out at once, and undone if it cannot all be.
    pub fn configure(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        package: &str,
        user: u32,
        changes: &BTreeMap<Item, Option<Mode>>,
    ) -> anyhow::Result<Configured> {
        let uid = self.uid_of_entry(writer, packages, package, user)?;
        let entry = match self.snapshot_read().get(&uid).cloned() {
            Some(entry) => Some(entry),
            None => writer.sandbox_entry(uid)?,
        };
        let mut entry = entry.with_context(|| format!("{package} is not in the sandbox"))?;
        let current = packages.packages_of(uid % PER_USER_RANGE);
        let recorded = writer.sandbox_packages(uid)?;
        let owner = self
            .existing_owner(&entry, current, &recorded)?
            .with_context(|| format!("{package} is not in the sandbox"))?;
        anyhow::ensure!(
            !entry.enabled || entry.package == owner,
            "the installation has changed"
        );
        entry.package = owner;
        let mut own = Settings::parse(&entry.settings);
        for (item, mode) in changes {
            own.set(*item, *mode)?;
        }
        let before = self.items(&entry);
        let previous = entry.clone();
        entry.settings = own.to_text();
        let after = self.items(&entry);
        if !entry.enabled || before == after {
            writer.put_sandbox(&entry)?;
            if entry.enabled {
                self.snapshot_write().insert(uid, entry);
            }
            return Ok(Configured {
                uid,
                settings: own,
                items: after,
            });
        }

        let sharing = packages.for_uid(uid).to_vec();
        // Until the effects have run, the row covers both what was in
        // effect and what is to be.
        let covering = appops::naming_taken(
            &Entry {
                settings: Settings::parse(&previous.settings)
                    .covering(&own, &self.global_read())
                    .to_text(),
                ..previous.clone()
            },
            &after,
        );
        if covering != previous {
            writer.put_sandbox(&covering)?;
        }
        // As for enabling: what is meant to be in effect changes first.
        self.snapshot_write().insert(uid, entry.clone());
        let mut applied = self.run(&mut entry, &sharing, Change::Update(&before, &after));
        if applied.is_ok() {
            applied = writer.put_sandbox(&entry);
            if applied.is_err() {
                let undone = self.run(
                    &mut entry.clone(),
                    &sharing,
                    Change::Update(&after, &before),
                );
                if let Err(e) = undone {
                    eprintln!("bbd: sandbox: cannot put uid {uid}'s items back: {e:#}");
                }
            }
        }
        if let Err(e) = applied {
            // Left covering both if it cannot be written back.
            if covering != previous
                && let Err(e) = writer.put_sandbox(&previous)
            {
                eprintln!("bbd: sandbox: cannot write uid {uid}'s entry back: {e:#}");
            }
            self.snapshot_write().insert(uid, previous);
            return Err(e);
        }
        self.snapshot_write().insert(uid, entry);
        Ok(Configured {
            uid,
            settings: own,
            items: after,
        })
    }

    /// Change the global settings, and with them every app in the sandbox
    /// that follows them. All or nothing: if one app cannot be changed, the
    /// apps changed already are put back and the settings stay as they were.
    pub fn set_global(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        changes: &BTreeMap<Item, Mode>,
    ) -> anyhow::Result<GlobalItems> {
        let old = self.global_read().clone();
        let mut new = old.clone();
        for (item, mode) in changes {
            new.set(*item, Some(*mode))?;
        }
        if new == old {
            return Ok(self.global_items());
        }
        let write_global = |settings: &Settings| {
            let pairs: Vec<(&str, &str)> = settings
                .iter()
                .map(|(item, mode)| (item.name(), items::mode_name(mode)))
                .collect();
            writer.put_sandbox_global(&pairs)
        };
        // As for the items of one app: until every app has changed, the
        // row covers both what was in effect and what is to be.
        let covering = old.covering(&new, &Settings::default());
        if covering != old {
            write_global(&covering)?;
        }
        let mut uids: Vec<u32> = self.snapshot_read().keys().copied().collect();
        uids.sort_unstable();
        *self.global.write().unwrap_or_else(|e| e.into_inner()) = new.clone();

        // Each app may wait for the app ops helper, on the loop that also
        // records: as with taking every app out, the rest are not waited
        // for one by one past a point.
        let until = std::time::Instant::now() + DISABLE_ALL_WAIT;
        let mut changed: Vec<(Entry, Vec<String>, Settings, Settings)> = Vec::new();
        let mut failure = None;
        for uid in uids {
            let Some(mut entry) = self.snapshot_read().get(&uid).cloned() else {
                continue;
            };
            let (before, after) = (items_of(&entry, &old), items_of(&entry, &new));
            if before == after {
                continue;
            }
            if std::time::Instant::now() > until {
                failure = Some(anyhow::anyhow!("taking too long"));
                break;
            }
            let sharing = packages.for_uid(uid).to_vec();
            let unchanged = entry.clone();
            // As for one app's items: a permission is named before it is
            // taken.
            let written = appops::naming_taken(&entry, &after);
            if written != unchanged
                && let Err(e) = writer.put_sandbox(&written)
            {
                failure = Some(e.context(format!("uid {uid}")));
                break;
            }
            match self.run(&mut entry, &sharing, Change::Update(&before, &after)) {
                Ok(()) => {
                    if entry != written
                        && let Err(e) = writer.put_sandbox(&entry)
                    {
                        failure = Some(e.context(format!("uid {uid}")));
                        changed.push((entry, sharing, before, after));
                        break;
                    }
                    if entry != unchanged {
                        self.snapshot_write().insert(uid, entry.clone());
                    }
                    changed.push((entry, sharing, before, after));
                }
                Err(e) => {
                    // Undone, and written down as it was.
                    if written != unchanged
                        && let Err(e) = writer.put_sandbox(&unchanged)
                    {
                        eprintln!("bbd: sandbox: cannot write uid {uid}'s entry back: {e:#}");
                    }
                    failure = Some(e.context(format!("uid {uid}")));
                    break;
                }
            }
        }
        let written = failure.is_none().then(|| write_global(&new));
        let failure = match written {
            Some(Err(e)) => Some(e),
            _ => failure,
        };
        if let Some(e) = failure {
            *self.global.write().unwrap_or_else(|e| e.into_inner()) = old.clone();
            for (mut entry, sharing, before, after) in changed.into_iter().rev() {
                let uid = entry.uid;
                let forward = entry.clone();
                if let Err(e) = self.run(&mut entry, &sharing, Change::Update(&after, &before)) {
                    eprintln!("bbd: sandbox: cannot put uid {uid}'s items back: {e:#}");
                    continue;
                }
                // What going back changed (modes saved anew) is kept.
                if entry != forward
                    && let Err(e) = writer.put_sandbox(&entry)
                {
                    eprintln!("bbd: sandbox: cannot write uid {uid}'s entry: {e:#}");
                }
                self.snapshot_write().insert(uid, entry);
            }
            // Left covering both if it cannot be written back.
            if covering != old
                && let Err(e) = write_global(&old)
            {
                eprintln!("bbd: sandbox: cannot write the global settings back: {e:#}");
            }
            return Err(e.context("the global settings are as they were"));
        }
        Ok(self.global_items())
    }

    /// The uid of `package` for Android user `user`, or, for an app
    /// uninstalled while it was sandboxed, the uid of its entry.
    fn uid_of_entry(
        &self,
        writer: &Writer,
        packages: &mut Packages,
        package: &str,
        user: u32,
    ) -> anyhow::Result<u32> {
        match uid_of(packages, package, user) {
            Ok(uid) => Ok(uid),
            Err(e) => writer
                .sandbox_entries()?
                .into_iter()
                .find(|entry| entry.package == package && entry.uid / PER_USER_RANGE == user)
                .map(|entry| entry.uid)
                .ok_or(e),
        }
    }
}

/// The uid `package` runs under for Android user `user`, if it is an app
/// that can be sandboxed.
fn uid_of(packages: &mut Packages, package: &str, user: u32) -> anyhow::Result<u32> {
    // What was installed or removed since the list was last read counts:
    // a name could otherwise lead to the uid of another app.
    anyhow::ensure!(packages.refresh(), "the package list cannot be read");
    let app_id = packages
        .app_id_of(package)
        .with_context(|| format!("{package} is not installed"))?;
    anyhow::ensure!(
        app_id >= FIRST_APPLICATION_UID,
        "{package} runs as a system uid ({app_id}) and cannot be sandboxed"
    );
    anyhow::ensure!(user <= LAST_USER, "no such user: {user}");
    Ok(user * PER_USER_RANGE + app_id)
}

/// A new ANDROID_ID: 64 random bits as 16 lowercase hex digits, the form
/// Android gives it.
fn new_android_id() -> anyhow::Result<String> {
    let bytes = random_bytes::<8>().context("reading /dev/urandom")?;
    Ok(format!("{:016x}", u64::from_ne_bytes(bytes)))
}

/// `N` random bytes, from the kernel's generator.
fn random_bytes<const N: usize>() -> std::io::Result<[u8; N]> {
    let mut bytes = [0; N];
    File::open("/dev/urandom")?.read_exact(&mut bytes)?;
    Ok(bytes)
}

/// The app ops helper's lines that give every app in the sandbox its app
/// ops and the permissions it may have been taken back, read from the
/// database without the daemon: for `uninstall.sh`,
/// which may run before the daemon has started, or after it failed to take
/// every app out.
pub fn restore_lines(db: &Path) -> anyhow::Result<Vec<String>> {
    let reader = crate::store::Reader::open(db)?;
    let global = Settings::parse(&reader.sandbox_global()?);
    let mut lines = Vec::new();
    for entry in reader.sandbox_entries()?.iter().filter(|e| e.enabled) {
        if let Some(command) = appops::restore_line(entry, &items_of(entry, &global)) {
            anyhow::ensure!(
                entry.install_id.len() == 32
                    && entry.install_id.bytes().all(|c| c.is_ascii_hexdigit()),
                "recovery record has no verified installation"
            );
            lines.push(format!(
                "bound {} {} {command}",
                entry.package, entry.install_id
            ));
        }
    }
    Ok(lines)
}

// ---- command line ----------------------------------------------------------

/// Send `request` to the daemon and print its answer: as the JSON line the
/// daemon sent with `json`, otherwise for a person to read.
pub fn command(db: &Path, request: Request, json: bool) -> anyhow::Result<()> {
    let answer = control::command(db, &request)?;
    if json {
        println!("{answer}");
        return Ok(());
    }
    let mut out = std::io::stdout().lock();
    match request {
        Request::SandboxEnable { package, .. } => {
            let enabled: Enabled = serde_json::from_value(answer)?;
            print_enabled(&mut out, &package, &enabled)
        }
        Request::SandboxDisable { package, .. } => {
            print_disabled(&mut out, &package, &serde_json::from_value(answer)?)
        }
        Request::SandboxDisableAll => {
            let all: DisabledAll = serde_json::from_value(answer)?;
            writeln!(out, "Sandbox disabled for {} apps.", all.disabled)?;
            if !all.failed.is_empty() {
                writeln!(out, "Still sandboxed: {} apps.", all.failed.len())?;
            }
            Ok(())
        }
        Request::SandboxList => print_list(&mut out, &serde_json::from_value(answer)?),
        Request::SandboxStatus => print_status(&mut out, &serde_json::from_value(answer)?),
        Request::SandboxSet { package, .. } => {
            print_configured(&mut out, &package, &serde_json::from_value(answer)?)
        }
        Request::SandboxGlobal | Request::SandboxSetGlobal { .. } => {
            let global: GlobalItems = serde_json::from_value(answer)?;
            print_items(&mut out, &global.items, |_| "")?;
            writeln!(out)?;
            auto::print(&mut out, global.auto)
        }
        Request::SandboxAuto | Request::SandboxSetAuto { .. } => {
            let switch: auto::Switch = serde_json::from_value(answer)?;
            auto::print(&mut out, switch.auto)
        }
        Request::NetBlock { .. } | Request::NetUnblock { .. } => writeln!(out, "Done."),
        Request::NetClear { .. } => writeln!(out, "Deleted {} rows.", answer["deleted"]),
    }?;
    Ok(())
}

fn print_enabled(
    out: &mut impl std::io::Write,
    package: &str,
    enabled: &Enabled,
) -> std::io::Result<()> {
    writeln!(out, "Sandbox enabled for {package} (uid {}).", enabled.uid)?;
    if !enabled.also.is_empty() {
        writeln!(
            out,
            "Also sandboxed, sharing its uid: {}.",
            enabled.also.join(", ")
        )?;
    }
    Ok(())
}

fn print_disabled(
    out: &mut impl std::io::Write,
    package: &str,
    disabled: &Disabled,
) -> std::io::Result<()> {
    if disabled.was_enabled {
        writeln!(out, "Sandbox disabled for {package}.")
    } else {
        writeln!(out, "{package} was not in the sandbox.")
    }
}

fn print_list(out: &mut impl std::io::Write, listed: &Listed) -> std::io::Result<()> {
    if listed.apps.is_empty() {
        return writeln!(out, "No apps in the sandbox.");
    }
    writeln!(out, "{:<10} {:<9} PACKAGE", "UID", "STATE")?;
    for app in &listed.apps {
        let state = if app.enabled { "enabled" } else { "disabled" };
        // What is not as an item is unless set otherwise.
        let not_on: Vec<String> = app
            .items
            .iter()
            .filter(|(item, mode)| *mode != item.default_mode())
            .map(|(item, mode)| format!("{} {}", item.name(), items::mode_name(mode)))
            .collect();
        let not_on = if not_on.is_empty() {
            String::new()
        } else {
            format!(" ({})", not_on.join(", "))
        };
        writeln!(out, "{:<10} {state:<9} {}{not_on}", app.uid, app.package)?;
        for (item, parts) in &app.unmet {
            let parts: Vec<&str> = parts.iter().map(|part| part.name()).collect();
            writeln!(
                out,
                "{:<20} {} not in effect, missing {}",
                "",
                item.name(),
                parts.join(", ")
            )?;
        }
    }
    Ok(())
}

/// Each item with its mode, and what `note` says of it.
fn print_items(
    out: &mut impl std::io::Write,
    items: &Settings,
    note: impl Fn(Item) -> &'static str,
) -> std::io::Result<()> {
    for (item, mode) in items.iter() {
        writeln!(
            out,
            "{:<20} {}{}",
            item.name(),
            items::mode_name(mode),
            note(item)
        )?;
    }
    Ok(())
}

fn print_configured(
    out: &mut impl std::io::Write,
    package: &str,
    configured: &Configured,
) -> std::io::Result<()> {
    writeln!(out, "Sandbox items for {package} (uid {}):", configured.uid)?;
    print_items(out, &configured.items, |item| {
        if configured.settings.get(item).is_some() {
            ""
        } else {
            " (global)"
        }
    })
}

fn print_status(out: &mut impl std::io::Write, status: &Status) -> std::io::Result<()> {
    let on = |active: bool| if active { "active" } else { "inactive" };
    writeln!(out, "Storage gate:      {}", on(status.gate))?;
    writeln!(out, "App ops:           {}", on(status.appops))?;
    writeln!(out, "Packet filter:     {}", on(status.network))?;
    writeln!(out, "ANDROID_ID hook:   {}", on(status.hook.android_id))?;
    writeln!(out, "App list hook:     {}", on(status.hook.app_list))?;
    writeln!(out, "File grants hook:  {}", on(status.hook.uri_grants))?;
    writeln!(out, "Ad ID hook:        {}", on(status.hook.ad_id))?;
    writeln!(out, "Starts admitted:   {}", status.admitted)?;
    writeln!(out, "Starts failed:     {}", status.failed)?;
    writeln!(out, "New apps held:     {}", on(status.new_apps_held))?;
    writeln!(out, "New apps stopped:  {}", status.new_apps_stopped)?;
    writeln!(out, "New apps pending:  {}", status.new_apps_pending)?;
    writeln!(out, "Hold time p95:     {} us", status.hold_p95_us)
}

#[cfg(test)]
mod tests {
    use std::{cell::RefCell, path::PathBuf, rc::Rc};

    use super::*;

    #[test]
    fn taking_every_app_out_answers_before_the_client_gives_up() {
        assert!(!DISABLE_ALL_WAIT.is_zero());
        assert!(DISABLE_ALL_WAIT + appops::ANSWER_WAIT < control::COMMAND_WAIT);
    }

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
            let list = dir.join("packages.list");
            std::fs::write(
                &list,
                "com.app 10100 0 /data/user/0/com.app default none\n\
                 com.shared.one 10200 0 /d x\n\
                 com.shared.two 10200 0 /d x\n\
                 com.android.phone 1001 0 /d x\n\
                 li.mercury.bigbrother 10300 0 /d x\n\
                 li.mercury.bigbrother.canary 10301 0 /d x\n",
            )
            .unwrap();
            let packages = Packages::new(list.to_str().unwrap());
            let writer = Writer::open(&dir.join("bb.db")).unwrap();
            Self {
                dir,
                writer,
                packages,
            }
        }

        fn sandbox(&self) -> Sandbox {
            self.sandbox_with(Vec::new())
        }

        fn sandbox_with(&self, effects: Vec<Box<dyn Effect>>) -> Sandbox {
            Sandbox::load(&self.writer, effects, None).unwrap()
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    /// Records what it was asked to do, in a log shared by several, and
    /// fails when told to.
    struct Recorder {
        name: &'static str,
        log: Rc<RefCell<Vec<String>>>,
        fail_enable: bool,
        fail_disable: bool,
        fail_update: bool,
    }

    impl Recorder {
        fn new(name: &'static str, log: &Rc<RefCell<Vec<String>>>) -> Box<Self> {
            Box::new(Self {
                name,
                log: log.clone(),
                fail_enable: false,
                fail_disable: false,
                fail_update: false,
            })
        }
    }

    impl Effect for Recorder {
        fn name(&self) -> &'static str {
            self.name
        }

        fn enable(&mut self, entry: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
            anyhow::ensure!(!self.fail_enable, "refused");
            self.log.borrow_mut().push(format!("+{}", self.name));
            entry.saved_ops = format!("{}=1", self.name);
            Ok(())
        }

        fn disable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
            anyhow::ensure!(!self.fail_disable, "refused");
            self.log.borrow_mut().push(format!("-{}", self.name));
            Ok(())
        }

        fn update(
            &mut self,
            _: &mut Entry,
            _: &[String],
            _: &Settings,
            _: &Settings,
        ) -> anyhow::Result<()> {
            anyhow::ensure!(!self.fail_update, "refused");
            self.log.borrow_mut().push(format!("*{}", self.name));
            Ok(())
        }

        fn resume(&mut self, apps: &[Resumed]) {
            for app in apps {
                self.log
                    .borrow_mut()
                    .push(format!("~{} {}", self.name, app.entry.uid));
            }
        }

        fn report(&self, status: &mut Status) {
            status.gate = true;
            status.gated_uids.insert(10100);
        }

        /// Finds every entry's permission granted.
        fn found(&mut self, entries: &mut HashMap<u32, Entry>) -> Vec<u32> {
            let mut changed = Vec::new();
            for entry in entries.values_mut() {
                entry.saved_permissions = format!("{}=granted", self.name);
                changed.push(entry.uid);
            }
            changed
        }
    }

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

        fn enable(
            &mut self,
            entry: &mut Entry,
            _: &[String],
            items: &Settings,
        ) -> anyhow::Result<()> {
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

    fn enabled(sandbox: &Sandbox) -> Vec<u32> {
        let mut uids: Vec<u32> = sandbox.snapshot_read().keys().copied().collect();
        uids.sort();
        uids
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
                &camera(Some(Mode::Off)),
            )
            .unwrap();
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
    fn what_an_app_is_held_to_is_named_as_the_daemon_starts() {
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
        assert_eq!(
            named,
            format!(
                "{}=default,{}=default",
                items::NOTIFICATION_ACCESS,
                items::SCREEN_ACCESS
            )
        );
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

        // A change that fails is written down as it was again.
        let log = Rc::new(RefCell::new(Vec::new()));
        let mut failing = Recorder::new("gate", &log);
        failing.fail_update = true;
        let mut sandbox = f.sandbox_with(vec![witness(), failing]);
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
                    &global(Item::Microphone, Mode::On)
                )
                .is_err()
        );
        assert_eq!(
            f.writer.sandbox_global().unwrap(),
            "camera=on,microphone=off"
        );
        // Both were seen covering the change while it ran.
        assert!(
            seen.borrow()[1..]
                .iter()
                .all(|seen| held(seen).contains("android:record_audio"))
        );
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
        let location = |mode| BTreeMap::from([(Item::Location, Some(mode))]);
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

        // Precise again: given back, and no longer named.
        sandbox
            .configure(
                &f.writer,
                &mut f.packages,
                "com.app",
                0,
                &location(Mode::On),
            )
            .unwrap();
        assert_eq!(row(10100), "");
        // Coarse from on, whose covering mode takes no permission: named
        // all the same.
        seen.borrow_mut().clear();
        sandbox
            .configure(
                &f.writer,
                &mut f.packages,
                "com.app",
                0,
                &location(Mode::Coarse),
            )
            .unwrap();
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

        // A change that fails is written down as it was.
        let log = Rc::new(RefCell::new(Vec::new()));
        let mut failing = Recorder::new("gate", &log);
        failing.fail_update = true;
        let mut sandbox = f.sandbox_with(vec![taker(), failing]);
        sandbox
            .configure(
                &f.writer,
                &mut f.packages,
                "com.app",
                0,
                &location(Mode::On),
            )
            .unwrap_err();
        assert_eq!(row(10100), granted);
        sandbox
            .set_global(&f.writer, &mut f.packages, &global(Mode::On))
            .unwrap_err();
        assert_eq!(row(10200), granted);
        sandbox
            .configure(
                &f.writer,
                &mut f.packages,
                "com.shared.one",
                0,
                &location(Mode::On),
            )
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
    fn the_restore_lines_give_back_the_ops_and_the_permissions_taken() {
        let f = Fixture::new("sandbox-restore-lines");
        let entry = |uid, enabled, settings: &str, saved_permissions: &str| Entry {
            uid,
            package: "com.app".into(),
            enabled,
            android_id: "0123456789abcdef".into(),
            saved_ops: "android:camera=4".into(),
            install_id: "a".repeat(32),
            saved_permissions: saved_permissions.into(),
            created: 1000,
            settings: settings.into(),
        };
        let fine = items::PRECISE_LOCATION;
        let off: String = Item::ALL
            .iter()
            .map(|item| format!("{}=off", item.name()))
            .collect::<Vec<_>>()
            .join(",");
        f.writer.put_sandbox_global(&[("location", "off")]).unwrap();
        // Coarse, and the state the permission had saved.
        f.writer
            .put_sandbox(&entry(
                10100,
                true,
                &format!("{off},location=coarse"),
                &format!("{fine}=denied+user_set"),
            ))
            .unwrap();
        // Written down as the camera only while location changed from
        // coarse: the permission is given back all the same.
        f.writer
            .put_sandbox(&entry(
                10200,
                true,
                &format!("{off},camera=on"),
                &format!("{fine}=default"),
            ))
            .unwrap();
        // Every item off, and nothing taken: nothing to give back.
        f.writer.put_sandbox(&entry(10300, true, &off, "")).unwrap();
        // Out of the sandbox already.
        f.writer
            .put_sandbox(&entry(10400, false, "location=coarse", ""))
            .unwrap();
        assert_eq!(
            restore_lines(&f.dir.join("bb.db")).unwrap(),
            [
                format!(
                    "bound com.app aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa restore 10100 {fine}=denied+user_set"
                ),
                format!(
                    "bound com.app aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa restore 10200 android:camera=4,{fine}=default"
                ),
            ]
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
            ask(
                r#"{"cmd":"sandbox.set","package":"com.shared.one","settings":{"storage":"coarse"}}"#
            ),
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
        assert_eq!(
            ask(r#"{"cmd":"sandbox.status"}"#),
            serde_json::json!({
                "ok": true, "gate": false, "appops": false, "network": false,
                "net_record": false, "dns_names": false, "traffic": false,
                "hook": {"android_id": false, "app_list": false, "uri_grants": false, "ad_id": false},
                "admitted": 0, "failed": 0, "new_apps_stopped": 0, "new_apps_pending": 0,
                "new_apps_held": false, "hold_p95_us": 0,
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

    #[test]
    fn answers_are_printed_for_a_person() {
        let mut out = Vec::new();
        print_enabled(
            &mut out,
            "com.shared.one",
            &Enabled {
                uid: 10200,
                also: vec!["com.shared.two".into()],
            },
        )
        .unwrap();
        print_list(
            &mut out,
            &Listed {
                apps: vec![
                    ListedApp {
                        uid: 10100,
                        package: "com.app".into(),
                        enabled: false,
                        created: 0,
                        settings: Settings::default(),
                        items: Settings::default(),
                        unmet: BTreeMap::new(),
                        network_rules: 0,
                        recording_since: None,
                    },
                    ListedApp {
                        uid: 10200,
                        package: "com.shared.one".into(),
                        enabled: true,
                        created: 0,
                        settings: Settings::parse("camera=off"),
                        items: Settings::parse("camera=off")
                            .over(&Settings::parse("location=coarse")),
                        unmet: BTreeMap::from([(
                            Item::DeviceIds,
                            vec![Part::AppOps, Part::AndroidIdHook],
                        )]),
                        network_rules: 0,
                        recording_since: None,
                    },
                ],
            },
        )
        .unwrap();
        print_list(&mut out, &Listed { apps: vec![] }).unwrap();
        assert_eq!(
            String::from_utf8(out).unwrap(),
            "Sandbox enabled for com.shared.one (uid 10200).\n\
             Also sandboxed, sharing its uid: com.shared.two.\n\
             UID        STATE     PACKAGE\n\
             10100      disabled  com.app\n\
             10200      enabled   com.shared.one (location coarse, camera off)\n\
             \x20                    device_ids not in effect, missing app_ops, android_id_hook\n\
             No apps in the sandbox.\n"
        );
    }
}
