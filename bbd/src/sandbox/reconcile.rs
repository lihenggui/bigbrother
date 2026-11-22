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

use std::collections::BTreeSet;

use crate::{
    packages::{Packages, app_id_of},
    store::Writer,
    sync::lock,
};

use super::{Entry, Resumed, Sandbox, dirs};

/// Least time between two log lines about entries that cannot be taken out:
/// each is tried again at every command and every look for new apps.
const RELEASE_LOG_INTERVAL: std::time::Duration = std::time::Duration::from_secs(60);

impl Sandbox {
    /// Put the apps that were sandboxed when the daemon last stopped back
    /// in. To be called once, when the daemon starts.
    pub fn resume(&mut self, writer: &Writer, packages: &mut Packages) {
        if packages.refresh() && !packages.is_empty() {
            self.reconcile_entries(writer, packages, None);
        }
        self.suspend_pending_appops();
        self.resume_verified(packages);
    }

    fn resume_verified(&mut self, packages: &mut Packages) {
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

    /// Read the current package list and retire entries whose installation
    /// is gone. A verified shared UID member can continue to own the sandbox.
    pub(crate) fn release_reassigned(&mut self, writer: &Writer, packages: &mut Packages) {
        if !self.snapshot_read().is_empty() && packages.refresh() && !packages.is_empty() {
            self.reconcile(writer, packages, None);
        }
    }

    pub(super) fn reconcile(
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
            self.resume_verified(packages);
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
            let current = packages.packages_of(app_id_of(uid));
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
                    if let Err(e) = writer.retire_sandbox(&entry) {
                        self.log_reconcile_error(uid, &e);
                        continue;
                    }
                    self.snapshot_write().remove(&uid);
                    self.awaiting_storage.remove(&uid);
                    self.auto.forget_uid(uid);
                    self.pending_net_refresh.retain(|pending| *pending != uid);
                    // Nothing saved for the old installation may be applied
                    // to the one the uid belongs to now.
                    for effect in &mut self.effects {
                        effect.forget(uid);
                    }
                    // Nor what it was kept from, or what was recorded of it.
                    lock(&self.book).remove(&uid);
                }
                Err(_) => {
                    // A directory may be unavailable while the user starts.
                }
            }
        }
        changed
    }

    pub(super) fn log_reconcile_error(&mut self, uid: u32, e: &anyhow::Error) {
        if self
            .release_logged
            .is_none_or(|at| at.elapsed() >= RELEASE_LOG_INTERVAL)
        {
            eprintln!("bbd: sandbox: cannot reconcile uid {uid}: {e:#}");
            self.release_logged = Some(std::time::Instant::now());
        }
    }

    pub(super) fn owns(&self, entry: &Entry) -> bool {
        self.matches_package(entry, &entry.package).unwrap_or(false)
    }

    fn matches_package(&self, entry: &Entry, package: &str) -> std::io::Result<bool> {
        match &self.user_data {
            Some(root) => dirs::matches_install(root, package, entry.uid, &entry.install_id),
            None => Ok(true),
        }
    }

    pub(super) fn existing_owner(
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

    pub(super) fn remember_packages(
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
}

#[cfg(test)]
mod tests;
