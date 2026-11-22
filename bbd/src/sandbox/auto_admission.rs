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

#[cfg(test)]
use bb_common::known_app_bit;

#[cfg(test)]
use crate::packages::app_id_of;

/// Mirrors the new-app policy for starts already queued by the kernel.
#[derive(Default)]
pub(super) struct Admission {
    pub(super) hold: bool,
    pub(super) words: Vec<u64>,
    pub(super) known: BTreeSet<u32>,
    pub(super) released: BTreeSet<u32>,
}

impl Admission {
    pub(super) fn releases(&self, uid: u32) -> bool {
        self.released.contains(&uid)
    }

    pub(super) fn ends_queued_start(&self, uid: u32) -> bool {
        // A newly installed mount policy can appear while a queued start
        // is being handled. Only release it when the auto hold itself ended.
        self.hold && !self.releases(uid)
    }

    #[cfg(test)]
    pub(super) fn holds(&self, uid: u32) -> bool {
        self.hold
            && known_app_bit(app_id_of(uid)).is_some_and(|(word, bit)| {
                self.words
                    .get(word as usize)
                    .is_none_or(|known| known & bit == 0)
            })
            && !self.known.contains(&uid)
    }
}
