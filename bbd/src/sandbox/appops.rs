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

//! Personal data: a sandboxed app is given none.
//!
//! Contacts, messages, the calendar, location, the camera and the like are
//! handed out by system services, which ask the system's "app ops" whether
//! the calling uid may have them. With an op set to "ignored" the app gets
//! an empty answer instead of a refusal, and its permissions still show as
//! granted.
//!
//! The ops can only be set from inside the Android runtime, so this is done
//! by a helper program from the app's APK (`AppOpsGuard`), kept running and
//! spoken to in lines. The helper also keeps the ops ignored: the system
//! sets them back whenever the app's permissions change.
//!
//! The helper is a Java runtime, tens of megabytes of it. It runs only
//! while an app is sandboxed: it is started for the first one and ended
//! with the last.
//!
//! What the ops were before is kept in the app's entry (`saved_ops`), and
//! put back when the app leaves the sandbox. For the op of a runtime
//! permission the helper saves, with the mode, the mode the system gave it
//! for the permission's grant then (`android:read_contacts=1/1`); where the
//! grant has changed by the time the op is given back, the op gets the
//! system's mode for the grant as it is then instead. The daemon keeps and
//! hands back what the helper saved. Ops without a grant mode are put back
//! as saved.
//!
//! Only the ops of the items that are on for the app are held (see
//! `items::Settings::op_targets`), ignored, or, for an item left to the
//! app in the foreground, at "foreground": the system then gives the data
//! while the app is in use and nothing in the background, as Android's
//! "only while using the app" does. An app with none of them on is not
//! handed to the helper.
//!
//! Location made coarse is the system's own approximate location: no mode
//! of the app ops gives it. The helper revokes the precise location
//! permission of every package of the uid that requests it, marked as the
//! user's choice so that the app cannot ask for it again, and holds it so
//! as it holds the ops (`items::Settings::taken_permissions`). What the
//! permission was before, granted or not and with the user's flags, is
//! kept in the entry (`saved_permissions`) and put back when the app leaves
//! coarse. If the user grants it again in the meantime, or revokes it as
//! the settings do, unfixed, the helper takes it back and reports the
//! state it found (`reverted <uid>
//! <permission>=<state>`): the user's latest choice, kept in the entry in
//! place of the one before (`Effect::found`) and given back instead. A separate journal holds the preimage of an
//! unfinished transaction, while the sandbox row keeps the committed policy.

use std::{
    cell::RefCell,
    collections::{BTreeMap, BTreeSet, HashMap, HashSet},
    process::ChildStdin,
    sync::{
        Arc, Condvar, Mutex, OnceLock, TryLockError,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};

use super::{
    Effect, Entry, Resumed, Status,
    items::{self, Settings},
};
use crate::{
    log::{Limited, Policy, SANDBOX_INTERVAL},
    store::{AppOpsUndo, Writer},
};

mod protocol;
mod transport;

use protocol::{
    Answer, Command, is_permission, names_in, pairs, saved_values_of, split_saved, targets_of,
};

/// How long the helper is given to answer. Setting thirty ops takes it a
/// few milliseconds; while the system is still starting it does not answer
/// at all.
pub const ANSWER_WAIT: Duration = Duration::from_secs(4);

#[derive(Default)]
struct State {
    /// Whether the helper is to run: an app is sandboxed, or about to be.
    wanted: bool,
    /// The helper was told to leave, and has not been seen to yet.
    dismissed: bool,
    /// A command is waiting for the helper: it is started now, not when
    /// the wait after its last failure is over.
    hurry: bool,
    /// The running helper's input; `None` while there is none.
    input: Option<ChildStdin>,
    /// The uids whose ops are held, each with the ops it is held to, as
    /// `set` takes them. A helper that starts is told of each, so that it
    /// carries on where the last one ended.
    guarded: BTreeMap<u32, String>,
    owners: BTreeMap<u32, (String, String)>,
    /// The uid of the command waiting for the helper's answer. What it is
    /// held to changes with the answer, and until then no `verify` names
    /// it: the helper would take that `verify` after the command, and hold
    /// the uid again to what the command took away.
    asking: Option<u32>,
    /// Recovery commands still to send, including when the pipe filled
    /// or a change was in progress for a UID.
    pending_verifications: BTreeSet<u32>,
    blocked: BTreeSet<u32>,
    /// Initial checks are in progress while replies arrive. After the
    /// regular command deadline without progress, an absent answer is
    /// reported as unavailable.
    checking_until: Option<Instant>,
    /// Answers not yet taken by who asked.
    answer: Option<Answer>,
    request: Option<(u32, u64, String)>,
    command_until: Option<Instant>,
    /// Periodic readback can overlap an explicit verify for another UID.
    check_until: BTreeMap<u32, Instant>,
    restart: bool,
    notify_restart: bool,
    generation: u64,
    unknown: BTreeSet<u32>,
    /// Kept apart from the mutating commands, with its request number so
    /// a late answer cannot classify another package of the same uid.
    kind: Option<(u32, u64, Option<bool>)>,
    /// The ops of guarded uids that the running helper last found it could
    /// not hold. A uid it has not reported on yet is not known to be held.
    unheld: BTreeMap<u32, (Instant, BTreeSet<String>)>,
    /// The permissions of guarded uids that a helper, this one or one before
    /// it, last reported it could not hold. A helper that starts is told so
    /// in its `verify`: it may find them half taken, which it must not take
    /// for the user's doing.
    unsure: BTreeMap<u32, BTreeSet<String>>,
    /// The permissions the helper found changed by the user, by uid, as
    /// [`protocol::found_in`] reads them, not yet kept in the entries.
    found: Vec<(u32, String)>,
    awaiting_write: BTreeMap<u32, BTreeMap<String, String>>,
    receipts: BTreeMap<u32, BTreeMap<String, String>>,
}

#[derive(Default)]
struct Shared {
    state: Mutex<State>,
    next_request: AtomicU64,
    /// An answer arrived, the helper started or ended, or it is wanted.
    changed: Condvar,
    /// Called when the helper reports a permission changed by the user.
    on_found: OnceLock<Box<dyn Fn() + Send + Sync>>,
    on_restart: OnceLock<Box<dyn Fn() + Send + Sync>>,
}

pub struct AppOps {
    shared: Arc<Shared>,
}

#[cfg(test)]
impl Drop for AppOps {
    fn drop(&mut self) {
        self.shared.state().input.take();
    }
}

impl AppOps {
    /// Run the helper on a thread of its own, whenever an app is sandboxed.
    pub fn start() -> Self {
        let shared = Arc::new(Shared::default());
        let supervised = shared.clone();
        std::thread::spawn(move || transport::supervise(&supervised));
        Self { shared }
    }

    /// Have `wake` called whenever the helper reports a permission changed
    /// by the user, which [`Effect::found`] then hands over: to be kept in
    /// the entry at once rather than with the next write of the records.
    /// The helper, which knows it too, ends with the daemon, so a daemon
    /// killed before that write would give back the state from before the
    /// user's choice.
    pub fn on_found(&self, wake: impl Fn() + Send + Sync + 'static) {
        // The original callback belongs to the only consumer.
        let _ = self.shared.on_found.set(Box::new(wake));
    }

    /// Wake auto-isolation when a supervised restart restores helper replies.
    pub fn on_restart(&self, wake: impl Fn() + Send + Sync + 'static) {
        let _ = self.shared.on_restart.set(Box::new(wake));
    }

    /// What tells, through the helper, libraries from apps (see `auto`).
    pub fn librarian(&self) -> Librarian {
        Librarian {
            shared: self.shared.clone(),
            next_request: AtomicU64::new(1),
        }
    }

    /// Called with the uid of an app that just started, to have its ops
    /// looked at then: the system may set them back as an app starts. Never
    /// waits, and does nothing if the helper is busy or away, which looks
    /// again on its own every minute.
    pub fn verifier(&self) -> impl Fn(u32) + Send + 'static {
        let shared = self.shared.clone();
        let errors = RefCell::new(Limited::new(SANDBOX_INTERVAL, Policy::Shared));
        move |uid| {
            let result = match shared.state.try_lock() {
                Ok(mut state) => state.verify(uid),
                // The helper checks again on its own, so admission need not wait.
                Err(TryLockError::WouldBlock) => return,
                Err(TryLockError::Poisoned(_)) => {
                    Err(anyhow::anyhow!("the app ops state lock is poisoned"))
                }
            };
            if let Err(e) = result {
                errors.borrow_mut().log(format_args!(
                    "bbd: sandbox: cannot request app ops verification: {e:#}"
                ));
            }
        }
    }
}

/// Asks the helper, which asks the package manager, whether a package is a
/// library rather than an app: by the flags the package manager keeps for
/// it, which the package cannot set.
pub struct Librarian {
    shared: Arc<Shared>,
    next_request: AtomicU64,
}

impl super::auto::Libraries for Librarian {
    fn is_library(&self, uid: u32, package: &str, until: Instant) -> Option<bool> {
        // A name the line could not carry is not one Android gives.
        let valid = !package.is_empty()
            && package
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_');
        if !valid {
            return None;
        }
        let deadline = until.min(Instant::now() + ANSWER_WAIT);
        let request = self.next_request.fetch_add(1, Ordering::Relaxed);
        let line = Command::Kind(uid, request, package).to_string();
        self.shared
            .ask_until(
                uid,
                deadline,
                |state| state.write_line(&line),
                |state| {
                    state
                        .kind
                        .take()
                        .filter(|(id, number, _)| (*id, *number) == (uid, request))
                        .map(|(_, _, kind)| kind)
                },
            )
            .ok()
            .flatten()
    }

    fn done(&self) {
        // Started for the questions, the helper is let go again unless an
        // app is sandboxed.
        self.shared.rest_if_unused();
    }
}

/// The framework's number for an op's mode, as the helper takes it.
/// What there is to give `entry` back: what it is held to (`held`, as
/// [`targets_of`] writes it), and every permission it names as perhaps
/// taken.
fn to_give_back<'a>(entry: &'a Entry, held: &'a str) -> Vec<&'a str> {
    let mut names = names_in(held);
    for name in names_in(&entry.saved_permissions) {
        if !names.contains(&name) {
            names.push(name);
        }
    }
    names
}

/// Keep in `entry` the states the helper found for the permissions it
/// took (`<permission>=<state>,...`), for those `entry` names none for: a
/// permission named already was taken before, and what the helper finds
/// now is its own doing.
fn keep_found(entry: &mut Entry, found: &str) {
    let named = names_in(&entry.saved_permissions);
    let kept: Vec<(&str, &str)> = pairs(found)
        .filter(|(name, _)| is_permission(name) && !named.contains(name))
        .collect();
    if kept.is_empty() {
        return;
    }
    entry.saved_permissions = pairs(&entry.saved_permissions)
        .chain(kept)
        .map(|(name, state)| format!("{name}={state}"))
        .collect::<Vec<_>>()
        .join(",");
}

/// Give the permissions `entry` names the states in `found`
/// (`<permission>=<state>`) in place of the ones saved: each was changed
/// by the user while the app was held to it revoked, their choice since it
/// was taken, and what to give back now. One `entry` does not name was not
/// taken from it. Whether anything changed.
fn keep_regranted(entry: &mut Entry, found: &[String]) -> bool {
    let found: HashMap<&str, &str> = found.iter().flat_map(|pair| pairs(pair)).collect();
    let mut changed = false;
    let kept: Vec<String> = pairs(&entry.saved_permissions)
        .map(|(name, saved)| {
            let state = found.get(name).map_or_else(
                || saved.to_string(),
                |found| regranted_state(name, saved, found),
            );
            changed |= state != saved;
            format!("{name}={state}")
        })
        .collect();
    entry.saved_permissions = kept.join(",");
    changed
}

/// The state to keep for `name`, saved as `saved`, found granted again as
/// `found`. A special access is given service by service: the user giving
/// one service the access again takes it from none of the others that had
/// it, so the services found join those saved. A helper that started
/// again since it took the access knows only what it found.
fn regranted_state(name: &str, saved: &str, found: &str) -> String {
    if !items::SPECIAL_ACCESS.contains(&name) || !saved.starts_with("granted") {
        return found.to_string();
    }
    let mut words: Vec<&str> = saved.split('+').collect();
    for word in found.split('+').skip(1) {
        if !words.contains(&word) {
            words.push(word);
        }
    }
    words.join("+")
}

/// Forget what `entry` saved for the permissions among `names`: they were
/// given back.
fn forget_given_back(entry: &mut Entry, names: &[String]) {
    entry.saved_permissions = pairs(&entry.saved_permissions)
        .filter(|(name, _)| !names.iter().any(|given| given == name))
        .map(|(name, state)| format!("{name}={state}"))
        .collect::<Vec<_>>()
        .join(",");
}

/// When disabling the module, committed restrictions need their saved grants
/// back. The interrupted change can also have touched items outside that policy.
pub fn offline_restore(
    entry: &Entry,
    items: &Settings,
    undo: Option<&AppOpsUndo>,
) -> Option<String> {
    let targets = targets_of(items);
    let names = to_give_back(entry, &targets);
    if names.is_empty() {
        return undo.map(undo_line);
    }
    let saved = saved_values_of(entry, &names);
    let Some(undo) = undo else {
        return Some(Command::Restore(entry.uid, &saved).to_string());
    };
    let values: BTreeMap<_, _> = pairs(&undo.preimage).chain(pairs(&saved)).collect();
    let values = values
        .into_iter()
        .map(|(name, value)| format!("{name}={value}"))
        .collect::<Vec<_>>()
        .join(",");
    Some(Command::Restore(entry.uid, &values).to_string())
}

pub fn undo_line(undo: &AppOpsUndo) -> String {
    Command::Undo(undo.uid, &undo.preimage).to_string()
}

#[cfg(test)]
fn restore_line(entry: &Entry, items: &Settings) -> Option<String> {
    offline_restore(entry, items, None)
}

impl Effect for AppOps {
    fn name(&self) -> &'static str {
        "app ops"
    }

    fn prepare_change(
        &mut self,
        entry: &mut Entry,
        before: Option<&Settings>,
        after: Option<&Settings>,
    ) -> anyhow::Result<Option<String>> {
        let held = before.map(targets_of).unwrap_or_default();
        let holding = after.map(targets_of).unwrap_or_default();
        if held == holding && (after.is_some() || entry.saved_permissions.is_empty()) {
            return Ok(None);
        }
        let next: BTreeMap<&str, &str> = pairs(&holding).collect();
        let mut names: BTreeMap<&str, &str> = pairs(&held).chain(pairs(&holding)).collect();
        if holding.is_empty() {
            // Older entries can name permissions outside the current targets.
            for permission in names_in(&entry.saved_permissions) {
                names.insert(permission, "revoked");
            }
        }
        if names.is_empty() {
            return Ok(None);
        }
        let capture = names
            .into_iter()
            .map(|(name, mode)| format!("{name}={mode}"))
            .collect::<Vec<_>>()
            .join(",");
        self.suspend(entry.uid);
        self.shared
            .bind(entry.uid, &entry.package, &entry.install_id);
        let Answer::Captured(_, Some(preimage)) = self
            .shared
            .ask(entry.uid, |request| {
                Command::Capture(entry.uid, request, &capture).to_string()
            })
            .map_err(super::auto::Transient)?
        else {
            anyhow::bail!("the app ops helper could not read the original values");
        };

        // The longest recovery command still has to fit in one pipe write.
        let recovery_overhead = format!(
            "bound {} {} request {} {}",
            entry.package,
            entry.install_id,
            u64::MAX,
            Command::CheckUndo(entry.uid, &preimage),
        );
        anyhow::ensure!(
            recovery_overhead.len() <= transport::MAX_LINE,
            "the Android preimage is too large"
        );

        let held: HashSet<_> = names_in(&held).into_iter().collect();
        let mut originals: BTreeMap<_, _> = pairs(&entry.saved_ops)
            .chain(pairs(&entry.saved_permissions))
            .filter(|(name, _)| held.contains(name) || is_permission(name))
            .collect();
        for (name, value) in pairs(&preimage) {
            if next.contains_key(name) && !held.contains(name) {
                originals.insert(name, value);
            }
        }
        let regranted: Vec<String> = pairs(&preimage)
            .filter(|(name, value)| {
                is_permission(name)
                    && held.contains(name)
                    && next.contains_key(name)
                    && value.starts_with("granted")
            })
            .map(|(name, value)| format!("{name}={value}"))
            .collect();
        let saved = originals
            .into_iter()
            .map(|(name, value)| format!("{name}={value}"))
            .collect::<Vec<_>>()
            .join(",");
        (entry.saved_ops, entry.saved_permissions) = split_saved(&saved);
        keep_regranted(entry, &regranted);
        Ok(Some(preimage))
    }

    fn suspend(&mut self, uid: u32) {
        let mut state = self.shared.state();
        state.blocked.insert(uid);
        state.invalidate(uid);
    }

    fn restores_from_preimage(&self) -> bool {
        true
    }

    fn restore_preimage(&mut self, undo: &AppOpsUndo, writer: &Writer) -> anyhow::Result<()> {
        const RECOVERY_ATTEMPTS: u32 = 3;
        self.shared.bind(undo.uid, &undo.package, &undo.install_id);
        let answer = if undo.attempts < RECOVERY_ATTEMPTS {
            self.shared.ask_with(
                undo.uid,
                |_| undo_line(undo),
                || writer.note_appops_recovery_attempt(undo.uid),
            )?
        } else {
            // Keep readback and the journal when Android is fighting the restore.
            self.shared.ask(undo.uid, |_| {
                Command::CheckUndo(undo.uid, &undo.preimage).to_string()
            })?
        };
        anyhow::ensure!(
            answer == Answer::Restored,
            "the Android preimage could not be restored"
        );
        Ok(())
    }

    fn finish_change(&mut self, entry: &Entry, items: Option<&Settings>) {
        let mut state = self.shared.state();
        let uid = entry.uid;
        if !state.blocked.remove(&uid) {
            return;
        }
        let targets = items.map(targets_of).unwrap_or_default();
        state.unheld.remove(&uid);
        if targets.is_empty() {
            state.guarded.remove(&uid);
            state.owners.remove(&uid);
            state.check_until.remove(&uid);
            state.unsure.remove(&uid);
            state.pending_verifications.remove(&uid);
        } else {
            state
                .owners
                .insert(uid, (entry.package.clone(), entry.install_id.clone()));
            state.guarded.insert(uid, targets);
            state.wanted = true;
            state.pending_verifications.insert(uid);
            state.send_pending_verifications();
            self.shared.changed.notify_all();
        }
        drop(state);
        self.shared.rest_if_unused();
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        let targets = targets_of(items);
        if targets.is_empty() {
            return Ok(());
        }
        let uid = entry.uid;
        self.shared.bind(uid, &entry.package, &entry.install_id);
        let Answer::Saved(saved) = self
            .shared
            .ask(uid, |_| Command::Set(uid, &targets).to_string())
            .map_err(super::auto::Transient)?
        else {
            anyhow::bail!("the system did not take the app ops");
        };
        // The journaled preimage is authoritative.
        let mut state = self.shared.state();
        if !state.blocked.contains(&uid) {
            let (ops, permissions) = split_saved(&saved);
            entry.saved_ops = ops;
            keep_found(entry, &permissions);
            state.guarded.insert(uid, targets);
        }
        Ok(())
    }

    fn disable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        let uid = entry.uid;
        self.shared.bind(uid, &entry.package, &entry.install_id);
        let held = self
            .shared
            .state()
            .guarded
            .get(&uid)
            .cloned()
            .unwrap_or_else(|| targets_of(items));
        let names = to_give_back(entry, &held);
        if names.is_empty() {
            return Ok(());
        }
        // Only the ops held go back: one the app was not held to is the
        // system's, and may have been changed since it was saved. Without
        // saved modes (the daemon stopped between setting them and writing
        // them down) their uid modes are cleared, and the system's defaults
        // apply; a permission without a saved state is left revoked, but
        // the app may ask for it again.
        let line = Command::Restore(uid, &saved_values_of(entry, &names)).to_string();
        let restored = self.shared.ask(uid, |_| line)?;
        anyhow::ensure!(
            restored == Answer::Restored,
            "the system did not take the app ops back"
        );
        entry.saved_permissions.clear();
        let mut state = self.shared.state();
        if !state.blocked.contains(&uid) {
            state.guarded.remove(&uid);
            state.pending_verifications.remove(&uid);
            state.unheld.remove(&uid);
            state.unsure.remove(&uid);
            drop(state);
            self.shared.rest_if_unused();
        }
        Ok(())
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        before: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        let (held, holding) = (targets_of(before), targets_of(after));
        if held == holding {
            return Ok(());
        }
        if held.is_empty() {
            return self.enable(entry, packages, after);
        }
        if holding.is_empty() {
            return self.disable(entry, packages, before);
        }
        // What is no longer held goes back to what it was before: an op to
        // its mode before the app was sandboxed, a permission to its state
        // before it was taken.
        let released = |from: &str, to: &str| -> Vec<String> {
            let kept: HashSet<&str> = names_in(to).into_iter().collect();
            names_in(from)
                .into_iter()
                .filter(|name| !kept.contains(name))
                .map(str::to_string)
                .collect()
        };
        let resets = |names: &[String]| {
            let names: Vec<&str> = names.iter().map(String::as_str).collect();
            saved_values_of(entry, &names)
        };
        let let_go = released(&held, &holding);
        let uid = entry.uid;
        self.shared.bind(uid, &entry.package, &entry.install_id);
        let line = Command::Apply(uid, &holding, &resets(&let_go)).to_string();
        let Answer::Applied(found) = self.shared.ask(uid, |_| line)? else {
            anyhow::bail!("the system did not take the app ops");
        };
        forget_given_back(entry, &let_go);
        let mut state = self.shared.state();
        if !state.blocked.contains(&uid) {
            keep_found(entry, &found);
            state.guarded.insert(uid, holding);
        }
        Ok(())
    }

    fn resume(&mut self, apps: &[Resumed]) {
        let mut state = self.shared.state();
        for app in apps {
            let uid = app.entry.uid;
            let owner = (&app.entry.package, &app.entry.install_id);
            if state
                .owners
                .get(&uid)
                .is_some_and(|(package, id)| (package, id) != owner)
            {
                state.invalidate(uid);
                state.unsure.remove(&uid);
            }
        }
        state.owners.extend(apps.iter().map(|app| {
            (
                app.entry.uid,
                (app.entry.package.clone(), app.entry.install_id.clone()),
            )
        }));
        // The helper is told of them as it starts.
        state.guarded.extend(apps.iter().filter_map(|app| {
            let targets = targets_of(&app.items);
            (!targets.is_empty()).then_some((app.entry.uid, targets))
        }));
        state.wanted = !state.guarded.is_empty() || !state.blocked.is_empty();
        if state.input.is_some() {
            state.tell_guarded();
        }
        self.shared.changed.notify_all();
    }

    fn forget(&mut self, uid: u32) {
        let mut state = self.shared.state();
        state.owners.remove(&uid);
        state.check_until.remove(&uid);
        state.guarded.remove(&uid);
        state.pending_verifications.remove(&uid);
        state.unheld.remove(&uid);
        state.unknown.remove(&uid);
        state.awaiting_write.remove(&uid);
        state.receipts.remove(&uid);
        state.unsure.remove(&uid);
        state.found.retain(|(id, _)| *id != uid);
        drop(state);
        self.shared.rest_if_unused();
    }

    fn report(&self, status: &mut Status) {
        let state = self.shared.state();
        status.appops = state.input.is_some() && !state.restart;
        let now = Instant::now();
        let checking = state.checking_until.is_some_and(|deadline| now < deadline);
        for (uid, targets) in &state.guarded {
            if state.blocked.contains(uid) {
                status.unknown_appops.insert(*uid);
            } else if let Some((_, unheld)) =
                state.unheld.get(uid).filter(|(until, _)| *until > now)
            {
                let held = names_in(targets)
                    .into_iter()
                    .filter(|name| !unheld.contains(*name))
                    .map(str::to_string)
                    .collect();
                status.held_ops.insert(*uid, held);
            } else if status.appops && checking && !state.unknown.contains(uid) {
                status.checking_appops.insert(*uid);
            } else {
                status.unknown_appops.insert(*uid);
            }
        }
    }

    fn found(&mut self, entries: &mut HashMap<u32, Entry>) -> Vec<u32> {
        let mut state = self.shared.state();
        let found = std::mem::take(&mut state.found);
        let mut changed = BTreeSet::new();
        for (uid, pair) in found {
            let Some((name, value)) = pair.split_once('=') else {
                continue;
            };
            if let Some(entry) = entries.get_mut(&uid)
                && state
                    .guarded
                    .get(&uid)
                    .is_some_and(|targets| names_in(targets).contains(&name))
            {
                if !names_in(&entry.saved_permissions).contains(&name) {
                    keep_found(entry, &pair);
                } else {
                    keep_regranted(entry, std::slice::from_ref(&pair));
                }
                state
                    .awaiting_write
                    .entry(uid)
                    .or_default()
                    .insert(name.to_owned(), value.to_owned());
                changed.insert(uid);
            }
        }
        changed.into_iter().collect()
    }

    fn found_written(&mut self, uid: u32) {
        let mut state = self.shared.state();
        if let Some(values) = state.awaiting_write.remove(&uid) {
            state.receipts.entry(uid).or_default().extend(values);
            state.pending_verifications.insert(uid);
            state.send_pending_verifications();
        }
    }
}

#[cfg(test)]
pub(super) mod tests;
