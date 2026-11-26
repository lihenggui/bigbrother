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

use anyhow::Context as _;

use crate::{
    packages::Packages,
    store::{Writer, net::RuleChange},
    sync::lock,
};

use super::{Sandbox, items_of, target::Target, uid_of};

/// The most destinations one app can be kept from: what bounds the list
/// the kernel side looks packets up in.
const RULES_PER_APP: usize = 1024;

impl Sandbox {
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
        if let Err(e) = writer.set_net_recording(&recorded, crate::clock::now()) {
            eprintln!("bbd: could not write which apps are recorded: {e:#}");
        }
    }

    /// Keep `package` from the destination `target`. The rule is the
    /// app's whether it is in the sandbox or not, and is carried out
    /// while it is.
    pub(super) fn net_block(
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
        if matches!(parsed, Target::Ip(..)) {
            anyhow::ensure!(
                self.status().network,
                "the packet filter is not available; destinations cannot be refused"
            );
        }
        if let Some(targets) = lock(&self.book).get(&uid) {
            anyhow::ensure!(
                targets.len() < RULES_PER_APP || targets.contains(&parsed),
                "an app can be kept from {RULES_PER_APP} destinations at most"
            );
        }
        self.change_net_rule(
            writer,
            uid,
            &parsed,
            RuleChange::Block {
                created: crate::clock::now(),
                manual,
            },
        )?;
        Ok(serde_json::json!({}))
    }

    pub(super) fn net_unblock(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        package: &str,
        user: u32,
        target: &str,
    ) -> anyhow::Result<serde_json::Value> {
        let uid = uid_of(packages, package, user)?;
        let parsed = Target::parse(target).context("not a domain or an address")?;
        self.change_net_rule(writer, uid, &parsed, RuleChange::Unblock)?;
        Ok(serde_json::json!({}))
    }

    fn change_net_rule(
        &mut self,
        writer: &Writer,
        uid: u32,
        target: &Target,
        change: RuleChange,
    ) -> anyhow::Result<()> {
        let blocked = matches!(change, RuleChange::Block { .. });
        let entry = self.snapshot_read().get(&uid).cloned();
        let before = self.set_net_rule(uid, target, blocked);
        if blocked
            && !matches!(target, Target::Ip(..))
            && let Some(entry) = &entry
        {
            for effect in &mut self.effects {
                effect.stage_name_rule(entry, target);
            }
        }
        // Refresh can run subprocesses and visit mount namespaces. Keep the
        // old rule durable until that succeeds so restart can restore it.
        let result = self.refresh(uid, false).and_then(|()| {
            let addresses: Vec<_> = self
                .effects
                .iter()
                .flat_map(|effect| effect.name_addresses())
                .collect();
            writer.change_net_rule(uid, &target.to_text(), change, &addresses)
        });
        self.pending_net_refresh.retain(|pending| *pending != uid);
        let result = result.map_err(|error| {
            // The old row is still committed. Effects may already have
            // changed part of the filter or the app's resolver.
            for effect in &mut self.effects {
                effect.cancel_name_rule();
            }
            self.set_net_rule(uid, target, before);
            match self.refresh(uid, true) {
                Ok(()) => error,
                Err(restore) => {
                    self.pending_net_refresh.push_back(uid);
                    error.context(restore)
                }
            }
        });
        for effect in &mut self.effects {
            effect.finish_refresh(uid);
        }
        result
    }

    /// Update the book and return whether the target was blocked before.
    fn set_net_rule(&self, uid: u32, target: &Target, blocked: bool) -> bool {
        let mut book = lock(&self.book);
        if blocked {
            !book.entry(uid).or_default().insert(target.clone())
        } else {
            book.get_mut(&uid)
                .is_some_and(|targets| targets.remove(target))
        }
    }

    /// Retry one incomplete restoration per periodic flush, taking turns
    /// when several apps need a retry.
    pub fn retry_net_refresh(&mut self) {
        let snapshot = self.snapshot();
        for effect in &mut self.effects {
            effect.retry_network(&snapshot);
        }
        if let Some(uid) = self.pending_net_refresh.pop_front()
            && self.refresh(uid, true).is_err()
        {
            self.pending_net_refresh.push_back(uid);
        }
    }

    /// Carry out a change of destinations for a sandboxed app. Restoring
    /// takes every effect back as far as possible even if one fails.
    fn refresh(&mut self, uid: u32, restoring: bool) -> anyhow::Result<()> {
        let Some(entry) = self.snapshot_read().get(&uid).cloned() else {
            return Ok(());
        };
        anyhow::ensure!(self.owns(&entry), "the installation could not be verified");
        let items = self.items(&entry);
        let mut failed = Vec::new();
        for effect in &mut self.effects {
            if let Err(error) = effect.refresh(&entry, &items) {
                if !restoring {
                    return Err(error.context(effect.name()));
                }
                failed.push(effect.name());
            }
        }
        anyhow::ensure!(
            failed.is_empty(),
            "could not restore the previous network policy ({})",
            failed.join(", ")
        );
        Ok(())
    }
}

#[cfg(test)]
mod tests;
