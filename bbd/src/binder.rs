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

//! Calls to system services that hand out information about the user.
//!
//! Most of what an app learns about the user it does not read from a file:
//! it asks a system service over binder for the installed apps, the
//! clipboard, the location, the accounts. The kernel side sees every such
//! call as an interface name and a transaction code (`bb-ebpf`, `binder_ioctl`)
//! and reports the ones listed here.
//!
//! Which code is which method depends on the Android version and on the
//! manufacturer's additions, so the list below names methods, and the codes
//! are looked up on the device at startup: the app ships a small program
//! that prints them from the framework's own classes ([`load`]). A method
//! the device doesn't have is simply not watched.
//!
//! A recorded call is stored as `binder:<interface>/<method>`, followed by
//! `#<package>` for the calls that ask about one particular app, or by
//! `@<sensor type>` for a sensor turned on (see `sensors`). Reads of the few
//! system settings that identify the device are stored as
//! `setting:<table>/<name>`.
//!
//! Only the call is seen, not the answer: a record means the app asked, not
//! that it was given anything.

use std::collections::HashMap;

use bb_common::{
    BINDER_CODES, BINDER_KEY_HEAD, BINDER_KEY_LEN, BINDER_KEY_TAIL, BINDER_MAX_NAME,
    BINDER_MIN_NAME, BINDER_UNNAMED_KEY, BINDER_VALUE_WORDS, BINDER_WAKE, BinderKey, BinderValue,
};

use crate::{rules::topic, sensors};

/// Prefix of a stored call.
pub const SCHEME: &str = "binder:";

/// A topic and the methods that fall under it.
type TopicMethods = (&'static str, &'static [&'static str]);

/// (interface, [(topic, methods)]). Method names from several Android
/// versions are listed side by side; each device has some of them.
const WATCHED: &[(&str, &[TopicMethods])] = &[
    (
        "android.content.pm.IPackageManager",
        &[(
            topic::INSTALLED_APPS,
            &[
                "getInstalledPackages",
                "getInstalledApplications",
                "getPackagesHoldingPermissions",
                "getPackagesForUid",
                "getNameForUid",
                "getPackageInfo",
                "getApplicationInfo",
                "getInstallerPackageName",
                "getInstallSourceInfo",
                "getPackageUid",
                "isPackageAvailable",
            ],
        )],
    ),
    // Reads, listening for changes and writes are told apart by the app.
    (
        "android.content.IClipboard",
        &[(
            topic::CLIPBOARD,
            &[
                "getPrimaryClip",
                "addPrimaryClipChangedListener",
                "setPrimaryClip",
                "setPrimaryClipAsPackage",
                "clearPrimaryClip",
            ],
        )],
    ),
    (
        "android.location.ILocationManager",
        &[(
            topic::LOCATION,
            &[
                "getLastLocation",
                "getCurrentLocation",
                "registerLocationListener",
                "registerLocationPendingIntent",
                "requestLocationUpdates",
                "registerGnssStatusCallback",
            ],
        )],
    ),
    (
        "android.net.wifi.IWifiManager",
        &[(
            topic::WIFI_INFO,
            &[
                "getScanResults",
                "getConnectionInfo",
                "getConfiguredNetworks",
                "getPrivilegedConfiguredNetworks",
            ],
        )],
    ),
    (
        "com.android.internal.telephony.IPhoneSubInfo",
        &[(
            topic::PHONE_IDENTITY,
            &[
                "getDeviceId",
                "getDeviceIdForPhone",
                "getDeviceIdWithFeature",
                "getImeiForSubscriber",
                "getSubscriberId",
                "getSubscriberIdForSubscriber",
                "getSubscriberIdWithFeature",
                "getIccSerialNumber",
                "getIccSerialNumberForSubscriber",
                "getIccSerialNumberWithFeature",
                "getLine1Number",
                "getLine1NumberForSubscriber",
            ],
        )],
    ),
    (
        "com.android.internal.telephony.ITelephony",
        &[
            (
                topic::PHONE_IDENTITY,
                &[
                    "getDeviceId",
                    "getDeviceIdWithFeature",
                    "getImeiForSlot",
                    "getMeidForSlot",
                    "getLine1NumberForDisplay",
                ],
            ),
            // The cell towers in range locate the device.
            (
                topic::LOCATION,
                &[
                    "getAllCellInfo",
                    "getCellLocation",
                    "requestCellInfoUpdate",
                    "getNeighboringCellInfo",
                ],
            ),
        ],
    ),
    (
        "android.accounts.IAccountManager",
        &[(
            topic::ACCOUNTS,
            &[
                "getAccounts",
                "getAccountsAsUser",
                "getAccountsForPackage",
                "getAccountsByTypeForPackage",
                "getAccountsByFeatures",
                "getAccountsAndVisibilityForPackage",
            ],
        )],
    ),
    (
        "android.app.IActivityManager",
        &[(
            topic::RUNNING_APPS,
            &[
                "getRunningAppProcesses",
                "getServices",
                "getTasks",
                "getRecentTasks",
            ],
        )],
    ),
    (
        "android.app.IActivityTaskManager",
        &[(topic::RUNNING_APPS, &["getTasks", "getRecentTasks"])],
    ),
    (
        "android.app.usage.IUsageStatsManager",
        &[(
            topic::USAGE_STATS,
            &["queryUsageStats", "queryEvents", "queryConfigurationStats"],
        )],
    ),
    (
        "com.android.internal.view.IInputMethodManager",
        &[(
            topic::ENABLED_SERVICES,
            &["getEnabledInputMethodList", "getInputMethodList"],
        )],
    ),
    (
        "android.gui.SensorServer",
        &[(topic::SENSORS, &["createSensorEventConnection"])],
    ),
    // Never stored as it is; see `sensors`. Only turning a sensor on is
    // stored as a call; turning it off and the connection's other methods
    // end what `sensors` keeps of it.
    (
        sensors::CONNECTION,
        &[(topic::SENSORS, &[sensors::ENABLE_DISABLE])],
    ),
    // Never stored under this name; see `CONTENT_PROVIDER`.
    (CONTENT_PROVIDER, &[(topic::ANDROID_ID, &["CALL"])]),
    (
        "android.view.accessibility.IAccessibilityManager",
        &[(
            topic::ENABLED_SERVICES,
            &[
                "getEnabledAccessibilityServiceList",
                "getInstalledAccessibilityServiceList",
            ],
        )],
    ),
];

/// Interfaces written in C++, which the device can't be asked about: their
/// codes are taken from the Android sources, where these have not moved
/// since the interface was introduced. (interface, method, code).
const FIXED: &[(&str, &str, u32)] = &[
    // The first step of listening to any sensor
    // (frameworks/native/libs/sensor/ISensorServer.cpp).
    ("android.gui.SensorServer", "createSensorEventConnection", 2),
    // frameworks/native/libs/sensor/ISensorEventConnection.cpp, the same
    // from Android 11 to 16.
    (sensors::CONNECTION, sensors::ENABLE_DISABLE, 2),
    (sensors::CONNECTION, sensors::SET_EVENT_RATE, 3),
];

/// The one call watched although it carries no interface name: closing a
/// sensor connection sends an empty parcel. The kernel side reports such
/// calls by code alone (`BINDER_UNNAMED_KEY`), and `sensors` keeps only
/// those that went to a connection it knows.
const UNNAMED: (&str, &str, u32) = (sensors::CONNECTION, sensors::DESTROY, 6);

/// Every content provider is called through this one interface; what is
/// wanted from it is the reads of system settings, which go to the settings
/// provider as `call("GET_<table>", "<name>")`. Its codes are constants of
/// the interface itself, which the catalog program prints by their name.
const CONTENT_PROVIDER: &str = "android.content.IContentProvider";
const SETTINGS_AUTHORITY: &str = "settings";

/// Prefix of a stored settings read.
pub const SETTING_SCHEME: &str = "setting:";

/// (table, name, topic) of the settings that identify the device.
const SETTINGS: &[(&str, &str, &str)] = &[
    ("secure", "android_id", topic::ANDROID_ID),
    ("secure", "bluetooth_name", topic::DEVICE_NAME),
    ("secure", "bluetooth_address", topic::MAC_ADDRESS),
    ("global", "device_name", topic::DEVICE_NAME),
];

/// Methods whose first argument is the name of the package asked about. The
/// name is stored with the call; a call about the caller itself is dropped,
/// since every app looks itself up as a matter of course.
const ABOUT_A_PACKAGE: &[&str] = &[
    "getPackageInfo",
    "getApplicationInfo",
    "getInstallerPackageName",
    "getInstallSourceInfo",
    "getPackageUid",
    "isPackageAvailable",
];

/// Packages the framework itself looks up inside every app's process: the
/// platform package for its resources, and the WebView implementation when
/// an app first shows a web page. Asking about these says nothing about the
/// app.
const FRAMEWORK_LOOKUPS: &[&str] = &[
    "android",
    "com.google.android.webview",
    "com.android.webview",
];

/// Apps that only a rooted or modified device has: root managers, tools
/// that hide root from other apps, and hooking frameworks. An app asking
/// whether one of these is installed is checking the device for root, which
/// says more than that it looked at the installed apps. Only fixed package
/// names can be listed; Magisk can be installed under a random one.
const ROOT_APPS: &[&str] = &[
    // Root managers.
    "com.topjohnwu.magisk",
    "io.github.huskydg.magisk",
    "io.github.vvb2060.magisk",
    "me.weishu.kernelsu",
    "me.bmax.apatch",
    "eu.chainfire.supersu",
    "com.koushikdutta.superuser",
    "com.noshufou.android.su",
    "com.noshufou.android.su.elite",
    "com.thirdparty.superuser",
    "com.yellowes.su",
    "me.phh.superuser",
    "com.kingroot.kinguser",
    "com.kingo.root",
    "com.smedialink.oneclickroot",
    "com.zhiqupk.root.global",
    "com.alephzain.framaroot",
    // Tools that hide root.
    "com.devadvance.rootcloak",
    "com.devadvance.rootcloakplus",
    "com.amphoras.hidemyroot",
    "com.amphoras.hidemyrootadfree",
    "com.formyhm.hideroot",
    "com.formyhm.hiderootPremium",
    "com.zachspong.temprootremovejb",
    "com.ramdroid.appquarantine",
    "com.tsng.hidemyapplist",
    // Hooking frameworks.
    "de.robv.android.xposed.installer",
    "org.meowcat.edxposed.manager",
    "org.lsposed.manager",
    "com.saurik.substrate",
    // Need root to work.
    "stericson.busybox",
    "com.chelpus.lackypatch",
    "com.dimonvideo.luckypatcher",
];

/// Put before the package name in a stored call about a package that was
/// not installed at the time: `.../getPackageInfo#!com.example`. Package
/// names cannot hold it.
const ABSENT: char = '!';

/// The package a stored call asked about, if it names one.
pub fn asked_package(subject: &str) -> Option<&str> {
    subject
        .strip_prefix(SCHEME)?
        .split_once('#')
        .map(|(_, package)| package)
}

/// A stored call about a package, marked as asking about one that is not
/// installed.
pub fn mark_absent(subject: &str) -> Option<String> {
    let (call, package) = subject.split_once('#')?;
    Some(format!("{call}#{ABSENT}{package}"))
}

/// Methods whose first argument is a uid. The framework asks these about
/// the app's own uid on its behalf; only questions about others are kept.
const ABOUT_A_UID: &[&str] = &["getPackagesForUid", "getNameForUid"];

/// The interfaces to ask the device about.
pub fn interfaces() -> impl Iterator<Item = &'static str> {
    WATCHED.iter().map(|(interface, _)| *interface)
}

fn topic_of_method(interface: &str, method: &str) -> Option<&'static str> {
    let (_, groups) = WATCHED.iter().find(|(name, _)| *name == interface)?;
    groups
        .iter()
        .find(|(_, methods)| methods.contains(&method))
        .map(|(topic, _)| *topic)
}

/// What a stored call reveals. `None` if `subject` is not a watched call.
pub fn topic_of(subject: &str) -> Option<&'static str> {
    if let Some(setting) = subject.strip_prefix(SETTING_SCHEME) {
        let (table, name) = setting.split_once('/')?;
        return SETTINGS
            .iter()
            .find(|(t, n, _)| *t == table && *n == name)
            .map(|(.., topic)| *topic);
    }
    let call = subject.strip_prefix(SCHEME)?;
    let (call, package) = call
        .split_once('#')
        .map_or((call, None), |(call, package)| (call, Some(package)));
    let call = call
        .split_once(sensors::SENSOR_MARK)
        .map_or(call, |(call, _)| call);
    let (interface, method) = call.rsplit_once('/')?;
    let topic = topic_of_method(interface, method)?;
    let Some(package) = package.filter(|_| topic == topic::INSTALLED_APPS) else {
        return Some(topic);
    };
    let (absent, package) = match package.strip_prefix(ABSENT) {
        Some(package) => (true, package),
        None => (false, package),
    };
    Some(if ROOT_APPS.contains(&package) {
        topic::ROOT_CHECK
    } else if absent {
        // An app has no reason to ask about one that isn't there, unless it
        // is going down a list to see which of them the device has.
        topic::APP_PROBE
    } else {
        topic
    })
}

/// The map key the kernel side computes for an interface name, or `None`
/// for a name too short or too long to be watched (see `bb_common`).
pub fn key(interface: &str) -> Option<BinderKey> {
    let units: Vec<u16> = interface.encode_utf16().collect();
    let len = u32::try_from(units.len()).ok()?;
    if !(BINDER_MIN_NAME..=BINDER_MAX_NAME).contains(&len) {
        return None;
    }
    let bytes: Vec<u8> = units.iter().flat_map(|unit| unit.to_le_bytes()).collect();
    let mut key = [0u8; BINDER_KEY_LEN];
    key[..4].copy_from_slice(&len.to_ne_bytes());
    key[4..4 + BINDER_KEY_HEAD].copy_from_slice(&bytes[..BINDER_KEY_HEAD]);
    key[4 + BINDER_KEY_HEAD..].copy_from_slice(&bytes[bytes.len() - BINDER_KEY_TAIL..]);
    Some(key)
}

/// The watched methods this device has, by transaction code.
#[derive(Default)]
pub struct Catalog {
    /// (index into `WATCHED`, code) -> method name.
    methods: HashMap<(u32, u32), &'static str>,
    /// The one call watched by code alone, if its interface is watched:
    /// (index into `WATCHED`, code).
    unnamed: Option<(u32, u32)>,
    /// How many of them the device reported, as opposed to `FIXED`.
    reflected: usize,
}

impl Catalog {
    /// From the output of the app's `BinderCatalog`: one line per method of
    /// each interface, `<interface>\t<code>\t<method>`.
    pub fn parse(output: &str) -> Self {
        let mut methods = HashMap::new();
        for line in output.lines() {
            let mut fields = line.split('\t');
            let (Some(interface), Some(code), Some(method)) =
                (fields.next(), fields.next(), fields.next())
            else {
                continue;
            };
            let Some(id) = WATCHED.iter().position(|(name, _)| *name == interface) else {
                continue;
            };
            let Ok(code) = code.parse::<u32>() else {
                continue;
            };
            // The static spelling of the name, and only watched methods.
            let name = WATCHED[id]
                .1
                .iter()
                .flat_map(|(_, methods)| methods.iter())
                .find(|name| **name == method);
            if let Some(name) = name
                && code < BINDER_CODES
            {
                methods.insert((id as u32, code), *name);
            }
        }
        let reflected = methods.len();
        for (interface, method, code) in FIXED {
            if let Some(id) = WATCHED.iter().position(|(name, _)| name == interface) {
                methods.insert((id as u32, *code), *method);
            }
        }
        let (interface, method, code) = UNNAMED;
        let unnamed = WATCHED
            .iter()
            .position(|(name, _)| *name == interface)
            .map(|id| (id as u32, code));
        if let Some(key) = unnamed {
            methods.insert(key, method);
        }
        Self {
            methods,
            unnamed,
            reflected,
        }
    }

    pub fn len(&self) -> usize {
        self.methods.len()
    }

    /// What to put in the kernel side's map.
    pub fn map_entries(&self) -> Vec<(BinderKey, BinderValue)> {
        let mut values: HashMap<u32, BinderValue> = HashMap::new();
        for (id, code) in self
            .methods
            .keys()
            .filter(|method| Some(**method) != self.unnamed)
        {
            values
                .entry(*id)
                .or_insert_with(|| map_value(*id, wakes(*id)))[1 + (*code / 64) as usize] |=
                1 << (*code % 64);
        }
        let mut entries: Vec<(BinderKey, BinderValue)> = values
            .into_iter()
            .filter_map(|(id, value)| Some((key(WATCHED.get(id as usize)?.0)?, value)))
            .collect();
        if let Some((id, code)) = self.unnamed {
            let mut value = map_value(id, false);
            value[1 + (code / 64) as usize] |= 1 << (code % 64);
            entries.push((BINDER_UNNAMED_KEY, value));
        }
        entries
    }

    /// A call to a sensor connection, which `sensors` keeps track of rather
    /// than it being stored as it is. `None` for any other call, or one whose
    /// arguments can't be read.
    pub fn sensor_request(
        &self,
        interface: u32,
        code: u32,
        args: &[u8],
    ) -> Option<sensors::Request> {
        let method = self.methods.get(&(interface, code))?;
        if WATCHED.get(interface as usize)?.0 != sensors::CONNECTION {
            return None;
        }
        sensors::request(method, args)
    }

    /// What to store for a call reported by the kernel side. `args` is the
    /// start of the call's arguments; `own_uids` and `own_packages` are the
    /// caller's: its process's uid and, for an isolated process, also its
    /// app's. `None` for a call that isn't watched or that is about the
    /// caller.
    pub fn subject(
        &self,
        interface: u32,
        code: u32,
        args: &[u8],
        own_uids: &[u32],
        own_packages: &[String],
    ) -> Option<String> {
        let method = self.methods.get(&(interface, code))?;
        let name = WATCHED.get(interface as usize)?.0;
        if name == CONTENT_PROVIDER {
            return setting_read(args);
        }
        if name == sensors::CONNECTION {
            return None;
        }
        let mut subject = format!("{SCHEME}{name}/{method}");
        if ABOUT_A_UID.contains(method)
            && let Some(asked) = args.first_chunk::<4>()
            && own_uids.contains(&u32::from_le_bytes(*asked))
        {
            return None;
        }
        if ABOUT_A_PACKAGE.contains(method)
            && let Some(package) = package_argument(args)
        {
            if own_packages.contains(&package) || FRAMEWORK_LOOKUPS.contains(&package.as_str()) {
                return None;
            }
            subject.push('#');
            subject.push_str(&package);
        }
        Some(subject)
    }
}

/// A map value for the interface `id`, with no code watched yet.
/// Whether calls to the interface `id` wake the daemon at once.
fn wakes(id: u32) -> bool {
    WATCHED
        .get(id as usize)
        .is_some_and(|(name, _)| *name == sensors::CONNECTION)
}

/// Calls to the sensor connection wake the daemon at once (`BINDER_WAKE`):
/// turning a sensor on starts a session, whose process is then checked
/// while it runs. Closing a connection, the call without a name, need not.
fn map_value(id: u32, wake: bool) -> BinderValue {
    let mut value = [0u64; BINDER_VALUE_WORDS];
    value[0] = u64::from(id);
    if wake {
        value[0] |= BINDER_WAKE;
    }
    value
}

/// The device-identifying setting a provider call reads, if that is what it
/// is. The call's arguments are the caller's identity (of a length that
/// varies), then the provider's authority, the method and its argument as
/// strings. Rather than depend on the layout of the first, the three strings
/// are looked for.
fn setting_read(args: &[u8]) -> Option<String> {
    if !contains_string(args, SETTINGS_AUTHORITY) {
        return None;
    }
    SETTINGS
        .iter()
        .find(|(table, name, _)| {
            contains_string(args, &format!("GET_{table}")) && contains_string(args, name)
        })
        .map(|(table, name, _)| format!("{SETTING_SCHEME}{table}/{name}"))
}

/// Whether `bytes` holds `text` as a whole parcel string: its length in
/// UTF-16 code units, the units, and a terminator.
fn contains_string(bytes: &[u8], text: &str) -> bool {
    let units: Vec<u16> = text.encode_utf16().collect();
    let mut needle = (units.len() as i32).to_le_bytes().to_vec();
    needle.extend(units.iter().flat_map(|unit| unit.to_le_bytes()));
    needle.extend([0, 0]);
    bytes.windows(needle.len()).any(|window| window == needle)
}

/// A package name written to a parcel as a string: a 32-bit length in UTF-16
/// code units, then the units. `None` if the bytes don't hold one.
fn package_argument(args: &[u8]) -> Option<String> {
    const MAX_CHARS: usize = 255;
    let len = i32::from_le_bytes(args.get(..4)?.try_into().ok()?);
    let len = usize::try_from(len)
        .ok()
        .filter(|l| (1..=MAX_CHARS).contains(l))?;
    let units: Vec<u16> = args
        .get(4..4 + len * 2)?
        .as_chunks::<2>()
        .0
        .iter()
        .map(|pair| u16::from_le_bytes(*pair))
        .collect();
    let name = String::from_utf16(&units).ok()?;
    name.bytes()
        .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_')
        .then_some(name)
}

// ---- asking the device ------------------------------------------------------

const HELPER_CLASS: &str = "BinderCatalog";

/// Run the app's catalog program and read the device's transaction codes.
pub fn load() -> anyhow::Result<Catalog> {
    let catalog = Catalog::parse(&crate::helper::run(HELPER_CLASS, interfaces())?);
    anyhow::ensure!(
        catalog.reflected > 0,
        "the catalog program found none of the watched methods"
    );
    Ok(catalog)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn string16(text: &str) -> Vec<u8> {
        let units: Vec<u16> = text.encode_utf16().collect();
        let mut bytes = (units.len() as i32).to_le_bytes().to_vec();
        bytes.extend(units.iter().flat_map(|u| u.to_le_bytes()));
        bytes.extend([0, 0]);
        bytes
    }

    const CATALOG: &str = "android.content.pm.IPackageManager\t3\tgetPackageInfo\n\
        android.content.pm.IPackageManager\t70\tgetInstalledPackages\n\
        android.content.pm.IPackageManager\t9\tcheckPermission\n\
        android.content.IClipboard\t4\tgetPrimaryClip\n\
        android.content.IClipboard\t9999\taddPrimaryClipChangedListener\n\
        android.os.IPowerManager\t1\tacquireWakeLock\n\
        android.content.IContentProvider\t21\tCALL\n\
        android.content.IContentProvider\t1\tQUERY\n\
        garbage line\n";

    #[test]
    fn the_catalog_keeps_watched_methods_only() {
        let catalog = Catalog::parse(CATALOG);
        // The fixed codes, and the one call watched without a name.
        assert_eq!((catalog.reflected, catalog.len()), (4, 4 + FIXED.len() + 1));
        let own = ["com.me".to_string()];
        assert_eq!(
            catalog.subject(0, 70, &[], &[10100], &own).as_deref(),
            Some("binder:android.content.pm.IPackageManager/getInstalledPackages")
        );
        assert_eq!(
            catalog.subject(1, 4, &[], &[10100], &own).as_deref(),
            Some("binder:android.content.IClipboard/getPrimaryClip")
        );
        // Not watched, unknown code, unknown interface.
        assert_eq!(catalog.subject(0, 9, &[], &[10100], &own), None);
        assert_eq!(catalog.subject(0, 71, &[], &[10100], &own), None);
        assert_eq!(catalog.subject(99, 1, &[], &[10100], &own), None);
    }

    #[test]
    fn calls_about_a_package_name_it_unless_it_is_the_callers() {
        let catalog = Catalog::parse(CATALOG);
        let own = ["com.me".to_string()];
        assert_eq!(
            catalog
                .subject(0, 3, &string16("com.tencent.mm"), &[10100], &own)
                .as_deref(),
            Some("binder:android.content.pm.IPackageManager/getPackageInfo#com.tencent.mm")
        );
        assert_eq!(
            catalog.subject(0, 3, &string16("com.me"), &[10100], &own),
            None
        );
        // Nor what the framework looks up on every app's behalf.
        let webview = string16("com.google.android.webview");
        assert_eq!(catalog.subject(0, 3, &webview, &[10100], &own), None);
        // Arguments that can't be read leave the call, without a name.
        for args in [&[][..], &[5, 0, 0, 0, b'a', 0], &string16("not a package!")] {
            assert_eq!(
                catalog.subject(0, 3, args, &[10100], &own).as_deref(),
                Some("binder:android.content.pm.IPackageManager/getPackageInfo")
            );
        }
    }

    #[test]
    fn calls_about_the_callers_own_uid_are_skipped() {
        let catalog = Catalog::parse("android.content.pm.IPackageManager\t40\tgetPackagesForUid\n");
        let about = |uid: u32| catalog.subject(0, 40, &uid.to_le_bytes(), &[10100], &[]);
        assert_eq!(about(10100), None);
        assert_eq!(
            about(10200).as_deref(),
            Some("binder:android.content.pm.IPackageManager/getPackagesForUid")
        );
        // An isolated process asking about its own uid is about itself too,
        // although it is recorded under its app's.
        let isolated = |uid: u32| catalog.subject(0, 40, &uid.to_le_bytes(), &[99012, 10100], &[]);
        assert_eq!(isolated(99012), None);
        assert_eq!(isolated(10100), None);
        assert!(isolated(10200).is_some());
    }

    #[test]
    fn the_kernel_map_has_a_bit_per_code() {
        let entries = Catalog::parse(CATALOG).map_entries();
        // Four interfaces from the catalog, two fixed ones, and the calls
        // without a name.
        assert_eq!(entries.len(), 6);
        let unnamed: Vec<_> = entries
            .iter()
            .filter(|(key, _)| *key == BINDER_UNNAMED_KEY)
            .collect();
        assert_eq!(unnamed.len(), 1);
        let connection = WATCHED
            .iter()
            .position(|(name, _)| *name == sensors::CONNECTION)
            .unwrap() as u64;
        assert_eq!(unnamed[0].1[0], connection);
        assert_eq!(unnamed[0].1[1], 1 << 6);
        // Named calls to the connection: enableDisable and setEventRate,
        // the only calls that wake the daemon at once.
        let named = entries
            .iter()
            .find(|(key, value)| value[0] == connection | BINDER_WAKE && *key != BINDER_UNNAMED_KEY)
            .unwrap();
        assert_eq!(named.1[1], 1 << 2 | 1 << 3);
        assert_eq!(
            entries
                .iter()
                .filter(|(_, value)| value[0] & BINDER_WAKE != 0)
                .count(),
            1
        );
        let packages = entries.iter().find(|(_, value)| value[0] == 0).unwrap();
        assert_eq!(
            packages.0,
            key("android.content.pm.IPackageManager").unwrap()
        );
        assert_eq!(packages.1[1], 1 << 3);
        assert_eq!(packages.1[2], 1 << (70 - 64));
        assert!(packages.1[3..].iter().all(|word| *word == 0));
    }

    #[test]
    fn only_reads_of_identifying_settings_are_recorded() {
        let catalog = Catalog::parse(CATALOG);
        let provider = WATCHED
            .iter()
            .position(|(name, _)| *name == CONTENT_PROVIDER)
            .unwrap() as u32;
        let call = |authority: &str, method: &str, arg: &str| {
            // Whatever precedes the strings is skipped over.
            let mut args = vec![0x5a; 137];
            for text in [authority, method, arg] {
                args.extend(string16(text));
            }
            catalog.subject(provider, 21, &args, &[10100], &[])
        };
        assert_eq!(
            call("settings", "GET_secure", "android_id").as_deref(),
            Some("setting:secure/android_id")
        );
        assert_eq!(
            call("settings", "GET_global", "device_name").as_deref(),
            Some("setting:global/device_name")
        );
        // Other settings, other tables, other providers.
        assert_eq!(call("settings", "GET_system", "screen_brightness"), None);
        assert_eq!(call("settings", "GET_global", "android_id"), None);
        assert_eq!(call("settings", "GET_secure", "android_id_x"), None);
        assert_eq!(call("com.example", "GET_secure", "android_id"), None);
        assert_eq!(
            topic_of("setting:secure/android_id"),
            Some(topic::ANDROID_ID)
        );
        assert_eq!(topic_of("setting:system/font_scale"), None);
    }

    #[test]
    fn sensors_are_watched_without_the_devices_help() {
        let catalog = Catalog::parse("");
        assert_eq!(catalog.reflected, 0);
        let sensors = WATCHED
            .iter()
            .position(|(name, _)| *name == "android.gui.SensorServer")
            .unwrap() as u32;
        assert_eq!(
            catalog.subject(sensors, 2, &[], &[10100], &[]).as_deref(),
            Some("binder:android.gui.SensorServer/createSensorEventConnection")
        );
    }

    #[test]
    fn sensor_connection_calls_are_requests_not_subjects() {
        let catalog = Catalog::parse("");
        let connection = WATCHED
            .iter()
            .position(|(name, _)| *name == sensors::CONNECTION)
            .unwrap() as u32;
        let mut enable = 3i32.to_le_bytes().to_vec();
        enable.extend(1i32.to_le_bytes());
        enable.extend(5_000_000i64.to_le_bytes());
        enable.extend([0; 12]);
        assert_eq!(
            catalog.sensor_request(connection, 2, &enable),
            Some(sensors::Request::Enable {
                handle: 3,
                period_ns: 5_000_000
            })
        );
        assert_eq!(
            catalog.sensor_request(connection, 6, &[]),
            Some(sensors::Request::Close)
        );
        // Never stored as calls of their own, and not watched otherwise.
        assert_eq!(catalog.subject(connection, 2, &enable, &[10100], &[]), None);
        assert_eq!(catalog.sensor_request(connection, 4, &[]), None);
        let server = WATCHED
            .iter()
            .position(|(name, _)| *name == "android.gui.SensorServer")
            .unwrap() as u32;
        assert_eq!(catalog.sensor_request(server, 2, &enable), None);
    }

    #[test]
    fn every_watched_interface_has_a_distinct_key() {
        let keys: Vec<BinderKey> = interfaces()
            .map(|name| key(name).unwrap_or_else(|| panic!("{name} can't be watched")))
            .collect();
        for (i, a) in keys.iter().enumerate() {
            assert!(keys[i + 1..].iter().all(|b| a != b));
        }
        // Length, then the first 8 and the last 16 characters.
        let key = key("android.content.IClipboard").unwrap();
        assert_eq!(key[..4], 26u32.to_ne_bytes());
        assert_eq!(&key[4..20], b"a\0n\0d\0r\0o\0i\0d\0.\0");
        assert_eq!(&key[key.len() - 8..], b"o\0a\0r\0d\0");
        assert_eq!(super::key("too.short"), None);
    }

    #[test]
    fn an_absent_package_is_marked_in_the_subject() {
        let subject = "binder:android.content.pm.IPackageManager/getPackageInfo#com.x";
        assert_eq!(asked_package(subject), Some("com.x"));
        let marked = mark_absent(subject).unwrap();
        assert_eq!(
            marked,
            "binder:android.content.pm.IPackageManager/getPackageInfo#!com.x"
        );
        assert_eq!(
            asked_package("binder:android.content.IClipboard/getPrimaryClip"),
            None
        );
        assert_eq!(
            mark_absent("binder:android.content.IClipboard/getPrimaryClip"),
            None
        );
    }

    #[test]
    fn topic_follows_the_method_and_the_package_asked_about() {
        for (subject, want) in [
            (
                "binder:android.content.pm.IPackageManager/getPackageInfo#com.x",
                topic::INSTALLED_APPS,
            ),
            (
                "binder:android.content.pm.IPackageManager/getPackageInfo#eu.chainfire.supersu",
                topic::ROOT_CHECK,
            ),
            (
                "binder:android.content.pm.IPackageManager/getApplicationInfo#org.lsposed.manager",
                topic::ROOT_CHECK,
            ),
            (
                "binder:android.content.pm.IPackageManager/getPackageInfo#!eu.chainfire.supersu",
                topic::ROOT_CHECK,
            ),
            (
                "binder:android.content.pm.IPackageManager/getPackageInfo#!com.x",
                topic::APP_PROBE,
            ),
            (
                "binder:android.content.IClipboard/getPrimaryClip",
                topic::CLIPBOARD,
            ),
            (
                "binder:android.content.IClipboard/addPrimaryClipChangedListener",
                topic::CLIPBOARD,
            ),
            (
                "binder:android.content.IClipboard/setPrimaryClip",
                topic::CLIPBOARD,
            ),
            (
                "binder:android.content.IClipboard/setPrimaryClipAsPackage",
                topic::CLIPBOARD,
            ),
            (
                "binder:android.content.IClipboard/clearPrimaryClip",
                topic::CLIPBOARD,
            ),
            (
                "binder:android.gui.SensorEventConnection/enableDisable@android.sensor.gyroscope:fast",
                topic::SENSORS,
            ),
            (
                "binder:com.android.internal.telephony.ITelephony/getImeiForSlot",
                topic::PHONE_IDENTITY,
            ),
            (
                "binder:com.android.internal.telephony.ITelephony/getAllCellInfo",
                topic::LOCATION,
            ),
        ] {
            assert_eq!(topic_of(subject), Some(want), "{subject}");
        }
        assert_eq!(topic_of("binder:android.content.IClipboard/clear"), None);
        // Turning a sensor off and closing the connection are not stored.
        assert_eq!(
            topic_of("binder:android.gui.SensorEventConnection/setEventRate"),
            None
        );
        assert_eq!(topic_of("binder:nonsense"), None);
        assert_eq!(topic_of("exec:su"), None);
        // Everything watched has a topic.
        for (interface, groups) in WATCHED.iter().filter(|(name, _)| *name != CONTENT_PROVIDER) {
            for method in groups.iter().flat_map(|(_, methods)| methods.iter()) {
                assert!(topic_of(&format!("{SCHEME}{interface}/{method}")).is_some());
            }
        }
    }
}
