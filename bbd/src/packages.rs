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

//! uid -> package name lookup, read from Android's package list.
//!
//! `/data/system/packages.list` has one line per package:
//!   `<package> <app_id> <debuggable> <data_dir> <seinfo> <gids> <profileable>
//!    <version> <...> <installer>`
//! Several packages can share one app_id (sharedUserId).

use std::{
    collections::{BTreeSet, HashMap, HashSet},
    fs,
    time::{Duration, Instant},
};

use bb_common::PER_USER_RANGE;

pub const PACKAGES_LIST: &str = "/data/system/packages.list";

#[inline]
pub const fn app_id_of(uid: u32) -> u32 {
    uid % PER_USER_RANGE
}

#[inline]
pub const fn user_of(uid: u32) -> u32 {
    uid / PER_USER_RANGE
}

/// How often an unknown uid may trigger a re-read of the list.
const RELOAD_INTERVAL: Duration = Duration::from_secs(10);

/// First app id of the ranges used for isolated processes (app zygotes from
/// 90000, isolated services from 99000). Such a uid belongs to no package.
pub const FIRST_ISOLATED_APP_ID: u32 = 90_000;

pub struct Packages {
    source: String,
    by_app_id: HashMap<u32, Vec<String>>,
    app_id_by_name: HashMap<String, (u32, Option<u64>)>,
    /// App ids with a package that came with the system.
    from_system: HashSet<u32>,
    loaded_at: Option<Instant>,
}

#[derive(Default)]
pub struct PackageVersions(HashMap<String, (u32, Option<u64>)>);

impl Packages {
    pub fn new(source: impl Into<String>) -> Self {
        let mut this = Self {
            source: source.into(),
            by_app_id: HashMap::new(),
            app_id_by_name: HashMap::new(),
            from_system: HashSet::new(),
            loaded_at: None,
        };
        this.reload();
        this
    }

    fn reload(&mut self) -> bool {
        self.loaded_at = Some(Instant::now());
        match fs::read_to_string(&self.source) {
            Ok(text) => {
                let list = parse(&text);
                self.by_app_id = list.by_app_id;
                self.from_system = list.from_system;
                self.app_id_by_name = list.app_id_by_name;
                true
            }
            Err(e) => {
                eprintln!("bbd: cannot read {}: {e}", self.source);
                false
            }
        }
    }

    /// Read the list again now, whatever its age: for a decision that must
    /// not be taken on a package installed or removed since.
    pub fn refresh(&mut self) -> bool {
        self.reload()
    }

    /// Whether no package is known, as when the list could not be read.
    pub fn is_empty(&self) -> bool {
        self.by_app_id.is_empty()
    }

    /// Packages installed under `uid`'s app_id. Empty for uids that aren't
    /// installed apps (isolated processes, or an app installed after the last
    /// load and still inside the reload interval).
    pub fn for_uid(&mut self, uid: u32) -> &[String] {
        let app_id = app_id_of(uid);
        if !self.by_app_id.contains_key(&app_id)
            && self
                .loaded_at
                .is_none_or(|t| t.elapsed() >= RELOAD_INTERVAL)
        {
            self.reload();
        }
        self.by_app_id.get(&app_id).map_or(&[], Vec::as_slice)
    }

    /// The packages of `app_id` as the list was last read. Unlike
    /// [`Self::for_uid`], an app id not found does not have it read again.
    pub fn packages_of(&self, app_id: u32) -> &[String] {
        self.by_app_id.get(&app_id).map_or(&[], Vec::as_slice)
    }

    /// The installed version as of the last refresh, when Android lists it.
    pub fn version_of(&self, package: &str) -> Option<u64> {
        self.app_id_by_name
            .get(package)
            .and_then(|(_, version)| *version)
    }

    /// Compare snapshots taken at package-list events so that an unrelated
    /// refresh cannot consume the update before its event is handled.
    pub fn updated_app_ids(&self, previous: &mut PackageVersions) -> BTreeSet<u32> {
        let mut updated = BTreeSet::new();
        for (name, current) in &self.app_id_by_name {
            if previous.0.get(name).is_some_and(|old| old != current) {
                updated.insert(current.0);
            }
        }
        previous.0.clone_from(&self.app_id_by_name);
        updated
    }

    /// Every app id in the list as it was last read, with its packages.
    pub fn app_ids(&self) -> impl Iterator<Item = (u32, &[String])> {
        self.by_app_id
            .iter()
            .map(|(app_id, names)| (*app_id, names.as_slice()))
    }

    /// Whether a package of `app_id` came with the system, rather than
    /// being installed by the user.
    pub fn is_system(&self, app_id: u32) -> bool {
        self.from_system.contains(&app_id)
    }
}

impl Packages {
    /// Whether `package` is installed (for any user). A name not found makes
    /// the list be read again, at most once per reload interval, so that an
    /// app installed since the last load is found.
    pub fn is_installed(&mut self, package: &str) -> bool {
        self.app_id_of(package).is_some()
    }

    /// The app id of `package`, if it is installed (for any user). Read
    /// again like [`Self::is_installed`] when not found.
    pub fn app_id_of(&mut self, package: &str) -> Option<u32> {
        if let Some((app_id, _)) = self.app_id_by_name.get(package) {
            return Some(*app_id);
        }
        if self
            .loaded_at
            .is_none_or(|t| t.elapsed() >= RELOAD_INTERVAL)
        {
            self.reload();
        }
        self.app_id_by_name.get(package).map(|(app_id, _)| *app_id)
    }

    /// The uid of the app an isolated process belongs to, given the process's
    /// name. Isolated processes (WebView renderers and the like) run under a
    /// throwaway uid, but are named `<package>:<suffix>` after their owner.
    pub fn owner_of_isolated(&self, isolated_uid: u32, process_name: &str) -> Option<u32> {
        let package = process_name.split(':').next()?;
        let (app_id, _) = self.app_id_by_name.get(package)?;
        Some(user_of(isolated_uid) * PER_USER_RANGE + app_id)
    }
}

/// What the package list holds.
struct List {
    by_app_id: HashMap<u32, Vec<String>>,
    app_id_by_name: HashMap<String, (u32, Option<u64>)>,
    from_system: HashSet<u32>,
}

fn parse(text: &str) -> List {
    let mut list = List {
        by_app_id: HashMap::new(),
        app_id_by_name: HashMap::new(),
        from_system: HashSet::new(),
    };
    for line in text.lines() {
        let fields: Vec<&str> = line.split_ascii_whitespace().collect();
        let (Some(name), Some(Ok(app_id))) = (fields.first(), fields.get(1).map(|id| id.parse()))
        else {
            continue;
        };
        let version = fields.get(7).and_then(|version| version.parse().ok());
        list.app_id_by_name
            .insert(name.to_string(), (app_id, version));
        list.by_app_id
            .entry(app_id)
            .or_default()
            .push(name.to_string());
        if came_with_system(&fields) {
            list.from_system.insert(app_id);
        }
    }
    for names in list.by_app_id.values_mut() {
        names.sort();
    }
    list
}

/// Whether the package of a line of the list came with the system. Android
/// names the partition such a package was installed from in its SELinux
/// information (`platform:privapp:targetSdkVersion=35:partition=system`),
/// and gives `@system` or `@product` as its installer; a package the user
/// installed has neither.
fn came_with_system(fields: &[&str]) -> bool {
    let partition = fields
        .get(4)
        .is_some_and(|seinfo| seinfo.split(':').any(|part| part.starts_with("partition=")));
    partition || matches!(fields.get(9), Some(&"@system" | &"@product"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_package_update_reports_only_the_changed_app_id() {
        let mut packages = Packages::new("/dev/null");
        let mut previous = PackageVersions::default();
        let initial = parse(
            "com.example.a 10100 0 /d default none 0 1\ncom.example.b 10101 0 /d default none 0 2",
        );
        packages.app_id_by_name = initial.app_id_by_name;
        assert!(packages.updated_app_ids(&mut previous).is_empty());

        let updated = parse(
            "com.example.a 10100 0 /d default none 0 3\ncom.example.b 10101 0 /d default none 0 2",
        );
        packages.app_id_by_name = updated.app_id_by_name;
        assert_eq!(
            packages.updated_app_ids(&mut previous),
            BTreeSet::from([10100])
        );
        assert!(packages.updated_app_ids(&mut previous).is_empty());
    }

    #[test]
    fn parses_shared_uids_and_skips_garbage() {
        let map = parse(
            "com.b 10050 0 /data/user/0/com.b default:targetSdkVersion=34 none 0 0 1 @null\n\
             com.a 10050 0 /data/user/0/com.a default none\n\
             com.c 10051 1 /data/user/0/com.c default 3003\n\
             broken-line\n\
             com.d notanumber 0\n",
        )
        .by_app_id;
        assert_eq!(map[&10050], ["com.a", "com.b"]);
        assert_eq!(map[&10051], ["com.c"]);
        assert_eq!(map.len(), 2);
    }

    #[test]
    fn tells_what_came_with_the_system_from_what_the_user_installed() {
        let list = parse(
            "com.android.chrome 10150 0 /data/user/0/com.android.chrome default:targetSdkVersion=35:partition=product 3003 0 712 1 @system\n\
             com.android.phone 1001 0 /data/user_de/0/com.android.phone platform:privapp:targetSdkVersion=35:partition=system 3002 0 35 1 @system\n\
             com.example.adb 10230 1 /data/user/0/com.example.adb default:targetSdkVersion=34 3003 1 1 1 @null\n\
             com.example.play 10231 0 /data/user/0/com.example.play default:targetSdkVersion=35 none 0 41 1 com.android.vending\n\
             com.example.old 10232 0 /data/user/0/com.example.old default none\n\
             com.example.preloaded 10233 0 /data/user/0/com.example.preloaded default none 0 1 1 @system\n\
             com.example.product 10234 0 /data/user/0/com.example.product default none 0 1 1 @product\n",
        );
        let system: Vec<u32> = {
            let mut ids: Vec<u32> = list.from_system.into_iter().collect();
            ids.sort();
            ids
        };
        assert_eq!(system, [1001, 10150, 10233, 10234]);
    }

    #[test]
    fn isolated_processes_map_to_their_owner() {
        let dir = std::env::temp_dir().join(format!("bbd-test-pkgs-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let list = dir.join("packages.list");
        std::fs::write(
            &list,
            "com.android.chrome 10150 0 /data/user/0/com.android.chrome x\n",
        )
        .unwrap();
        let packages = Packages::new(list.to_str().unwrap());

        let renderer = "com.android.chrome:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0";
        assert_eq!(packages.owner_of_isolated(99_012, renderer), Some(10_150));
        // The owner is in the same Android user as the isolated process.
        assert_eq!(
            packages.owner_of_isolated(1_099_012, renderer),
            Some(1_010_150)
        );
        assert_eq!(packages.owner_of_isolated(99_012, "com.unknown:x"), None);
        assert_eq!(packages.owner_of_isolated(99_012, ""), None);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
