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
//! hands back what the helper saved as it is; a mode written by earlier
//! builds, without a grant, is put back as saved.
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
//! coarse. If the user grants it again in the meantime, the helper takes it
//! back and reports the state it found (`reverted <uid>
//! <permission>=<state>`): the user's latest choice, kept in the entry in
//! place of the one before (`Effect::found`) and given back instead. No mode of the items covers both the ops of one mode and the
//! permission of another (`items::Settings::covering`), so a permission is
//! named in the entry before it is taken, its state not known yet
//! ([`naming_taken`]), and whatever the entry names is put back when the app
//! leaves the sandbox, whatever its items say.

use std::{
    collections::{BTreeMap, BTreeSet, HashMap, HashSet},
    io::{BufRead as _, BufReader, Write as _},
    os::fd::AsRawFd as _,
    process::ChildStdin,
    sync::{Arc, Condvar, Mutex, MutexGuard},
    time::{Duration, Instant},
};

use anyhow::Context as _;

use super::{
    Effect, Entry, Resumed, Status,
    items::{self, OpMode, Settings},
};
use crate::helper;

const HELPER_CLASS: &str = "AppOpsGuard";

/// How long the helper is given to answer. Setting thirty ops takes it a
/// few milliseconds; while the system is still starting it does not answer
/// at all.
pub const ANSWER_WAIT: Duration = Duration::from_secs(5);

/// How soon the helper is started again after it ends: twice as long after
/// each time, each start being a Java runtime's. A helper that ran for
/// `HEALTHY` starts the count again.
const RETRY: Duration = Duration::from_secs(10);
const RETRY_MAX: Duration = Duration::from_secs(1800);
const HEALTHY: Duration = Duration::from_secs(600);

fn retry_delay(failures: u32) -> Duration {
    RETRY
        .saturating_mul(1 << failures.saturating_sub(1).min(16))
        .min(RETRY_MAX)
}

/// The helper's answer to a command about one uid.
#[derive(Debug, PartialEq, Eq)]
enum Answer {
    /// To `set`: the modes the ops had and the states of the permissions
    /// taken, as `restore` takes them.
    Saved(String),
    Restored,
    /// To `apply`: the states of the permissions it took anew.
    Applied(String),
    Failed,
}

/// A line the helper printed: the uid it is about, and the answer if it is
/// one. `reverted` and `verified` lines report what the helper did by
/// itself and answer nothing.
fn parse_line(line: &str) -> Option<(u32, Option<Answer>)> {
    let mut words = line.split_ascii_whitespace();
    let kind = words.next()?;
    let uid = words.next()?.parse().ok()?;
    let answer = match kind {
        "saved" => Some(Answer::Saved(words.next().unwrap_or("").to_string())),
        "restored" => Some(Answer::Restored),
        "applied" => Some(Answer::Applied(words.next().unwrap_or("").to_string())),
        "failed" => Some(Answer::Failed),
        "reverted" | "verified" | "unheld" => None,
        _ => return None,
    };
    Some((uid, answer))
}

/// `unheld <uid> <op>,...` (or `-` for none): the ops of a guarded uid that
/// the system did not keep at the mode the uid is held to, after the
/// helper set them. Ops this Android version does not have are not named.
fn parse_unheld(line: &str) -> Option<(u32, BTreeSet<String>)> {
    let mut words = line.split_ascii_whitespace();
    if words.next()? != "unheld" {
        return None;
    }
    let uid = words.next()?.parse().ok()?;
    let ops = match words.next() {
        None | Some("-") => BTreeSet::new(),
        Some(ops) => ops.split(',').map(str::to_string).collect(),
    };
    Some((uid, ops))
}

/// What a `reverted` line reports of a permission the helper found granted
/// again while it held it revoked, and took back: the uid, and
/// `<permission>=<state>`, the state it was found in.
fn found_in(line: &str) -> Option<(u32, String)> {
    let mut words = line.split_ascii_whitespace();
    if words.next()? != "reverted" {
        return None;
    }
    let uid = words.next()?.parse().ok()?;
    let found = words.next()?;
    let (name, state) = found.split_once('=')?;
    (is_permission(name) && !state.is_empty() && words.next().is_none())
        .then(|| (uid, found.to_string()))
}

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
    /// A helper started while that command was waiting, and was told of
    /// every uid but its own: it is told of that one with the answer.
    untold: bool,
    /// Answers not yet taken by who asked.
    answers: HashMap<u32, Answer>,
    /// The ops of guarded uids that the running helper last found it could
    /// not hold. A uid it has not reported on yet is not known to be held.
    unheld: BTreeMap<u32, BTreeSet<String>>,
    /// The permissions the helper found granted again, by uid, as
    /// [`found_in`] reads them, not yet kept in the entries.
    found: Vec<(u32, String)>,
}

impl State {
    /// Send a line to the helper, without ever waiting for it: its input is
    /// a pipe that does not block, and a line is short enough to be written
    /// whole or not at all.
    fn send(&mut self, line: &str) -> anyhow::Result<()> {
        let uid = line
            .split_ascii_whitespace()
            .nth(1)
            .and_then(|word| word.parse::<u32>().ok())
            .context("invalid helper command")?;
        let line = match self.owners.get(&uid) {
            Some((package, id)) if !id.is_empty() => format!("bound {package} {id} {line}"),
            #[cfg(test)]
            _ => line.to_owned(),
            #[cfg(not(test))]
            _ => anyhow::bail!("missing installation identity"),
        };
        let input = self
            .input
            .as_mut()
            .context("the app ops helper is not running")?;
        input
            .write_all(format!("{line}\n").as_bytes())
            .context("the app ops helper takes no commands")
    }

    /// Have the helper look at the ops of `uid` and hold it to what it is
    /// held to here. Nothing for a uid not held, or one a command is
    /// changing.
    fn verify(&mut self, uid: u32) {
        if self.asking == Some(uid) {
            return;
        }
        if let Some(targets) = self.guarded.get(&uid) {
            let line = Command::Verify(uid, targets).to_string();
            let _ = self.send(&line);
        }
    }

    /// Tell a helper that just started of every uid held, so that it
    /// carries on where the last one ended.
    fn tell_guarded(&mut self) {
        let uids: Vec<u32> = self.guarded.keys().copied().collect();
        for uid in uids {
            self.verify(uid);
        }
        // The command waiting went to the helper before this one; what its
        // uid is held to is known with the answer.
        self.untold = self.asking.is_some();
    }
}

#[derive(Default)]
struct Shared {
    state: Mutex<State>,
    /// An answer arrived, the helper started or ended, or it is wanted.
    changed: Condvar,
}

impl Shared {
    fn state(&self) -> MutexGuard<'_, State> {
        // Whoever panicked holding it left nothing half done that matters:
        // an answer is inserted or not.
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Send a command about `uid`, wait for the helper's answer, and hand
    /// it to `settle`, which changes what the uid is held to. That is done
    /// before the state is let go of, so that no `verify` sent in between
    /// names what the uid was held to before the command.
    fn ask<T>(
        &self,
        uid: u32,
        line: &str,
        settle: impl FnOnce(&mut State, Answer) -> T,
    ) -> anyhow::Result<T> {
        let deadline = Instant::now() + ANSWER_WAIT;
        let mut state = self.state();
        let wait = |state, left| {
            self.changed
                .wait_timeout(state, left)
                .unwrap_or_else(|e| e.into_inner())
                .0
        };
        let late = || anyhow::anyhow!("the app ops helper did not answer in time");
        // Started for this command if it was not running, which takes a
        // second or two.
        state.wanted = true;
        state.hurry = state.input.is_none();
        self.changed.notify_all();
        while state.input.is_none() {
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return Err(late());
            }
            state = wait(state, left);
        }
        state.answers.remove(&uid);
        state.send(line)?;
        state.asking = Some(uid);
        let settled = loop {
            if let Some(answer) = state.answers.remove(&uid) {
                break Ok(settle(&mut state, answer));
            }
            if state.input.is_none() {
                break Err(anyhow::anyhow!("the app ops helper ended"));
            }
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                break Err(late());
            }
            state = wait(state, left);
        };
        state.asking = None;
        if std::mem::take(&mut state.untold) {
            state.verify(uid);
        }
        settled
    }

    /// End the helper if no app is sandboxed any more. Closing its input
    /// is what tells it to leave.
    fn rest_if_unused(&self) {
        let mut state = self.state();
        if state.guarded.is_empty() {
            state.wanted = false;
            // Kept if it was told already and has not been seen to leave.
            state.dismissed |= state.input.take().is_some();
        }
    }
}

pub struct AppOps {
    shared: Arc<Shared>,
}

impl AppOps {
    /// Run the helper on a thread of its own, whenever an app is sandboxed.
    pub fn start() -> Self {
        let shared = Arc::new(Shared::default());
        let supervised = shared.clone();
        std::thread::spawn(move || supervise(&supervised));
        Self { shared }
    }

    /// Called with the uid of an app that just started, to have its ops
    /// looked at then: the system may set them back as an app starts. Never
    /// waits, and does nothing if the helper is busy or away, which looks
    /// again on its own every minute.
    pub fn verifier(&self) -> impl Fn(u32) + Send + 'static {
        let shared = self.shared.clone();
        move |uid| {
            // An app held at the gate for its mounts alone has no ops to
            // look at.
            if let Ok(mut state) = shared.state.try_lock() {
                state.verify(uid);
            }
        }
    }
}

/// The framework's number for an op's mode, as the helper takes it.
fn mode_number(mode: OpMode) -> u8 {
    match mode {
        // `AppOpsManager.MODE_IGNORED`: the app is given an empty answer,
        // with its permission still shown as granted.
        OpMode::Ignored => 1,
        // `AppOpsManager.MODE_FOREGROUND`.
        OpMode::Foreground => 4,
    }
}

/// What the helper holds a permission to: revoked, and not to be asked for
/// again.
const REVOKED: &str = "revoked";

/// What `restore` and `apply` are given for an op or a permission whose
/// state before is not known: an op loses its uid mode, a permission the
/// mark that keeps the app from asking for it.
const NOT_KNOWN: &str = "default";

/// What to hold an app to, as the helper takes it: `<op>=<mode>` for each
/// op, with the mode's number, then `<permission>=revoked` for each
/// permission taken, joined by commas. Empty if nothing.
fn targets_of(items: &Settings) -> String {
    targets_text(&items.op_targets(), &items.taken_permissions())
}

/// `ops` at their modes and `permissions` revoked, as [`targets_of`]
/// writes them.
fn targets_text(ops: &[(&str, OpMode)], permissions: &[&str]) -> String {
    let ops = ops
        .iter()
        .map(|(op, mode)| format!("{op}={}", mode_number(*mode)));
    let permissions = permissions
        .iter()
        .map(|permission| format!("{permission}={REVOKED}"));
    ops.chain(permissions).collect::<Vec<_>>().join(",")
}

/// A line the daemon tells the helper (`parseCommand` in
/// `AppOpsGuardProtocol.kt`): targets as [`targets_of`] writes them,
/// values as [`saved_values_of`] does.
enum Command<'a> {
    Set(u32, &'a str),
    Verify(u32, &'a str),
    Release(u32),
    Restore(u32, &'a str),
    /// The targets, then what to set back; nothing to set back is `-`.
    Apply(u32, &'a str, &'a str),
}

impl std::fmt::Display for Command<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match *self {
            Self::Set(uid, targets) => write!(f, "set {uid} {targets}"),
            Self::Verify(uid, targets) => write!(f, "verify {uid} {targets}"),
            Self::Release(uid) => write!(f, "release {uid}"),
            Self::Restore(uid, values) => write!(f, "restore {uid} {values}"),
            Self::Apply(uid, targets, resets) => {
                let resets = if resets.is_empty() { "-" } else { resets };
                write!(f, "apply {uid} {targets} {resets}")
            }
        }
    }
}

/// Whether `name`, in what the helper is told, is a permission's rather
/// than an op's (`android:camera`).
fn is_permission(name: &str) -> bool {
    name.starts_with("android.permission.")
}

/// The pairs of a list of `<name>=<value>,...`.
fn pairs(list: &str) -> impl Iterator<Item = (&str, &str)> {
    list.split(',').filter_map(|pair| pair.split_once('='))
}

/// The names in a list of `<name>=<value>,...`, such as [`targets_of`]
/// writes.
fn names_in(list: &str) -> Vec<&str> {
    pairs(list).map(|(name, _)| name).collect()
}

/// What `entry` saved for each of `names`, as `restore` and `apply` take
/// it: an op's mode from `saved_ops`, a permission's state from
/// `saved_permissions`, `default` for what it saved nothing for.
fn saved_values_of(entry: &Entry, names: &[&str]) -> String {
    let saved: HashMap<&str, &str> = pairs(&entry.saved_ops)
        .chain(pairs(&entry.saved_permissions))
        .collect();
    names
        .iter()
        .map(|name| format!("{name}={}", saved.get(name).copied().unwrap_or(NOT_KNOWN)))
        .collect::<Vec<_>>()
        .join(",")
}

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
/// (`<permission>=<state>`) in place of the ones saved: each was granted
/// again while the app was held to it revoked, the user's choice since it
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

/// The ops and the permissions of the helper's `saved` answer: what goes in
/// an entry's `saved_ops`, and the states it found of the permissions.
fn split_saved(saved: &str) -> (String, String) {
    let (permissions, ops): (Vec<&str>, Vec<&str>) = saved
        .split(',')
        .filter(|pair| !pair.is_empty())
        .partition(|pair| is_permission(pair.split('=').next().unwrap_or("")));
    (ops.join(","), permissions.join(","))
}

/// `entry` as written down before `items` are carried out: naming each
/// permission they take that it does not name yet, its state not known.
/// The helper finds the state as it takes the permission; a daemon that
/// stops before it writes that down still gives the permission back, as
/// it does an op whose mode it did not write down.
pub fn naming_taken(entry: &Entry, items: &Settings) -> Entry {
    let mut named = entry.clone();
    let unknown: Vec<String> = items
        .taken_permissions()
        .into_iter()
        .map(|permission| format!("{permission}={NOT_KNOWN}"))
        .collect();
    keep_found(&mut named, &unknown.join(","));
    named
}

/// The helper's line that gives `entry` back the ops `items` hold it to,
/// at the modes saved for them, and the permissions it may have been
/// taken, as disabling it would; `None` if there is nothing to give back.
/// For when the daemon is not there to disable it (`uninstall.sh`).
pub fn restore_line(entry: &Entry, items: &Settings) -> Option<String> {
    let targets = targets_of(items);
    let names = to_give_back(entry, &targets);
    (!names.is_empty())
        .then(|| Command::Restore(entry.uid, &saved_values_of(entry, &names)).to_string())
}

impl Effect for AppOps {
    fn name(&self) -> &'static str {
        "app ops"
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        self.shared
            .state()
            .owners
            .insert(entry.uid, (entry.package.clone(), entry.install_id.clone()));
        let targets = targets_of(items);
        if targets.is_empty() {
            return Ok(());
        }
        let uid = entry.uid;
        let line = Command::Set(uid, &targets).to_string();
        let saved = self.shared.ask(uid, &line, |state, answer| match answer {
            Answer::Saved(saved) => {
                state.guarded.insert(uid, targets);
                Some(saved)
            }
            _ => None,
        });
        match saved {
            Ok(Some(saved)) => {
                let (ops, permissions) = split_saved(&saved);
                entry.saved_ops = ops;
                keep_found(entry, &permissions);
                Ok(())
            }
            failed => {
                // It may have set them all the same, or be about to: a
                // helper that is there puts back what it saved itself.
                let _ = self.shared.state().send(&Command::Release(uid).to_string());
                self.shared.rest_if_unused();
                failed?;
                anyhow::bail!("the system did not take the app ops")
            }
        }
    }

    fn disable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        let uid = entry.uid;
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
        let restored = self.shared.ask(uid, &line, |state, answer| {
            let restored = answer == Answer::Restored;
            if restored {
                state.guarded.remove(&uid);
                state.unheld.remove(&uid);
            }
            restored
        })?;
        anyhow::ensure!(restored, "the system did not take the app ops back");
        entry.saved_permissions.clear();
        self.shared.rest_if_unused();
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
        let (let_go, taken_back) = (released(&held, &holding), released(&holding, &held));
        let uid = entry.uid;
        let line = Command::Apply(uid, &holding, &resets(&let_go)).to_string();
        let back = Command::Apply(uid, &held, &resets(&taken_back)).to_string();
        let applied = self.shared.ask(uid, &line, |state, answer| match answer {
            Answer::Applied(found) => {
                state.guarded.insert(uid, holding.clone());
                Some(found)
            }
            _ => None,
        });
        match applied {
            Ok(Some(found)) => {
                forget_given_back(entry, &let_go);
                keep_found(entry, &found);
                Ok(())
            }
            failed => {
                // It may have applied them all the same, or be about to:
                // what was held is held again.
                let _ = self.shared.state().send(&back);
                failed?;
                anyhow::bail!("the system did not take the app ops")
            }
        }
    }

    fn resume(&mut self, apps: &[Resumed]) {
        let mut state = self.shared.state();
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
        state.wanted = !state.guarded.is_empty();
        if state.input.is_some() {
            state.tell_guarded();
        }
        self.shared.changed.notify_all();
    }

    fn forget(&mut self, uid: u32) {
        let mut state = self.shared.state();
        state.owners.remove(&uid);
        state.guarded.remove(&uid);
        state.unheld.remove(&uid);
        state.found.retain(|(id, _)| *id != uid);
        drop(state);
        self.shared.rest_if_unused();
    }

    fn report(&self, status: &mut Status) {
        let state = self.shared.state();
        status.appops = state.input.is_some();
        // Without a helper nothing holds the ops: the system may already
        // have set them back. A uid it has not reported on since it
        // started is not known to be held either.
        if status.appops {
            status.held_ops = state
                .guarded
                .iter()
                .filter_map(|(uid, targets)| {
                    let unheld = state.unheld.get(uid)?;
                    let held = names_in(targets)
                        .into_iter()
                        .filter(|name| !unheld.contains(*name))
                        .map(str::to_string)
                        .collect();
                    Some((*uid, held))
                })
                .collect();
        }
    }

    fn found(&mut self, entries: &mut HashMap<u32, Entry>) -> Vec<u32> {
        let found = std::mem::take(&mut self.shared.state().found);
        let mut changed = Vec::new();
        for (uid, state) in found {
            if let Some(entry) = entries.get_mut(&uid)
                && keep_regranted(entry, &[state])
                && !changed.contains(&uid)
            {
                changed.push(uid);
            }
        }
        changed
    }
}

/// Run the helper while it is wanted, starting it again when it ends by
/// itself.
fn supervise(shared: &Shared) {
    let mut failures = 0;
    let mut last_said = String::new();
    loop {
        {
            let mut state = shared.state();
            while !state.wanted {
                state = shared
                    .changed
                    .wait(state)
                    .unwrap_or_else(|e| e.into_inner());
            }
        }
        let started = Instant::now();
        let ended = run_helper(shared);
        // Ended on purpose: started at once when wanted again.
        if std::mem::take(&mut shared.state().dismissed) {
            failures = 0;
            continue;
        }
        if started.elapsed() >= HEALTHY {
            failures = 0;
        }
        failures += 1;
        // The same reason again says nothing new.
        let said = format!("{ended:#}");
        if said != last_said {
            eprintln!("bbd: sandbox: app ops helper: {said}");
            last_said = said;
        }
        // Until the delay is over, or a command wants the helper now.
        let until = Instant::now() + retry_delay(failures);
        let mut state = shared.state();
        while !state.hurry {
            let left = until.saturating_duration_since(Instant::now());
            if left.is_zero() {
                break;
            }
            state = shared
                .changed
                .wait_timeout(state, left)
                .unwrap_or_else(|e| e.into_inner())
                .0;
        }
    }
}

/// Run the helper until it ends, passing its answers on. Returns why it
/// ended, which it is not meant to.
fn run_helper(shared: &Shared) -> anyhow::Error {
    let mut helper = match helper::spawn(HELPER_CLASS, std::iter::empty()) {
        Ok(helper) => helper,
        Err(e) => return e,
    };
    let output = helper.stdout.take().expect("spawned with a piped stdout");
    let input = helper.stdin.take().expect("spawned with a piped stdin");
    // Read as it comes, so that the helper never waits for room in the
    // pipe; the first line is the reason if it fails.
    let errors = helper.stderr.take().map(|stderr| {
        std::thread::spawn(move || {
            let mut first = None;
            for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                if first.is_none() && !line.trim().is_empty() {
                    first = Some(line.trim().chars().take(200).collect::<String>());
                }
            }
            first
        })
    });
    // See `State::send`.
    unsafe { libc::fcntl(input.as_raw_fd(), libc::F_SETFL, libc::O_NONBLOCK) };
    {
        let mut state = shared.state();
        state.input = Some(input);
        // Whoever was in a hurry has it now.
        state.hurry = false;
        state.tell_guarded();
        shared.changed.notify_all();
    }
    for line in BufReader::new(output).lines().map_while(Result::ok) {
        if let Some((uid, ops)) = parse_unheld(&line) {
            shared.state().unheld.insert(uid, ops);
        } else if let Some((uid, Some(answer))) = parse_line(&line) {
            shared.state().answers.insert(uid, answer);
            shared.changed.notify_all();
        } else if let Some(found) = found_in(&line) {
            shared.state().found.push(found);
        }
    }
    // Closes its input, if it is still there to notice. The next helper
    // reports again on every uid it is told of.
    {
        let mut state = shared.state();
        state.input = None;
        state.unheld.clear();
    }
    shared.changed.notify_all();
    let status = match helper.wait() {
        Ok(status) => status.to_string(),
        Err(e) => e.to_string(),
    };
    let said = errors
        .and_then(|reader| reader.join().ok())
        .flatten()
        .map_or_else(String::new, |line| format!(": {line}"));
    anyhow::anyhow!("ended ({status}){said}")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The `<name>=<value>` of a value word of a case (`op=` or
    /// `permission=`), as the helper writes it and the daemon keeps it and
    /// hands it back unread: an op's mode, `default` for none, then `/`
    /// and its grant's mode; a permission's state with its flags joined by
    /// `+`, `default` where it is not known.
    fn value_of(word: &str) -> (&str, String) {
        let mut parts = word.split('/');
        let name = parts.next().unwrap();
        let first = match parts.next().unwrap() {
            "none" | "unknown" => NOT_KNOWN,
            value => value,
        };
        let rest: Vec<&str> = std::iter::once(first).chain(parts).collect();
        let value = rest.join(if is_permission(name) { "+" } else { "/" });
        (name, value)
    }

    /// The values a case names, in order.
    fn values_of(case: &crate::protocol_cases::Case) -> Vec<(&str, String)> {
        case.meaning
            .iter()
            .filter(|(key, _)| key == "op" || key == "permission")
            .map(|(_, word)| value_of(word))
            .collect()
    }

    fn joined(values: &[(&str, String)], permissions: bool) -> String {
        values
            .iter()
            .filter(|(name, _)| is_permission(name) == permissions)
            .map(|(name, value)| format!("{name}={value}"))
            .collect::<Vec<_>>()
            .join(",")
    }

    #[test]
    fn every_command_is_written_as_the_helper_reads_it() {
        let cases = crate::protocol_cases::appops();
        for case in crate::protocol_cases::of(&cases, &["command"]) {
            let uid: u32 = case.get("uid").unwrap().parse().unwrap();
            let ops: Vec<(&str, OpMode)> = case
                .all("hold")
                .map(|word| {
                    let (op, mode) = word.split_once('/').unwrap();
                    let mode = match mode {
                        "1" => OpMode::Ignored,
                        "4" => OpMode::Foreground,
                        other => panic!("no op is held at {other}"),
                    };
                    (op, mode)
                })
                .collect();
            let permissions: Vec<&str> = case.all("revoke").collect();
            let targets = targets_text(&ops, &permissions);
            // What the entry saved; a value not known is not in it, and
            // is sent as not known all the same.
            let values = values_of(case);
            let known: Vec<(&str, String)> = values
                .iter()
                .filter(|(_, value)| value != NOT_KNOWN)
                .cloned()
                .collect();
            let entry = Entry {
                saved_ops: joined(&known, false),
                saved_permissions: joined(&known, true),
                ..sandboxed(uid)
            };
            let names: Vec<&str> = values.iter().map(|(name, _)| *name).collect();
            let saved = saved_values_of(&entry, &names);
            let line = match case.first() {
                "set" => Command::Set(uid, &targets),
                "verify" => Command::Verify(uid, &targets),
                "release" => Command::Release(uid),
                "restore" => Command::Restore(uid, &saved),
                "apply" => Command::Apply(uid, &targets, &saved),
                other => panic!("no command {other}"),
            }
            .to_string();
            assert_eq!(line, case.line);
        }
    }

    #[test]
    fn every_answer_and_report_is_read_as_the_helper_writes_it() {
        let cases = crate::protocol_cases::appops();
        for case in crate::protocol_cases::of(&cases, &["answer"]) {
            let line = case.line.as_str();
            let uid: u32 = case.get("uid").unwrap().parse().unwrap();
            let values = values_of(case);
            let text = values
                .iter()
                .map(|(name, value)| format!("{name}={value}"))
                .collect::<Vec<_>>()
                .join(",");
            let answer = match case.first() {
                "saved" => Some(Answer::Saved(text.clone())),
                "restored" => Some(Answer::Restored),
                "applied" => Some(Answer::Applied(text.clone())),
                "failed" => Some(Answer::Failed),
                "verified" | "reverted" | "unheld" => None,
                other => panic!("no answer {other}"),
            };
            assert_eq!(parse_line(line), Some((uid, answer)), "{line}");
            if case.first() == "saved" {
                assert_eq!(
                    split_saved(&text),
                    (joined(&values, false), joined(&values, true)),
                    "{line}"
                );
            }
            let unheld = (case.first() == "unheld")
                .then(|| (uid, case.all("not_held").map(str::to_string).collect()));
            assert_eq!(parse_unheld(line), unheld, "{line}");
            let found = (case.first() == "reverted" && !values.is_empty()).then_some((uid, text));
            assert_eq!(found_in(line), found, "{line}");
        }
        for case in crate::protocol_cases::of(&cases, &["answer-bad"]) {
            let line = case.line.as_str();
            assert_eq!(parse_line(line), None, "{line:?}");
            assert_eq!(parse_unheld(line), None, "{line:?}");
            assert_eq!(found_in(line), None, "{line:?}");
        }
    }

    fn sandboxed(uid: u32) -> Entry {
        Entry {
            uid,
            package: "com.app".into(),
            enabled: true,
            android_id: String::new(),
            saved_ops: String::new(),
            install_id: String::new(),
            saved_permissions: String::new(),
            created: 0,
            settings: String::new(),
        }
    }

    const FINE: &str = super::super::items::PRECISE_LOCATION;

    /// Every item off, but those `on` names, at their modes.
    fn only(on: &[(super::super::items::Item, super::super::items::Mode)]) -> Settings {
        use super::super::items::{Item, Mode};
        let mut own = Settings::default();
        for item in Item::ALL {
            own.set(item, Some(Mode::Off)).unwrap();
        }
        for (item, mode) in on {
            own.set(*item, Some(*mode)).unwrap();
        }
        own.over(&Settings::default())
    }

    #[test]
    fn the_helper_is_wanted_only_while_an_app_is_sandboxed() {
        let mut appops = AppOps {
            shared: Arc::new(Shared::default()),
        };
        let app = |uid, items| Resumed {
            entry: sandboxed(uid),
            packages: vec![],
            items,
        };
        appops.resume(&[]);
        assert!(!appops.shared.state().wanted);
        // An app with no item that holds an op does not need it.
        let mut off = Settings::default();
        for item in super::super::items::Item::ALL {
            off.set(item, Some(super::super::items::Mode::Off)).unwrap();
        }
        appops.resume(&[app(10101, off.over(&Settings::default()))]);
        assert!(!appops.shared.state().wanted);
        appops.resume(&[app(10100, Settings::all_on())]);
        assert!(appops.shared.state().wanted);
        let mut status = Status::default();
        appops.report(&mut status);
        assert!(!status.appops);

        appops.shared.rest_if_unused();
        assert!(appops.shared.state().wanted);
        appops.shared.state().guarded.clear();
        appops.shared.rest_if_unused();
        assert!(!appops.shared.state().wanted);
    }

    #[test]
    fn the_helper_is_told_the_ops_of_the_items_on() {
        use super::super::items::{Item, Mode};
        let items = only(&[(Item::Camera, Mode::On), (Item::Contacts, Mode::On)]);
        assert_eq!(
            targets_of(&items),
            "android:read_contacts=1,android:write_contacts=1,android:camera=1"
        );
        let entry = Entry {
            saved_ops: "android:camera=4,android:read_contacts=default".into(),
            saved_permissions: format!("{FINE}=denied+user_set"),
            ..sandboxed(10100)
        };
        assert_eq!(
            saved_values_of(
                &entry,
                &[
                    "android:camera",
                    "android:read_contacts",
                    "android:record_audio",
                    FINE,
                    "android.permission.CAMERA",
                ]
            ),
            "android:camera=4,android:read_contacts=default,android:record_audio=default,\
             android.permission.ACCESS_FINE_LOCATION=denied+user_set,\
             android.permission.CAMERA=default"
        );
        assert_eq!(
            names_in(&targets_of(&items)),
            [
                "android:read_contacts",
                "android:write_contacts",
                "android:camera"
            ]
        );
        assert!(names_in("").is_empty());
    }

    #[test]
    fn location_made_coarse_takes_the_precise_permission_and_no_location_op() {
        use super::super::items::{Item, Mode};
        assert_eq!(
            targets_of(&only(&[
                (Item::Location, Mode::Coarse),
                (Item::Camera, Mode::On)
            ])),
            "android:camera=1,android.permission.ACCESS_FINE_LOCATION=revoked"
        );
        assert_eq!(
            targets_of(&only(&[(Item::Location, Mode::Coarse)])),
            "android.permission.ACCESS_FINE_LOCATION=revoked"
        );
        // What the helper saved goes where it belongs.
        assert_eq!(
            split_saved(&format!(
                "android:camera=4,{FINE}=granted,android:read_sms=default"
            )),
            (
                "android:camera=4,android:read_sms=default".to_string(),
                format!("{FINE}=granted")
            )
        );
        assert_eq!(split_saved(""), (String::new(), String::new()));
    }

    #[test]
    fn an_item_left_to_the_foreground_holds_its_ops_at_foreground() {
        use super::super::items::{Item, Mode};
        let mut appops = AppOps {
            shared: Arc::new(Shared::default()),
        };
        let (input, sent) = stand_in(&appops.shared, |_, _| {});
        appops.shared.state().input = Some(input);
        let on = only(&[(Item::Location, Mode::On), (Item::Camera, Mode::On)]);
        let foreground = only(&[
            (Item::Location, Mode::Foreground),
            (Item::Camera, Mode::Foreground),
        ]);
        let coarse = only(&[
            (Item::Location, Mode::Coarse),
            (Item::Camera, Mode::Foreground),
        ]);
        let mut entry = Entry {
            saved_ops: "android:wifi_scan=4,android:camera=default".into(),
            ..sandboxed(10100)
        };
        appops.resume(&[Resumed {
            entry: entry.clone(),
            packages: vec![],
            items: on.clone(),
        }]);

        appops.update(&mut entry, &[], &on, &foreground).unwrap();
        appops
            .update(&mut entry, &[], &foreground, &coarse)
            .unwrap();
        appops.disable(&mut entry, &[], &coarse).unwrap();
        assert_eq!(
            sent.join().unwrap(),
            [
                "verify 10100 android:coarse_location=1,android:fine_location=1,android:wifi_scan=1,android:camera=1".to_owned(),
                // The Wi-Fi scan goes back to what it was, and then with
                // the coarse location, its switch op, to the foreground.
                "apply 10100 android:coarse_location=4,android:fine_location=4,\
                 android:camera=4 android:wifi_scan=4"
                    .to_string(),
                format!(
                    "apply 10100 android:camera=4,{FINE}=revoked \
                     android:coarse_location=default,android:fine_location=default"
                ),
                format!("restore 10100 android:camera=default,{FINE}=granted"),
            ]
        );
    }

    #[test]
    fn a_permission_is_named_before_it_is_taken_and_its_first_state_kept() {
        use super::super::items::{Item, Mode};
        let coarse = only(&[(Item::Location, Mode::Coarse)]);
        let named = naming_taken(&sandboxed(10100), &coarse);
        assert_eq!(named.saved_permissions, format!("{FINE}=default"));
        // Nothing to name for items that take no permission, and nothing
        // again for one named already.
        assert_eq!(
            naming_taken(&sandboxed(10100), &only(&[(Item::Location, Mode::On)])),
            sandboxed(10100)
        );
        let saved = Entry {
            saved_permissions: format!("{FINE}=granted+user_set"),
            ..sandboxed(10100)
        };
        assert_eq!(naming_taken(&saved, &coarse), saved);

        // What the helper finds is kept only where nothing was named: for
        // a permission named already, it is the helper's own doing.
        let mut entry = sandboxed(10100);
        keep_found(&mut entry, &format!("{FINE}=granted"));
        assert_eq!(entry.saved_permissions, format!("{FINE}=granted"));
        keep_found(&mut entry, &format!("{FINE}=denied+user_fixed"));
        assert_eq!(entry.saved_permissions, format!("{FINE}=granted"));
        let mut leftover = named.clone();
        keep_found(&mut leftover, &format!("{FINE}=denied+user_fixed"));
        assert_eq!(leftover.saved_permissions, format!("{FINE}=default"));
        // An op is never taken for a permission.
        keep_found(&mut entry, "android:camera=1");
        assert_eq!(entry.saved_permissions, format!("{FINE}=granted"));
        forget_given_back(&mut entry, &[FINE.to_string()]);
        assert_eq!(entry.saved_permissions, "");
    }

    /// Stands in for the helper: `cat`, which gives back every line it was
    /// sent, in the order the helper would take them. Each command is
    /// answered as the helper would answer it, after `meanwhile` was given
    /// the state, as it is while the command waits for its answer.
    fn stand_in(
        shared: &Arc<Shared>,
        mut meanwhile: impl FnMut(&mut State, u32) + Send + 'static,
    ) -> (ChildStdin, std::thread::JoinHandle<Vec<String>>) {
        let mut cat = std::process::Command::new("cat")
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .spawn()
            .unwrap();
        let input = cat.stdin.take().unwrap();
        let output = cat.stdout.take().unwrap();
        let shared = shared.clone();
        let sent = std::thread::spawn(move || {
            let mut sent = Vec::new();
            for line in BufReader::new(output).lines().map_while(Result::ok) {
                let mut words = line.split(' ');
                let kind = words.next().unwrap().to_string();
                let uid = words.next().unwrap().parse().unwrap();
                // Each permission taken was granted, as far as it says.
                let found = names_in(words.next().unwrap_or(""))
                    .into_iter()
                    .filter(|name| is_permission(name))
                    .map(|name| format!("{name}=granted"))
                    .collect::<Vec<_>>()
                    .join(",");
                sent.push(line.clone());
                let answer = match kind.as_str() {
                    "set" => Answer::Saved(found),
                    "apply" => Answer::Applied(found),
                    "restore" => Answer::Restored,
                    _ => continue,
                };
                // Locked only once the command waits.
                let mut state = shared.state();
                meanwhile(&mut state, uid);
                state.answers.insert(uid, answer);
                shared.changed.notify_all();
            }
            let _ = cat.wait();
            sent
        });
        (input, sent)
    }

    /// The camera and the microphone on, as `camera` says, and every other
    /// item off.
    fn camera_and_microphone(camera: bool) -> Settings {
        use super::super::items::{Item, Mode};
        let mut own = Settings::default();
        for item in Item::ALL {
            own.set(item, Some(Mode::Off)).unwrap();
        }
        let on = |yes| if yes { Mode::On } else { Mode::Off };
        own.set(Item::Camera, Some(on(camera))).unwrap();
        own.set(Item::Microphone, Some(Mode::On)).unwrap();
        own.over(&Settings::default())
    }

    #[test]
    fn an_app_starting_while_its_ops_change_is_not_held_to_what_they_were() {
        let mut appops = AppOps {
            shared: Arc::new(Shared::default()),
        };
        let (both, microphone) = (camera_and_microphone(true), camera_and_microphone(false));
        let mut entry = sandboxed(10100);
        appops
            .shared
            .state()
            .guarded
            .insert(10100, targets_of(&both));
        // The app starts, and the gate has its ops looked at, while each
        // command is on its way.
        let (input, sent) = stand_in(&appops.shared, |state, uid| state.verify(uid));
        appops.shared.state().input = Some(input);

        // The camera turned off, then the app taken out: the last one, so
        // the helper is told to leave.
        appops.update(&mut entry, &[], &both, &microphone).unwrap();
        appops.verifier()(10100);
        appops.disable(&mut entry, &[], &microphone).unwrap();
        assert!(!appops.shared.state().wanted);
        assert_eq!(
            sent.join().unwrap(),
            [
                "apply 10100 android:record_audio=1 android:camera=default",
                "verify 10100 android:record_audio=1",
                "restore 10100 android:record_audio=default",
            ]
        );
    }

    #[test]
    fn a_helper_started_while_a_command_waits_is_told_of_its_uid_with_the_answer() {
        let mut appops = AppOps {
            shared: Arc::new(Shared::default()),
        };
        let (both, microphone) = (camera_and_microphone(true), camera_and_microphone(false));
        let mut entry = sandboxed(10100);
        {
            let mut state = appops.shared.state();
            state.guarded.insert(10100, targets_of(&both));
            state.guarded.insert(10200, targets_of(&microphone));
        }
        // The helper ends after it applied the change and answered, and
        // the next one starts before the answer is taken.
        let (next, sent_next) = stand_in(&appops.shared, |_, _| {});
        let mut next = Some(next);
        let (input, sent) = stand_in(&appops.shared, move |state, _| {
            state.input = next.take();
            state.tell_guarded();
        });
        appops.shared.state().input = Some(input);

        appops.update(&mut entry, &[], &both, &microphone).unwrap();
        appops.shared.state().input = None;
        assert_eq!(
            sent.join().unwrap(),
            ["apply 10100 android:record_audio=1 android:camera=default"]
        );
        assert_eq!(
            sent_next.join().unwrap(),
            [
                "verify 10200 android:record_audio=1",
                "verify 10100 android:record_audio=1",
            ]
        );
    }

    #[test]
    fn location_made_coarse_and_back_saves_and_gives_back_the_permission() {
        use super::super::items::{Item, Mode};
        let mut appops = AppOps {
            shared: Arc::new(Shared::default()),
        };
        let (input, sent) = stand_in(&appops.shared, |_, _| {});
        appops.shared.state().input = Some(input);
        let coarse = only(&[(Item::Location, Mode::Coarse), (Item::Camera, Mode::On)]);
        let on = only(&[(Item::Location, Mode::On), (Item::Camera, Mode::On)]);
        let mut entry = sandboxed(10100);

        appops.enable(&mut entry, &[], &coarse).unwrap();
        assert_eq!(entry.saved_permissions, format!("{FINE}=granted"));
        // Given back as it was when the location is no longer coarse...
        appops.update(&mut entry, &[], &coarse, &on).unwrap();
        assert_eq!(entry.saved_permissions, "");
        // ...and saved anew when it is again, as the user may have changed
        // it in between.
        appops.update(&mut entry, &[], &on, &coarse).unwrap();
        assert_eq!(entry.saved_permissions, format!("{FINE}=granted"));
        appops.disable(&mut entry, &[], &coarse).unwrap();
        assert_eq!(entry.saved_permissions, "");
        assert_eq!(
            sent.join().unwrap(),
            [
                format!("set 10100 android:camera=1,{FINE}=revoked"),
                format!(
                    "apply 10100 android:coarse_location=1,android:fine_location=1,\
                     android:wifi_scan=1,android:camera=1 {FINE}=granted"
                ),
                format!(
                    "apply 10100 android:camera=1,{FINE}=revoked \
                     android:coarse_location=default,android:fine_location=default,\
                     android:wifi_scan=default"
                ),
                format!("restore 10100 android:camera=default,{FINE}=granted"),
            ]
        );
    }

    #[test]
    fn a_permission_named_is_given_back_whatever_the_items_say() {
        use super::super::items::{Item, Mode};
        let mut appops = AppOps {
            shared: Arc::new(Shared::default()),
        };
        let (input, sent) = stand_in(&appops.shared, |_, _| {});
        appops.shared.state().input = Some(input);
        // A daemon that stopped while location was made coarse from on,
        // written down as on, and the state not yet known.
        let on = only(&[(Item::Location, Mode::On)]);
        let off = only(&[]);
        let mut entry = Entry {
            saved_permissions: format!("{FINE}=default"),
            ..sandboxed(10100)
        };
        assert_eq!(
            restore_line(&entry, &on).as_deref(),
            Some(
                "restore 10100 android:coarse_location=default,android:fine_location=default,\
                 android:wifi_scan=default,android.permission.ACCESS_FINE_LOCATION=default"
            )
        );
        assert_eq!(
            restore_line(&entry, &off).as_deref(),
            Some("restore 10100 android.permission.ACCESS_FINE_LOCATION=default")
        );
        appops.resume(&[Resumed {
            entry: entry.clone(),
            packages: vec![],
            items: on.clone(),
        }]);
        appops.update(&mut entry, &[], &on, &off).unwrap();
        assert_eq!(entry.saved_permissions, "");
        assert_eq!(
            sent.join().unwrap(),
            ["verify 10100 android:coarse_location=1,android:fine_location=1,android:wifi_scan=1".to_owned(), format!(
                "restore 10100 android:coarse_location=default,android:fine_location=default,\
                 android:wifi_scan=default,{FINE}=default"
            )]
        );
    }

    #[test]
    fn an_app_is_given_back_the_ops_it_is_held_to_at_their_saved_modes() {
        use super::super::items::{Item, Mode};
        let entry = Entry {
            saved_ops: "android:camera=4,android:read_sms=1".into(),
            ..sandboxed(10100)
        };
        // The processor information holds no op: nothing to give back.
        assert_eq!(
            restore_line(&entry, &only(&[(Item::CpuInfo, Mode::On)])),
            None
        );
        assert_eq!(
            restore_line(&entry, &only(&[(Item::Camera, Mode::On)])).as_deref(),
            Some("restore 10100 android:camera=4")
        );
        assert_eq!(
            restore_line(
                &entry,
                &only(&[(Item::Camera, Mode::On), (Item::Microphone, Mode::On)])
            )
            .as_deref(),
            Some("restore 10100 android:camera=4,android:record_audio=default")
        );
        // The precise location permission at the state saved for it.
        let coarse = Entry {
            saved_permissions: format!("{FINE}=denied+user_set"),
            ..entry
        };
        assert_eq!(
            restore_line(&coarse, &only(&[(Item::Location, Mode::Coarse)])).as_deref(),
            Some("restore 10100 android.permission.ACCESS_FINE_LOCATION=denied+user_set")
        );
    }

    #[test]
    fn ops_saved_with_their_grants_go_back_with_them() {
        use super::super::items::{Item, Mode};
        let (ops, permissions) = split_saved(&format!(
            "android:read_contacts=1/1,android:camera=default/4,{FINE}=granted"
        ));
        assert_eq!(ops, "android:read_contacts=1/1,android:camera=default/4");
        assert_eq!(permissions, format!("{FINE}=granted"));
        let entry = Entry {
            saved_ops: ops,
            ..sandboxed(10100)
        };
        // The helper sets each op by its grant then and now.
        assert_eq!(
            restore_line(
                &entry,
                &only(&[(Item::Camera, Mode::On), (Item::Contacts, Mode::On)])
            )
            .as_deref(),
            Some(
                "restore 10100 android:read_contacts=1/1,android:write_contacts=default,\
                 android:camera=default/4"
            )
        );
    }

    #[test]
    fn a_permission_found_granted_again_is_read_from_the_helpers_lines() {
        assert_eq!(
            found_in(&format!("reverted 10100 {FINE}=granted+user_set")),
            Some((10100, format!("{FINE}=granted+user_set")))
        );
        // Answers nothing.
        assert_eq!(
            parse_line(&format!("reverted 10100 {FINE}=granted")),
            Some((10100, None))
        );
        for line in [
            format!("reverted 10100 {FINE}"),
            "reverted 10100 android:camera".to_string(),
            "reverted 10100 android:camera=1".to_string(),
            format!("reverted 10100 {FINE}="),
            format!("verified 10100 {FINE}=granted"),
            format!("reverted x {FINE}=granted"),
        ] {
            assert_eq!(found_in(&line), None, "{line}");
        }
    }

    #[test]
    fn a_service_given_the_access_again_keeps_the_others_that_had_it() {
        let access = items::NOTIFICATION_ACCESS;
        let both = format!("{access}=granted+service:a:a.A+service:a:a.B");
        let mut entry = Entry {
            saved_permissions: both.clone(),
            ..sandboxed(10100)
        };
        // One of the two given it again: both keep it.
        assert!(!keep_regranted(
            &mut entry,
            &[format!("{access}=granted+service:a:a.A")]
        ));
        assert_eq!(entry.saved_permissions, both);
        // One that did not have it, given it by the user: it joins them.
        assert!(keep_regranted(
            &mut entry,
            &[format!("{access}=granted+service:a:a.C")]
        ));
        assert_eq!(entry.saved_permissions, format!("{both}+service:a:a.C"));
        // Nothing known before: what was found.
        let mut unknown = Entry {
            saved_permissions: format!("{access}=default"),
            ..sandboxed(10100)
        };
        assert!(keep_regranted(
            &mut unknown,
            &[format!("{access}=granted+service:a:a.A")]
        ));
        assert_eq!(
            unknown.saved_permissions,
            format!("{access}=granted+service:a:a.A")
        );
    }

    #[test]
    fn a_permission_granted_again_while_held_is_given_back_granted() {
        use super::super::items::{Item, Mode};
        let mut appops = AppOps {
            shared: Arc::new(Shared::default()),
        };
        let entry = Entry {
            saved_permissions: format!("{FINE}=denied+user_set"),
            ..sandboxed(10100)
        };
        let mut entries = HashMap::from([(10100, entry), (10101, sandboxed(10101))]);
        appops.shared.state().found.extend([
            (10100, format!("{FINE}=granted+user_set")),
            // Not taken from this one, nor is there an entry for the last.
            (10101, format!("{FINE}=granted")),
            (10102, format!("{FINE}=granted")),
        ]);

        assert_eq!(appops.found(&mut entries), [10100]);
        assert!(appops.shared.state().found.is_empty());
        assert_eq!(entries[&10101].saved_permissions, "");
        assert_eq!(
            restore_line(&entries[&10100], &only(&[(Item::Location, Mode::Coarse)])).as_deref(),
            Some("restore 10100 android.permission.ACCESS_FINE_LOCATION=granted+user_set")
        );
        // Found again as it is now: nothing to write.
        appops
            .shared
            .state()
            .found
            .push((10100, format!("{FINE}=granted+user_set")));
        assert!(appops.found(&mut entries).is_empty());
    }

    #[test]
    fn restarts_are_spaced_further_and_further_apart() {
        assert_eq!(retry_delay(1), RETRY);
        assert_eq!(retry_delay(2), RETRY * 2);
        assert_eq!(retry_delay(40), RETRY_MAX);
    }
}
