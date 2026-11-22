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

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

use crate::sandbox_item::Item;

use super::items::{Part, Settings};

/// The answer to `sandbox.enable`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Enabled {
    pub uid: u32,
    /// The other packages that share the uid, and are sandboxed with it.
    pub also: Vec<String>,
}

/// The answer to `sandbox.disable`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Disabled {
    /// False if the app was not in the sandbox, and nothing was done.
    pub was_enabled: bool,
}

/// The answer to `sandbox.disable_all`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DisabledAll {
    /// How many apps were taken out.
    pub disabled: u32,
    /// The uids that could not be, and are sandboxed still.
    pub failed: Vec<u32>,
}

/// The answer to `sandbox.list`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Listed {
    pub apps: Vec<ListedApp>,
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ListedApp {
    pub uid: u32,
    pub package: String,
    pub enabled: bool,
    /// When it was first put in the sandbox, in seconds since the epoch.
    pub created: i64,
    /// The items it has a mode of its own for.
    pub settings: Settings,
    /// What applies to it: its own settings over the global ones.
    pub items: Settings,
    /// For an app in the sandbox, the items that are on but not in effect,
    /// each with the parts that are not in place.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub unmet: BTreeMap<Item, Vec<Part>>,
    /// The AppOps helper is still reading back the restrictions for this app.
    #[serde(default)]
    pub appops_checking: bool,
    /// How many destinations it is kept from.
    pub network_rules: u32,
    /// Since when its connections are recorded, in seconds since the
    /// epoch, if they are.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recording_since: Option<i64>,
}

/// The answer to `sandbox.global` and `sandbox.set_global`: every item,
/// with the mode it has for an app that has none of its own.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct GlobalItems {
    pub items: Settings,
    /// Whether apps installed from now on are put in the sandbox with them
    /// (see [`auto`]).
    pub auto: bool,
}

/// The answer to `sandbox.set`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Configured {
    pub uid: u32,
    /// The items the app now has a mode of its own for.
    pub settings: Settings,
    /// What applies to it now.
    pub items: Settings,
}

/// The answer to `sandbox.status`: whether each part of the sandbox works on
/// this device, and how the starts of sandboxed apps went. What no effect
/// reports on stays false or zero.
#[derive(Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Status {
    /// Starting apps are held until their storage is replaced.
    pub gate: bool,
    /// Personal data is refused through app ops.
    pub appops: bool,
    /// The packet filter refuses the network to the apps kept from it.
    pub network: bool,
    /// The connections of the apps the user chose can be recorded.
    pub net_record: bool,
    /// The names apps look up are known, and can be refused.
    pub dns_names: bool,
    /// The bytes of connections are counted.
    pub traffic: bool,
    pub hook: HookStatus,
    /// Starts of sandboxed apps let through, and those that were stopped.
    pub admitted: u64,
    pub failed: u64,
    /// Starts of newly installed apps ended before they were in the
    /// sandbox (see [`auto`]).
    pub new_apps_stopped: u64,
    /// Newly installed apps not in the sandbox yet: not put in by the last
    /// look, or failed to be and waiting to be tried again.
    pub new_apps_pending: u32,
    /// Newly installed apps whose starts were released after isolation failed.
    #[serde(default)]
    pub new_apps_failed: u32,
    /// Whether starts of newly installed apps not in the sandbox yet are
    /// held.
    pub new_apps_held: bool,
    /// How long starts were held, 95th percentile, in microseconds.
    pub hold_p95_us: u64,
    /// The ops each sandboxed uid is held to and the system keeps, by uid:
    /// what [`Sandbox::list`] reports per app.
    #[serde(skip)]
    pub gated_uids: BTreeSet<u32>,
    #[serde(skip)]
    pub held_ops: BTreeMap<u32, BTreeSet<String>>,
    #[serde(skip)]
    pub checking_appops: BTreeSet<u32>,
}

/// What the system server hook reports it has in place.
#[derive(Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct HookStatus {
    pub android_id: bool,
    pub app_list: bool,
    /// Whether file grants an app kept from before its storage was
    /// isolated are withheld from it.
    pub uri_grants: bool,
    /// Whether the advertising ID is withheld from the apps whose device
    /// identifiers are.
    pub ad_id: bool,
}
