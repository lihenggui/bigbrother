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

//! Report the enforcement observed for each Jail entry.

use std::collections::BTreeMap;

use super::Sandbox;
use crate::{
    sandbox::{
        Status,
        answers::{Listed, ListedApp},
        items::{InPlace, Part, Settings},
        target::Target,
    },
    sandbox_item::Item,
    store::Writer,
    sync::lock,
};

impl Sandbox {
    pub fn status(&self) -> Status {
        let mut status = Status::default();
        for effect in &self.effects {
            effect.report(&mut status);
        }
        self.auto.report(&mut status);
        status
    }

    /// Every entry, enabled or not.
    pub(super) fn list(&self, writer: &Writer) -> anyhow::Result<Listed> {
        let global = self.global_read();
        let status = self.status();
        let (rules, recording) = (writer.net_rule_counts()?, writer.net_recording()?);
        let apps = writer
            .sandbox_entries()?
            .into_iter()
            .map(|entry| {
                let in_place = InPlace {
                    mounts: status.gate && status.gated_uids.contains(&entry.uid),
                    android_id_hook: status.hook.android_id,
                    app_list_hook: status.hook.app_list,
                    uri_grants_hook: status.hook.uri_grants,
                    ad_id_hook: status.hook.ad_id,
                    packet_filter: status.network_uids.contains(&entry.uid),
                    network_dns: status.network_dns_uids.contains(&entry.uid),
                    recording: status.recording_uids.contains(&entry.uid),
                    dns_names: status.dns_uids.contains(&entry.uid),
                };
                let settings = Settings::parse(&entry.settings);
                let items = settings.over(&global);
                let unmet = if entry.enabled {
                    let held = status.held_ops.get(&entry.uid);
                    let mut unmet =
                        items.unmet(&in_place, |op| held.is_some_and(|held| held.contains(op)));
                    annotate_unmet(&status, entry.uid, &mut unmet);
                    unmet
                } else {
                    BTreeMap::new()
                };
                ListedApp {
                    unmet,
                    appops_checking: entry.enabled && status.checking_appops.contains(&entry.uid),
                    items,
                    network_rules: rules.get(&entry.uid).copied().unwrap_or(0),
                    name_rules_not_in_effect: if entry.enabled && !in_place.dns_names {
                        lock(&self.book).get(&entry.uid).map_or(0, |targets| {
                            targets
                                .iter()
                                .filter(|target| !matches!(target, Target::Ip(..)))
                                .count() as u32
                        })
                    } else {
                        0
                    },
                    recording_since: recording.get(&entry.uid).copied(),
                    uid: entry.uid,
                    package: entry.package,
                    enabled: entry.enabled,
                    created: entry.created,
                    settings,
                }
            })
            .collect();
        Ok(Listed {
            apps,
            hook_health: status.hook.health,
        })
    }
}

fn annotate_unmet(status: &Status, uid: u32, unmet: &mut BTreeMap<Item, Vec<Part>>) {
    if status.unknown_appops.contains(&uid) {
        for part in unmet.values_mut().flatten() {
            if *part == Part::AppOps {
                *part = Part::AppOpsUnknown;
            }
        }
    }
    if let Some(&cause) = status.mount_failures.get(&uid) {
        for (item, parts) in unmet {
            if parts.contains(&Part::Mounts) {
                parts.push(cause);
                if matches!(item, Item::Storage | Item::CpuInfo)
                    && status.mount_launch_refused.contains(&uid)
                {
                    parts.push(Part::MountLaunchRefused);
                }
            }
        }
    }
}
