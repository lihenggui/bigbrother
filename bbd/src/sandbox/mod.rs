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

//! The sandbox: which apps the user put in it, and turning it on and off.
//!
//! The sandbox applies to a uid, not a package. Android runs every package
//! that shares a uid as the same user, with the same files and permissions,
//! so one of them cannot be held apart from the others; enabling it for one
//! puts them all in, and says so.
//!
//! What it does to an app is a list of [`Effect`]s, carried out in order
//! when it is enabled and disabled. Two records say what holds:
//!
//! - The `sandbox` table says what may be in effect. It is written before
//!   the effects of an enable and after those of a disable, so that whenever
//!   any effect may be in place the row says the app is sandboxed, and a
//!   daemon that stopped half way can finish or undo the change when it
//!   starts again.
//! - The snapshot, in memory, says what is meant to be in effect. It changes
//!   before the effects run, which may read it, and is shared with whatever
//!   needs to look up a uid without waiting for the daemon's main loop.
//!
//! The effects, in the order they are carried out:
//!
//! - [`appops`]: personal data is refused through app ops.
//! - [`identity`]: the system server hook is told which uids to answer
//!   differently.
//! - [`gate`]: starting apps are held until [`mounts`] has replaced the
//!   storage they see.
//! - [`sweep`]: the app's running processes are ended, so that it starts
//!   again as it should.
//!
//! Taking an app out begins with the one that can fail, restoring its app
//! ops, so that a failure leaves the app as sandboxed as it was.

pub mod appops;
mod dirs;
pub mod gate;
pub mod identity;
mod mounts;
pub mod sweep;

use std::{
    collections::HashMap,
    fs::File,
    io::{Read as _, Write as _},
    path::{Path, PathBuf},
    sync::{Arc, RwLock},
};

use anyhow::Context as _;
use serde::{Deserialize, Serialize};

use crate::{
    control::{self, Request},
    packages::{PER_USER_RANGE, Packages},
    store::Writer,
};

pub use crate::store::SandboxEntry as Entry;

/// App ids from here up belong to installed apps. Below are the system's
/// own uids, which are shared by many components and cannot be sandboxed.
const FIRST_APPLICATION_UID: u32 = 10_000;

/// Packages that are never sandboxed: the app, whose own reads of the
/// device would be cut off from what it reports on. The canary test app
/// (`li.mercury.bigbrother.canary`) is not among them; it is what the
/// sandbox is tested with.
const OWN_PACKAGES: &[&str] = &["li.mercury.bigbrother"];

/// The highest Android user whose uids still fit the 31 bits Android keeps
/// them in.
const LAST_USER: u32 = (i32::MAX as u32 - (PER_USER_RANGE - 1)) / PER_USER_RANGE;

/// The longest taking every app out may go on for, short of what a client
/// waits for an answer (`control::COMMAND_WAIT`).
const DISABLE_ALL_WAIT: std::time::Duration = std::time::Duration::from_secs(6);

/// The sandboxed uids and their entries, enabled ones only.
pub type Snapshot = Arc<RwLock<HashMap<u32, Entry>>>;

/// One thing the sandbox does to an app (say, the storage it sees, or the
/// app ops it is refused).
///
/// Both [`Effect::enable`] and [`Effect::disable`] run the effects in the
/// order they were added, since a step such as stopping the app has to come
/// last either way. When one fails, those that already ran are undone in
/// reverse order and the command fails, so that an app is never left half
/// sandboxed.
pub trait Effect {
    /// Names the effect in an error.
    fn name(&self) -> &'static str;

    /// Apply the effect to `entry.uid`, under which run `packages`. It may
    /// change the entry, for instance to keep what it replaced; the entry is
    /// written after the last effect.
    fn enable(&mut self, entry: &mut Entry, packages: &[String]) -> anyhow::Result<()>;

    /// Take the effect off again, from what `entry` keeps.
    fn disable(&mut self, entry: &mut Entry, packages: &[String]) -> anyhow::Result<()>;

    /// Take up again, in a daemon that just started, the apps that were
    /// sandboxed when the last one stopped: each entry with the packages
    /// under its uid. Unlike [`Effect::enable`] this cannot fail the whole:
    /// an effect reports what it could not do and carries on.
    fn resume(&mut self, _entries: &[(Entry, Vec<String>)]) {}

    /// Fill in what the effect knows about its own state.
    fn report(&self, _status: &mut Status) {}
}

/// The answer to `sandbox.enable`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Enabled {
    pub uid: u32,
    /// The other packages that share the uid, and are sandboxed with it.
    pub also: Vec<String>,
}

/// The answer to `sandbox.disable`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Disabled {
    /// False if the app was not in the sandbox, and nothing was done.
    #[serde(default = "yes")]
    pub was_enabled: bool,
}

/// What a daemon that does not say is taken to mean.
fn yes() -> bool {
    true
}

/// The answer to `sandbox.disable_all`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DisabledAll {
    /// How many apps were taken out.
    pub disabled: u32,
    /// The uids that could not be, and are sandboxed still.
    pub failed: Vec<u32>,
}

/// The answer to `sandbox.list`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Listed {
    pub apps: Vec<ListedApp>,
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ListedApp {
    pub uid: u32,
    pub package: String,
    pub enabled: bool,
}

/// The answer to `sandbox.status`: whether each part of the sandbox works on
/// this device, and how the starts of sandboxed apps went. What no effect
/// reports on stays false or zero.
#[derive(Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Status {
    /// Starting apps are held until their storage is replaced.
    pub gate: bool,
    /// Personal data is refused through app ops.
    pub appops: bool,
    pub hook: HookStatus,
    /// Starts of sandboxed apps let through, and those that were stopped.
    pub admitted: u64,
    pub failed: u64,
    /// How long starts were held, 95th percentile, in microseconds.
    pub hold_p95_us: u64,
}

/// What the system server hook reports it has in place.
#[derive(Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct HookStatus {
    pub android_id: bool,
    pub app_list: bool,
}

pub struct Sandbox {
    snapshot: Snapshot,
    effects: Vec<Box<dyn Effect>>,
    /// Where each Android user's apps keep their data, a directory per user
    /// and package (`/data/user`): how an app is known to be installed for a
    /// user. Not checked when `None`.
    user_data: Option<PathBuf>,
}

impl Sandbox {
    /// The sandbox as last written, doing `effects`, in this order, to the
    /// apps enabled and disabled from now on. The snapshot holds the enabled
    /// entries.
    pub fn load(
        writer: &Writer,
        effects: Vec<Box<dyn Effect>>,
        user_data: Option<&Path>,
    ) -> anyhow::Result<Self> {
        let enabled = writer
            .sandbox_entries()?
            .into_iter()
            .filter(|entry| entry.enabled)
            .map(|entry| (entry.uid, entry))
            .collect();
        Ok(Self {
            snapshot: Arc::new(RwLock::new(enabled)),
            effects,
            user_data: user_data.map(Path::to_path_buf),
        })
    }

    /// The enabled entries, for an effect that needs them all.
    pub fn snapshot(&self) -> Snapshot {
        self.snapshot.clone()
    }

    /// Add an effect, after those there are.
    pub fn add_effect(&mut self, effect: Box<dyn Effect>) {
        self.effects.push(effect);
    }

    /// Put the apps that were sandboxed when the daemon last stopped back
    /// in. To be called once, when the daemon starts.
    pub fn resume(&mut self, writer: &Writer, packages: &mut Packages) {
        self.release_reassigned(writer, packages);
        let mut entries: Vec<(Entry, Vec<String>)> = self
            .snapshot_read()
            .values()
            .map(|entry| (entry.clone(), packages.for_uid(entry.uid).to_vec()))
            .collect();
        entries.sort_by_key(|(entry, _)| entry.uid);
        for effect in &mut self.effects {
            effect.resume(&entries);
        }
    }

    /// Take out the entries whose uid no longer runs the app that was put
    /// in. Android hands the uid of an uninstalled app to one installed
    /// later, which would otherwise find itself sandboxed, with the other's
    /// identity.
    fn release_reassigned(&mut self, writer: &Writer, packages: &mut Packages) {
        packages.refresh();
        // Without the package list nothing can be told.
        if packages.is_empty() {
            return;
        }
        let reassigned: Vec<Entry> = self
            .snapshot_read()
            .values()
            .filter(|entry| !packages.for_uid(entry.uid).contains(&entry.package))
            .cloned()
            .collect();
        for mut entry in reassigned {
            let uid = entry.uid;
            eprintln!("bbd: sandbox: the app of uid {uid} is gone; the uid is taken out");
            let kept = entry.clone();
            self.snapshot_write().remove(&uid);
            entry.enabled = false;
            // What was saved was the other app's: the uid goes back to the
            // system's defaults.
            entry.saved_ops.clear();
            let released = self
                .run(&mut entry, &[], false)
                .and_then(|()| writer.put_sandbox(&entry));
            if let Err(e) = released {
                // Still sandboxed, and listed as such, rather than half
                // taken out with nothing to show it. Tried again with the
                // next command, and when the daemon next starts.
                eprintln!("bbd: sandbox: uid {uid} cannot be taken out yet: {e:#}");
                self.snapshot_write().insert(uid, kept);
            }
        }
    }

    fn snapshot_read(&self) -> std::sync::RwLockReadGuard<'_, HashMap<u32, Entry>> {
        // A reader that panicked cannot have left the map half changed.
        self.snapshot.read().unwrap_or_else(|e| e.into_inner())
    }

    fn snapshot_write(&self) -> std::sync::RwLockWriteGuard<'_, HashMap<u32, Entry>> {
        self.snapshot.write().unwrap_or_else(|e| e.into_inner())
    }

    /// Answer a request line from `bbd.ctl`.
    pub fn answer(&mut self, line: &str, writer: &Writer, packages: &mut Packages) -> String {
        let request = match Request::parse(line) {
            Ok(request) => request,
            Err(e) => return control::failure(&e),
        };
        if !matches!(request, Request::SandboxStatus) {
            self.release_reassigned(writer, packages);
        }
        let answer = match request {
            Request::SandboxEnable { package, user } => self
                .enable(writer, packages, &package, user, crate::now())
                .map(control::success),
            Request::SandboxDisable { package, user } => self
                .disable(writer, packages, &package, user)
                .map(control::success),
            Request::SandboxDisableAll => Ok(control::success(self.disable_all(writer))),
            Request::SandboxList => list(writer).map(control::success),
            Request::SandboxStatus => Ok(control::success(self.status())),
        };
        answer.unwrap_or_else(|e| control::failure(&format!("{e:#}")))
    }

    /// Put `package`, as installed for Android user `user`, in the sandbox,
    /// with every package that shares its uid.
    pub fn enable(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        package: &str,
        user: u32,
        now: i64,
    ) -> anyhow::Result<Enabled> {
        let uid = uid_of(packages, package, user)?;
        if let Some(user_data) = &self.user_data {
            anyhow::ensure!(
                user_data.join(user.to_string()).join(package).exists(),
                "{package} is not installed for user {user}"
            );
        }
        let sharing = packages.for_uid(uid).to_vec();
        anyhow::ensure!(
            !sharing.iter().any(|p| OWN_PACKAGES.contains(&p.as_str())),
            "Big Brother cannot sandbox itself"
        );
        let also = sharing.iter().filter(|p| *p != package).cloned().collect();
        if self.snapshot_read().contains_key(&uid) {
            return Ok(Enabled { uid, also });
        }

        let before = writer.sandbox_entry(uid)?;
        let mut entry = match &before {
            // The identity it had stays.
            Some(old) => Entry {
                package: package.to_string(),
                enabled: true,
                ..old.clone()
            },
            None => Entry {
                uid,
                package: package.to_string(),
                enabled: true,
                android_id: new_android_id()?,
                saved_ops: String::new(),
                created: now,
            },
        };
        writer.put_sandbox(&entry)?;
        self.snapshot_write().insert(uid, entry.clone());

        let written = entry.clone();
        let mut applied = self.run(&mut entry, &sharing, true);
        if applied.is_ok() && entry != written {
            applied = writer.put_sandbox(&entry);
            if applied.is_err() {
                // What the effects changed is lost: they cannot stay.
                if let Err(e) = self.run(&mut entry.clone(), &sharing, false) {
                    eprintln!("bbd: sandbox: cannot take uid {uid}'s effects off: {e:#}");
                }
            }
        }
        if let Err(e) = applied {
            self.snapshot_write().remove(&uid);
            let restored = match &before {
                Some(old) => writer.put_sandbox(old),
                None => writer.delete_sandbox(uid),
            };
            if let Err(restore) = restored {
                eprintln!("bbd: sandbox: cannot restore uid {uid}'s entry: {restore:#}");
            }
            return Err(e);
        }
        self.snapshot_write().insert(uid, entry);
        Ok(Enabled { uid, also })
    }

    /// Take `package`'s uid, as installed for Android user `user`, out of
    /// the sandbox. Its entry is kept, disabled, so that enabling it again
    /// gives the app the identity it had. Nothing is done for an app that is
    /// not sandboxed, and the answer says so.
    pub fn disable(
        &mut self,
        writer: &Writer,
        packages: &mut Packages,
        package: &str,
        user: u32,
    ) -> anyhow::Result<Disabled> {
        // An app uninstalled while sandboxed is found by its entry.
        let uid = match uid_of(packages, package, user) {
            Ok(uid) => uid,
            Err(e) => writer
                .sandbox_entries()?
                .into_iter()
                .find(|entry| entry.package == package && entry.uid / PER_USER_RANGE == user)
                .map(|entry| entry.uid)
                .ok_or(e)?,
        };
        let sharing = packages.for_uid(uid).to_vec();
        self.disable_uid(writer, uid, &sharing)
    }

    fn disable_uid(
        &mut self,
        writer: &Writer,
        uid: u32,
        sharing: &[String],
    ) -> anyhow::Result<Disabled> {
        let Some(mut entry) = self.snapshot_write().remove(&uid) else {
            return Ok(Disabled { was_enabled: false });
        };
        let enabled = entry.clone();
        entry.enabled = false;
        if let Err(e) = self.run(&mut entry, sharing, false) {
            self.snapshot_write().insert(uid, enabled);
            return Err(e);
        }
        if let Err(e) = writer.put_sandbox(&entry) {
            // The row still says the app is sandboxed: so it stays.
            if let Err(e) = self.run(&mut enabled.clone(), sharing, true) {
                eprintln!("bbd: sandbox: cannot put uid {uid}'s effects back: {e:#}");
            }
            self.snapshot_write().insert(uid, enabled);
            return Err(e);
        }
        Ok(Disabled { was_enabled: true })
    }

    /// Take every app out of the sandbox, going on past one that cannot be.
    pub fn disable_all(&mut self, writer: &Writer) -> DisabledAll {
        let mut uids: Vec<u32> = self.snapshot_read().keys().copied().collect();
        uids.sort_unstable();
        let mut answer = DisabledAll {
            disabled: 0,
            failed: Vec::new(),
        };
        // Each app may wait for the app ops helper, on the loop that also
        // records. With a helper that does not answer, the rest are not
        // waited for one by one.
        let until = std::time::Instant::now() + DISABLE_ALL_WAIT;
        for uid in uids {
            if std::time::Instant::now() > until {
                answer.failed.push(uid);
                continue;
            }
            match self.disable_uid(writer, uid, &[]) {
                Ok(_) => answer.disabled += 1,
                Err(e) => {
                    eprintln!("bbd: sandbox: cannot take uid {uid} out: {e:#}");
                    answer.failed.push(uid);
                }
            }
        }
        answer
    }

    /// Carry out every effect, enabling or disabling. When one fails, those
    /// that already ran are reversed, last first.
    fn run(&mut self, entry: &mut Entry, packages: &[String], enable: bool) -> anyhow::Result<()> {
        for done in 0..self.effects.len() {
            let effect = &mut self.effects[done];
            let result = if enable {
                effect.enable(entry, packages)
            } else {
                effect.disable(entry, packages)
            };
            if let Err(e) = result {
                let failed = effect.name();
                for effect in self.effects[..done].iter_mut().rev() {
                    let undone = if enable {
                        effect.disable(entry, packages)
                    } else {
                        effect.enable(entry, packages)
                    };
                    if let Err(undo) = undone {
                        eprintln!(
                            "bbd: sandbox: cannot undo {} for uid {}: {undo:#}",
                            effect.name(),
                            entry.uid
                        );
                    }
                }
                return Err(e.context(failed));
            }
        }
        Ok(())
    }

    pub fn status(&self) -> Status {
        let mut status = Status::default();
        for effect in &self.effects {
            effect.report(&mut status);
        }
        status
    }
}

/// Every entry, enabled or not.
fn list(writer: &Writer) -> anyhow::Result<Listed> {
    let apps = writer
        .sandbox_entries()?
        .into_iter()
        .map(|entry| ListedApp {
            uid: entry.uid,
            package: entry.package,
            enabled: entry.enabled,
        })
        .collect();
    Ok(Listed { apps })
}

/// The uid `package` runs under for Android user `user`, if it is an app
/// that can be sandboxed.
fn uid_of(packages: &mut Packages, package: &str, user: u32) -> anyhow::Result<u32> {
    // What was installed or removed since the list was last read counts:
    // a name could otherwise lead to the uid of another app.
    packages.refresh();
    let app_id = packages
        .app_id_of(package)
        .with_context(|| format!("{package} is not installed"))?;
    anyhow::ensure!(
        app_id >= FIRST_APPLICATION_UID,
        "{package} runs as a system uid ({app_id}) and cannot be sandboxed"
    );
    anyhow::ensure!(user <= LAST_USER, "no such user: {user}");
    Ok(user * PER_USER_RANGE + app_id)
}

/// A new ANDROID_ID: 64 random bits as 16 lowercase hex digits, the form
/// Android gives it.
fn new_android_id() -> anyhow::Result<String> {
    let mut bytes = [0; 8];
    File::open("/dev/urandom")
        .and_then(|mut random| random.read_exact(&mut bytes))
        .context("reading /dev/urandom")?;
    Ok(format!("{:016x}", u64::from_ne_bytes(bytes)))
}

// ---- command line ----------------------------------------------------------

/// Send `request` to the daemon and print its answer: as the JSON line the
/// daemon sent with `json`, otherwise for a person to read.
pub fn command(db: &Path, request: Request, json: bool) -> anyhow::Result<()> {
    let answer = control::command(db, &request)?;
    if json {
        println!("{answer}");
        return Ok(());
    }
    let mut out = std::io::stdout().lock();
    match request {
        Request::SandboxEnable { package, .. } => {
            let enabled: Enabled = serde_json::from_value(answer)?;
            print_enabled(&mut out, &package, &enabled)
        }
        Request::SandboxDisable { package, .. } => {
            print_disabled(&mut out, &package, &serde_json::from_value(answer)?)
        }
        Request::SandboxDisableAll => {
            let all: DisabledAll = serde_json::from_value(answer)?;
            writeln!(out, "Sandbox disabled for {} apps.", all.disabled)?;
            if !all.failed.is_empty() {
                writeln!(out, "Still sandboxed: {} apps.", all.failed.len())?;
            }
            Ok(())
        }
        Request::SandboxList => print_list(&mut out, &serde_json::from_value(answer)?),
        Request::SandboxStatus => print_status(&mut out, &serde_json::from_value(answer)?),
    }?;
    Ok(())
}

fn print_enabled(
    out: &mut impl std::io::Write,
    package: &str,
    enabled: &Enabled,
) -> std::io::Result<()> {
    writeln!(out, "Sandbox enabled for {package} (uid {}).", enabled.uid)?;
    if !enabled.also.is_empty() {
        writeln!(
            out,
            "Also sandboxed, sharing its uid: {}.",
            enabled.also.join(", ")
        )?;
    }
    Ok(())
}

fn print_disabled(
    out: &mut impl std::io::Write,
    package: &str,
    disabled: &Disabled,
) -> std::io::Result<()> {
    if disabled.was_enabled {
        writeln!(out, "Sandbox disabled for {package}.")
    } else {
        writeln!(out, "{package} was not in the sandbox.")
    }
}

fn print_list(out: &mut impl std::io::Write, listed: &Listed) -> std::io::Result<()> {
    if listed.apps.is_empty() {
        return writeln!(out, "No apps in the sandbox.");
    }
    writeln!(out, "{:<10} {:<9} PACKAGE", "UID", "STATE")?;
    for app in &listed.apps {
        let state = if app.enabled { "enabled" } else { "disabled" };
        writeln!(out, "{:<10} {state:<9} {}", app.uid, app.package)?;
    }
    Ok(())
}

fn print_status(out: &mut impl std::io::Write, status: &Status) -> std::io::Result<()> {
    let on = |active: bool| if active { "active" } else { "inactive" };
    writeln!(out, "Storage gate:      {}", on(status.gate))?;
    writeln!(out, "App ops:           {}", on(status.appops))?;
    writeln!(out, "ANDROID_ID hook:   {}", on(status.hook.android_id))?;
    writeln!(out, "App list hook:     {}", on(status.hook.app_list))?;
    writeln!(out, "Starts admitted:   {}", status.admitted)?;
    writeln!(out, "Starts failed:     {}", status.failed)?;
    writeln!(out, "Hold time p95:     {} us", status.hold_p95_us)
}

#[cfg(test)]
mod tests {
    use std::{cell::RefCell, path::PathBuf, rc::Rc};

    use super::*;

    struct Fixture {
        dir: PathBuf,
        writer: Writer,
        packages: Packages,
    }

    impl Fixture {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("bbd-test-{name}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();
            let list = dir.join("packages.list");
            std::fs::write(
                &list,
                "com.app 10100 0 /data/user/0/com.app default none\n\
                 com.shared.one 10200 0 /d x\n\
                 com.shared.two 10200 0 /d x\n\
                 com.android.phone 1001 0 /d x\n\
                 li.mercury.bigbrother 10300 0 /d x\n\
                 li.mercury.bigbrother.canary 10301 0 /d x\n",
            )
            .unwrap();
            let packages = Packages::new(list.to_str().unwrap());
            let writer = Writer::open(&dir.join("bb.db")).unwrap();
            Self {
                dir,
                writer,
                packages,
            }
        }

        fn sandbox(&self) -> Sandbox {
            self.sandbox_with(Vec::new())
        }

        fn sandbox_with(&self, effects: Vec<Box<dyn Effect>>) -> Sandbox {
            Sandbox::load(&self.writer, effects, None).unwrap()
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    /// Records what it was asked to do, in a log shared by several, and
    /// fails when told to.
    struct Recorder {
        name: &'static str,
        log: Rc<RefCell<Vec<String>>>,
        fail_enable: bool,
        fail_disable: bool,
    }

    impl Recorder {
        fn new(name: &'static str, log: &Rc<RefCell<Vec<String>>>) -> Box<Self> {
            Box::new(Self {
                name,
                log: log.clone(),
                fail_enable: false,
                fail_disable: false,
            })
        }
    }

    impl Effect for Recorder {
        fn name(&self) -> &'static str {
            self.name
        }

        fn enable(&mut self, entry: &mut Entry, _: &[String]) -> anyhow::Result<()> {
            anyhow::ensure!(!self.fail_enable, "refused");
            self.log.borrow_mut().push(format!("+{}", self.name));
            entry.saved_ops = format!("{}=1", self.name);
            Ok(())
        }

        fn disable(&mut self, _: &mut Entry, _: &[String]) -> anyhow::Result<()> {
            anyhow::ensure!(!self.fail_disable, "refused");
            self.log.borrow_mut().push(format!("-{}", self.name));
            Ok(())
        }

        fn resume(&mut self, entries: &[(Entry, Vec<String>)]) {
            for (entry, _) in entries {
                self.log
                    .borrow_mut()
                    .push(format!("~{} {}", self.name, entry.uid));
            }
        }

        fn report(&self, status: &mut Status) {
            status.gate = true;
        }
    }

    fn enabled(sandbox: &Sandbox) -> Vec<u32> {
        let mut uids: Vec<u32> = sandbox.snapshot_read().keys().copied().collect();
        uids.sort();
        uids
    }

    #[test]
    fn packages_sharing_the_uid_are_sandboxed_with_it() {
        let mut f = Fixture::new("sandbox-shared");
        let mut sandbox = f.sandbox();
        let answer = sandbox
            .enable(&f.writer, &mut f.packages, "com.shared.two", 0, 1000)
            .unwrap();
        assert_eq!(
            answer,
            Enabled {
                uid: 10200,
                also: vec!["com.shared.one".into()]
            }
        );
        let alone = sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        assert!(alone.also.is_empty());
        assert_eq!(enabled(&sandbox), [10100, 10200]);
    }

    #[test]
    fn the_uid_is_that_of_the_given_user() {
        let mut f = Fixture::new("sandbox-user");
        let mut sandbox = f.sandbox();
        let answer = sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 10, 1000)
            .unwrap();
        assert_eq!(answer.uid, 1_010_100);
        assert!(
            sandbox
                .enable(&f.writer, &mut f.packages, "com.app", u32::MAX, 1000)
                .is_err()
        );
    }

    #[test]
    fn refuses_system_uids_itself_and_what_is_not_installed() {
        let mut f = Fixture::new("sandbox-refuse");
        let mut sandbox = f.sandbox();
        for package in ["com.android.phone", "li.mercury.bigbrother", "com.absent"] {
            assert!(
                sandbox
                    .enable(&f.writer, &mut f.packages, package, 0, 1000)
                    .is_err(),
                "{package}"
            );
        }
        assert!(f.writer.sandbox_entries().unwrap().is_empty());
        // The canary is how the sandbox is tested.
        sandbox
            .enable(
                &f.writer,
                &mut f.packages,
                "li.mercury.bigbrother.canary",
                0,
                1000,
            )
            .unwrap();
        assert_eq!(enabled(&sandbox), [10301]);
    }

    #[test]
    fn disabling_keeps_the_android_id() {
        let mut f = Fixture::new("sandbox-identity");
        let mut sandbox = f.sandbox();
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        let first = f.writer.sandbox_entry(10100).unwrap().unwrap();
        assert_eq!(first.android_id.len(), 16);
        assert!(
            first
                .android_id
                .chars()
                .all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c))
        );
        assert_eq!((first.enabled, first.created), (true, 1000));

        sandbox
            .disable(&f.writer, &mut f.packages, "com.app", 0)
            .unwrap();
        assert!(enabled(&sandbox).is_empty());
        let disabled = f.writer.sandbox_entry(10100).unwrap().unwrap();
        assert!(!disabled.enabled);
        assert_eq!(disabled.android_id, first.android_id);

        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 2000)
            .unwrap();
        assert_eq!(f.writer.sandbox_entry(10100).unwrap(), Some(first));
        // Another app gets an identity of its own.
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 10, 2000)
            .unwrap();
        let other = f.writer.sandbox_entry(1_010_100).unwrap().unwrap();
        assert_ne!(
            other.android_id,
            f.writer.sandbox_entry(10100).unwrap().unwrap().android_id
        );
    }

    #[test]
    fn the_snapshot_is_loaded_from_the_enabled_entries() {
        let mut f = Fixture::new("sandbox-load");
        let mut sandbox = f.sandbox();
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        sandbox
            .enable(&f.writer, &mut f.packages, "com.shared.one", 0, 1000)
            .unwrap();
        sandbox
            .disable(&f.writer, &mut f.packages, "com.shared.one", 0)
            .unwrap();
        assert_eq!(enabled(&f.sandbox()), [10100]);
    }

    #[test]
    fn enabling_twice_or_disabling_what_is_not_sandboxed_does_nothing() {
        let mut f = Fixture::new("sandbox-idempotent");
        let log = Rc::new(RefCell::new(Vec::new()));
        let mut sandbox = f.sandbox_with(vec![Recorder::new("gate", &log)]);
        sandbox
            .disable(&f.writer, &mut f.packages, "com.app", 0)
            .unwrap();
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        sandbox
            .enable(&f.writer, &mut f.packages, "com.shared.one", 0, 1000)
            .unwrap();
        // Through another package of the same uid.
        sandbox
            .enable(&f.writer, &mut f.packages, "com.shared.two", 0, 1000)
            .unwrap();
        assert_eq!(*log.borrow(), ["+gate", "+gate"]);
        assert!(
            sandbox
                .disable(&f.writer, &mut f.packages, "com.absent", 0)
                .is_err()
        );
    }

    #[test]
    fn an_app_uninstalled_while_sandboxed_can_be_disabled() {
        let mut f = Fixture::new("sandbox-uninstalled");
        let mut sandbox = f.sandbox();
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        let mut gone = Packages::new(f.dir.join("absent.list").to_str().unwrap());
        sandbox.disable(&f.writer, &mut gone, "com.app", 0).unwrap();
        assert!(enabled(&sandbox).is_empty());
        assert!(!f.writer.sandbox_entry(10100).unwrap().unwrap().enabled);
    }

    #[test]
    fn effects_run_in_order_and_what_they_change_is_kept() {
        let mut f = Fixture::new("sandbox-effects");
        let log = Rc::new(RefCell::new(Vec::new()));
        let mut sandbox = f.sandbox_with(vec![
            Recorder::new("gate", &log),
            Recorder::new("appops", &log),
        ]);
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        assert_eq!(
            f.writer.sandbox_entry(10100).unwrap().unwrap().saved_ops,
            "appops=1"
        );
        assert_eq!(sandbox.snapshot_read()[&10100].saved_ops, "appops=1");
        sandbox
            .disable(&f.writer, &mut f.packages, "com.app", 0)
            .unwrap();
        assert_eq!(*log.borrow(), ["+gate", "+appops", "-gate", "-appops"]);
        assert!(sandbox.status().gate);
    }

    #[test]
    fn a_failed_enable_is_undone() {
        let mut f = Fixture::new("sandbox-enable-fails");
        let log = Rc::new(RefCell::new(Vec::new()));
        let mut failing = Recorder::new("identity", &log);
        failing.fail_enable = true;
        let mut sandbox = f.sandbox_with(vec![
            Recorder::new("gate", &log),
            Recorder::new("appops", &log),
            failing,
        ]);

        let e = sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap_err();
        assert_eq!(format!("{e:#}"), "identity: refused");
        assert_eq!(*log.borrow(), ["+gate", "+appops", "-appops", "-gate"]);
        assert!(enabled(&sandbox).is_empty());
        assert_eq!(f.writer.sandbox_entry(10100).unwrap(), None);
    }

    #[test]
    fn a_failed_reenable_keeps_the_disabled_entry() {
        let mut f = Fixture::new("sandbox-reenable-fails");
        let log = Rc::new(RefCell::new(Vec::new()));
        let mut sandbox = f.sandbox();
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        sandbox
            .disable(&f.writer, &mut f.packages, "com.app", 0)
            .unwrap();
        let disabled = f.writer.sandbox_entry(10100).unwrap();
        let mut failing = Recorder::new("gate", &log);
        failing.fail_enable = true;
        let mut sandbox = f.sandbox_with(vec![failing]);
        assert!(
            sandbox
                .enable(&f.writer, &mut f.packages, "com.app", 0, 2000)
                .is_err()
        );
        assert_eq!(f.writer.sandbox_entry(10100).unwrap(), disabled);
    }

    #[test]
    fn a_failed_disable_is_undone() {
        let mut f = Fixture::new("sandbox-disable-fails");
        let log = Rc::new(RefCell::new(Vec::new()));
        let mut failing = Recorder::new("appops", &log);
        failing.fail_disable = true;
        let mut sandbox = f.sandbox_with(vec![Recorder::new("gate", &log), failing]);
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        log.borrow_mut().clear();

        assert!(
            sandbox
                .disable(&f.writer, &mut f.packages, "com.app", 0)
                .is_err()
        );
        assert_eq!(*log.borrow(), ["-gate", "+gate"]);
        assert_eq!(enabled(&sandbox), [10100]);
        assert!(f.writer.sandbox_entry(10100).unwrap().unwrap().enabled);
    }

    #[test]
    fn a_uid_handed_to_another_app_is_taken_out() {
        let mut f = Fixture::new("sandbox-reassigned");
        let log = Rc::new(RefCell::new(Vec::new()));
        let mut sandbox = f.sandbox_with(vec![Recorder::new("gate", &log)]);
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        sandbox
            .enable(&f.writer, &mut f.packages, "com.shared.one", 0, 1000)
            .unwrap();
        log.borrow_mut().clear();
        // com.app was uninstalled, and its uid given to an app installed
        // after it. The shared uid keeps the package that was named.
        std::fs::write(
            f.dir.join("packages.list"),
            "com.newcomer 10100 0 /d x\ncom.shared.one 10200 0 /d x\n",
        )
        .unwrap();

        let mut restarted = f.sandbox_with(vec![Recorder::new("gate", &log)]);
        restarted.resume(&f.writer, &mut f.packages);
        assert_eq!(enabled(&restarted), [10200]);
        let released = f.writer.sandbox_entry(10100).unwrap().unwrap();
        assert!(!released.enabled);
        assert_eq!(released.saved_ops, "");
        // Taken off the one, taken up again for the other.
        assert_eq!(*log.borrow(), ["-gate", "~gate 10200"]);

        // Without a package list nothing is concluded.
        std::fs::remove_file(f.dir.join("packages.list")).unwrap();
        restarted.resume(&f.writer, &mut f.packages);
        assert_eq!(enabled(&restarted), [10200]);
    }

    #[test]
    fn an_app_must_be_installed_for_the_user_named() {
        let mut f = Fixture::new("sandbox-user-data");
        let user_data = f.dir.join("user");
        std::fs::create_dir_all(user_data.join("0/com.app")).unwrap();
        let mut sandbox = Sandbox::load(&f.writer, Vec::new(), Some(&user_data)).unwrap();
        let e = sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 10, 1000)
            .unwrap_err();
        assert_eq!(e.to_string(), "com.app is not installed for user 10");
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        // A user whose uids Android could not hold.
        assert!(
            sandbox
                .enable(&f.writer, &mut f.packages, "com.app", LAST_USER + 1, 1000)
                .is_err()
        );
    }

    #[test]
    fn every_app_can_be_taken_out_at_once() {
        let mut f = Fixture::new("sandbox-disable-all");
        let log = Rc::new(RefCell::new(Vec::new()));
        let mut sandbox = f.sandbox_with(vec![Recorder::new("gate", &log)]);
        for package in ["com.app", "com.shared.one"] {
            sandbox
                .enable(&f.writer, &mut f.packages, package, 0, 1000)
                .unwrap();
        }
        assert_eq!(
            sandbox.disable_all(&f.writer),
            DisabledAll {
                disabled: 2,
                failed: vec![]
            }
        );
        assert!(enabled(&sandbox).is_empty());
        assert!(
            f.writer
                .sandbox_entries()
                .unwrap()
                .iter()
                .all(|e| !e.enabled)
        );

        // One that cannot be taken out is reported, and stays.
        let mut failing = Recorder::new("appops", &log);
        failing.fail_disable = true;
        let mut sandbox = f.sandbox_with(vec![failing]);
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 2000)
            .unwrap();
        assert_eq!(
            sandbox.disable_all(&f.writer),
            DisabledAll {
                disabled: 0,
                failed: vec![10100]
            }
        );
        assert_eq!(enabled(&sandbox), [10100]);
    }

    #[test]
    fn requests_are_answered_with_one_json_line() {
        let mut f = Fixture::new("sandbox-answer");
        let mut sandbox = f.sandbox();
        let mut ask = |line: &str| -> serde_json::Value {
            let answer = sandbox.answer(line, &f.writer, &mut f.packages);
            assert!(!answer.contains('\n'));
            serde_json::from_str(&answer).unwrap()
        };
        assert_eq!(
            ask(r#"{"cmd":"sandbox.enable","package":"com.shared.one","user":0}"#),
            serde_json::json!({"ok": true, "uid": 10200, "also": ["com.shared.two"]})
        );
        // The user may be left out.
        assert_eq!(
            ask(r#"{"cmd":"sandbox.enable","package":"com.app"}"#)["uid"],
            10100
        );
        assert_eq!(
            ask(r#"{"cmd":"sandbox.disable","package":"com.app","user":0}"#),
            serde_json::json!({"ok": true, "was_enabled": true})
        );
        assert_eq!(
            ask(r#"{"cmd":"sandbox.disable","package":"com.app","user":0}"#),
            serde_json::json!({"ok": true, "was_enabled": false})
        );
        assert_eq!(
            ask(r#"{"cmd":"sandbox.list"}"#),
            serde_json::json!({"ok": true, "apps": [
                {"uid": 10100, "package": "com.app", "enabled": false},
                {"uid": 10200, "package": "com.shared.one", "enabled": true},
            ]})
        );
        assert_eq!(
            ask(r#"{"cmd":"sandbox.status"}"#),
            serde_json::json!({
                "ok": true, "gate": false, "appops": false,
                "hook": {"android_id": false, "app_list": false},
                "admitted": 0, "failed": 0, "hold_p95_us": 0,
            })
        );
        assert_eq!(
            ask(r#"{"cmd":"sandbox.enable","package":"com.absent"}"#),
            serde_json::json!({"ok": false, "error": "com.absent is not installed"})
        );
        assert_eq!(
            ask(r#"{"cmd":"sandbox.shred"}"#),
            serde_json::json!({"ok": false, "error": "unknown command"})
        );
        assert_eq!(ask("not json")["ok"], false);
        assert_eq!(ask(r#"{"cmd":"sandbox.enable"}"#)["ok"], false);
    }

    #[test]
    fn answers_are_printed_for_a_person() {
        let mut out = Vec::new();
        print_enabled(
            &mut out,
            "com.shared.one",
            &Enabled {
                uid: 10200,
                also: vec!["com.shared.two".into()],
            },
        )
        .unwrap();
        print_list(
            &mut out,
            &Listed {
                apps: vec![ListedApp {
                    uid: 10100,
                    package: "com.app".into(),
                    enabled: false,
                }],
            },
        )
        .unwrap();
        print_list(&mut out, &Listed { apps: vec![] }).unwrap();
        assert_eq!(
            String::from_utf8(out).unwrap(),
            "Sandbox enabled for com.shared.one (uid 10200).\n\
             Also sandboxed, sharing its uid: com.shared.two.\n\
             UID        STATE     PACKAGE\n\
             10100      disabled  com.app\n\
             No apps in the sandbox.\n"
        );
    }
}
