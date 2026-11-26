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

use std::collections::BTreeMap;

use anyhow::Context as _;

use crate::{
    packages::{Packages, app_id_of},
    sandbox_item::{Item, Mode},
    store::Writer,
    sync::write,
};

use super::{
    DISABLE_ALL_WAIT, Entry, Sandbox,
    answers::{Configured, GlobalItems},
    effect::Change,
    items::{self, Settings},
    items_of,
};

struct AppliedUpdate {
    entry: Entry,
    sharing: Vec<String>,
    before: Settings,
    after: Settings,
}

impl Sandbox {
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
        self.require_recovered(uid)?;
        let entry = match self.snapshot_read().get(&uid).cloned() {
            Some(entry) => Some(entry),
            None => writer.sandbox_entry(uid)?,
        };
        let mut entry = entry.with_context(|| format!("{package} is not in the sandbox"))?;
        let current = packages.packages_of(app_id_of(uid));
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
        let change = Change::Update(&before, &after);
        self.stage_and_apply(writer, &mut entry, &sharing, change, true)?;
        if let Err(error) = writer.commit_sandbox(&[&entry], None) {
            self.snapshot_write().insert(uid, previous);
            self.reverse_completed(&mut entry, &sharing, change);
            return Err(error);
        }
        self.complete_change(&entry, Some(&after));
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
        let pairs: Vec<(&str, &str)> = new
            .iter()
            .map(|(item, mode)| (item.name(), items::mode_name(mode)))
            .collect();
        let mut uids: Vec<u32> = self.snapshot_read().keys().copied().collect();
        uids.sort_unstable();
        *write(&self.global) = new.clone();

        let mut changed = Vec::new();
        for effect in &mut self.effects {
            effect.begin_updates();
        }
        let result = self
            .update_global_entries(writer, packages, uids, &old, &new, &mut changed)
            .and_then(|()| self.publish_global_updates())
            .and_then(|()| {
                let entries: Vec<_> = changed.iter().map(|update| &update.entry).collect();
                writer.commit_sandbox(&entries, Some(&pairs))
            });
        if let Err(error) = result {
            self.reverse_global_updates(old, changed);
            return Err(error.context("the global settings are as they were"));
        }
        for effect in &mut self.effects {
            effect.end_updates();
        }
        for update in changed {
            self.complete_change(&update.entry, Some(&update.after));
            self.snapshot_write().insert(update.entry.uid, update.entry);
        }
        self.auto_settings_changed();
        Ok(self.global_items())
    }

    fn publish_global_updates(&mut self) -> anyhow::Result<()> {
        for effect in &mut self.effects {
            effect.publish_updates().with_context(|| effect.name())?;
        }
        Ok(())
    }

    fn reverse_global_updates(&mut self, old: Settings, changed: Vec<AppliedUpdate>) {
        *write(&self.global) = old;
        for effect in &mut self.effects {
            // Discard entries staged by an app whose update failed partway.
            effect.end_updates();
            effect.begin_updates();
        }
        for AppliedUpdate {
            mut entry,
            sharing,
            before,
            after,
        } in changed.into_iter().rev()
        {
            self.reverse_completed(&mut entry, &sharing, Change::Update(&before, &after));
        }
        for effect in &mut self.effects {
            if let Err(undo) = effect.publish_updates() {
                eprintln!(
                    "bbd: sandbox: cannot undo {} for the global settings: {undo:#}",
                    effect.name()
                );
            }
            effect.end_updates();
        }
    }

    fn update_global_entries(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        uids: Vec<u32>,
        old: &Settings,
        new: &Settings,
        changed: &mut Vec<AppliedUpdate>,
    ) -> anyhow::Result<()> {
        // Each app may wait for the app ops helper, on the loop that also
        // records: as with taking every app out, the rest are not waited
        // for one by one past a point.
        let until = std::time::Instant::now() + DISABLE_ALL_WAIT;
        for uid in uids {
            let Some(mut entry) = self.snapshot_read().get(&uid).cloned() else {
                continue;
            };
            let (before, after) = (items_of(&entry, old), items_of(&entry, new));
            if before == after {
                continue;
            }
            anyhow::ensure!(std::time::Instant::now() <= until, "taking too long");

            let sharing = packages.for_uid(uid).to_vec();
            self.stage_and_apply(
                writer,
                &mut entry,
                &sharing,
                Change::Update(&before, &after),
                false,
            )
            .with_context(|| format!("uid {uid}"))?;
            changed.push(AppliedUpdate {
                entry,
                sharing,
                before,
                after,
            });
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests;
