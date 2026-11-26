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
//! app's LSPosed module). So are the grants to files the app was given
//! through the system (a document or a folder it was let pick): the
//! providers that open those files, outside the sandbox, ask system_server
//! whether the app holds a grant. While its storage is isolated, an app is
//! held to the grants of the moment, made as the user picks a file, and
//! not to those kept across restarts (persisted), which reach the real
//! files around the sandbox however long ago they were made. The daemon
//! and the hook share two files:
//!
//! - The list, written here: one `<uid> <android id>` line per sandboxed
//!   app the hook answers for, and an `items <uid> <item>[,<item>]` line
//!   naming the hook's items that are on for it (`device_ids`,
//!   `installed_apps`, `storage`), but for an app that has the first two
//!   and not storage: a uid without the line has those. The hook reads the
//!   list again when it changes, so an app enters and leaves the sandbox
//!   without a restart of the system.
//! - The status, written by the hook when system_server starts: which of
//!   its hooks are in place, and the boot it wrote that in.
//!
//! The hook is optional. Without LSPosed the list is written and nobody
//! reads it; the rest of the sandbox works, and `bbd sandbox status` shows
//! this part as inactive.

use std::{
    collections::BTreeMap,
    fs,
    io::{self, Write as _},
    os::unix::fs::{OpenOptionsExt as _, PermissionsExt as _},
    path::PathBuf,
};

use anyhow::Context as _;

use super::answers::{HookHealth, HookState};
use super::{
    Effect, Entry, Global, HookStatus, Resumed, Snapshot, Status, items::Settings, items_of,
};
use crate::{
    dir::Dir,
    sandbox_item::{Item, Mode},
    sync::read,
};

/// Keep in sync with `SandboxHook.kt`.
const LIST: &str = "/data/system/big_brother_sandbox";
const STATUS: &str = "/data/system/big_brother_sandbox.status";
/// A different value at every boot.
const BOOT_ID: &str = "/proc/sys/kernel/random/boot_id";

/// The `system` user, which system_server runs as.
const SYSTEM_UID: u32 = 1000;
pub(super) const ACK_WAIT: std::time::Duration = std::time::Duration::from_secs(2);

pub struct Identity {
    user_data: Option<PathBuf>,
    pins: std::cell::RefCell<Vec<Dir>>,
    revision: String,
    /// What the list says, or is to say once written: the line of each uid
    /// the hook answers for.
    listed: BTreeMap<u32, Listed>,
    updates: Option<BTreeMap<u32, Option<Listed>>>,
    list: PathBuf,
    status: PathBuf,
    #[cfg(test)]
    writes: std::sync::Arc<std::sync::atomic::AtomicUsize>,
}

/// What the list says of a uid.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Listed {
    package: String,
    install_id: String,
    android_id: String,
    /// Whether the hook gives it its ANDROID_ID.
    own_id: bool,
    /// Whether the hook hides the installed apps from it.
    hides_apps: bool,
    /// Whether the hook holds it to the file grants of the moment.
    storage: bool,
}

/// What the list says of `entry`, to which `items` apply; `None` if the
/// hook has nothing to do for it.
fn listed(entry: &Entry, items: &Settings) -> Option<Listed> {
    let (own_id, hides_apps) = items.hooked();
    let storage = items.mode(Item::Storage) != Mode::Off;
    (own_id || hides_apps || storage).then(|| Listed {
        package: entry.package.clone(),
        install_id: entry.install_id.clone(),
        android_id: entry.android_id.clone(),
        own_id,
        hides_apps,
        storage,
    })
}

impl Identity {
    /// The list for the sandboxed apps in `snapshot`, to which `global`
    /// applies, as the daemon starts.
    ///
    /// From then on an app's line changes only as that app is enabled,
    /// changed or disabled, and is not worked out again from the global
    /// settings: those change before the apps that follow them, which
    /// change one after another, and a change that fails half way must
    /// leave the lines of the apps it did not reach, or undid, as they
    /// were.
    pub fn new(snapshot: &Snapshot, global: &Global) -> Self {
        let global = read(global);
        let listed = read(snapshot)
            .values()
            .filter_map(|entry| Some((entry.uid, listed(entry, &items_of(entry, &global))?)))
            .collect();
        Self {
            listed,
            user_data: Some("/data/user_de".into()),
            pins: Default::default(),
            revision: String::new(),
            updates: None,
            list: LIST.into(),
            status: STATUS.into(),
            #[cfg(test)]
            writes: Default::default(),
        }
    }

    fn live_status(&self) -> Option<String> {
        // A missing or unverifiable status cannot establish that the hook is active.
        let text = fs::read_to_string(&self.status).ok()?;
        let pid: u32 = text
            .lines()
            .find_map(|l| l.strip_prefix("pid="))?
            .parse()
            .ok()?;
        let boot = fs::read_to_string(BOOT_ID).ok()?;
        if !text
            .lines()
            .any(|s| s.strip_prefix("boot=") == Some(boot.trim()))
        {
            return None;
        }
        let comm = fs::read_to_string(format!("/proc/{pid}/comm")).ok()?;
        (comm.trim() == "system_server").then_some(text)
    }

    fn acknowledged(&self, text: &str) -> bool {
        text.lines().any(|line| line == "list=1")
            && text.lines().find_map(|l| l.strip_prefix("revision="))
                == Some(self.revision.as_str())
    }

    /// Write the list with `line` as the line of `uid`, or without one.
    /// Kept only once written, so that what is kept is what the hook reads.
    fn write(&mut self, uid: u32, line: Option<Listed>) -> anyhow::Result<()> {
        if let Some(updates) = &mut self.updates {
            updates.insert(uid, line);
            return Ok(());
        }
        let mut listed = self.listed.clone();
        Self::set_line(&mut listed, uid, line);
        self.publish(listed)
    }

    fn set_line(listed: &mut BTreeMap<u32, Listed>, uid: u32, line: Option<Listed>) {
        if let Some(line) = line {
            listed.insert(uid, line);
        } else {
            listed.remove(&uid);
        }
    }

    fn publish(&mut self, listed: BTreeMap<u32, Listed>) -> anyhow::Result<()> {
        let active = self
            .live_status()
            .is_some_and(|text| text.lines().any(|line| line == "list=1"));
        let revision = self.write_list(&listed)?;
        self.revision = revision;
        if active {
            let until = std::time::Instant::now() + ACK_WAIT;
            loop {
                if self.live_status().is_some_and(|s| self.acknowledged(&s)) {
                    break;
                }
                if std::time::Instant::now() >= until {
                    self.revision = self.write_list(&self.listed)?;
                    anyhow::bail!("the system hook did not load the new sandbox list");
                }
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
        }
        self.listed = listed;
        Ok(())
    }

    fn write_list(&self, apps: &BTreeMap<u32, Listed>) -> anyhow::Result<String> {
        #[cfg(test)]
        self.writes
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        if apps.is_empty() {
            // No list: nothing is sandboxed.
            self.pins.borrow_mut().clear();
            return match fs::remove_file(&self.list) {
                Err(e) if e.kind() != io::ErrorKind::NotFound => {
                    Err(e).context("removing the sandbox list")
                }
                _ => Ok("none".into()),
            };
        }
        // Renamed into place, so that the hook never reads half a list.
        let revision = super::new_android_id()?;
        let mut owners = String::new();
        let mut pins = Vec::new();
        if let Some(root) = &self.user_data {
            use std::fmt::Write as _;
            writeln!(owners, "boot {}", fs::read_to_string(BOOT_ID)?.trim())?;
            for (uid, item) in apps {
                let (dir, id) = super::dirs::pin_install(root, &item.package, *uid, false)?;
                anyhow::ensure!(
                    id == item.install_id,
                    "installation changed while publishing hook policy"
                );
                let stat = dir.stat()?;
                writeln!(
                    owners,
                    "owner {uid} {} {} {}",
                    item.package, stat.st_dev, stat.st_ino
                )?;
                pins.push(dir);
            }
        }
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
                writeln!(file, "# {revision}")?;
                file.write_all(owners.as_bytes())?;
                file.write_all(list_text(apps).as_bytes())?;
                file.set_permissions(fs::Permissions::from_mode(0o644))?;
                // system_server is not root: the list must be its own to
                // read. Not fatal where it is not allowed (a test, not as
                // root).
                let _ = std::os::unix::fs::fchown(&file, Some(SYSTEM_UID), Some(SYSTEM_UID));
                Ok(())
            })
            .and_then(|()| fs::rename(&temporary, &self.list))
            .context("writing the sandbox list")?;
        self.pins.replace(pins);
        Ok(revision)
    }
}

/// The list as the hook reads it (`parseSandboxList` in `SandboxRules.kt`).
fn list_text(apps: &BTreeMap<u32, Listed>) -> String {
    let mut text = String::new();
    for (uid, listed) in apps {
        text.push_str(&format!("{uid} {}\n", listed.android_id));
        // Without the line, both of the items there were before storage:
        // what a hook from then still reads it as.
        if !(listed.own_id && listed.hides_apps) || listed.storage {
            let items: Vec<&str> = [
                (listed.own_id, "device_ids"),
                (listed.hides_apps, "installed_apps"),
                (listed.storage, "storage"),
            ]
            .into_iter()
            .filter_map(|(on, item)| on.then_some(item))
            .collect();
            text.push_str(&format!("items {uid} {}\n", items.join(",")));
        }
    }
    text
}

impl Effect for Identity {
    fn name(&self) -> &'static str {
        "identity"
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        self.write(entry.uid, listed(entry, items))
    }

    fn disable(&mut self, entry: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        self.write(entry.uid, None)
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        _: &[String],
        before: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        if listed(entry, before) == listed(entry, after) {
            return Ok(());
        }
        self.write(entry.uid, listed(entry, after))
    }

    fn begin_updates(&mut self) {
        self.updates = Some(BTreeMap::new());
    }

    fn publish_updates(&mut self) -> anyhow::Result<()> {
        let Some(updates) = &mut self.updates else {
            return Ok(());
        };
        if updates.is_empty() {
            return Ok(());
        }
        let mut listed = self.listed.clone();
        for (uid, line) in std::mem::take(updates) {
            Self::set_line(&mut listed, uid, line);
        }
        if listed != self.listed {
            self.publish(listed)?;
        }
        Ok(())
    }

    fn end_updates(&mut self) {
        self.updates = None;
    }

    fn resume(&mut self, apps: &[Resumed]) {
        // The list left on the disk may be from a daemon that stopped half
        // way through a change.
        self.listed = apps
            .iter()
            .filter_map(|app| Some((app.entry.uid, listed(&app.entry, &app.items)?)))
            .collect();
        self.revision = self.write_list(&self.listed).unwrap_or_else(|e| {
            eprintln!("bbd: sandbox: {e:#}");
            String::new()
        });
    }

    fn forget(&mut self, uid: u32) {
        if self.write(uid, None).is_err() {
            eprintln!("bbd: cannot retire sandbox hook entry");
        }
    }

    fn report(&self, status: &mut Status) {
        // Without the boot ID, the granular status must default to unavailable.
        let boot = fs::read_to_string(BOOT_ID).unwrap_or_default();
        status.hook = self
            .live_status()
            .map(|text| parse_status(&text, boot.trim(), self.acknowledged(&text)))
            .unwrap_or_default();
    }
}

/// A live hook can report installation errors before it acknowledges a list.
fn parse_status(text: &str, boot_id: &str, acknowledged: bool) -> HookStatus {
    let value = |key: &str| {
        text.lines()
            .filter_map(|line| line.split_once('='))
            .find(|(name, _)| name.trim() == key)
            .map(|(_, value)| value.trim())
    };
    if boot_id.is_empty() || value("boot") != Some(boot_id) {
        return HookStatus::default();
    }

    let ready = acknowledged && value("list") == Some("1");
    let state = |key: &str| {
        let installed = match value(&format!("{key}_health")) {
            Some("working") => HookState::Working,
            Some("degraded") => HookState::Degraded,
            Some(_) => HookState::Incompatible,
            // A current legacy writer has loaded the scope, even if its hook did not attach.
            None if value(key) == Some("1") => HookState::Working,
            None => HookState::Incompatible,
        };
        if installed == HookState::Working && !ready {
            HookState::Degraded
        } else {
            installed
        }
    };
    HookHealth {
        android_id_hook: state("android_id"),
        app_list_hook: state("app_list"),
        uri_grants_hook: state("uri_grants"),
        ad_id_hook: state("ad_id"),
    }
    .into()
}

#[cfg(test)]
impl Identity {
    pub(super) fn writes(&self) -> std::sync::Arc<std::sync::atomic::AtomicUsize> {
        self.writes.clone()
    }

    /// Written in `dir` rather than where the hook reads it.
    pub(super) fn at(self, dir: &std::path::Path) -> Self {
        Self {
            user_data: None,
            list: dir.join("list"),
            status: dir.join("status"),
            ..self
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(uid: u32, android_id: &str) -> Entry {
        Entry {
            uid,
            package: "com.app".into(),
            enabled: true,
            android_id: android_id.into(),
            saved_ops: String::new(),
            install_id: String::new(),
            saved_permissions: String::new(),
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
        let mut identity = Identity::new(&Snapshot::default(), &Global::default()).at(&dir);
        let list = || {
            fs::read_to_string(dir.join("list"))
                .unwrap()
                .split_once('\n')
                .unwrap()
                .1
                .to_owned()
        };

        let all = Settings::all_on();
        let mut first = entry(1_010_100, "00000000000000aa");
        identity.enable(&mut first, &[], &all).unwrap();
        let mut second = entry(10100, "00000000000000bb");
        identity.enable(&mut second, &[], &all).unwrap();
        assert_eq!(
            list(),
            "10100 00000000000000bb\nitems 10100 device_ids,installed_apps,storage\n\
             1010100 00000000000000aa\nitems 1010100 device_ids,installed_apps,storage\n"
        );
        let mode = fs::metadata(dir.join("list")).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o644);

        identity.disable(&mut second, &[], &all).unwrap();
        assert_eq!(
            list(),
            "1010100 00000000000000aa\nitems 1010100 device_ids,installed_apps,storage\n"
        );
        identity.resume(&[]);
        assert!(!dir.join("list").exists());
        // Nothing to remove is not an error.
        identity.disable(&mut first, &[], &all).unwrap();
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn only_the_hooks_items_that_are_on_are_listed() {
        let dir = test_dir("identity-items");
        let mut identity = Identity::new(&Snapshot::default(), &Global::default()).at(&dir);
        let list = || {
            fs::read_to_string(dir.join("list"))
                .unwrap_or_default()
                .split_once('\n')
                .map(|(_, list)| list.to_owned())
                .unwrap_or_default()
        };
        let with = |pairs: &[(Item, Mode)]| {
            let mut own = Settings::default();
            own.set(Item::Storage, Some(Mode::Off)).unwrap();
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

        // As the daemon starts, the apps it loaded are listed as their
        // settings over the global ones say, also by a write that comes
        // before they are taken up again (a uid taken out first).
        app.settings = "device_ids=off,storage=off".into();
        let snapshot = Snapshot::default();
        snapshot.write().unwrap().insert(app.uid, app.clone());
        let global = Global::default();
        let mut started = Identity::new(&snapshot, &global).at(&dir);
        started
            .disable(&mut entry(10200, "00000000000000bb"), &[], &both_off)
            .unwrap();
        assert_eq!(
            list(),
            "10100 00000000000000aa\nitems 10100 installed_apps\n"
        );
        // Taken up again, with what applies to them.
        let mut global = Settings::default();
        global.set(Item::InstalledApps, Some(Mode::Off)).unwrap();
        started.resume(&[Resumed {
            items: Settings::parse(&app.settings).over(&global),
            entry: app,
            packages: vec![],
        }]);
        assert!(!dir.join("list").exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn an_app_with_its_storage_isolated_is_listed_for_it() {
        let dir = test_dir("identity-storage");
        let mut identity = Identity::new(&Snapshot::default(), &Global::default()).at(&dir);
        let list = || {
            fs::read_to_string(dir.join("list"))
                .unwrap_or_default()
                .split_once('\n')
                .map(|(_, list)| list.to_owned())
                .unwrap_or_default()
        };
        let only = |on: &[Item]| {
            let mut own = Settings::default();
            for item in Item::ALL {
                let mode = if on.contains(&item) {
                    Mode::On
                } else {
                    Mode::Off
                };
                own.set(item, Some(mode)).unwrap();
            }
            own.over(&Settings::default())
        };

        let mut app = entry(10100, "00000000000000aa");
        identity
            .enable(&mut app, &[], &only(&[Item::Storage]))
            .unwrap();
        assert_eq!(list(), "10100 00000000000000aa\nitems 10100 storage\n");
        identity
            .update(
                &mut app,
                &[],
                &only(&[Item::Storage]),
                &only(&[Item::Storage, Item::DeviceIds]),
            )
            .unwrap();
        assert_eq!(
            list(),
            "10100 00000000000000aa\nitems 10100 device_ids,storage\n"
        );
        identity
            .update(
                &mut app,
                &[],
                &only(&[Item::Storage, Item::DeviceIds]),
                &only(&[Item::DeviceIds]),
            )
            .unwrap();
        assert_eq!(list(), "10100 00000000000000aa\nitems 10100 device_ids\n");
        identity
            .update(&mut app, &[], &only(&[Item::DeviceIds]), &only(&[]))
            .unwrap();
        assert!(!dir.join("list").exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn the_list_is_written_as_the_hook_reads_it() {
        let cases = crate::protocol_cases::sandbox_list();
        for case in crate::protocol_cases::of(&cases, &["line"]) {
            let apps: BTreeMap<u32, Listed> = case
                .all("app")
                .map(|app| {
                    let mut parts = app.split('/');
                    let uid = parts.next().unwrap().parse().unwrap();
                    let android_id = parts.next().unwrap().to_string();
                    let items: Vec<&str> = parts.collect();
                    let listed = Listed {
                        package: String::new(),
                        install_id: String::new(),
                        android_id,
                        own_id: items.contains(&"device_ids"),
                        hides_apps: items.contains(&"installed_apps"),
                        storage: items.contains(&"storage"),
                    };
                    (uid, listed)
                })
                .collect();
            assert_eq!(list_text(&apps), case.line);
        }
    }

    #[test]
    fn the_status_is_read_as_the_hook_writes_it() {
        let cases = crate::protocol_cases::sandbox_status();
        for case in crate::protocol_cases::of(&cases, &["line"])
            .into_iter()
            .chain(crate::protocol_cases::of(&cases, &["read"]))
        {
            let now = case.get("now").or(case.get("boot")).unwrap();
            let expected = |key: &str| match case.get(key).unwrap() {
                "working" => HookState::Working,
                "incompatible" => HookState::Incompatible,
                "degraded" => HookState::Degraded,
                "not_loaded" => HookState::NotLoaded,
                state => panic!("unexpected hook state {state}"),
            };
            assert_eq!(
                parse_status(&case.line, now, case.get("ack") != Some("0")),
                HookHealth {
                    android_id_hook: expected("android_id"),
                    app_list_hook: expected("app_list"),
                    uri_grants_hook: expected("uri_grants"),
                    ad_id_hook: expected("ad_id"),
                }
                .into(),
                "{:?}",
                case.line
            );
        }
    }
}
