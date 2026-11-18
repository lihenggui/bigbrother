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

//! What a sandboxed app is told about the device by the system server.
//!
//! Its ANDROID_ID and the list of installed apps are answered inside
//! system_server, where only a hook can change them (`SandboxHook` in the
//! app's LSPosed module). The daemon and the hook share two files:
//!
//! - The list, written here: one `<uid> <android id>` line per sandboxed
//!   app the hook answers for, and for one that has only one of the hook's
//!   items on (`device_ids`, `installed_apps`), an `items <uid> <item>`
//!   line naming it. The hook reads the list again when it changes, so an
//!   app enters and leaves the sandbox without a restart of the system. A
//!   hook from before the items line skips it, and answers for the uid in
//!   both ways.
//! - The status, written by the hook when system_server starts: which of
//!   its hooks are in place, and the boot it wrote that in.
//!
//! The hook is optional. Without LSPosed the list is written and nobody
//! reads it; the rest of the sandbox works, and `bbd sandbox status` shows
//! this part as inactive.

use std::{
    fs,
    io::{self, Write as _},
    os::unix::fs::{OpenOptionsExt as _, PermissionsExt as _},
    path::PathBuf,
};

use anyhow::Context as _;

use super::{
    Effect, Entry, Global, HookStatus, Resumed, Snapshot, Status, items::Settings, items_of,
};

/// Keep in sync with `SandboxHook.kt`.
const LIST: &str = "/data/system/big_brother_sandbox";
const STATUS: &str = "/data/system/big_brother_sandbox.status";
/// A different value at every boot.
const BOOT_ID: &str = "/proc/sys/kernel/random/boot_id";

/// The `system` user, which system_server runs as.
const SYSTEM_UID: u32 = 1000;

pub struct Identity {
    snapshot: Snapshot,
    global: Global,
    list: PathBuf,
    status: PathBuf,
}

/// A line of the list: the uid, its ANDROID_ID, and whether the hook gives
/// it that ID and hides the installed apps from it.
type Listed = (u32, String, bool, bool);

/// What the list says of `entry`, to which `items` apply; `None` if the
/// hook has nothing to do for it.
fn listed(entry: &Entry, items: &Settings) -> Option<Listed> {
    let (android_id, app_list) = items.hooked();
    (android_id || app_list).then(|| (entry.uid, entry.android_id.clone(), android_id, app_list))
}

impl Identity {
    /// `snapshot`: the sandboxed apps, as the sandbox keeps them, and
    /// `global`, the global settings, which say what applies to them.
    pub fn new(snapshot: Snapshot, global: Global) -> Self {
        Self {
            snapshot,
            global,
            list: LIST.into(),
            status: STATUS.into(),
        }
    }

    /// Write the list: the sandboxed apps, with `changed` as given whether
    /// or not the snapshot has it so yet, and without `removed`.
    fn write(
        &self,
        changed: Option<(&Entry, &Settings)>,
        removed: Option<u32>,
    ) -> anyhow::Result<()> {
        let global = self
            .global
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        let given = changed.map(|(entry, _)| entry.uid);
        let mut apps: Vec<Listed> = self
            .snapshot
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .values()
            .filter(|entry| Some(entry.uid) != given)
            .filter_map(|entry| listed(entry, &items_of(entry, &global)))
            .chain(changed.and_then(|(entry, items)| listed(entry, items)))
            .filter(|(uid, ..)| Some(*uid) != removed)
            .collect();
        apps.sort();
        if apps.is_empty() {
            // No list: nothing is sandboxed.
            return match fs::remove_file(&self.list) {
                Err(e) if e.kind() != io::ErrorKind::NotFound => {
                    Err(e).context("removing the sandbox list")
                }
                _ => Ok(()),
            };
        }
        // Renamed into place, so that the hook never reads half a list.
        let temporary = self.list.with_extension("new");
        // Created, not opened: the directory is the system's to write in,
        // and what it may have put under this name is not written through.
        let _ = fs::remove_file(&temporary);
        fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .custom_flags(libc::O_NOFOLLOW)
            .mode(0o644)
            .open(&temporary)
            .and_then(|mut file| {
                file.write_all(list_text(&apps).as_bytes())?;
                file.set_permissions(fs::Permissions::from_mode(0o644))?;
                // system_server is not root: the list must be its own to
                // read. Not fatal where it is not allowed (a test, not as
                // root).
                let _ = std::os::unix::fs::fchown(&file, Some(SYSTEM_UID), Some(SYSTEM_UID));
                Ok(())
            })
            .and_then(|()| fs::rename(&temporary, &self.list))
            .context("writing the sandbox list")
    }
}

/// The list as the hook reads it (`parseSandboxList` in `SandboxRules.kt`).
fn list_text(apps: &[Listed]) -> String {
    let mut text = String::new();
    for (uid, android_id, own_id, hides_apps) in apps {
        text.push_str(&format!("{uid} {android_id}\n"));
        if !(*own_id && *hides_apps) {
            let item = if *own_id {
                "device_ids"
            } else {
                "installed_apps"
            };
            text.push_str(&format!("items {uid} {item}\n"));
        }
    }
    text
}

impl Effect for Identity {
    fn name(&self) -> &'static str {
        "identity"
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        self.write(Some((entry, items)), None)
    }

    fn disable(&mut self, entry: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        self.write(None, Some(entry.uid))
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        _: &[String],
        before: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        if before.hooked() == after.hooked() {
            return Ok(());
        }
        self.write(Some((entry, after)), None)
    }

    fn resume(&mut self, _: &[Resumed]) {
        if let Err(e) = self.write(None, None) {
            eprintln!("bbd: sandbox: {e:#}");
        }
    }

    fn report(&self, status: &mut Status) {
        let boot = fs::read_to_string(BOOT_ID).unwrap_or_default();
        status.hook = fs::read_to_string(&self.status)
            .map(|text| parse_status(&text, boot.trim()))
            .unwrap_or_default();
    }
}

/// What the hook wrote about itself (`sandboxStatusText` in
/// `SandboxRules.kt`): `android_id=1`, `app_list=1`, `list=1`, `boot=<id>`,
/// one per line. Nothing is in place unless it was written in this boot (a
/// file left from an earlier one says what was then) and the hook could
/// read the list.
fn parse_status(text: &str, boot_id: &str) -> HookStatus {
    let value = |key: &str| {
        text.lines()
            .filter_map(|line| line.split_once('='))
            .find(|(name, _)| name.trim() == key)
            .map(|(_, value)| value.trim())
    };
    let on = |key: &str| value(key) == Some("1");
    let current = !boot_id.is_empty() && value("boot") == Some(boot_id) && on("list");
    HookStatus {
        android_id: current && on("android_id"),
        app_list: current && on("app_list"),
    }
}

#[cfg(test)]
impl Identity {
    fn at(snapshot: Snapshot, dir: &std::path::Path) -> Self {
        Self {
            snapshot,
            global: Global::default(),
            list: dir.join("list"),
            status: dir.join("status"),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::{
        collections::HashMap,
        sync::{Arc, RwLock},
    };

    use super::*;

    fn entry(uid: u32, android_id: &str) -> Entry {
        Entry {
            uid,
            package: "com.app".into(),
            enabled: true,
            android_id: android_id.into(),
            saved_ops: String::new(),
            created: 0,
            settings: String::new(),
        }
    }

    fn test_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("bbd-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn the_list_names_every_sandboxed_uid_with_its_android_id() {
        let dir = test_dir("identity-list");
        let snapshot: Snapshot = Arc::new(RwLock::new(HashMap::new()));
        let mut identity = Identity::at(snapshot.clone(), &dir);
        let list = || fs::read_to_string(dir.join("list")).unwrap();

        // Enabled before the snapshot has it, or after: listed once.
        let all = Settings::all_on();
        let mut first = entry(1_010_100, "00000000000000aa");
        identity.enable(&mut first, &[], &all).unwrap();
        snapshot.write().unwrap().insert(first.uid, first.clone());
        let mut second = entry(10100, "00000000000000bb");
        snapshot.write().unwrap().insert(second.uid, second.clone());
        identity.enable(&mut second, &[], &all).unwrap();
        assert_eq!(list(), "10100 00000000000000bb\n1010100 00000000000000aa\n");
        let mode = fs::metadata(dir.join("list")).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o644);

        // Disabled while the snapshot still has it (a failed enable undone).
        identity.disable(&mut second, &[], &all).unwrap();
        assert_eq!(list(), "1010100 00000000000000aa\n");
        snapshot.write().unwrap().clear();
        identity.resume(&[]);
        assert!(!dir.join("list").exists());
        // Nothing to remove is not an error.
        identity.disable(&mut first, &[], &all).unwrap();
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn only_the_hooks_items_that_are_on_are_listed() {
        use super::super::items::{Item, Mode};
        let dir = test_dir("identity-items");
        let snapshot: Snapshot = Arc::new(RwLock::new(HashMap::new()));
        let mut identity = Identity::at(snapshot.clone(), &dir);
        let list = || fs::read_to_string(dir.join("list")).unwrap_or_default();
        let with = |pairs: &[(Item, Mode)]| {
            let mut own = Settings::default();
            for (item, mode) in pairs {
                own.set(*item, Some(*mode)).unwrap();
            }
            own.over(&Settings::default())
        };

        let mut app = entry(10100, "00000000000000aa");
        identity
            .enable(&mut app, &[], &with(&[(Item::InstalledApps, Mode::Off)]))
            .unwrap();
        assert_eq!(list(), "10100 00000000000000aa\nitems 10100 device_ids\n");
        let both_off = with(&[
            (Item::InstalledApps, Mode::Off),
            (Item::DeviceIds, Mode::Off),
        ]);
        let only_ids = with(&[(Item::InstalledApps, Mode::Off)]);
        identity
            .update(&mut app, &[], &only_ids, &both_off)
            .unwrap();
        assert!(!dir.join("list").exists());
        let only_apps = with(&[(Item::DeviceIds, Mode::Off)]);
        identity
            .update(&mut app, &[], &both_off, &only_apps)
            .unwrap();
        assert_eq!(
            list(),
            "10100 00000000000000aa\nitems 10100 installed_apps\n"
        );

        // What the snapshot has follows the global settings.
        app.settings = "device_ids=off".into();
        snapshot.write().unwrap().insert(app.uid, app.clone());
        identity.resume(&[]);
        assert_eq!(
            list(),
            "10100 00000000000000aa\nitems 10100 installed_apps\n"
        );
        identity
            .global
            .write()
            .unwrap()
            .set(Item::InstalledApps, Some(Mode::Off))
            .unwrap();
        identity.resume(&[]);
        assert!(!dir.join("list").exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn hooks_count_only_if_reported_in_this_boot() {
        let on = HookStatus {
            android_id: true,
            app_list: true,
        };
        let text = "android_id=1\napp_list=1\napi=35\nlist=1\nboot=abc-1\n";
        assert_eq!(parse_status(text, "abc-1"), on);
        // Left from a boot in which the hook was there.
        assert_eq!(parse_status(text, "abc-2"), HookStatus::default());
        assert_eq!(parse_status(text, ""), HookStatus::default());
        // Written by a hook that does not say which boot.
        assert_eq!(
            parse_status("android_id=1\napp_list=1\napi=35\n", "abc-1"),
            HookStatus::default()
        );
        // The hook could not read the list.
        assert_eq!(
            parse_status("android_id=1\napp_list=1\nlist=0\nboot=abc-1\n", "abc-1"),
            HookStatus::default()
        );
        assert_eq!(
            parse_status("android_id=1\napp_list=0\nlist=1\nboot=abc-1\n", "abc-1"),
            HookStatus {
                android_id: true,
                app_list: false
            }
        );
        assert_eq!(parse_status("", "abc-1"), HookStatus::default());
    }
}
