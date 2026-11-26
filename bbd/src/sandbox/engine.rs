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

use std::{
    collections::{BTreeMap, BTreeSet, HashMap, VecDeque},
    path::{Path, PathBuf},
    sync::{Arc, RwLock},
    time::{Duration, Instant},
};

use anyhow::Context as _;
use bb_common::{FIRST_APPLICATION_UID, PER_USER_RANGE};

use crate::{
    control::{self, Request},
    packages::{Packages, app_id_of, user_of},
    store::{AppOpsUndo, Writer},
    sync::{lock, read, write},
};

use super::{
    Effect, Entry,
    answers::{Disabled, DisabledAll, Enabled, GlobalItems},
    appops, auto, destinations, dirs,
    effect::Change,
    identity,
    items::Settings,
    target::Target,
};

mod listing;
mod transactions;

/// Packages that are never sandboxed: the app, whose own reads of the
/// device would be cut off from what it reports on. The canary test app
/// (`li.mercury.bigbrother.canary`) is not among them; it is what the
/// sandbox is tested with.
pub(super) const OWN_PACKAGES: &[&str] = &["li.mercury.bigbrother"];

/// The highest Android user whose uids still fit the 31 bits Android keeps
/// them in.
pub(super) const LAST_USER: u32 = (i32::MAX as u32 - (PER_USER_RANGE - 1)) / PER_USER_RANGE;

#[derive(PartialEq, Eq)]
enum AppOpsUndoState {
    Pending,
    // A confirmed zero-write rejection whose durable journal could not yet be deleted.
    Unused,
}

/// How long after it began taking every app out (or carrying out a change
/// of the global settings) an app may still be started on. It is checked
/// before each app, leaving time for two AppOps requests, the hook's
/// acknowledgement, and the command's answer.
pub(super) const DISABLE_ALL_WAIT: std::time::Duration = control::BULK_COMMAND_WAIT
    .saturating_sub(appops::ANSWER_WAIT.saturating_mul(2))
    .saturating_sub(identity::ACK_WAIT)
    .saturating_sub(transactions::STALE_RETRY_WINDOW)
    .saturating_sub(Duration::from_secs(1));

/// The sandboxed uids and their entries, enabled ones only.
pub type Snapshot = Arc<RwLock<HashMap<u32, Entry>>>;

/// The global settings: the mode of each item of an app that has none of
/// its own.
pub type Global = Arc<RwLock<Settings>>;

/// What applies to `entry`: its own settings over the global ones.
pub fn items_of(entry: &Entry, global: &Settings) -> Settings {
    Settings::parse(&entry.settings).over(global)
}

pub struct Sandbox {
    pub(super) awaiting_storage: BTreeSet<u32>,
    unsaved_permissions: BTreeSet<u32>,
    next_found_save: Option<Instant>,
    appops_undo: BTreeMap<u32, AppOpsUndoState>,
    next_recovery: Option<Instant>,
    last_recovery: u32,
    snapshot: Snapshot,
    pub(super) global: Global,
    pub(super) effects: Vec<Box<dyn Effect>>,
    /// Where each Android user's apps keep their data, a directory per user
    /// and package (`/data/user_de`, which can be read before the user
    /// unlocks the device): how an app is known to be installed for a user.
    /// Not checked when `None`.
    pub(super) user_data: Option<PathBuf>,
    pub(super) auto: auto::Auto,
    /// When an entry that could not be taken out was last logged.
    pub(super) release_logged: Option<std::time::Instant>,
    /// The destinations apps are kept from, as written.
    pub(super) book: destinations::Book,
    /// Apps whose previous network rules could not be restored completely.
    pub(super) pending_net_refresh: VecDeque<u32>,
    pub(super) net_retry_at: Option<Instant>,
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
            let mut targets = lock(&book);
            for rule in writer.net_rules()? {
                if let Some(target) = Target::parse(&rule.target) {
                    targets.entry(rule.uid).or_default().insert(target);
                }
            }
        }
        Ok(Self {
            book,
            pending_net_refresh: VecDeque::new(),
            net_retry_at: None,
            snapshot: Arc::new(RwLock::new(enabled)),
            global: Arc::new(RwLock::new(global)),
            effects,
            user_data: user_data.map(Path::to_path_buf),
            auto: auto::Auto::load(writer)?,
            release_logged: None,
            awaiting_storage: BTreeSet::new(),
            unsaved_permissions: BTreeSet::new(),
            next_found_save: None,
            appops_undo: writer
                .appops_undo()?
                .into_iter()
                .map(|undo| (undo.uid, AppOpsUndoState::Pending))
                .collect(),
            next_recovery: None,
            last_recovery: 0,
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

    pub(super) fn global_read(&self) -> std::sync::RwLockReadGuard<'_, Settings> {
        read(&self.global)
    }

    /// What applies to `entry`.
    pub(super) fn items(&self, entry: &Entry) -> Settings {
        items_of(entry, &self.global_read())
    }

    /// Add an effect, after those there are.
    pub fn add_effect(&mut self, effect: Box<dyn Effect>) {
        self.effects.push(effect);
    }

    pub(super) fn snapshot_read(&self) -> std::sync::RwLockReadGuard<'_, HashMap<u32, Entry>> {
        // A reader that panicked cannot have left the map half changed.
        read(&self.snapshot)
    }

    pub(super) fn snapshot_write(&self) -> std::sync::RwLockWriteGuard<'_, HashMap<u32, Entry>> {
        write(&self.snapshot)
    }

    /// Keep in the entries, and write, what the effects found changed by
    /// the user while the apps were sandboxed (`Effect::found`). Commands
    /// also give the effects a chance to save these before restoring grants.
    pub fn keep_found(&mut self, writer: &Writer) {
        let pending = std::mem::take(&mut self.unsaved_permissions);
        let to_write: Vec<_> = {
            let mut entries = write(&self.snapshot);
            let mut changed = BTreeSet::new();
            for effect in &mut self.effects {
                changed.extend(effect.found(&mut entries));
            }
            pending
                .union(&changed)
                .filter_map(|uid| entries.get(uid).cloned())
                .collect()
        };
        // Readers need the snapshot while the durable write waits for SQLite.
        for entry in to_write {
            let uid = entry.uid;
            if let Err(e) = writer.put_sandbox(&entry) {
                self.unsaved_permissions.insert(uid);
                if !pending.contains(&uid) {
                    eprintln!("bbd: sandbox: cannot write what was found for uid {uid}: {e:#}");
                }
            } else {
                for effect in &mut self.effects {
                    effect.found_written(uid);
                }
            }
        }
        self.next_found_save = (!self.unsaved_permissions.is_empty())
            .then(|| Instant::now() + Duration::from_secs(30));
    }

    pub fn next_found_save(&self) -> Option<Instant> {
        self.next_found_save
    }

    /// Answer a request line from `bbd.ctl`.
    pub fn answer(&mut self, line: &str, writer: &Writer, packages: &mut Packages) -> String {
        let request = match Request::parse(line) {
            Ok(request) => request,
            Err(e) => return control::failure(&e),
        };
        self.keep_found(writer);
        if !self.appops_undo.is_empty() {
            match &request {
                Request::SandboxEnable { package, user }
                | Request::SandboxDisable { package, user }
                | Request::SandboxSet { package, user, .. } => {
                    if let Ok(uid) = self.uid_of_entry(writer, packages, package, *user) {
                        self.retry_appops(writer, uid);
                    }
                }
                Request::SandboxDisableAll => {
                    for uid in self.appops_undo.keys().copied().collect::<Vec<_>>() {
                        self.retry_appops(writer, uid);
                    }
                }
                _ => {}
            }
        }
        if !matches!(request, Request::SandboxStatus) {
            self.release_reassigned(writer, packages);
        }
        let answer = match request {
            Request::SandboxEnable { package, user } => self
                .enable(writer, packages, &package, user, crate::clock::now())
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
        let enabled = self.enable_uid(writer, packages, package, uid, now)?;
        self.auto_enabled(writer, uid, package);
        Ok(enabled)
    }

    pub(super) fn enable_uid(
        &mut self,
        writer: &Writer,
        packages: &Packages,
        package: &str,
        uid: u32,
        now: i64,
    ) -> anyhow::Result<Enabled> {
        self.require_recovered(uid)?;
        let sharing = packages.packages_of(app_id_of(uid)).to_vec();
        anyhow::ensure!(
            !sharing.iter().any(|p| OWN_PACKAGES.contains(&p.as_str())),
            "Big Brother cannot sandbox itself"
        );
        let also = sharing.iter().filter(|p| *p != package).cloned().collect();
        let before = writer.sandbox_entry(uid)?;
        let recorded = writer.sandbox_packages(uid)?;
        let (owner, install_id) =
            self.verify_install(package, uid, before.as_ref(), &sharing, &recorded)?;
        if let Some(held) = self.snapshot_read().get(&uid) {
            if !owner.as_ref().is_some_and(|owner| owner == &held.package) || !self.owns(held) {
                return Err(auto::Transient(anyhow::anyhow!(
                    "the uid still has an unverified sandbox entry"
                ))
                .into());
            }
            return Ok(Enabled { uid, also });
        }

        let mut entry = build_entry(package, uid, now, before.as_ref(), owner, install_id)?;
        let items = self.items(&entry);
        if let Some(root) = &self.user_data {
            for sibling in sharing.iter().filter(|sibling| *sibling != &entry.package) {
                let _ = dirs::share_install(root, sibling, uid, &entry.install_id);
            }
        }
        let change = Change::Enable(&items);
        self.stage_and_apply(writer, &mut entry, &sharing, change, true)?;
        if let Err(error) = writer.commit_sandbox(&[&entry], None) {
            self.snapshot_write().remove(&uid);
            self.reverse_completed(&mut entry, &sharing, change);
            return Err(error);
        }
        self.complete_change(&entry, Some(&items));
        self.record_packages(writer, uid, &entry, &sharing, before.as_ref(), recorded);
        self.snapshot_write().insert(uid, entry);
        Ok(Enabled { uid, also })
    }

    fn verify_install(
        &self,
        package: &str,
        uid: u32,
        before: Option<&Entry>,
        sharing: &[String],
        recorded: &BTreeSet<String>,
    ) -> anyhow::Result<(Option<String>, String)> {
        let user = user_of(uid);
        let verify_error = || format!("cannot verify the installation for user {user}");
        let owner = match before {
            Some(entry) => self
                .existing_owner(entry, sharing, recorded)
                .with_context(verify_error)?,
            None => None,
        };
        // Even when another package owns the sandbox, the requested package
        // must belong to this UID and be installed for the requested user.
        let install_id = match &self.user_data {
            Some(root) => {
                if let (Some(old), Some(_)) = (before, &owner) {
                    dirs::share_install(root, package, uid, &old.install_id)
                        .with_context(verify_error)?;
                    old.install_id.clone()
                } else {
                    dirs::install_id(root, package, uid, true).with_context(verify_error)?
                }
            }
            None => package.to_owned(),
        };
        Ok((owner, install_id))
    }

    pub(super) fn require_recovered(&self, uid: u32) -> anyhow::Result<()> {
        anyhow::ensure!(
            !self.appops_undo.contains_key(&uid),
            "Android restrictions are recovering"
        );
        Ok(())
    }

    fn retry_appops(&mut self, writer: &Writer, uid: u32) {
        if self.appops_undo.contains_key(&uid) {
            match writer.retry_appops_recovery(uid) {
                Ok(()) => self.next_recovery = Some(Instant::now()),
                Err(error) => self.log_reconcile_error(uid, &error),
            }
        }
    }

    pub fn next_appops_recovery(&self) -> Option<Instant> {
        self.next_recovery
    }

    pub(super) fn suspend_pending_appops(&mut self) {
        for uid in self.appops_undo.keys() {
            for effect in &mut self.effects {
                effect.suspend(*uid);
            }
        }
        if !self.appops_undo.is_empty() {
            self.next_recovery = Some(Instant::now());
        }
    }

    pub fn recover_appops(&mut self, writer: &Writer, packages: &mut Packages) {
        self.next_recovery = None;
        if self.appops_undo.is_empty() {
            return;
        }
        let deadline = Instant::now() + appops::ANSWER_WAIT;
        let mut failed = false;
        if packages.refresh() && !packages.is_empty() {
            let mut pending = match writer.appops_undo() {
                Ok(pending) => pending,
                Err(error) => {
                    self.log_reconcile_error(0, &error);
                    self.next_recovery = Some(Instant::now() + Duration::from_secs(10));
                    return;
                }
            };
            let next = pending.partition_point(|undo| undo.uid <= self.last_recovery);
            if !pending.is_empty() {
                let count = pending.len();
                pending.rotate_left(next % count);
            }
            for undo in pending {
                if Instant::now() >= deadline {
                    break;
                }
                self.last_recovery = undo.uid;
                match self.recover_appops_entry(writer, packages, &undo) {
                    Ok(()) => {
                        self.appops_undo.remove(&undo.uid);
                    }
                    Err(error) => {
                        failed = true;
                        self.log_reconcile_error(undo.uid, &error);
                    }
                }
            }
        } else {
            failed = true;
        }
        if !self.appops_undo.is_empty() {
            let delay = if failed {
                Duration::from_secs(10)
            } else {
                Duration::ZERO
            };
            self.next_recovery = Some(Instant::now() + delay);
        }
    }

    fn recover_appops_entry(
        &mut self,
        writer: &Writer,
        packages: &Packages,
        undo: &AppOpsUndo,
    ) -> anyhow::Result<()> {
        let uid = undo.uid;
        let committed = writer.sandbox_entry(uid)?;
        let mut owner = committed
            .clone()
            .filter(|entry| entry.install_id == undo.install_id)
            .unwrap_or_else(|| Entry {
                uid,
                package: undo.package.clone(),
                install_id: undo.install_id.clone(),
                enabled: false,
                android_id: String::new(),
                saved_ops: String::new(),
                saved_permissions: String::new(),
                created: 0,
                settings: String::new(),
            });
        let current = packages.packages_of(app_id_of(uid));
        let candidates = current.iter().cloned().collect();
        if self.appops_undo.get(&uid) != Some(&AppOpsUndoState::Unused)
            && let Some(package) = self
                .existing_owner(&owner, current, &candidates)
                .context("cannot verify the installation for Android restriction recovery")?
        {
            owner.package = package;
            let undo = AppOpsUndo {
                package: owner.package.clone(),
                ..undo.clone()
            };
            for effect in &mut self.effects {
                effect.restore_preimage(&undo, writer)?;
            }
        }
        writer.clear_appops_undo(uid)?;
        let current = self.snapshot_read().get(&uid).cloned();
        let items = current
            .as_ref()
            .filter(|entry| self.owns(entry))
            .map(|entry| self.items(entry));
        self.finish_effects(current.as_ref().unwrap_or(&owner), items.as_ref());
        Ok(())
    }

    fn record_packages(
        &mut self,
        writer: &Writer,
        uid: u32,
        entry: &Entry,
        sharing: &[String],
        before: Option<&Entry>,
        recorded: BTreeSet<String>,
    ) {
        let previous_packages = if before.is_some_and(|old| old.install_id == entry.install_id) {
            recorded
        } else {
            BTreeSet::new()
        };
        if let Err(e) = self.remember_packages(writer, entry, sharing, &previous_packages) {
            self.log_reconcile_error(uid, &e);
        }
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
        self.require_recovered(uid)?;
        let Some(mut entry) = self.snapshot_read().get(&uid).cloned() else {
            return Ok(Disabled { was_enabled: false });
        };
        let items = self.items(&entry);
        let enabled = entry.clone();
        entry.enabled = false;
        let change = Change::Disable(&items);
        self.stage_and_apply(writer, &mut entry, sharing, change, true)?;
        if let Err(error) = writer.commit_sandbox(&[&entry], None) {
            self.snapshot_write().insert(uid, enabled);
            self.reverse_completed(&mut entry, sharing, change);
            return Err(error);
        }
        self.complete_change(&entry, None);
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
    pub(super) fn run(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        change: Change,
    ) -> anyhow::Result<()> {
        for done in 0..self.effects.len() {
            let effect = &mut self.effects[done];
            let owns = self.user_data.as_deref().is_none_or(|root| {
                dirs::matches_install(root, &entry.package, entry.uid, &entry.install_id)
                    .unwrap_or(false)
            });
            let result = if owns {
                change.apply(effect.as_mut(), entry, packages)
            } else {
                Err(anyhow::anyhow!("the installation has changed"))
            };
            if let Err(error) = result {
                let name = effect.name();
                self.reverse_effects(done, entry, packages, change);
                return Err(error.context(name));
            }
        }
        Ok(())
    }

    pub(super) fn reverse_completed(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        change: Change,
    ) {
        self.reverse_effects(self.effects.len(), entry, packages, change);
    }

    fn reverse_effects(
        &mut self,
        done: usize,
        entry: &mut Entry,
        packages: &[String],
        change: Change,
    ) {
        for index in (0..done).rev() {
            if !self.owns(entry) {
                break;
            }
            let effect = &mut self.effects[index];
            if effect.restores_from_preimage() {
                continue;
            }
            if let Err(undo) = change.reversed().apply(effect.as_mut(), entry, packages) {
                eprintln!(
                    "bbd: sandbox: cannot undo {} for uid {}: {undo:#}",
                    effect.name(),
                    entry.uid
                );
            }
        }
    }

    /// Every item, with its global mode.
    pub(super) fn global_items(&self) -> GlobalItems {
        GlobalItems {
            items: self.global_read().over(&Settings::default()),
            auto: self.auto_switch().auto,
        }
    }

    /// The uid of `package` for Android user `user`, or, for an app
    /// uninstalled while it was sandboxed, the uid of its entry.
    pub(super) fn uid_of_entry(
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
                .find(|entry| entry.package == package && user_of(entry.uid) == user)
                .map(|entry| entry.uid)
                .ok_or(e),
        }
    }
}

fn build_entry(
    package: &str,
    uid: u32,
    now: i64,
    before: Option<&Entry>,
    owner: Option<String>,
    install_id: String,
) -> anyhow::Result<Entry> {
    Ok(match (before, owner) {
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
    })
}

/// The uid `package` runs under for Android user `user`, if it is an app
/// that can be sandboxed.
pub(super) fn uid_of(packages: &mut Packages, package: &str, user: u32) -> anyhow::Result<u32> {
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
pub(super) fn new_android_id() -> anyhow::Result<String> {
    let bytes = dirs::random_bytes::<8>().context("reading /dev/urandom")?;
    Ok(format!("{:016x}", u64::from_ne_bytes(bytes)))
}

#[cfg(test)]
mod tests;
