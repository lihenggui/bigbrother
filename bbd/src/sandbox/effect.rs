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

use std::collections::HashMap;

use super::{Entry, Status, items::Settings};

/// One thing the sandbox does to an app (say, the storage it sees, or the
/// app ops it is refused).
///
/// Both [`Effect::enable`] and [`Effect::disable`] run the effects in the
/// order they were added, since a step such as stopping the app has to come
/// last either way. When one fails, those that already ran are undone in
/// reverse order and the command fails, so that an app is never left half
/// sandboxed.
pub trait Effect: Send {
    /// Names the effect in an error.
    fn name(&self) -> &'static str;

    /// Read the original state needed for recovery before enabling an app.
    fn prepare_enable(&mut self, _entry: &mut Entry, _items: &Settings) -> anyhow::Result<()> {
        Ok(())
    }

    /// Discard preparation when the entry cannot be committed.
    fn cancel_enable(&mut self, _uid: u32) {}

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

    /// Release state kept for undoing a refresh once its rule is committed
    /// or the previous rules are back in the book.
    fn finish_refresh(&mut self, _uid: u32) {}

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
pub(super) enum Change<'a> {
    Enable(&'a Settings),
    Disable(&'a Settings),
    Update(&'a Settings, &'a Settings),
}

impl Change<'_> {
    /// What undoes it.
    pub(super) fn reversed(self) -> Self {
        match self {
            Change::Enable(items) => Change::Disable(items),
            Change::Disable(items) => Change::Enable(items),
            Change::Update(before, after) => Change::Update(after, before),
        }
    }

    pub(super) fn apply(
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
