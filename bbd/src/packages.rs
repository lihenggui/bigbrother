//! uid -> package name lookup, read from Android's package list.
//!
//! `/data/system/packages.list` has one line per package:
//!   `<package> <app_id> <debuggable> <data_dir> <seinfo> <gids> ...`
//! Several packages can share one app_id (sharedUserId).

use std::{
    collections::HashMap,
    fs,
    time::{Duration, Instant},
};

pub const PACKAGES_LIST: &str = "/data/system/packages.list";

/// Android multi-user layout: uid = user_id * 100_000 + app_id.
pub const PER_USER_RANGE: u32 = 100_000;

/// How often an unknown uid may trigger a re-read of the list.
const RELOAD_INTERVAL: Duration = Duration::from_secs(10);

/// First app id of the ranges used for isolated processes (app zygotes from
/// 90000, isolated services from 99000). Such a uid belongs to no package.
pub const FIRST_ISOLATED_APP_ID: u32 = 90_000;

pub struct Packages {
    source: String,
    by_app_id: HashMap<u32, Vec<String>>,
    app_id_by_name: HashMap<String, u32>,
    loaded_at: Option<Instant>,
}

impl Packages {
    pub fn new(source: impl Into<String>) -> Self {
        let mut this = Self {
            source: source.into(),
            by_app_id: HashMap::new(),
            app_id_by_name: HashMap::new(),
            loaded_at: None,
        };
        this.reload();
        this
    }

    fn reload(&mut self) {
        self.loaded_at = Some(Instant::now());
        match fs::read_to_string(&self.source) {
            Ok(text) => {
                self.by_app_id = parse(&text);
                self.app_id_by_name = self
                    .by_app_id
                    .iter()
                    .flat_map(|(id, names)| names.iter().map(move |name| (name.clone(), *id)))
                    .collect();
            }
            Err(e) => eprintln!("bbd: cannot read {}: {e}", self.source),
        }
    }

    /// Packages installed under `uid`'s app_id. Empty for uids that aren't
    /// installed apps (isolated processes, or an app installed after the last
    /// load and still inside the reload interval).
    pub fn for_uid(&mut self, uid: u32) -> &[String] {
        let app_id = uid % PER_USER_RANGE;
        if !self.by_app_id.contains_key(&app_id)
            && self
                .loaded_at
                .is_none_or(|t| t.elapsed() >= RELOAD_INTERVAL)
        {
            self.reload();
        }
        self.by_app_id.get(&app_id).map_or(&[], Vec::as_slice)
    }
}

impl Packages {
    /// The uid of the app an isolated process belongs to, given the process's
    /// name. Isolated processes (WebView renderers and the like) run under a
    /// throwaway uid, but are named `<package>:<suffix>` after their owner.
    pub fn owner_of_isolated(&self, isolated_uid: u32, process_name: &str) -> Option<u32> {
        let package = process_name.split(':').next()?;
        let app_id = self.app_id_by_name.get(package)?;
        Some(isolated_uid / PER_USER_RANGE * PER_USER_RANGE + app_id)
    }
}

fn parse(text: &str) -> HashMap<u32, Vec<String>> {
    let mut map: HashMap<u32, Vec<String>> = HashMap::new();
    for line in text.lines() {
        let mut fields = line.split_ascii_whitespace();
        let (Some(name), Some(app_id)) = (fields.next(), fields.next()) else {
            continue;
        };
        if let Ok(app_id) = app_id.parse() {
            map.entry(app_id).or_default().push(name.to_string());
        }
    }
    for names in map.values_mut() {
        names.sort();
    }
    map
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_shared_uids_and_skips_garbage() {
        let map = parse(
            "com.b 10050 0 /data/user/0/com.b default:targetSdkVersion=34 none 0 0 1 @null\n\
             com.a 10050 0 /data/user/0/com.a default none\n\
             com.c 10051 1 /data/user/0/com.c default 3003\n\
             broken-line\n\
             com.d notanumber 0\n",
        );
        assert_eq!(map[&10050], ["com.a", "com.b"]);
        assert_eq!(map[&10051], ["com.c"]);
        assert_eq!(map.len(), 2);
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
