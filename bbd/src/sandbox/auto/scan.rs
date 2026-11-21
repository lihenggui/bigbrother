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

//! Plan a look at installed packages and carry it out within its deadline.

use std::{
    collections::{BTreeMap, BTreeSet},
    path::Path,
    time::Instant,
};

use bb_common::PER_USER_RANGE;

use super::{Known, Retry, is_app};
use crate::{
    packages::{Packages, app_id_of, user_of},
    sandbox::{LAST_USER, OWN_PACKAGES, Sandbox, uid_of},
    store::Writer,
};

/// What a look at the package list finds.
#[derive(Debug, Default, PartialEq, Eq)]
struct Plan {
    /// Apps installed since and not dealt with, to put in the sandbox: the
    /// uid, and its packages installed for the user (the first is named).
    isolate: Vec<(u32, BTreeSet<String>)>,
    /// What is known from now on, those apps aside.
    known: Known,
}

/// What is new in `packages` against `known`, for `users`; `installed` says
/// whether a package is installed for a user.
fn plan(
    known: &Known,
    packages: &Packages,
    users: &[u32],
    installed: impl Fn(u32, &str) -> bool,
) -> Plan {
    let names_of =
        |app_id| -> BTreeSet<String> { packages.packages_of(app_id).iter().cloned().collect() };
    // An app id or uid stays known, with the packages it has now, while any
    // package it was known with is installed. One whose packages are all
    // gone was uninstalled, or given to another app.
    let mut next = Known::default();
    for (app_id, was) in &known.apps {
        let now = names_of(*app_id);
        if !now.is_disjoint(was) {
            next.apps.insert(*app_id, now);
        }
    }
    for (&uid, was) in &known.uids {
        let user = user_of(uid);
        let mut now = names_of(app_id_of(uid));
        now.retain(|name| installed(user, name));
        if !now.is_disjoint(was) {
            next.uids.insert(uid, now);
        }
    }
    // A library is left alone for as long as that package is installed.
    for (app_id, was) in &known.libraries {
        let now: BTreeSet<String> = names_of(*app_id).intersection(was).cloned().collect();
        if !now.is_empty() {
            next.libraries.insert(*app_id, now);
        }
    }

    let mut app_ids: Vec<(u32, &[String])> = packages
        .app_ids()
        .filter(|(app_id, _)| is_app(*app_id) && !next.apps.contains_key(app_id))
        .collect();
    app_ids.sort_unstable();
    let mut isolate = Vec::new();
    for (app_id, names) in app_ids {
        if packages.is_system(app_id) || names.iter().any(|n| OWN_PACKAGES.contains(&n.as_str())) {
            next.apps.insert(app_id, names_of(app_id));
            continue;
        }
        for &user in users {
            let uid = user * PER_USER_RANGE + app_id;
            if next.uids.contains_key(&uid) {
                continue;
            }
            let libraries = next.libraries.get(&app_id);
            let here: BTreeSet<String> = names
                .iter()
                .filter(|name| libraries.is_none_or(|libraries| !libraries.contains(*name)))
                .filter(|name| installed(user, name))
                .cloned()
                .collect();
            if !here.is_empty() {
                isolate.push((uid, here));
            }
        }
    }
    // An app id the system took over (an app the user installed that a
    // system update then brought) needs no uids of its own.
    next.uids
        .retain(|&uid, _| !next.apps.contains_key(&app_id_of(uid)));
    Plan {
        isolate,
        known: next,
    }
}

impl Sandbox {
    /// Look at what is installed: forget what is not any more, know the
    /// system's new apps, and with `isolate`, put new apps in the sandbox.
    pub(super) fn look(&mut self, writer: &Writer, packages: &mut Packages, isolate: bool) {
        let started = Instant::now();
        // Each question to the app ops helper and each app put in may wait
        // for it, on the loop that also records. As when taking every app
        // out, nothing more is begun past a point: one app being put in
        // then waits no longer than a command does, and the rest are left
        // for the next look, with their starts held meanwhile.
        let until = started + self.auto.look_wait;
        let Some(plan) = self.plan_look(writer, packages, isolate, until) else {
            return;
        };
        let (known, isolated) = self.put_in(writer, packages, plan, isolate, until);
        if !isolated.is_empty() {
            // How long it took: putting an app in can wait for the app ops
            // helper to start, and for the database to reach the disk.
            let uids: Vec<String> = isolated.iter().map(u32::to_string).collect();
            eprintln!(
                "bbd: sandbox: newly installed, put in the sandbox: uid {} ({} ms)",
                uids.join(", "),
                started.elapsed().as_millis()
            );
        }
        self.save_known(writer, known);
    }

    fn plan_look(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        isolate: bool,
        until: Instant,
    ) -> Option<Plan> {
        let fresh = packages.refresh();
        let users = self.users();
        // Without the package list, or the users, nothing can be told, and
        // nothing known is forgotten.
        if !fresh || packages.is_empty() || users.is_empty() {
            self.auto.put_off();
            return None;
        }
        if isolate {
            self.reconcile(writer, packages, Some(until));
        }
        let plan = plan(&self.auto.known, packages, &users, |user, package| {
            self.installed_for(user, package)
        });
        // What was told of packages that are gone, or under another app id
        // now, is asked again if they come back.
        self.auto
            .told_apps
            .retain(|(app_id, package)| packages.packages_of(*app_id).contains(package));
        Some(plan)
    }

    fn put_in(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        plan: Plan,
        isolate: bool,
        until: Instant,
    ) -> (Known, Vec<u32>) {
        let mut known = plan.known;
        let mut isolated = Vec::new();
        // The new apps this look leaves out, and when to try them again.
        let mut left = 0;
        let mut retries = BTreeMap::new();
        let mut asked = false;
        for (uid, names) in plan.isolate {
            let app_id = app_id_of(uid);
            let retry = self.auto.retries.get(&uid).copied();
            if !isolate || retry.is_some_and(|retry| retry.at > Instant::now()) {
                let later = match retry {
                    Some(retry) if retry.at > Instant::now() => retry,
                    // Left by a look that puts nothing in: due at once.
                    _ => Retry {
                        at: Instant::now(),
                        failures: retry.map_or(0, |retry| retry.failures),
                    },
                };
                retries.insert(uid, later);
                left += 1;
                continue;
            }
            let apps = match self.ask_libraries(uid, &names, until, &mut known, &mut asked, retry) {
                Ok(apps) => apps,
                Err(later) => {
                    retries.insert(uid, later);
                    left += 1;
                    continue;
                }
            };
            // Libraries alone.
            let Some(package) = apps.first().cloned() else {
                continue;
            };
            let result = (|| {
                let current = uid_of(packages, &package, user_of(uid))?;
                anyhow::ensure!(
                    current == uid && !packages.is_system(app_id),
                    "the package changed during the scan"
                );
                self.enable_uid(writer, packages, &package, uid, crate::clock::now())
            })();
            match result {
                // Only an app in the sandbox under its own entry is known.
                Ok(_) => {
                    isolated.push(uid);
                    known.uids.insert(uid, apps);
                }
                Err(e) => {
                    retries.insert(uid, Retry::after(retry));
                    left += 1;
                    // Package names are not logged; the error may name it.
                    let e = format!("{e:#}").replace(package.as_str(), "the app");
                    self.auto.failures.log(format_args!(
                        "bbd: sandbox: uid {uid}, newly installed, is not in the sandbox: {e}"
                    ));
                }
            }
        }
        if asked && let Some(libraries) = &self.auto.libraries {
            libraries.done();
        }
        self.auto.pending = left;
        self.auto.retries = retries;
        (known, isolated)
    }

    fn ask_libraries(
        &mut self,
        uid: u32,
        names: &BTreeSet<String>,
        until: Instant,
        known: &mut Known,
        asked: &mut bool,
        retry: Option<Retry>,
    ) -> Result<BTreeSet<String>, Retry> {
        let app_id = app_id_of(uid);
        // Each package is asked about once. A library is known, and
        // left alone; one not told about yet waits for the next try.
        let mut apps = BTreeSet::new();
        for name in names {
            if Instant::now() >= until {
                return Err(Retry::soon(retry));
            }
            *asked |= self.auto.libraries.is_some();
            match self.auto.kind_of(uid, name, until) {
                Some(true) => {
                    known
                        .libraries
                        .entry(app_id)
                        .or_default()
                        .insert(name.clone());
                }
                Some(false) => {
                    apps.insert(name.clone());
                }
                None => return Err(Retry::after(retry)),
            }
        }
        if !apps.is_empty() && Instant::now() >= until {
            return Err(Retry::soon(retry));
        }
        Ok(apps)
    }

    fn save_known(&mut self, writer: &Writer, known: Known) {
        if known != self.auto.known || self.auto.known_dirty {
            self.auto.known = known;
            self.auto.sync();
            self.auto.known_dirty = true;
        }
        if self.auto.known_dirty
            && let Err(e) = writer.put_sandbox_auto(true, &self.auto.known.rows())
        {
            eprintln!("bbd: sandbox: cannot write the apps known to be installed: {e:#}");
            self.auto.rescan = Some(Retry::after(self.auto.rescan));
            return;
        }
        self.auto.known_dirty = false;
        self.auto.rescan = None;
    }

    fn auto_user_data(&self) -> Option<&Path> {
        #[cfg(test)]
        if let Some(path) = &self.auto.user_data_fixture {
            return Some(path);
        }
        self.user_data.as_deref()
    }

    /// The Android users, by their directories in `user_data`; user 0 alone
    /// where that is not checked.
    fn users(&self) -> Vec<u32> {
        let Some(dir) = self.auto_user_data() else {
            return vec![0];
        };
        let mut users: Vec<u32> = std::fs::read_dir(dir)
            .into_iter()
            .flatten()
            .flatten()
            .filter_map(|entry| entry.file_name().to_str()?.parse().ok())
            .filter(|user| *user <= LAST_USER)
            .collect();
        users.sort_unstable();
        users
    }

    /// Whether `package` is installed for Android user `user`.
    fn installed_for(&self, user: u32, package: &str) -> bool {
        self.auto_user_data()
            .is_none_or(|dir| dir.join(user.to_string()).join(package).exists())
    }
}

#[cfg(test)]
mod tests {
    use super::super::tests::{Fixture, names};
    use super::*;

    #[test]
    fn what_is_new_is_worked_out_per_app_id_and_user() {
        let mut f = Fixture::new("auto-plan");
        f.list(&[
            "com.android.chrome 10150 0 /d default:partition=product none 0 1 1 @system",
            "com.shared.one 10220 0 /d default none 0 1 1 @null",
            "com.shared.two 10220 0 /d default none 0 1 1 @null",
            "com.example.gone 10221 0 /d default none 0 1 1 @null",
            "com.android.phone 1001 0 /d platform:partition=system none 0 1 1 @system",
        ]);
        let known = Known {
            apps: BTreeMap::from([(10150, names(&["com.android.chrome"]))]),
            uids: BTreeMap::from([
                (10221, names(&["com.example.gone"])),
                (1_010_221, names(&["com.example.gone"])),
            ]),
            libraries: BTreeMap::new(),
        };
        // User 10 has removed it, and has only the second of the pair.
        let installed = |user: u32, package: &str| {
            !(user == 10 && matches!(package, "com.example.gone" | "com.shared.one"))
        };
        let plan = plan(&known, &f.packages, &[0, 10], installed);
        assert_eq!(
            plan.isolate,
            [
                (10220, names(&["com.shared.one", "com.shared.two"])),
                (1_010_220, names(&["com.shared.two"]))
            ]
        );
        assert_eq!(
            plan.known,
            Known {
                apps: known.apps.clone(),
                uids: BTreeMap::from([(10221, names(&["com.example.gone"]))]),
                libraries: BTreeMap::new(),
            }
        );
    }
}
