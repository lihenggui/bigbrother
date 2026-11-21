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

//! Item and mode names shared by the sandbox and its control protocol.
//!
//! This module lives outside `sandbox/` so the command protocol in
//! `control.rs` can use these names without depending on the sandbox engine.

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
    /// What the notifications of every app say, read by a notification
    /// listener the user let the app have.
    NotificationContent,
    /// What is on the screen in every app, read, and acted on, by an
    /// accessibility service the user let the app have.
    ScreenContent,
    /// The network: what the app sends anywhere but to the device itself.
    Network,
    /// Not something kept from the app: where it connects to is recorded.
    NetworkRecord,
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
