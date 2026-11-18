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
//! put back when the app leaves the sandbox.
//!
//! Only the ops of the items that are on for the app are held (see
//! `items::Settings::op_targets`), ignored. An app with none of them on is
//! not handed to the helper.

use std::{
    collections::{BTreeMap, HashMap, HashSet},
    io::{BufRead as _, BufReader, Write as _},
    os::fd::AsRawFd as _,
    process::ChildStdin,
    sync::{Arc, Condvar, Mutex, MutexGuard},
    time::{Duration, Instant},
};

use anyhow::Context as _;

use super::{Effect, Entry, Resumed, Status, items::Settings};
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
    /// To `set`: the modes the ops had, as `restore` takes them.
    Saved(String),
    Restored,
    /// To `apply`.
    Applied,
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
        "applied" => Some(Answer::Applied),
        "failed" => Some(Answer::Failed),
        "reverted" | "verified" => None,
        _ => return None,
    };
    Some((uid, answer))
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
}

impl State {
    /// Send a line to the helper, without ever waiting for it: its input is
    /// a pipe that does not block, and a line is short enough to be written
    /// whole or not at all.
    fn send(&mut self, line: &str) -> anyhow::Result<()> {
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
            let line = format!("verify {uid} {targets}");
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

/// `AppOpsManager.MODE_IGNORED`: the app is given an empty answer, with its
/// permission still shown as granted.
const MODE_IGNORED: u8 = 1;

/// The ops to hold an app to, as the helper takes them: `<op>=<mode>,...`,
/// with the mode's number. Empty if none.
fn targets_of(items: &Settings) -> String {
    items
        .op_targets()
        .iter()
        .map(|op| format!("{op}={MODE_IGNORED}"))
        .collect::<Vec<_>>()
        .join(",")
}

/// The ops named in `targets` (as [`targets_of`] writes them).
fn ops_in(targets: &str) -> Vec<&str> {
    targets
        .split(',')
        .filter_map(|pair| pair.split_once('=').map(|(op, _)| op))
        .collect()
}

/// The modes `saved` (an entry's `saved_ops`) has for `ops`, as `restore`
/// and `apply` take them: `default` for an op it does not name.
fn saved_modes_of(saved: &str, ops: &[&str]) -> String {
    let saved: HashMap<&str, &str> = saved
        .split(',')
        .filter_map(|pair| pair.split_once('='))
        .collect();
    ops.iter()
        .map(|op| format!("{op}={}", saved.get(op).copied().unwrap_or("default")))
        .collect::<Vec<_>>()
        .join(",")
}

/// The helper's line that gives `entry` back the ops `items` hold it to,
/// at the modes saved for them, as disabling it would; `None` if they hold
/// none. For when the daemon is not there to disable it (`uninstall.sh`).
pub fn restore_line(entry: &Entry, items: &Settings) -> Option<String> {
    let targets = targets_of(items);
    (!targets.is_empty()).then(|| {
        format!(
            "restore {} {}",
            entry.uid,
            saved_modes_of(&entry.saved_ops, &ops_in(&targets))
        )
    })
}

impl Effect for AppOps {
    fn name(&self) -> &'static str {
        "app ops"
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        let targets = targets_of(items);
        if targets.is_empty() {
            return Ok(());
        }
        let uid = entry.uid;
        let line = format!("set {uid} {targets}");
        let saved = self.shared.ask(uid, &line, |state, answer| match answer {
            Answer::Saved(modes) => {
                state.guarded.insert(uid, targets);
                Some(modes)
            }
            _ => None,
        });
        match saved {
            Ok(Some(modes)) => {
                entry.saved_ops = modes;
                Ok(())
            }
            failed => {
                // It may have set them all the same, or be about to: a
                // helper that is there puts back what it saved itself.
                let _ = self.shared.state().send(&format!("release {uid}"));
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
        if held.is_empty() {
            return Ok(());
        }
        // Only the ops held go back: one the app was not held to is the
        // system's, and may have been changed since it was saved. Without
        // saved modes (the daemon stopped between setting them and writing
        // them down) their uid modes are cleared, and the system's defaults
        // apply.
        let line = format!(
            "restore {uid} {}",
            saved_modes_of(&entry.saved_ops, &ops_in(&held))
        );
        let restored = self.shared.ask(uid, &line, |state, answer| {
            let restored = answer == Answer::Restored;
            if restored {
                state.guarded.remove(&uid);
            }
            restored
        })?;
        anyhow::ensure!(restored, "the system did not take the app ops back");
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
        // The ops no longer held go back to what they were before the app
        // was sandboxed.
        let resets = |from: &str, to: &str| {
            let kept: HashSet<&str> = ops_in(to).into_iter().collect();
            let released: Vec<&str> = ops_in(from)
                .into_iter()
                .filter(|op| !kept.contains(op))
                .collect();
            if released.is_empty() {
                "-".to_string()
            } else {
                saved_modes_of(&entry.saved_ops, &released)
            }
        };
        let uid = entry.uid;
        let line = format!("apply {uid} {holding} {}", resets(&held, &holding));
        let applied = self.shared.ask(uid, &line, |state, answer| {
            let applied = answer == Answer::Applied;
            if applied {
                state.guarded.insert(uid, holding.clone());
            }
            applied
        });
        match applied {
            Ok(true) => Ok(()),
            failed => {
                // It may have applied them all the same, or be about to:
                // what was held is held again.
                let back = format!("apply {uid} {held} {}", resets(&holding, &held));
                let _ = self.shared.state().send(&back);
                failed?;
                anyhow::bail!("the system did not take the app ops")
            }
        }
    }

    fn resume(&mut self, apps: &[Resumed]) {
        let mut state = self.shared.state();
        // The helper is told of them as it starts.
        state.guarded.extend(apps.iter().filter_map(|app| {
            let targets = targets_of(&app.items);
            (!targets.is_empty()).then_some((app.entry.uid, targets))
        }));
        state.wanted = !state.guarded.is_empty();
        self.shared.changed.notify_all();
    }

    fn report(&self, status: &mut Status) {
        status.appops = self.shared.state().input.is_some();
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
        if let Some((uid, Some(answer))) = parse_line(&line) {
            shared.state().answers.insert(uid, answer);
            shared.changed.notify_all();
        }
    }
    // Closes its input, if it is still there to notice.
    shared.state().input = None;
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

    #[test]
    fn the_helpers_lines_are_read_as_it_prints_them() {
        assert_eq!(
            parse_line("saved 10100 android:camera=default,android:fine_location=4"),
            Some((
                10100,
                Some(Answer::Saved(
                    "android:camera=default,android:fine_location=4".into()
                ))
            ))
        );
        // A device that knows none of the ops saved none.
        assert_eq!(
            parse_line("saved 10100"),
            Some((10100, Some(Answer::Saved(String::new()))))
        );
        assert_eq!(
            parse_line("restored 1010100"),
            Some((1_010_100, Some(Answer::Restored)))
        );
        assert_eq!(
            parse_line("failed 10100"),
            Some((10100, Some(Answer::Failed)))
        );
        // What the helper did by itself answers nothing.
        assert_eq!(
            parse_line("reverted 10100 android:camera"),
            Some((10100, None))
        );
        assert_eq!(parse_line("verified 10100"), Some((10100, None)));
        assert_eq!(
            parse_line("applied 10100"),
            Some((10100, Some(Answer::Applied)))
        );
        for line in ["", "saved", "saved x", "hello 10100", "restored -1"] {
            assert_eq!(parse_line(line), None, "{line:?}");
        }
    }

    fn sandboxed(uid: u32) -> Entry {
        Entry {
            uid,
            package: "com.app".into(),
            enabled: true,
            android_id: String::new(),
            saved_ops: String::new(),
            created: 0,
            settings: String::new(),
        }
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
        let mut own = Settings::default();
        for item in Item::ALL {
            own.set(item, Some(Mode::Off)).unwrap();
        }
        own.set(Item::Camera, Some(Mode::On)).unwrap();
        own.set(Item::Contacts, Some(Mode::On)).unwrap();
        let items = own.over(&Settings::default());
        assert_eq!(
            targets_of(&items),
            "android:read_contacts=1,android:write_contacts=1,android:camera=1"
        );
        assert_eq!(
            saved_modes_of(
                "android:camera=4,android:read_contacts=default",
                &[
                    "android:camera",
                    "android:read_contacts",
                    "android:record_audio"
                ]
            ),
            "android:camera=4,android:read_contacts=default,android:record_audio=default"
        );
        assert_eq!(
            ops_in(&targets_of(&items)),
            [
                "android:read_contacts",
                "android:write_contacts",
                "android:camera"
            ]
        );
        assert!(ops_in("").is_empty());
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
                sent.push(line);
                let answer = match kind.as_str() {
                    "set" => Answer::Saved(String::new()),
                    "apply" => Answer::Applied,
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
    fn an_app_is_given_back_the_ops_it_is_held_to_at_their_saved_modes() {
        use super::super::items::{Item, Mode};
        let entry = Entry {
            uid: 10100,
            package: "com.app".into(),
            enabled: true,
            android_id: String::new(),
            saved_ops: "android:camera=4,android:read_sms=1".into(),
            created: 0,
            settings: String::new(),
        };
        let mut own = Settings::default();
        for item in Item::ALL {
            own.set(item, Some(Mode::Off)).unwrap();
        }
        // The processor information holds no op: nothing to give back.
        own.set(Item::CpuInfo, Some(Mode::On)).unwrap();
        assert_eq!(restore_line(&entry, &own.over(&Settings::default())), None);
        own.set(Item::Camera, Some(Mode::On)).unwrap();
        assert_eq!(
            restore_line(&entry, &own.over(&Settings::default())).as_deref(),
            Some("restore 10100 android:camera=4")
        );
        own.set(Item::Microphone, Some(Mode::On)).unwrap();
        assert_eq!(
            restore_line(&entry, &own.over(&Settings::default())).as_deref(),
            Some("restore 10100 android:camera=4,android:record_audio=default")
        );
    }

    #[test]
    fn restarts_are_spaced_further_and_further_apart() {
        assert_eq!(retry_delay(1), RETRY);
        assert_eq!(retry_delay(2), RETRY * 2);
        assert_eq!(retry_delay(40), RETRY_MAX);
    }
}
