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
        .position(|(name, _)| *name == CONNECTION)
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
        .position(|(name, _)| *name == CONNECTION)
        .unwrap() as u32;
    let mut enable = 3i32.to_le_bytes().to_vec();
    enable.extend(1i32.to_le_bytes());
    enable.extend(5_000_000i64.to_le_bytes());
    enable.extend([0; 12]);
    assert_eq!(
        catalog.sensor_request(connection, 2, &enable),
        Some(SensorRequest::Enable {
            handle: 3,
            period_ns: 5_000_000
        })
    );
    assert_eq!(
        catalog.sensor_request(connection, 6, &[]),
        Some(SensorRequest::Close)
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

#[test]
fn calls_are_read_from_their_arguments() {
    let args = |values: &[&[u8]]| values.concat();
    let enable = args(&[
        &7i32.to_le_bytes(),
        &1i32.to_le_bytes(),
        &20_000_000i64.to_le_bytes(),
        &0i64.to_le_bytes(),
        &0i32.to_le_bytes(),
    ]);
    assert_eq!(
        decode_sensor_request(ENABLE_DISABLE, &enable),
        Some(SensorRequest::Enable {
            handle: 7,
            period_ns: 20_000_000
        })
    );
    let disable = args(&[&7i32.to_le_bytes(), &0i32.to_le_bytes(), &[0; 20]]);
    assert_eq!(
        decode_sensor_request(ENABLE_DISABLE, &disable),
        Some(SensorRequest::Disable { handle: 7 })
    );
    let rate = args(&[&(-2i32).to_le_bytes(), &5_000_000i64.to_le_bytes()]);
    assert_eq!(
        decode_sensor_request(SET_EVENT_RATE, &rate),
        Some(SensorRequest::Rate {
            handle: -2,
            period_ns: 5_000_000
        })
    );
    assert_eq!(
        decode_sensor_request(DESTROY, &[]),
        Some(SensorRequest::Close)
    );
    // Too short to hold the period.
    assert_eq!(decode_sensor_request(ENABLE_DISABLE, &enable[..12]), None);
    assert_eq!(decode_sensor_request("flush", &enable), None);
}
