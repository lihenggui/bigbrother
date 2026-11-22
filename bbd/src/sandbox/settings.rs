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
    appops,
    effect::Change,
    items::{self, Settings},
    items_of,
};

#[derive(Clone, Copy)]
enum UpdateScope {
    App,
    Global,
}

struct EntryUpdate {
    entry: Entry,
    previous: Entry,
    covering: Entry,
    scope: UpdateScope,
}

struct UpdateError {
    error: anyhow::Error,
    /// A global rollback must also undo the effects of an app whose final write failed.
    applied: Option<Box<Entry>>,
}

impl From<anyhow::Error> for UpdateError {
    fn from(error: anyhow::Error) -> Self {
        Self {
            error,
            applied: None,
        }
    }
}

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
        let entry = self
            .update_entry(
                writer,
                uid,
                &sharing,
                &before,
                &after,
                EntryUpdate {
                    entry,
                    previous,
                    covering,
                    scope: UpdateScope::App,
                },
            )
            .map_err(|failure| failure.error)?;
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
        *write(&self.global) = new.clone();

        let mut changed = Vec::new();
        let result = self
            .update_global_entries(writer, packages, uids, &old, &new, &mut changed)
            .and_then(|()| write_global(&new));
        if let Err(e) = result {
            *write(&self.global) = old.clone();
            self.undo_global_entries(writer, changed);
            // Left covering both if it cannot be written back.
            if covering != old
                && let Err(e) = write_global(&old)
            {
                eprintln!("bbd: sandbox: cannot write the global settings back: {e:#}");
            }
            return Err(e.context("the global settings are as they were"));
        }
        self.auto_settings_changed();
        Ok(self.global_items())
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
            let Some(entry) = self.snapshot_read().get(&uid).cloned() else {
                continue;
            };
            let (before, after) = (items_of(&entry, old), items_of(&entry, new));
            if before == after {
                continue;
            }
            anyhow::ensure!(std::time::Instant::now() <= until, "taking too long");

            let sharing = packages.for_uid(uid).to_vec();
            let previous = entry.clone();
            // The global row already covers both sets of items, but cannot
            // name a permission that is about to be taken.
            let covering = appops::naming_taken(&entry, &after);
            let update = EntryUpdate {
                entry,
                previous,
                covering,
                scope: UpdateScope::Global,
            };
            match self.update_entry(writer, uid, &sharing, &before, &after, update) {
                Ok(entry) => changed.push(AppliedUpdate {
                    entry,
                    sharing,
                    before,
                    after,
                }),
                Err(UpdateError { error, applied }) => {
                    if let Some(entry) = applied {
                        changed.push(AppliedUpdate {
                            entry: *entry,
                            sharing,
                            before,
                            after,
                        });
                    }
                    return Err(error.context(format!("uid {uid}")));
                }
            }
        }
        Ok(())
    }

    fn update_entry(
        &mut self,
        writer: &Writer,
        uid: u32,
        sharing: &[String],
        before: &Settings,
        after: &Settings,
        update: EntryUpdate,
    ) -> Result<Entry, UpdateError> {
        let EntryUpdate {
            mut entry,
            previous,
            covering,
            scope,
        } = update;
        let app_change = matches!(scope, UpdateScope::App);
        if covering != previous {
            writer.put_sandbox(&covering)?;
        }
        // With the global settings already published, only a per-app change
        // needs to publish the new entry before the effects run.
        if app_change {
            self.snapshot_write().insert(uid, entry.clone());
        }
        let applied = self.run(&mut entry, sharing, Change::Update(before, after));
        let result = if applied.is_ok() && (app_change || entry != covering) {
            match writer.put_sandbox(&entry) {
                Ok(()) => Ok(()),
                Err(error) if !app_change => {
                    // Undo after the old global settings are visible again.
                    return Err(UpdateError {
                        error,
                        applied: Some(Box::new(entry)),
                    });
                }
                Err(error) => {
                    let undone =
                        self.run(&mut entry.clone(), sharing, Change::Update(after, before));
                    if let Err(e) = undone {
                        eprintln!("bbd: sandbox: cannot put uid {uid}'s items back: {e:#}");
                    }
                    Err(error)
                }
            }
        } else {
            applied
        };
        if let Err(error) = result {
            // Left covering both if it cannot be written back.
            if covering != previous
                && let Err(e) = writer.put_sandbox(&previous)
            {
                eprintln!("bbd: sandbox: cannot write uid {uid}'s entry back: {e:#}");
            }
            if app_change {
                self.snapshot_write().insert(uid, previous);
            }
            return Err(error.into());
        }
        if !app_change && entry != previous {
            self.snapshot_write().insert(uid, entry.clone());
        }
        Ok(entry)
    }

    fn undo_global_entries(&mut self, writer: &Writer, changed: Vec<AppliedUpdate>) {
        for AppliedUpdate {
            mut entry,
            sharing,
            before,
            after,
        } in changed.into_iter().rev()
        {
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
    }
}

#[cfg(test)]
mod tests;
