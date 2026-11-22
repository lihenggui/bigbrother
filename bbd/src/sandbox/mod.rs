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

//! The sandbox: which apps the user put in it, and turning it on and off.
//!
//! The sandbox applies to a uid, not a package. Android runs every package
//! that shares a uid as the same user, with the same files and permissions,
//! so one of them cannot be held apart from the others; enabling it for one
//! puts them all in, and says so.
//!
//! What it does to an app is a list of [`Effect`]s, carried out in order
//! when it is enabled and disabled. Two records say what holds:
//!
//! - The `sandbox` table says what may be in effect. It is written before
//!   the effects of an enable and after those of a disable, so that whenever
//!   any effect may be in place the row says the app is sandboxed, and a
//!   daemon that stopped half way can finish or undo the change when it
//!   starts again. A change of items is written before its effects as
//!   items that keep from the app all that the old or the new ones keep
//!   (`items::Settings::covering`), and after them as the new ones, and so
//!   are the global settings: an app op held anew is named before it is
//!   held, and one let go stays named until it is. A runtime permission
//!   taken anew is named in the row before it is taken
//!   (`appops::naming_taken`), as no mode of the items covers it together
//!   with the ops of another.
//! - The snapshot, in memory, says what is meant to be in effect. It changes
//!   before the effects run, which may read it, and is shared with whatever
//!   needs to look up a uid without waiting for the daemon's main loop.
//!
//! The effects, in the order they are carried out:
//!
//! - [`appops`]: personal data is refused through app ops, and location
//!   made approximate through its permission.
//! - [`identity`]: the system server hook is told which uids to answer
//!   differently.
//! - [`network`]: the packet filter refuses what the app sends.
//! - [`gate`]: starting apps are held until [`mounts`] has replaced the
//!   storage they see.
//! - [`sweep`]: the app's running processes are ended, so that it starts
//!   again as it should. The system ends them itself when [`appops`] takes
//!   a permission the app had: Android does whenever a runtime permission
//!   is revoked.
//!
//! Taking an app out begins with the one that can fail, restoring its app
//! ops, so that a failure leaves the app as sandboxed as it was.
//!
//! What each effect does depends on the app's [`items`]: the items the app
//! has a mode of its own for, over the global settings. An item is on unless
//! set otherwise, except the network, and an app with every item off has
//! nothing done to it.
//!
//! Apps can also be put in as they are installed ([`auto`]).

mod answers;
pub mod appops;
pub mod auto;
mod cli;
pub mod destinations;
pub(crate) mod dirs;
mod effect;
mod engine;
pub mod gate;
pub mod identity;
pub mod items;
mod mounts;
pub mod names;
mod net_rules;
pub mod network;
mod reconcile;
pub mod resolver;
mod settings;
pub mod sweep;
pub mod target;
#[cfg(test)]
mod tests;

pub use crate::store::SandboxEntry as Entry;
pub use answers::{HookStatus, Status};
pub use cli::{command, remove_network, restore_lines};
pub use effect::{Effect, Resumed};
pub use engine::{Global, Sandbox, Snapshot, items_of};

use engine::{DISABLE_ALL_WAIT, LAST_USER, OWN_PACKAGES, new_android_id, uid_of};
