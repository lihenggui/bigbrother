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

//! What the sandbox does to an app, one item at a time.
//!
//! Each item is a kind of data the app is kept from, and has a mode: off
//! (the app gets what the system gives it), on (an empty, made-up or
//! separate answer), and for location, coarse (an approximate one). The
//! settings of a sandboxed app name the items it has
//! its own mode for; every other item has the mode of the global settings,
//! and every item is on unless set otherwise.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// A kind of data the sandbox can keep from an app.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Item {
    /// Shared storage, and the media library behind it.
    Storage,
    /// What `/proc/cpuinfo` says about the chip.
    CpuInfo,
    Contacts,
    /// Messages and the call log.
    Messages,
    Calendar,
    /// The clipboard and the accounts on the device.
    ClipboardAccounts,
    Location,
    Camera,
    Microphone,
    /// Body sensors and physical activity.
    BodySensors,
    /// ANDROID_ID, the phone number and the phone's state.
    DeviceIds,
    /// The installed apps, and how they are used.
    InstalledApps,
    /// Bluetooth and Wi-Fi devices nearby.
    NearbyDevices,
}

/// What an item does to the app.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Mode {
    /// Nothing: the app gets what the system gives it.
    Off,
    /// An empty, made-up or separate answer, with the permissions still
    /// shown as granted.
    On,
    /// For location: an approximate one, and nothing more precise.
    Coarse,
}

impl Item {
    pub const ALL: [Item; 13] = [
        Item::Storage,
        Item::CpuInfo,
        Item::Contacts,
        Item::Messages,
        Item::Calendar,
        Item::ClipboardAccounts,
        Item::Location,
        Item::Camera,
        Item::Microphone,
        Item::BodySensors,
        Item::DeviceIds,
        Item::InstalledApps,
        Item::NearbyDevices,
    ];

    /// The name the item goes by in the database, in commands and in JSON.
    pub fn name(self) -> &'static str {
        match self {
            Item::Storage => "storage",
            Item::CpuInfo => "cpu_info",
            Item::Contacts => "contacts",
            Item::Messages => "messages",
            Item::Calendar => "calendar",
            Item::ClipboardAccounts => "clipboard_accounts",
            Item::Location => "location",
            Item::Camera => "camera",
            Item::Microphone => "microphone",
            Item::BodySensors => "body_sensors",
            Item::DeviceIds => "device_ids",
            Item::InstalledApps => "installed_apps",
            Item::NearbyDevices => "nearby_devices",
        }
    }

    pub fn from_name(name: &str) -> Option<Item> {
        Item::ALL.into_iter().find(|item| item.name() == name)
    }

    /// Whether the item can be set to `mode`. Every item can be off or on;
    /// only location can be made coarse.
    pub fn allows(self, mode: Mode) -> bool {
        match mode {
            Mode::Off | Mode::On => true,
            Mode::Coarse => self == Item::Location,
        }
    }

    /// The app ops through which the item's data is handed out. The app ops
    /// helper (`AppOpsGuardProtocol.GUARDED_OPS`) knows every one of them.
    pub fn ops(self) -> &'static [&'static str] {
        match self {
            Item::Storage => &[
                "android:read_external_storage",
                "android:write_external_storage",
                "android:manage_external_storage",
                "android:access_media_location",
                "android:read_media_images",
                "android:read_media_video",
                "android:read_media_audio",
                "android:read_media_visual_user_selected",
            ],
            Item::CpuInfo => &[],
            Item::Contacts => &["android:read_contacts", "android:write_contacts"],
            Item::Messages => &[
                "android:read_sms",
                "android:receive_sms",
                "android:read_call_log",
            ],
            Item::Calendar => &["android:read_calendar", "android:write_calendar"],
            Item::ClipboardAccounts => &["android:read_clipboard", "android:get_accounts"],
            Item::Location => &[
                "android:coarse_location",
                "android:fine_location",
                "android:wifi_scan",
            ],
            Item::Camera => &["android:camera"],
            Item::Microphone => &["android:record_audio"],
            Item::BodySensors => &["android:body_sensors", "android:activity_recognition"],
            Item::DeviceIds => &["android:read_phone_state", "android:read_phone_numbers"],
            Item::InstalledApps => &["android:get_usage_stats"],
            Item::NearbyDevices => &[
                "android:bluetooth_scan",
                "android:bluetooth_connect",
                "android:nearby_wifi_devices",
            ],
        }
    }
}

/// The location ops that location made coarse leaves to the system. The
/// system keeps the Wi-Fi scan op's mode under the coarse location op (its
/// switch op): holding the scan would refuse the coarse location as well.
const COARSE_KEPT: &[&str] = &["android:coarse_location", "android:wifi_scan"];

/// The mode of some items: an app's own, or the global settings.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Settings(BTreeMap<Item, Mode>);

/// What the sandbox mounts in an app's namespace as it starts.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Mounts {
    pub storage: bool,
    pub cpu_info: bool,
}

impl Mounts {
    pub fn any(self) -> bool {
        self.storage || self.cpu_info
    }
}

impl Settings {
    /// Every item on: what a sandboxed app gets unless set otherwise.
    #[cfg(test)]
    pub fn all_on() -> Self {
        Self(Item::ALL.into_iter().map(|item| (item, Mode::On)).collect())
    }

    /// Read settings as [`Self::to_text`] writes them. What this build does
    /// not know, or an item cannot be set to, is left out: a later build
    /// may have written it.
    pub fn parse(text: &str) -> Self {
        let mut settings = Self::default();
        for pair in text.split(',') {
            let Some((item, mode)) = pair.split_once('=') else {
                continue;
            };
            let (Some(item), Some(mode)) = (Item::from_name(item.trim()), parse_mode(mode.trim()))
            else {
                continue;
            };
            let _ = settings.set(item, Some(mode));
        }
        settings
    }

    /// `item=mode,...`, by item.
    pub fn to_text(&self) -> String {
        self.0
            .iter()
            .map(|(item, mode)| format!("{}={}", item.name(), mode_name(*mode)))
            .collect::<Vec<_>>()
            .join(",")
    }

    /// Set `item` to `mode`, or, with `None`, have it follow the global
    /// settings again. Fails for a mode the item cannot have.
    pub fn set(&mut self, item: Item, mode: Option<Mode>) -> anyhow::Result<()> {
        match mode {
            Some(mode) => {
                anyhow::ensure!(
                    item.allows(mode),
                    "{} cannot be {}",
                    item.name(),
                    mode_name(mode)
                );
                self.0.insert(item, mode);
            }
            None => {
                self.0.remove(&item);
            }
        }
        Ok(())
    }

    /// The mode `item` has here, if it has one.
    pub fn get(&self, item: Item) -> Option<Mode> {
        self.0.get(&item).copied()
    }

    /// What applies: these settings where they name an item, `global` where
    /// they do not, and on for an item neither names.
    pub fn over(&self, global: &Settings) -> Settings {
        Self(
            Item::ALL
                .into_iter()
                .map(|item| {
                    let mode = self.get(item).or(global.get(item)).unwrap_or(Mode::On);
                    (item, mode)
                })
                .collect(),
        )
    }

    /// The mode of `item` in settings that apply ([`Self::over`]).
    pub fn mode(&self, item: Item) -> Mode {
        self.get(item).unwrap_or(Mode::On)
    }

    /// Whether any item does something to the app.
    pub fn any_on(&self) -> bool {
        Item::ALL
            .into_iter()
            .any(|item| self.mode(item) != Mode::Off)
    }

    pub fn mounts(&self) -> Mounts {
        Mounts {
            storage: self.mode(Item::Storage) != Mode::Off,
            cpu_info: self.mode(Item::CpuInfo) != Mode::Off,
        }
    }

    /// Whether the system server hook answers for the app: with its own
    /// ANDROID_ID, and with no installed app but itself and the system's.
    pub fn hooked(&self) -> (bool, bool) {
        (
            self.mode(Item::DeviceIds) != Mode::Off,
            self.mode(Item::InstalledApps) != Mode::Off,
        )
    }

    /// The app ops to hold the app to, ignored. Location made coarse holds
    /// only the precise location ([`COARSE_KEPT`]).
    pub fn op_targets(&self) -> Vec<&'static str> {
        let mut targets = Vec::new();
        for item in Item::ALL {
            let mode = self.mode(item);
            if mode == Mode::Off {
                continue;
            }
            for op in item.ops() {
                if mode == Mode::Coarse && COARSE_KEPT.contains(op) {
                    continue;
                }
                targets.push(*op);
            }
        }
        targets
    }

    pub fn iter(&self) -> impl Iterator<Item = (Item, Mode)> + '_ {
        self.0.iter().map(|(item, mode)| (*item, *mode))
    }

    /// Settings that keep from an app all that `self` or `other` keeps,
    /// both over `global`: for each item, the mode of whichever keeps more,
    /// `self`'s where they keep as much. What is written down while an app
    /// changes from the one to the other, so that a daemon that stopped
    /// half way takes it up again with every app op, mount and hook
    /// answer it may have been given.
    pub fn covering(&self, other: &Settings, global: &Settings) -> Settings {
        let (mine, theirs) = (self.over(global), other.over(global));
        let mut covering = Settings::default();
        for item in Item::ALL {
            let wider = if keeps(theirs.mode(item)) > keeps(mine.mode(item)) {
                other
            } else {
                self
            };
            if let Some(mode) = wider.get(item) {
                covering.0.insert(item, mode);
            }
        }
        covering
    }
}

/// How much of an item `mode` keeps from the app, to compare two modes:
/// what a mode keeps, one that keeps more keeps too.
fn keeps(mode: Mode) -> u8 {
    match mode {
        Mode::Off => 0,
        // All but an approximate location.
        Mode::Coarse => 1,
        Mode::On => 2,
    }
}

pub fn mode_name(mode: Mode) -> &'static str {
    match mode {
        Mode::Off => "off",
        Mode::On => "on",
        Mode::Coarse => "coarse",
    }
}

pub fn parse_mode(name: &str) -> Option<Mode> {
    [Mode::Off, Mode::On, Mode::Coarse]
        .into_iter()
        .find(|mode| mode_name(*mode) == name)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_are_written_and_read_back() {
        let mut settings = Settings::default();
        settings.set(Item::Camera, Some(Mode::Off)).unwrap();
        settings.set(Item::Location, Some(Mode::Coarse)).unwrap();
        let text = settings.to_text();
        assert_eq!(text, "location=coarse,camera=off");
        assert_eq!(Settings::parse(&text), settings);
        assert_eq!(Settings::parse(""), Settings::default());
    }

    #[test]
    fn what_is_not_known_or_not_allowed_is_left_out() {
        // `deny` is not a mode (any more): the item follows the global settings.
        let settings = Settings::parse(
            "camera=off,teleport=on,storage=coarse,x,=,contacts=deny,location=coarse",
        );
        assert_eq!(settings.to_text(), "location=coarse,camera=off");
        let mut settings = Settings::default();
        assert!(settings.set(Item::Camera, Some(Mode::Coarse)).is_err());
        assert_eq!(parse_mode("deny"), None);
    }

    #[test]
    fn an_apps_own_settings_go_over_the_global_ones_and_the_rest_are_on() {
        let mut global = Settings::default();
        global.set(Item::Camera, Some(Mode::Off)).unwrap();
        global.set(Item::Microphone, Some(Mode::Off)).unwrap();
        let mut own = Settings::default();
        own.set(Item::Microphone, Some(Mode::On)).unwrap();
        let applies = own.over(&global);
        assert_eq!(applies.mode(Item::Camera), Mode::Off);
        assert_eq!(applies.mode(Item::Microphone), Mode::On);
        assert_eq!(applies.mode(Item::Contacts), Mode::On);
        assert_eq!(applies.iter().count(), Item::ALL.len());
        // Following the global settings again.
        own.set(Item::Microphone, None).unwrap();
        assert_eq!(own.over(&global).mode(Item::Microphone), Mode::Off);
    }

    #[test]
    fn with_every_item_off_nothing_is_done() {
        let mut off = Settings::default();
        for item in Item::ALL {
            off.set(item, Some(Mode::Off)).unwrap();
        }
        let applies = off.over(&Settings::all_on());
        assert!(!applies.any_on());
        assert!(!applies.mounts().any());
        assert_eq!(applies.hooked(), (false, false));
        assert!(applies.op_targets().is_empty());
    }

    #[test]
    fn op_targets_follow_the_modes() {
        let mut own = Settings::default();
        for item in Item::ALL {
            own.set(item, Some(Mode::Off)).unwrap();
        }
        own.set(Item::Contacts, Some(Mode::On)).unwrap();
        own.set(Item::Location, Some(Mode::Coarse)).unwrap();
        let targets = own.over(&Settings::default()).op_targets();
        assert_eq!(
            targets,
            [
                "android:read_contacts",
                "android:write_contacts",
                // Coarse: the precise location only.
                "android:fine_location",
            ]
        );
        own.set(Item::Location, Some(Mode::On)).unwrap();
        assert!(
            own.over(&Settings::default())
                .op_targets()
                .contains(&"android:wifi_scan")
        );
    }

    #[test]
    fn covering_settings_keep_all_that_either_keeps() {
        let ops = |items: &Settings| -> std::collections::BTreeSet<&str> {
            items.op_targets().into_iter().collect()
        };
        let global = Settings::parse("camera=off");
        let before = Settings::parse("location=coarse,microphone=on");
        let after = Settings::parse("camera=on,location=off,microphone=on");
        let covering = before.covering(&after, &global);
        // What follows the global settings still does.
        assert_eq!(
            covering.to_text(),
            "location=coarse,camera=on,microphone=on"
        );
        let held = ops(&covering.over(&global));
        assert!(held.is_superset(&ops(&before.over(&global))));
        assert!(held.is_superset(&ops(&after.over(&global))));

        // A change that keeps nothing more is covered by what was.
        let wide = Settings::parse("camera=on");
        assert_eq!(
            wide.covering(&Settings::parse("location=coarse"), &global),
            wide
        );
        // The global settings, over every item on.
        assert_eq!(
            Settings::parse("camera=off")
                .covering(
                    &Settings::parse("camera=on,microphone=off"),
                    &Settings::default()
                )
                .to_text(),
            "camera=on"
        );
    }

    #[test]
    fn items_and_modes_have_stable_names() {
        for item in Item::ALL {
            assert_eq!(Item::from_name(item.name()), Some(item));
            let json = serde_json::to_string(&item).unwrap();
            assert_eq!(json, format!("\"{}\"", item.name()));
        }
        assert_eq!(parse_mode("coarse"), Some(Mode::Coarse));
        assert_eq!(serde_json::to_string(&Mode::Coarse).unwrap(), "\"coarse\"");
    }
}
