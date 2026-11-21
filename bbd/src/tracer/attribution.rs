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

//! Attribute events from isolated processes to their app.

use std::{
    fs,
    time::{Duration, Instant},
};

use bb_common::FIRST_APPLICATION_UID;

use super::Tracer;
use crate::{
    packages::{FIRST_ISOLATED_APP_ID, Packages, app_id_of, user_of},
    proc::{parent_pid, real_uid, uid_of_pid},
    record::{MODE_ISOLATED, Mode},
};

/// How long the owner found for an isolated process is remembered after it
/// was last seen, for events handled after it has exited. Short, because an
/// isolated uid is handed to another process, possibly another app's, once
/// it is free.
const ISOLATED_OWNER_TTL: Duration = Duration::from_secs(60);

impl Tracer {
    /// The app a process should be attributed to, and [`MODE_ISOLATED`] if
    /// it was traced from one of the app's isolated processes (0 otherwise).
    /// Isolated processes run under a uid of their own. Best effort: when
    /// the process is gone and its uid was not seen recently, the record
    /// stays under the isolated uid, without the flag.
    pub(super) fn owner_uid(&mut self, uid: u32, pid: u32) -> (u32, Mode) {
        if app_id_of(uid) < FIRST_ISOLATED_APP_ID {
            return (uid, 0);
        }
        let now = Instant::now();
        // A process keeps its owner for life, so one already traced is not
        // looked up again: a renderer sends many events.
        if let Some((owner, seen)) = self.isolated_owners.get_mut(&(pid, uid)) {
            *seen = now;
            return (*owner, MODE_ISOLATED);
        }
        if let Some(owner) = isolated_owner(&self.packages, uid, pid) {
            self.isolated_owners.insert((pid, uid), (owner, now));
            return (owner, MODE_ISOLATED);
        }
        // The process has exited: fall back on the owner found for another
        // process of the same uid a moment ago.
        self.isolated_owners
            .iter()
            .filter(|((_, isolated), _)| *isolated == uid)
            .max_by_key(|(_, (_, seen))| *seen)
            .map_or((uid, 0), |(_, (owner, _))| (*owner, MODE_ISOLATED))
    }

    /// Forget isolated processes not seen for a while: their pids and uids
    /// are handed out again.
    pub(crate) fn prune_isolated_owners(&mut self) {
        let now = Instant::now();
        self.isolated_owners
            .retain(|_, (_, seen)| now.duration_since(*seen) < ISOLATED_OWNER_TTL);
    }
}

/// The owner of a live isolated process. One forked from an app zygote has
/// that zygote, which runs as the app itself, for a parent: the answer from
/// the first moment. Otherwise the process's name says which app it belongs
/// to. A pid may already belong to another process, so it must still run as
/// `uid`.
fn isolated_owner(packages: &Packages, uid: u32, pid: u32) -> Option<u32> {
    let status = fs::read_to_string(format!("/proc/{pid}/status")).ok()?;
    if real_uid(status.as_bytes()) != Some(uid) {
        return None;
    }
    let parent = parent_pid(status.as_bytes()).and_then(uid_of_pid);
    if let Some(parent) = parent
        && user_of(parent) == user_of(uid)
        && (FIRST_APPLICATION_UID..FIRST_ISOLATED_APP_ID).contains(&app_id_of(parent))
    {
        return Some(parent);
    }
    let cmdline = fs::read(format!("/proc/{pid}/cmdline")).ok()?;
    let name = cmdline.split(|&b| b == 0).next()?;
    packages.owner_of_isolated(uid, std::str::from_utf8(name).ok()?)
}
