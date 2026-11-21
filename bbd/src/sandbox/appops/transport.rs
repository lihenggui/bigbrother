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

//! Run the AppOps helper and exchange commands, answers and unsolicited reports.

use std::{
    collections::{BTreeMap, BTreeSet, HashMap},
    io::{BufRead as _, BufReader, Write as _},
    os::fd::AsRawFd as _,
    sync::MutexGuard,
    time::{Duration, Instant},
};

use anyhow::Context as _;

use super::{
    ANSWER_WAIT, Shared, State,
    protocol::{
        Answer, Command, found_in, is_permission, marking_unheld, parse_line, parse_unheld,
    },
};
use crate::{
    backoff::Backoff,
    helper,
    log::{Limited, Policy, SANDBOX_INTERVAL},
    sync::{lock, recover},
};

const HELPER_CLASS: &str = "AppOpsGuard";

/// Each helper restart starts a Java runtime. A healthy run brings the
/// delay back to its initial value.
const RESTART_BACKOFF: Backoff = Backoff {
    first: Duration::from_secs(10),
    max: Duration::from_secs(1800),
    healthy: Some(Duration::from_secs(600)),
};

impl Default for State {
    fn default() -> Self {
        Self {
            wanted: false,
            dismissed: false,
            hurry: false,
            input: None,
            guarded: BTreeMap::new(),
            owners: BTreeMap::new(),
            asking: None,
            pending_verifications: BTreeSet::new(),
            rollback_errors: Limited::new(SANDBOX_INTERVAL, Policy::Shared),
            checking_until: None,
            answers: HashMap::new(),
            captured: None,
            kind: None,
            unheld: BTreeMap::new(),
            unsure: BTreeMap::new(),
            found: Vec::new(),
        }
    }
}

impl State {
    /// Send a line to the helper, without ever waiting for it: its input is
    /// a pipe that does not block, and a line is short enough to be written
    /// whole or not at all.
    pub(super) fn send(&mut self, line: &str) -> anyhow::Result<()> {
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
        self.write_line(&line)
    }

    pub(super) fn write_line(&mut self, line: &str) -> anyhow::Result<()> {
        let input = self
            .input
            .as_mut()
            .context("the app ops helper is not running")?;
        input
            .write_all(format!("{line}\n").as_bytes())
            .context("the app ops helper takes no commands")
    }

    pub(super) fn rollback(&mut self, line: &str) {
        if let Err(e) = self.send(line) {
            self.rollback_errors.log(format_args!(
                "bbd: sandbox: cannot roll back app ops with the helper: {e:#}"
            ));
        }
    }

    /// Have the helper look at the ops of `uid` and hold it to what it is
    /// held to here. Nothing for a uid not held, or one a command is
    /// changing. A start held only for its mounts can have no ops to check.
    pub(super) fn verify(&mut self, uid: u32) -> anyhow::Result<()> {
        if self.asking != Some(uid)
            && let Some(targets) = self.guarded.get(&uid)
        {
            let targets = marking_unheld(targets, self.unsure.get(&uid));
            let line = Command::Verify(uid, &targets).to_string();
            self.send(&line)?;
        }
        Ok(())
    }

    /// Tell a helper that just started of every uid held. If the pipe
    /// fills, its answers make room for the remaining commands.
    pub(super) fn tell_guarded(&mut self) {
        self.pending_verifications.extend(self.guarded.keys());
        self.checking_until = Some(Instant::now() + ANSWER_WAIT);
        self.send_pending_verifications();
    }

    fn send_pending_verifications(&mut self) {
        while let Some(uid) = self
            .pending_verifications
            .iter()
            .copied()
            .find(|uid| Some(*uid) != self.asking)
        {
            if self.verify(uid).is_err() {
                break;
            }
            self.pending_verifications.remove(&uid);
        }
    }
}

impl Shared {
    pub(super) fn state(&self) -> MutexGuard<'_, State> {
        // Whoever panicked holding it left nothing half done that matters:
        // an answer is inserted or not.
        lock(&self.state)
    }

    /// Send a command about `uid`, wait for the helper's answer, and hand
    /// it to `settle`, which changes what the uid is held to. That is done
    /// before the state is let go of, so that no `verify` sent in between
    /// names what the uid was held to before the command.
    pub(super) fn ask<T>(
        &self,
        uid: u32,
        line: &str,
        settle: impl FnOnce(&mut State, Answer) -> T,
    ) -> anyhow::Result<T> {
        let deadline = Instant::now() + ANSWER_WAIT;
        self.ask_until(
            uid,
            deadline,
            |state| state.send(line),
            |state| state.answers.remove(&uid),
            settle,
        )
    }

    /// As [`Shared::ask`], waiting until `deadline`, for the answer `take`
    /// takes from the state.
    pub(super) fn ask_until<A, T>(
        &self,
        uid: u32,
        deadline: Instant,
        send: impl FnOnce(&mut State) -> anyhow::Result<()>,
        mut take: impl FnMut(&mut State) -> Option<A>,
        settle: impl FnOnce(&mut State, A) -> T,
    ) -> anyhow::Result<T> {
        let mut state = self.state();
        let wait = |state, left| recover(self.changed.wait_timeout(state, left)).0;
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
        // One left from an earlier command that was not waited for.
        take(&mut state);
        anyhow::ensure!(
            Instant::now() < deadline,
            "the app ops helper did not answer in time"
        );
        send(&mut state)?;
        state.asking = Some(uid);
        let settled = loop {
            if let Some(answer) = take(&mut state) {
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
        state.send_pending_verifications();
        settled
    }

    pub(super) fn receive(&self, line: &str) {
        if let Some((uid, ops)) = parse_unheld(line) {
            let mut state = self.state();
            let permissions = ops
                .iter()
                .filter(|name| is_permission(name))
                .cloned()
                .collect();
            state.unsure.insert(uid, permissions);
            if state.unheld.insert(uid, ops).is_none() && state.guarded.contains_key(&uid) {
                state.checking_until = Some(Instant::now() + ANSWER_WAIT);
            }
            state.send_pending_verifications();
        } else if let Some(found) = found_in(line) {
            self.state().found.push(found);
            if let Some(wake) = self.on_found.get() {
                wake();
            }
        } else if let Some((uid, answer)) = parse_line(line) {
            let mut state = self.state();
            state.send_pending_verifications();
            let Some(answer) = answer else {
                return;
            };
            match answer {
                Answer::Kind(request, kind) => state.kind = Some((uid, request, kind)),
                Answer::Captured(request, values) => state.captured = Some((uid, request, values)),
                _ => {
                    state.answers.insert(uid, answer);
                }
            }
            drop(state);
            self.changed.notify_all();
        }
    }

    /// End the helper if no app is sandboxed any more. Closing its input
    /// is what tells it to leave.
    pub(super) fn rest_if_unused(&self) {
        let mut state = self.state();
        if state.guarded.is_empty() {
            state.wanted = false;
            // Kept if it was told already and has not been seen to leave.
            state.dismissed |= state.input.take().is_some();
        }
    }
}

/// Run the helper while it is wanted, starting it again when it ends by
/// itself.
pub(super) fn supervise(shared: &Shared) {
    let mut failures = 0;
    let mut last_said = String::new();
    loop {
        {
            let mut state = shared.state();
            while !state.wanted {
                state = recover(shared.changed.wait(state));
            }
        }
        let started = Instant::now();
        let ended = run_helper(shared);
        // Ended on purpose: started at once when wanted again.
        if std::mem::take(&mut shared.state().dismissed) {
            failures = 0;
            continue;
        }
        failures = RESTART_BACKOFF.failures_after_run(failures, started.elapsed());
        // The same reason again says nothing new.
        let said = format!("{ended:#}");
        if said != last_said {
            eprintln!("bbd: sandbox: app ops helper: {said}");
            last_said = said;
        }
        // Until the delay is over, or a command wants the helper now.
        let until = Instant::now() + RESTART_BACKOFF.delay(failures);
        let mut state = shared.state();
        while !state.hurry {
            let left = until.saturating_duration_since(Instant::now());
            if left.is_zero() {
                break;
            }
            state = recover(shared.changed.wait_timeout(state, left)).0;
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
        shared.receive(&line);
    }
    // Closes its input, if it is still there to notice. The next helper
    // reports again on every uid it is told of.
    {
        let mut state = shared.state();
        state.input = None;
        state.unheld.clear();
        state.pending_verifications.clear();
        state.checking_until = None;
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
    use std::{os::fd::OwnedFd, process::ChildStdin, sync::Arc};

    use super::super::{
        AppOps,
        protocol::targets_of,
        tests::{camera_and_microphone, sandboxed, stand_in},
    };
    use super::*;
    use crate::sandbox::{Effect, Entry, Resumed, Status, items::Settings};

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
        for item in crate::sandbox_item::Item::ALL {
            off.set(item, Some(crate::sandbox_item::Mode::Off)).unwrap();
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
    fn recovery_checks_every_app_even_when_the_helpers_pipe_fills() {
        let (read, write) = std::io::pipe().unwrap();
        let input = ChildStdin::from(OwnedFd::from(write));
        // No reader runs until the initial batch has been offered, so the
        // result is independent of the scheduling of the helper thread.
        let capacity = unsafe { libc::fcntl(input.as_raw_fd(), libc::F_SETPIPE_SZ, 4096) };
        assert!(capacity > 0);
        for fd in [input.as_raw_fd(), read.as_raw_fd()] {
            assert_eq!(
                unsafe { libc::fcntl(fd, libc::F_SETFL, libc::O_NONBLOCK) },
                0
            );
        }

        let items = Settings::all_on();
        let count = capacity as usize / targets_of(&items).len() + 5;
        let apps = 10100..10100 + u32::try_from(count).unwrap();
        let mut appops = AppOps {
            shared: Arc::new(Shared::default()),
        };
        appops.shared.state().input = Some(input);
        appops.resume(
            &apps
                .clone()
                .map(|uid| Resumed {
                    entry: Entry {
                        install_id: "1234567890abcdef1234567890abcdef".into(),
                        ..sandboxed(uid)
                    },
                    packages: vec![],
                    items: items.clone(),
                })
                .collect::<Vec<_>>(),
        );

        let mut status = Status::default();
        appops.report(&mut status);
        assert_eq!(status.checking_appops, apps.clone().collect());
        assert!(status.held_ops.is_empty());
        appops.shared.state().checking_until = Some(Instant::now());
        let mut status = Status::default();
        appops.report(&mut status);
        assert!(status.checking_appops.is_empty());
        assert!(status.held_ops.is_empty());

        let mut reader = BufReader::new(read);
        let mut reached = BTreeSet::new();
        let mut line = String::new();
        loop {
            match reader.read_line(&mut line) {
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Ok(bytes) if bytes > 0 => {
                    let mut fields = line.split_ascii_whitespace();
                    assert_eq!(fields.nth(3), Some("verify"));
                    let uid = fields.next().unwrap().parse::<u32>().unwrap();
                    assert!(reached.insert(uid), "duplicate verification for {uid}");
                    let unheld = if uid == apps.start {
                        "android:camera"
                    } else {
                        "-"
                    };
                    appops.shared.receive(&format!("unheld {uid} {unheld}"));
                    line.clear();
                }
                result => panic!("reading the helper's commands: {result:?}"),
            }
        }

        assert_eq!(reached, apps.clone().collect());
        let mut status = Status::default();
        appops.report(&mut status);
        assert!(status.checking_appops.is_empty());
        assert_eq!(status.held_ops.len(), apps.len());
        assert!(!status.held_ops[&apps.start].contains("android:camera"));
        assert!(status.held_ops[&(apps.start + 1)].contains("android:camera"));
    }

    #[test]
    fn an_answer_to_a_question_is_never_taken_for_a_commands() {
        use crate::sandbox::auto::Libraries as _;
        let mut appops = AppOps {
            shared: Arc::new(Shared::default()),
        };
        // The stand-in answers no question: it is given up on in time.
        let (input, sent) = stand_in(&appops.shared, |_, _| {});
        appops.shared.state().input = Some(input);
        let librarian = appops.librarian();
        let until = Instant::now() + Duration::from_millis(100);
        assert_eq!(librarian.is_library(10230, "com.example.new", until), None);
        // Its answer comes late, as the app is put in: the command gets
        // its own.
        let (uid, Some(Answer::Kind(request, kind))) = parse_line("app 10230 1").unwrap() else {
            panic!("expected a kind answer");
        };
        appops.shared.state().kind = Some((uid, request, kind));
        let mut entry = sandboxed(10230);
        appops
            .enable(&mut entry, &[], &camera_and_microphone(true))
            .unwrap();
        appops.shared.state().input = None;
        let sent = sent.join().unwrap();
        assert_eq!(sent[0], "kind 10230 1 com.example.new");
        assert!(sent[1].starts_with("set 10230 "));
        // A name no package can have is not asked about.
        assert_eq!(librarian.is_library(10230, "com.example app", until), None);
    }

    #[test]
    fn restarts_are_spaced_further_and_further_apart() {
        assert_eq!(RESTART_BACKOFF.delay(1), Duration::from_secs(10));
        assert_eq!(RESTART_BACKOFF.delay(2), Duration::from_secs(20));
        assert_eq!(RESTART_BACKOFF.delay(40), Duration::from_secs(1800));
    }
}
