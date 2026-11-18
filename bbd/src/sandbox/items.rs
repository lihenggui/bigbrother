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
//! separate answer), for location, coarse (an approximate one), and for
//! location, the camera and the microphone, foreground (the data only
//! while the app is in use). The settings of a sandboxed app name the items
//! it has its own mode for; every other item has the mode of the global
//! settings, and every item is on unless set otherwise.

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
    /// For location, the camera and the microphone: what the system gives
    /// while the app is in use, and nothing in the background, as Android's
    /// "only while using the app".
    Foreground,
}

/// The mode an app op is held to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OpMode {
    /// `AppOpsManager.MODE_IGNORED`: an empty answer, never a refusal.
    Ignored,
    /// `AppOpsManager.MODE_FOREGROUND`: allowed while the app is in the
    /// foreground, ignored in the background. The system keeps it for the
    /// ops of runtime permissions, as it does ignored.
    Foreground,
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
    /// only location can be made coarse, and only what Android itself
    /// grants while an app is in use (location, the camera and the
    /// microphone) can be left to the app in the foreground.
    pub fn allows(self, mode: Mode) -> bool {
        match mode {
            Mode::Off | Mode::On => true,
            Mode::Coarse => self == Item::Location,
            Mode::Foreground => matches!(self, Item::Location | Item::Camera | Item::Microphone),
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
            Item::BodySensors => &[
                "android:body_sensors",
                "android:activity_recognition",
                // From Android 16, which splits the body sensors permission.
                "android:read_heart_rate",
                "android:read_skin_temperature",
                "android:read_oxygen_saturation",
            ],
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

/// The runtime permission location made coarse takes from the app. The
/// system's own "approximate location" is this permission revoked with the
/// coarse one kept, and no mode of the app ops gives the same: with the
/// precise location op ignored, the location service still takes the app
/// for one with precise location, and gives it none at all.
pub const PRECISE_LOCATION: &str = "android.permission.ACCESS_FINE_LOCATION";

/// The ops whose uid mode the system keeps under another op of the same
/// item (their switch op), which holds them in the foreground: the Wi-Fi
/// scan follows the coarse location.
const SWITCHED_WITH_ANOTHER: &[&str] = &["android:wifi_scan"];

/// The mode of some items: an app's own, or the global settings.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Settings(BTreeMap<Item, Mode>);

/// What carries out an item. Most items are only app ops; storage is app
/// ops and mounts, the device identifiers and the installed apps are app
/// ops and an answer of the system server hook.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Part {
    Mounts,
    AppOps,
    AndroidIdHook,
    AppListHook,
}

impl Part {
    pub fn name(self) -> &'static str {
        match self {
            Part::Mounts => "mounts",
            Part::AppOps => "app_ops",
            Part::AndroidIdHook => "android_id_hook",
            Part::AppListHook => "app_list_hook",
        }
    }
}

/// Which parts that serve every app are in place on the device. Whether
/// an app's ops are held is the app's own.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct InPlace {
    pub mounts: bool,
    pub android_id_hook: bool,
    pub app_list_hook: bool,
}

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

    /// The app ops to hold the app to, and the mode of each: ignored for
    /// the items on, foreground for those left to the app in the
    /// foreground. Location made coarse holds none of its ops: it takes a
    /// permission instead ([`Self::taken_permissions`]).
    pub fn op_targets(&self) -> Vec<(&'static str, OpMode)> {
        Item::ALL
            .into_iter()
            .flat_map(|item| self.op_targets_of(item))
            .collect()
    }

    /// The app ops `item` holds the app to in its mode here.
    fn op_targets_of(&self, item: Item) -> Vec<(&'static str, OpMode)> {
        match self.mode(item) {
            Mode::On => item.ops().iter().map(|op| (*op, OpMode::Ignored)).collect(),
            // An op switched with another is held through that one:
            // holding it as well would set the same uid mode twice.
            Mode::Foreground => item
                .ops()
                .iter()
                .filter(|op| !SWITCHED_WITH_ANOTHER.contains(op))
                .map(|op| (*op, OpMode::Foreground))
                .collect(),
            Mode::Off | Mode::Coarse => Vec::new(),
        }
    }

    /// What the app ops helper holds for `item` in its mode here, by name:
    /// its app ops, and the permissions it takes.
    fn held_for(&self, item: Item) -> Vec<&'static str> {
        let mut names: Vec<&'static str> = self
            .op_targets_of(item)
            .into_iter()
            .map(|(op, _)| op)
            .collect();
        if item == Item::Location && self.mode(item) == Mode::Coarse {
            names.push(PRECISE_LOCATION);
        }
        names
    }

    /// The parts of each item that is on that are not in place for an app
    /// with these settings: an item is in effect only with all its parts.
    /// `held` says whether the helper holds the app to an op or permission.
    pub fn unmet(&self, parts: &InPlace, held: impl Fn(&str) -> bool) -> BTreeMap<Item, Vec<Part>> {
        let mut unmet = BTreeMap::new();
        for item in Item::ALL {
            if self.mode(item) == Mode::Off {
                continue;
            }
            let mut missing = Vec::new();
            if matches!(item, Item::Storage | Item::CpuInfo) && !parts.mounts {
                missing.push(Part::Mounts);
            }
            if self.held_for(item).into_iter().any(|name| !held(name)) {
                missing.push(Part::AppOps);
            }
            if item == Item::DeviceIds && !parts.android_id_hook {
                missing.push(Part::AndroidIdHook);
            }
            if item == Item::InstalledApps && !parts.app_list_hook {
                missing.push(Part::AppListHook);
            }
            if !missing.is_empty() {
                unmet.insert(item, missing);
            }
        }
        unmet
    }

    /// The runtime permissions to keep revoked: the precise location while
    /// location is coarse.
    pub fn taken_permissions(&self) -> Vec<&'static str> {
        if self.mode(Item::Location) == Mode::Coarse {
            vec![PRECISE_LOCATION]
        } else {
            Vec::new()
        }
    }

    pub fn iter(&self) -> impl Iterator<Item = (Item, Mode)> + '_ {
        self.0.iter().map(|(item, mode)| (*item, *mode))
    }

    /// Settings that keep from an app all that `self` or `other` keeps,
    /// both over `global`: for each item, the mode of whichever keeps all
    /// the other keeps, `self`'s where both do, and on where neither does
    /// (coarse keeps the precise location, foreground what the app is
    /// given in the background). What is written down while an app changes
    /// from the one to the other, so that a daemon that stopped half way
    /// takes it up again with every app op, mount and hook answer it may
    /// have been given.
    pub fn covering(&self, other: &Settings, global: &Settings) -> Settings {
        let (mine, theirs) = (self.over(global), other.over(global));
        let mut covering = Settings::default();
        for item in Item::ALL {
            let (my_mode, their_mode) = (mine.mode(item), theirs.mode(item));
            let mode = if keeps_all_of(my_mode, their_mode) {
                self.get(item)
            } else if keeps_all_of(their_mode, my_mode) {
                other.get(item)
            } else {
                Some(Mode::On)
            };
            if let Some(mode) = mode {
                covering.0.insert(item, mode);
            }
        }
        covering
    }
}

/// Whether `wide` keeps from the app all that `narrow` keeps: on keeps
/// everything, off nothing, and coarse and foreground each keep what the
/// other does not.
fn keeps_all_of(wide: Mode, narrow: Mode) -> bool {
    wide == narrow || wide == Mode::On || narrow == Mode::Off
}

pub fn mode_name(mode: Mode) -> &'static str {
    match mode {
        Mode::Off => "off",
        Mode::On => "on",
        Mode::Coarse => "coarse",
        Mode::Foreground => "foreground",
    }
}

pub fn parse_mode(name: &str) -> Option<Mode> {
    [Mode::Off, Mode::On, Mode::Coarse, Mode::Foreground]
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
        // Foreground is for what Android grants while an app is in use.
        for item in [Item::Location, Item::Camera, Item::Microphone] {
            settings.set(item, Some(Mode::Foreground)).unwrap();
        }
        for item in [Item::Contacts, Item::Storage, Item::BodySensors] {
            assert!(settings.set(item, Some(Mode::Foreground)).is_err());
        }
        assert_eq!(
            Settings::parse("contacts=foreground,camera=foreground").to_text(),
            "camera=foreground"
        );
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
    fn an_item_is_in_effect_only_with_all_its_parts() {
        let all = InPlace {
            mounts: true,
            android_id_hook: true,
            app_list_hook: true,
        };
        let items = Settings::all_on();
        assert!(items.unmet(&all, |_| true).is_empty());

        // The ANDROID_ID answered but the phone's state not held: the
        // device identifiers are not in effect, for that part only.
        let unmet = items.unmet(&all, |op| op != "android:read_phone_state");
        assert_eq!(unmet.len(), 1);
        assert_eq!(unmet[&Item::DeviceIds], [Part::AppOps]);

        let none = InPlace::default();
        let unmet = items.unmet(&none, |_| false);
        assert_eq!(unmet[&Item::Storage], [Part::Mounts, Part::AppOps]);
        assert_eq!(unmet[&Item::CpuInfo], [Part::Mounts]);
        assert_eq!(unmet[&Item::DeviceIds], [Part::AppOps, Part::AndroidIdHook]);
        assert_eq!(
            unmet[&Item::InstalledApps],
            [Part::AppOps, Part::AppListHook]
        );
        assert_eq!(unmet[&Item::Camera], [Part::AppOps]);
    }

    #[test]
    fn items_that_are_off_coarse_or_foreground_ask_only_for_what_they_do() {
        let mut own = Settings::default();
        for item in Item::ALL {
            own.set(item, Some(Mode::Off)).unwrap();
        }
        own.set(Item::Location, Some(Mode::Coarse)).unwrap();
        let items = own.over(&Settings::default());
        // Coarse takes the precise location permission and holds no op: the
        // location ops not being held is nothing missing.
        let held = |name: &str| name == PRECISE_LOCATION;
        assert!(items.unmet(&InPlace::default(), held).is_empty());
        let unmet = items.unmet(&InPlace::default(), |_| false);
        assert_eq!(unmet.keys().copied().collect::<Vec<_>>(), [Item::Location]);

        // Foreground holds the camera op, at another mode.
        own.set(Item::Camera, Some(Mode::Foreground)).unwrap();
        let items = own.over(&Settings::default());
        let unmet = items.unmet(&InPlace::default(), |name| name == PRECISE_LOCATION);
        assert_eq!(unmet.keys().copied().collect::<Vec<_>>(), [Item::Camera]);
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
        assert!(applies.taken_permissions().is_empty());
    }

    #[test]
    fn op_targets_follow_the_modes() {
        let mut own = Settings::default();
        for item in Item::ALL {
            own.set(item, Some(Mode::Off)).unwrap();
        }
        own.set(Item::Contacts, Some(Mode::On)).unwrap();
        own.set(Item::Location, Some(Mode::Coarse)).unwrap();
        let coarse = own.over(&Settings::default());
        // Coarse holds no location op: it takes the precise location
        // permission.
        assert_eq!(
            coarse.op_targets(),
            [
                ("android:read_contacts", OpMode::Ignored),
                ("android:write_contacts", OpMode::Ignored)
            ]
        );
        assert_eq!(coarse.taken_permissions(), [PRECISE_LOCATION]);
        own.set(Item::Location, Some(Mode::On)).unwrap();
        let on = own.over(&Settings::default());
        assert!(
            on.op_targets()
                .contains(&("android:wifi_scan", OpMode::Ignored))
        );
        assert!(on.taken_permissions().is_empty());
    }

    #[test]
    fn foreground_holds_the_ops_in_the_foreground() {
        let mut own = Settings::default();
        for item in Item::ALL {
            own.set(item, Some(Mode::Off)).unwrap();
        }
        for item in [Item::Location, Item::Camera, Item::Microphone] {
            own.set(item, Some(Mode::Foreground)).unwrap();
        }
        let foreground = own.over(&Settings::default());
        // The Wi-Fi scan goes with the coarse location, its switch op.
        assert_eq!(
            foreground.op_targets(),
            [
                ("android:coarse_location", OpMode::Foreground),
                ("android:fine_location", OpMode::Foreground),
                ("android:camera", OpMode::Foreground),
                ("android:record_audio", OpMode::Foreground),
            ]
        );
        assert!(foreground.taken_permissions().is_empty());
        assert!(foreground.any_on());
        assert!(!foreground.mounts().any());
    }

    #[test]
    fn covering_settings_keep_all_that_either_keeps() {
        let ops = |items: &Settings| -> std::collections::BTreeSet<&str> {
            items.op_targets().into_iter().map(|(op, _)| op).collect()
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
        assert_eq!(
            covering.over(&global).taken_permissions(),
            [PRECISE_LOCATION]
        );

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
    fn coarse_and_foreground_are_covered_by_on() {
        let none = Settings::default();
        let covers = |before: &str, after: &str| {
            Settings::parse(before)
                .covering(&Settings::parse(after), &none)
                .to_text()
        };
        // Each keeps what the other gives: the precise location, the
        // location in the background.
        assert_eq!(
            covers("location=coarse", "location=foreground"),
            "location=on"
        );
        assert_eq!(
            covers("location=foreground", "location=coarse"),
            "location=on"
        );
        // Foreground keeps more than off, and on more than foreground.
        assert_eq!(
            covers("camera=off", "camera=foreground"),
            "camera=foreground"
        );
        assert_eq!(
            covers("camera=foreground", "camera=off"),
            "camera=foreground"
        );
        assert_eq!(
            covers("microphone=foreground", "microphone=on"),
            "microphone=on"
        );
        // Following the global settings, which make it foreground.
        let global = Settings::parse("location=foreground");
        let covering = Settings::default().covering(&Settings::parse("location=coarse"), &global);
        assert_eq!(covering.to_text(), "location=on");
        let held: std::collections::BTreeSet<&str> = covering
            .over(&global)
            .op_targets()
            .into_iter()
            .map(|(op, _)| op)
            .collect();
        assert!(held.contains("android:coarse_location") && held.contains("android:fine_location"));
        assert_eq!(
            Settings::parse("location=foreground").covering(&Settings::default(), &global),
            Settings::parse("location=foreground")
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
        assert_eq!(parse_mode("foreground"), Some(Mode::Foreground));
        assert_eq!(
            serde_json::to_string(&Mode::Foreground).unwrap(),
            "\"foreground\""
        );
    }
}
