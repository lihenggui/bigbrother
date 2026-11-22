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
    io::{BufRead as _, BufReader, Write as _},
    os::fd::AsRawFd as _,
    process::ChildStdout,
    sync::{
        MutexGuard,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

use anyhow::Context as _;

use super::{
    ANSWER_WAIT, Shared, State,
    protocol::{
        Answer, Command, found_in, is_permission, marking_unheld, parse_line, parse_reply,
        parse_unheld, request_line,
    },
};
use crate::{
    backoff::Backoff,
    helper,
    sync::{lock, recover},
};

const HELPER_CLASS: &str = "AppOpsGuard";
pub(super) const MAX_LINE: usize = 4095;
const READY_WAIT: Duration = Duration::from_secs(4);
const CHECK_WAIT: Duration = Duration::from_secs(5);
// The helper's fallback check runs once a minute when there is no change event.
const READBACK_AGE: Duration = Duration::from_secs(75);

/// Each helper restart starts a Java runtime. A healthy run brings the
/// delay back to its initial value.
const RESTART_BACKOFF: Backoff = Backoff {
    first: Duration::from_secs(10),
    max: Duration::from_secs(60),
    healthy: Some(Duration::from_secs(600)),
};

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
        self.send_bound(uid, line)
    }

    fn send_bound(&mut self, uid: u32, line: &str) -> anyhow::Result<()> {
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
        anyhow::ensure!(line.len() <= MAX_LINE, "the app ops command is too large");
        let input = self
            .input
            .as_mut()
            .context("the app ops helper is not running")?;
        input
            .write_all(format!("{line}\n").as_bytes())
            .context("the app ops helper takes no commands")
    }

    pub(super) fn invalidate(&mut self, uid: u32) {
        self.unheld.remove(&uid);
        self.unknown.insert(uid);
    }

    fn accept_helper_line<'a>(&self, line: &'a str) -> Option<&'a str> {
        #[cfg(not(test))]
        if self.input.is_none() || self.restart {
            return None;
        }
        if let Some(report) = line.strip_prefix("report ") {
            let (token, line) = report.split_once(' ')?;
            let uid = line
                .split_ascii_whitespace()
                .nth(1)
                .and_then(|uid| uid.parse::<u32>().ok())?;
            return self
                .owners
                .get(&uid)
                .is_some_and(|(_, owner)| owner == token)
                .then_some(line);
        }
        #[cfg(not(test))]
        if !line.starts_with("reply ")
            && !matches!(parse_line(line), Some((_, Some(Answer::Kind(..)))))
        {
            return None;
        }
        Some(line)
    }

    /// Have the helper look at the ops of `uid` and hold it to what it is
    /// held to here. Nothing for a uid not held, or one a command is
    /// changing. A start held only for its mounts can have no ops to check.
    pub(super) fn verify(&mut self, uid: u32) -> anyhow::Result<()> {
        if !self.restart
            && self.asking != Some(uid)
            && !self.blocked.contains(&uid)
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

    pub(super) fn send_pending_verifications(&mut self) {
        while let Some(uid) = self
            .pending_verifications
            .iter()
            .copied()
            .find(|uid| Some(*uid) != self.asking && !self.blocked.contains(uid))
        {
            if let Some(values) = self.receipts.remove(&uid) {
                for (name, value) in &values {
                    if self
                        .send(&format!("persisted {uid} {name}={value}"))
                        .is_err()
                    {
                        self.receipts.insert(uid, values);
                        return;
                    }
                }
            }
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

    pub(super) fn bind(&self, uid: u32, package: &str, token: &str) {
        let mut state = self.state();
        let owner = (package.to_owned(), token.to_owned());
        if state.owners.get(&uid) != Some(&owner) {
            state.invalidate(uid);
            state.unsure.remove(&uid);
            state.found.retain(|(id, _)| *id != uid);
            state.awaiting_write.remove(&uid);
            state.receipts.remove(&uid);
            state.owners.insert(uid, owner);
        }
    }

    pub(super) fn ask(&self, uid: u32, line: impl FnOnce(u64) -> String) -> anyhow::Result<Answer> {
        self.ask_with(uid, line, || Ok(()))
    }

    pub(super) fn ask_with(
        &self,
        uid: u32,
        line: impl FnOnce(u64) -> String,
        before_send: impl FnOnce() -> anyhow::Result<()>,
    ) -> anyhow::Result<Answer> {
        let request = self.next_request.fetch_add(1, Ordering::Relaxed);
        let line = line(request);
        let deadline = Instant::now() + ANSWER_WAIT;
        self.ask_until(
            uid,
            deadline,
            |state| {
                let token = state
                    .owners
                    .get(&uid)
                    .context("missing installation identity")?
                    .1
                    .clone();
                before_send()?;
                anyhow::ensure!(
                    Instant::now() < deadline,
                    "the app ops helper did not answer in time"
                );
                if let Err(error) = state.send_bound(uid, &request_line(request, &line)) {
                    state.restart = true;
                    state.invalidate(uid);
                    self.changed.notify_all();
                    return Err(error);
                }
                state.request = Some((uid, request, token));
                Ok(())
            },
            |state| state.answer.take(),
        )
    }

    /// As [`Shared::ask`], waiting until `deadline`, for the answer `take`
    /// takes from the state.
    pub(super) fn ask_until<A>(
        &self,
        uid: u32,
        deadline: Instant,
        send: impl FnOnce(&mut State) -> anyhow::Result<()>,
        mut take: impl FnMut(&mut State) -> Option<A>,
    ) -> anyhow::Result<A> {
        let mut state = self.state();
        let wait = |state, left| recover(self.changed.wait_timeout(state, left)).0;
        let late = || anyhow::anyhow!("the app ops helper did not answer in time");
        // Started for this command if it was not running, which takes a
        // second or two.
        state.wanted = true;
        state.hurry = state.input.is_none();
        self.changed.notify_all();
        while state.input.is_none() || state.restart {
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
        state.command_until = Some(deadline);
        let generation = state.generation;
        self.changed.notify_all();
        let settled = loop {
            if state.input.is_none() || state.restart || state.generation != generation {
                break Err(anyhow::anyhow!("the app ops helper ended"));
            }
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                state.restart = true;
                break Err(late());
            }
            if let Some(answer) = take(&mut state) {
                break Ok(answer);
            }
            state = wait(state, left);
        };
        state.asking = None;
        state.request = None;
        state.command_until = None;
        if state.restart {
            state.invalidate(uid);
        } else {
            state.send_pending_verifications();
        }
        self.changed.notify_all();
        settled
    }

    pub(super) fn receive(&self, line: &str) {
        let mut state = self.state();
        let Some(line) = state.accept_helper_line(line) else {
            return;
        };
        let mut wake_found = false;
        let mut wake_restart = false;
        if let Some(uid) = line
            .strip_prefix("checking ")
            .and_then(|uid| uid.parse::<u32>().ok())
        {
            state.check_until = Some((uid, Instant::now() + CHECK_WAIT));
        } else if let Some(uid) = line
            .strip_prefix("unknown-readback ")
            .and_then(|uid| uid.parse::<u32>().ok())
        {
            state.invalidate(uid);
            state.check_until = None;
        } else if let Some((uid, ops)) = parse_unheld(line) {
            if state
                .check_until
                .is_some_and(|(checking, _)| checking == uid)
            {
                state.check_until = None;
            }
            let permissions = ops
                .iter()
                .filter(|name| is_permission(name))
                .cloned()
                .collect();
            state.unsure.insert(uid, permissions);
            state
                .unheld
                .insert(uid, (Instant::now() + READBACK_AGE, ops));
            state.unknown.remove(&uid);
            wake_restart = std::mem::take(&mut state.notify_restart);
        } else if let Some(found) = found_in(line) {
            if state.owners.contains_key(&found.0) {
                state.found.push(found);
                wake_found = true;
            }
        } else if let Some((uid, request, token, answer)) = parse_reply(line) {
            if state.request.as_ref().is_some_and(|(id, number, owner)| {
                (*id, *number, owner.as_str()) == (uid, request, token)
            }) {
                state.answer = Some(answer);
            }
        } else if let Some((uid, Some(Answer::Kind(request, kind)))) = parse_line(line) {
            state.kind = Some((uid, request, kind));
        }
        state.send_pending_verifications();
        drop(state);
        self.changed.notify_all();
        if wake_found && let Some(wake) = self.on_found.get() {
            wake();
        }
        if wake_restart && let Some(wake) = self.on_restart.get() {
            wake();
        }
    }

    /// End the helper if no app is sandboxed any more. Closing its input
    /// is what tells it to leave.
    pub(super) fn rest_if_unused(&self) {
        let mut state = self.state();
        if state.guarded.is_empty() && state.blocked.is_empty() {
            state.wanted = false;
            // Kept if it was told already and has not been seen to leave.
            state.dismissed |= state.input.take().is_some();
            self.changed.notify_all();
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
            // An explicit request expedites one launch.
            state.hurry = false;
        }
        let started = Instant::now();
        let ended = run_helper(shared);
        // Ended on purpose: started at once when wanted again.
        {
            let mut state = shared.state();
            if std::mem::take(&mut state.dismissed) {
                failures = 0;
                state.notify_restart = false;
                continue;
            }
        }
        failures = RESTART_BACKOFF.failures_after_run(failures, started.elapsed());
        if failures == 1 {
            // Another crash during the same recovery must not turn a
            // retried auto-enable into an endless series of attempts.
            let mut state = shared.state();
            state.notify_restart = !state.guarded.is_empty();
        }
        // The same reason again says nothing new.
        let said = format!("{ended:#}");
        if said != last_said {
            eprintln!("bbd: sandbox: app ops helper: {said}");
            last_said = said;
        }
        // Until the delay is over, or a command wants the helper now.
        let until = Instant::now() + RESTART_BACKOFF.delay(failures);
        let mut state = shared.state();
        while state.wanted && !state.hurry {
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
    let mut helper = match helper::spawn_guard(HELPER_CLASS, std::iter::empty()) {
        Ok(helper) => helper,
        Err(e) => return e,
    };
    supervise_child(shared, &mut helper, READY_WAIT)
}

fn kill_helper(helper: &mut std::process::Child) {
    // The helper has its own process group, including any blocked settings command.
    unsafe { libc::kill(-(helper.id() as i32), libc::SIGKILL) };
    let _ = helper.wait();
}

fn supervise_child(
    shared: &Shared,
    helper: &mut std::process::Child,
    ready_wait: Duration,
) -> anyhow::Error {
    let output = helper.stdout.take().expect("spawned with a piped stdout");
    let input = helper.stdin.take().expect("spawned with a piped stdin");
    // Drain without logging framework output, which can include package names.
    let errors = helper.stderr.take().map(|mut stderr| {
        std::thread::spawn(move || std::io::copy(&mut stderr, &mut std::io::sink()))
    });
    if unsafe { libc::fcntl(input.as_raw_fd(), libc::F_SETFL, libc::O_NONBLOCK) } == -1 {
        kill_helper(helper);
        return anyhow::anyhow!("cannot make the helper pipe nonblocking");
    }
    let ready = AtomicBool::new(false);
    let ended = AtomicBool::new(false);
    std::thread::scope(|scope| {
        scope.spawn(|| read_helper_output(shared, output, &ready, &ended));

        let ready_until = Instant::now() + ready_wait;
        let mut state = shared.state();
        while !ready.load(Ordering::Relaxed) && !ended.load(Ordering::Relaxed) && state.wanted {
            let left = ready_until.saturating_duration_since(Instant::now());
            if left.is_zero() {
                break;
            }
            state = recover(shared.changed.wait_timeout(state, left)).0;
        }
        let reason =
            if ready.load(Ordering::Relaxed) && state.wanted && !ended.load(Ordering::Relaxed) {
                state.input = Some(input);
                state.generation += 1;
                state.hurry = false;
                state.tell_guarded();
                shared.changed.notify_all();
                loop {
                    if ended.load(Ordering::Relaxed) || state.dismissed || !state.wanted {
                        break "the helper ended";
                    }
                    let until = state
                        .command_until
                        .into_iter()
                        .chain(state.check_until.map(|(_, until)| until))
                        .min();
                    if state.restart || until.is_some_and(|until| until <= Instant::now()) {
                        break "the helper stopped responding";
                    }
                    state =
                        if let Some(until) = until {
                            recover(shared.changed.wait_timeout(
                                state,
                                until.saturating_duration_since(Instant::now()),
                            ))
                            .0
                        } else {
                            recover(shared.changed.wait(state))
                        };
                }
            } else {
                "the helper did not become ready"
            };
        state.input = None;
        state.restart = false;
        state.unheld.clear();
        state.unknown.clear();
        state.check_until = None;
        state.receipts.clear();
        state.pending_verifications.clear();
        state.checking_until = None;
        shared.changed.notify_all();
        drop(state);
        kill_helper(helper);
        if let Some(errors) = errors {
            let _ = errors.join();
        }
        anyhow::anyhow!(reason)
    })
}

fn read_helper_output(
    shared: &Shared,
    output: ChildStdout,
    ready: &AtomicBool,
    ended: &AtomicBool,
) {
    // A read error ends the session just like EOF.
    let mut lines = BufReader::new(output).lines().map_while(Result::ok);
    if lines.next().as_deref() == Some("ready") {
        {
            let _state = shared.state();
            ready.store(true, Ordering::Relaxed);
            shared.changed.notify_all();
        }
        for line in lines {
            shared.receive(&line);
        }
    }
    let _state = shared.state();
    ended.store(true, Ordering::Relaxed);
    shared.changed.notify_all();
}

#[cfg(test)]
mod tests {
    use std::{collections::BTreeSet, os::fd::OwnedFd, process::ChildStdin, sync::Arc};

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
        {
            let mut state = appops.shared.state();
            assert!(state.restart);
            // A replacement that became ready before the next request.
            state.restart = false;
            state.kind = Some((uid, request, kind));
        }
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
    fn ignores_a_mutating_reply_from_an_earlier_request_or_installation() {
        let shared = Shared::default();
        let token = "0123456789abcdef0123456789abcdef";
        let other = "fedcba9876543210fedcba9876543210";
        shared.state().request = Some((10100, 24, token.to_owned()));
        shared.receive(&format!("reply 23 {token} restored 10100"));
        shared.receive(&format!("reply 24 {other} restored 10100"));
        shared.receive("restored 10100");
        assert!(shared.state().answer.is_none());
        shared.receive(&format!("reply 24 {token} restored 10100"));
        assert_eq!(shared.state().answer.take(), Some(Answer::Restored));
    }

    #[test]
    fn helper_recovery_wakes_auto_isolation_once_after_current_readback() {
        let appops = AppOps {
            shared: Arc::new(Shared::default()),
        };
        let token = "0123456789abcdef0123456789abcdef";
        let wakes = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        appops.on_restart({
            let wakes = wakes.clone();
            move || {
                wakes.fetch_add(1, Ordering::Relaxed);
            }
        });
        appops.shared.bind(10100, "com.example.app", token);
        appops.shared.state().notify_restart = true;

        appops
            .shared
            .receive("report outdated unknown-readback 10100");
        appops.shared.receive("report outdated unheld 10100 -");
        appops
            .shared
            .receive(&format!("report {token} checking 10100"));
        assert_eq!(wakes.load(Ordering::Relaxed), 0);

        appops
            .shared
            .receive(&format!("report {token} unheld 10100 -"));
        appops
            .shared
            .receive(&format!("report {token} unheld 10100 -"));
        assert_eq!(wakes.load(Ordering::Relaxed), 1);
    }

    #[test]
    fn a_failed_or_expired_check_invalidates_only_its_installation() {
        let mut appops = AppOps {
            shared: Arc::new(Shared::default()),
        };
        let token = "0123456789abcdef0123456789abcdef";
        let other = "fedcba9876543210fedcba9876543210";
        let app = |uid| Resumed {
            entry: Entry {
                install_id: token.to_owned(),
                ..sandboxed(uid)
            },
            packages: vec![],
            items: camera_and_microphone(true),
        };
        appops.resume(&[app(10100), app(10101)]);
        for uid in [10100, 10101] {
            appops
                .shared
                .receive(&format!("report {token} unheld {uid} -"));
        }
        appops
            .shared
            .receive(&format!("report {other} unknown-readback 10101"));
        appops
            .shared
            .receive(&format!("report {token} unknown-readback 10100"));
        let mut status = Status::default();
        appops.report(&mut status);
        assert_eq!(status.unknown_appops, [10100].into());
        assert!(status.held_ops[&10101].contains("android:camera"));
        appops.shared.state().unheld.get_mut(&10101).unwrap().0 = Instant::now();
        let mut status = Status::default();
        appops.report(&mut status);
        assert_eq!(status.unknown_appops, [10100, 10101].into());
        assert!(status.held_ops.is_empty());
    }

    fn shell_helper(script: &str) -> std::process::Child {
        use std::{
            os::unix::process::CommandExt as _,
            process::{Command, Stdio},
        };
        Command::new("sh")
            .process_group(0)
            .args(["-c", script])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap()
    }

    #[test]
    fn a_child_that_never_reports_ready_is_killed_without_publishing_its_input() {
        let shared = Shared::default();
        shared.state().wanted = true;
        let mut child = shell_helper("while IFS= read -r line; do :; done");
        let outcome = supervise_child(&shared, &mut child, Duration::from_millis(30));
        assert!(outcome.to_string().contains("did not become ready"));
        assert!(shared.state().input.is_none());
        assert_eq!(shared.state().generation, 0);
        assert!(child.try_wait().unwrap().is_some());
    }

    #[test]
    fn a_ready_child_that_ignores_a_request_is_recycled() {
        let shared = Shared::default();
        shared.state().wanted = true;
        let mut child = shell_helper("printf 'ready\n'; while IFS= read -r line; do :; done");
        let outcome = std::thread::scope(|scope| {
            let supervised =
                scope.spawn(|| supervise_child(&shared, &mut child, Duration::from_secs(2)));
            let deadline = Instant::now() + Duration::from_secs(2);
            let mut state = shared.state();
            while state.input.is_none() {
                let left = deadline.saturating_duration_since(Instant::now());
                assert!(!left.is_zero(), "the child did not report ready");
                state = recover(shared.changed.wait_timeout(state, left)).0;
            }
            drop(state);
            let result = shared.ask_until(
                10100,
                Instant::now() + Duration::from_millis(30),
                |state| state.write_line("kind 10100 1 com.example.app"),
                |state| state.kind.take(),
            );
            assert!(result.is_err());
            supervised.join().unwrap()
        });
        assert!(outcome.to_string().contains("stopped responding"));
        assert!(child.try_wait().unwrap().is_some());
        assert!(shared.state().input.is_none());
    }

    #[test]
    fn restarts_are_spaced_further_and_further_apart() {
        assert_eq!(RESTART_BACKOFF.delay(1), Duration::from_secs(10));
        assert_eq!(RESTART_BACKOFF.delay(2), Duration::from_secs(20));
        assert_eq!(RESTART_BACKOFF.delay(40), Duration::from_secs(60));
    }
}
