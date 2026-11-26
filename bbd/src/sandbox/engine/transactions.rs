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

use std::time::{Duration, Instant};

use anyhow::Context as _;

use super::{AppOpsUndoState, Sandbox};
use crate::{
    sandbox::{Entry, appops::StalePreimage, effect::Change, items::Settings},
    store::{AppOpsUndo, Writer},
};

const STALE_ATTEMPTS: u32 = 3;
const STALE_RETRY_DELAY: Duration = Duration::from_millis(100);
/// Time in which to start another attempt, in addition to the normal time for one change.
pub(super) const STALE_RETRY_WINDOW: Duration = Duration::from_secs(1);

impl Sandbox {
    /// Stage the undo and run the effects. Per-app changes publish their entry
    /// while effects run. Global changes publish their entries at batch commit.
    pub(in crate::sandbox) fn stage_and_apply(
        &mut self,
        writer: &Writer,
        entry: &mut Entry,
        packages: &[String],
        change: Change<'_>,
        publish: bool,
    ) -> anyhow::Result<()> {
        let (before, after) = change.settings();
        let uid = entry.uid;
        let original = entry.clone();
        let previous = if publish {
            self.snapshot_read().get(&uid).cloned()
        } else {
            None
        };
        let until = Instant::now() + STALE_RETRY_WINDOW;
        let mut attempts = 0;
        loop {
            attempts += 1;
            self.stage_change(writer, entry, before, after)?;
            if publish {
                self.publish_entry(uid, after.map(|_| &*entry));
            }
            let Err(error) = self.run(entry, packages, change) else {
                return Ok(());
            };
            if publish {
                self.publish_entry(uid, previous.as_ref());
            }
            if !error.is::<StalePreimage>() {
                return Err(error);
            }

            self.clear_unused_undo(writer, uid)?;
            // A new capture must derive its saved values from the committed entry.
            entry.clone_from(&original);
            if attempts == STALE_ATTEMPTS || Instant::now() + STALE_RETRY_DELAY >= until {
                self.finish_effects(entry, before);
                return Err(error);
            }
            std::thread::sleep(STALE_RETRY_DELAY);
        }
    }

    fn publish_entry(&self, uid: u32, entry: Option<&Entry>) {
        let mut snapshot = self.snapshot_write();
        if let Some(entry) = entry {
            snapshot.insert(uid, entry.clone());
        } else {
            snapshot.remove(&uid);
        }
    }

    fn clear_unused_undo(&mut self, writer: &Writer, uid: u32) -> anyhow::Result<()> {
        let state = self
            .appops_undo
            .get_mut(&uid)
            .context("missing app ops recovery entry")?;
        *state = AppOpsUndoState::Unused;
        writer
            .clear_appops_undo(uid)
            .context("clearing the unused Android restriction journal")?;
        self.remove_undo(uid);
        Ok(())
    }

    fn stage_change(
        &mut self,
        writer: &Writer,
        entry: &mut Entry,
        before: Option<&Settings>,
        after: Option<&Settings>,
    ) -> anyhow::Result<()> {
        let uid = entry.uid;
        self.require_recovered(uid)?;
        let result = (|| {
            anyhow::ensure!(self.owns(entry), "the installation has changed");
            for effect in &mut self.effects {
                let preimage = effect
                    .prepare_change(entry, before, after)
                    .with_context(|| effect.name())?;
                if let Some(preimage) = preimage {
                    let undo = AppOpsUndo {
                        uid,
                        package: entry.package.clone(),
                        install_id: entry.install_id.clone(),
                        preimage,
                        attempts: 0,
                    };
                    writer.stage_appops(&undo)?;
                    self.appops_undo.insert(uid, AppOpsUndoState::Pending);
                    self.next_recovery = Some(Instant::now());
                }
            }
            Ok(())
        })();
        if result.is_err() && !self.appops_undo.contains_key(&uid) {
            self.finish_effects(entry, before);
        }
        result
    }

    pub(in crate::sandbox) fn complete_change(&mut self, entry: &Entry, items: Option<&Settings>) {
        self.remove_undo(entry.uid);
        self.finish_effects(entry, items);
    }

    fn remove_undo(&mut self, uid: u32) {
        self.appops_undo.remove(&uid);
        if self.appops_undo.is_empty() {
            self.next_recovery = None;
        }
    }

    pub(super) fn finish_effects(&mut self, entry: &Entry, items: Option<&Settings>) {
        for effect in &mut self.effects {
            effect.finish_change(entry, items);
        }
    }
}
