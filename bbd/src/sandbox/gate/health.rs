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

//! Admission failures stay visible until a later launch verifies the mounts.

use std::sync::{Arc, atomic::Ordering};

use super::{Gated, Shared, recording_only};
use crate::{
    sandbox::{
        Entry, Status,
        items::{Mounts, Part},
    },
    sync::{lock, read},
};

#[derive(Clone, Copy, Default)]
pub(super) struct AdmissionHealth {
    failure: Option<Part>,
    retry: bool,
    missed_stop: bool,
}

impl AdmissionHealth {
    pub(super) fn cause(&self) -> Option<Part> {
        self.failure
            .or(self.missed_stop.then_some(Part::MountStartFailed))
    }

    pub(super) fn refuses_start(&self) -> bool {
        self.failure.is_some() && !self.retry
    }

    pub(super) fn fail(&mut self, cause: Part) {
        self.failure = Some(cause);
        self.retry = false;
    }

    pub(super) fn recover(&mut self) {
        if self.failure.is_some() {
            // Keep the failure visible and sweeps disabled until a launch
            // verifies the new conditions.
            self.retry = true;
        }
    }

    pub(super) fn missed_stop(&mut self) {
        self.missed_stop = true;
    }

    pub(super) fn admitted(&mut self) {
        self.failure = None;
        self.retry = false;
    }
}

impl Shared {
    pub(super) fn mounts_ready(&self, entry: &Entry, mounts: Mounts) -> bool {
        self.open.load(Ordering::Acquire)
            && read(&self.apps).get(&entry.uid).is_some_and(|app| {
                app.install_id == entry.install_id
                    && app.mounts == mounts
                    && lock(&app.health).cause().is_none()
            })
    }

    pub(super) fn refuse(&self, uid: u32, prepared: &Arc<Gated>, cause: Option<Part>) -> bool {
        let apps = read(&self.apps);
        let Some(app) = apps
            .get(&uid)
            .filter(|app| !recording_only((app.mounts, app.resolver)))
        else {
            return false;
        };
        // A changed policy still needs its own mounts. Refuse this start
        // without assigning the previous policy's failure to the new one.
        if Arc::ptr_eq(app, prepared)
            && let Some(cause) = cause
        {
            lock(&app.health).fail(cause);
        }
        true
    }

    pub(super) fn report_gate(&self, status: &mut Status) {
        status.gate = self.open.load(Ordering::Acquire);
        status.dns_names = status.gate && self.relay.alive();
        self.report_mounts(status);
    }

    pub(super) fn report_mounts(&self, status: &mut Status) {
        for (&uid, app) in read(&self.apps).iter() {
            let health = lock(&app.health);
            if let Some(cause) = health.cause() {
                status.mount_failures.insert(uid, cause);
            } else {
                status.gated_uids.insert(uid);
            }
            if status.gate && app.mounts.any() && health.refuses_start() {
                status.mount_launch_refused.insert(uid);
            }
        }
    }
}
