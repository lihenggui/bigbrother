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
};

use anyhow::Context as _;
use bb_common::{FIRST_APPLICATION_UID, PER_USER_RANGE};

use crate::{
    control::{self, Request},
    packages::{Packages, app_id_of, user_of},
    store::Writer,
    sync::{lock, read, write},
};

use super::{
    Effect, Entry, Status,
    answers::{Disabled, DisabledAll, Enabled, GlobalItems, Listed, ListedApp},
    appops, auto, destinations, dirs,
    effect::Change,
    items::{InPlace, Settings},
    target::Target,
};

/// Packages that are never sandboxed: the app, whose own reads of the
/// device would be cut off from what it reports on. The canary test app
/// (`li.mercury.bigbrother.canary`) is not among them; it is what the
/// sandbox is tested with.
pub(super) const OWN_PACKAGES: &[&str] = &["li.mercury.bigbrother"];

/// The highest Android user whose uids still fit the 31 bits Android keeps
/// them in.
pub(super) const LAST_USER: u32 = (i32::MAX as u32 - (PER_USER_RANGE - 1)) / PER_USER_RANGE;

/// How long after it began taking every app out (or carrying out a change
/// of the global settings) an app may still be started on. It is checked
/// before each app, and one app can then wait for the app ops helper for
/// up to `appops::ANSWER_WAIT`: with a second to spare, the answer still
/// reaches the client before it gives up (`control::COMMAND_WAIT`).
pub(super) const DISABLE_ALL_WAIT: std::time::Duration = control::COMMAND_WAIT
    .saturating_sub(appops::ANSWER_WAIT)
    .saturating_sub(std::time::Duration::from_secs(1));

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
            snapshot: Arc::new(RwLock::new(enabled)),
            global: Arc::new(RwLock::new(global)),
            effects,
            user_data: user_data.map(Path::to_path_buf),
            auto: auto::Auto::load(writer)?,
            release_logged: None,
            awaiting_storage: BTreeSet::new(),
            unsaved_permissions: BTreeSet::new(),
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
    /// the user while the apps were sandboxed (`Effect::found`): called
    /// with every write of the records, and before every command, which
    /// may give back what the entries hold.
    pub fn keep_found(&mut self, writer: &Writer) {
        let snapshot = self.snapshot.clone();
        let mut entries = write(&snapshot);
        let mut changed = BTreeSet::new();
        for effect in &mut self.effects {
            changed.extend(effect.found(&mut entries));
        }
        let pending = std::mem::take(&mut self.unsaved_permissions);
        for uid in pending.union(&changed) {
            if let Some(entry) = entries.get(uid)
                && let Err(e) = writer.put_sandbox(entry)
            {
                self.unsaved_permissions.insert(*uid);
                if !pending.contains(uid) {
                    eprintln!("bbd: sandbox: cannot write what was found for uid {uid}: {e:#}");
                }
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
        let written = self.prepare_effects(writer, &mut entry, &items)?;
        self.snapshot_write().insert(uid, entry.clone());
        self.apply_or_restore(
            writer,
            &mut entry,
            &sharing,
            &items,
            &written,
            (uid, before.as_ref()),
        )?;
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

    fn prepare_effects(
        &mut self,
        writer: &Writer,
        entry: &mut Entry,
        items: &Settings,
    ) -> anyhow::Result<Entry> {
        let uid = entry.uid;
        let prepared = (|| {
            for effect in &mut self.effects {
                effect
                    .prepare_enable(entry, items)
                    .with_context(|| effect.name())?;
            }
            let written = appops::naming_taken(entry, items);
            writer.put_sandbox(&written)?;
            Ok(written)
        })();
        if prepared.is_err() {
            for effect in &mut self.effects {
                effect.cancel_enable(uid);
            }
        }
        prepared
    }

    fn apply_or_restore(
        &mut self,
        writer: &Writer,
        entry: &mut Entry,
        sharing: &[String],
        items: &Settings,
        written: &Entry,
        previous: (u32, Option<&Entry>),
    ) -> anyhow::Result<()> {
        let (uid, before) = previous;
        let mut applied = self.run(entry, sharing, Change::Enable(items));
        if applied.is_ok() && entry != written {
            applied = writer.put_sandbox(entry);
            if applied.is_err() {
                // What the effects changed is lost: they cannot stay.
                if let Err(e) = self.run(&mut entry.clone(), sharing, Change::Disable(items)) {
                    eprintln!("bbd: sandbox: cannot take uid {uid}'s effects off: {e:#}");
                }
            }
        }
        if let Err(e) = applied {
            self.snapshot_write().remove(&uid);
            let restored = match before {
                Some(old) => writer.put_sandbox(old),
                None => writer.delete_sandbox(uid),
            };
            if let Err(restore) = restored {
                eprintln!("bbd: sandbox: cannot restore uid {uid}'s entry: {restore:#}");
            }
            return Err(e);
        }
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
    pub(super) fn run(
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
                    appops_checking: entry.enabled && status.checking_appops.contains(&entry.uid),
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
        Ok(Listed {
            apps,
            hook_health: status.hook.health,
        })
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
