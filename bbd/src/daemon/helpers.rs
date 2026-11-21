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

//! Read Android service information without holding up the event loop.

use std::time::{Duration, Instant};

use crate::{
    appops,
    backoff::Backoff,
    binder::{self, Catalog},
    helper,
    log::{Limited, Policy, RECURRING_INTERVAL},
};

/// Each restart costs a Java runtime's start-up. The helper itself waits
/// for the system service at boot, so this covers failures after that.
const APPOPS_BACKOFF: Backoff = Backoff {
    first: Duration::from_secs(30),
    max: Duration::from_secs(3600),
    healthy: Some(Duration::from_secs(600)),
};

/// Failures without a single reading before reporting the records as
/// unavailable. Attempts go on regardless.
const APPOPS_GIVE_UP: u32 = 5;

/// How often the system's permission usage records are read. The records
/// carry their own times, so the interval costs precision only when an app
/// uses the same permission more than once in between.
const APPOPS_INTERVAL: Duration = Duration::from_secs(30);

/// Read the permission usage records on a thread of its own, for as long as
/// the daemon runs: one helper process is kept running and prints a reading
/// per interval, rather than a Java runtime being started for each. Sends
/// each reading, or `None` once to say that there seems to be none to be
/// had.
pub(super) fn spawn_appops_reader() -> std::sync::mpsc::Receiver<Option<Vec<appops::Entry>>> {
    let (sender, receiver) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let mut failures = 0;
        let mut read_once = false;
        let mut log = Limited::new(RECURRING_INTERVAL, Policy::ResetOnChange);
        loop {
            let started = Instant::now();
            // Returns when the helper has ended, which it is not meant to.
            let e = match stream_appops(&sender, &mut read_once) {
                StreamEnd::Helper(e) => e,
                StreamEnd::DaemonGone => return,
            };
            failures = APPOPS_BACKOFF.failures_after_run(failures, started.elapsed());
            log.log(format_args!("bbd: reading permission use: {e:#}"));
            // Reported once, when it stops looking like a system that is
            // still starting.
            if failures == APPOPS_GIVE_UP && !read_once {
                eprintln!("bbd: warning: not recording permission use");
                if sender.send(None).is_err() {
                    return;
                }
            }
            std::thread::sleep(APPOPS_BACKOFF.delay(failures));
        }
    });
    receiver
}

/// Why [`stream_appops`] returned.
enum StreamEnd {
    /// The helper could not be started or stopped printing.
    Helper(anyhow::Error),
    /// Nobody is listening any more.
    DaemonGone,
}

/// Run the helper and pass on each reading it prints, until it ends.
fn stream_appops(
    sender: &std::sync::mpsc::Sender<Option<Vec<appops::Entry>>>,
    read_once: &mut bool,
) -> StreamEnd {
    let interval = APPOPS_INTERVAL.as_secs().to_string();
    let mut args: Vec<&str> = vec!["--every", &interval];
    for op in appops::ops() {
        args.push(op);
    }
    // Its stdin is held open for as long as `helper` lives; the helper
    // leaves when it closes, so it does not outlive the daemon.
    let helper = match helper::spawn(appops::HELPER_CLASS, args.into_iter()) {
        Ok(helper) => helper,
        Err(e) => return StreamEnd::Helper(e),
    };
    read_appops(helper, sender, read_once)
}

fn read_appops(
    mut helper: std::process::Child,
    sender: &std::sync::mpsc::Sender<Option<Vec<appops::Entry>>>,
    read_once: &mut bool,
) -> StreamEnd {
    use std::io::BufRead as _;

    let output = helper.stdout.take().expect("spawned with a piped stdout");
    // What it says when it fails, for the message; read as it comes, so
    // that the helper never waits for room in the pipe.
    let errors = helper
        .stderr
        .take()
        .map(|stderr| std::thread::spawn(move || first_line(std::io::BufReader::new(stderr))));
    let mut reading = String::new();
    for line in std::io::BufReader::new(output).lines() {
        let Ok(line) = line else { break };
        if line != appops::END_OF_READING {
            reading.push_str(&line);
            reading.push('\n');
            continue;
        }
        *read_once = true;
        if sender.send(Some(appops::parse(&reading))).is_err() {
            // Closing the helper's input on return also tells it to exit.
            let _ = helper.kill();
            let _ = helper.wait();
            return StreamEnd::DaemonGone;
        }
        reading.clear();
    }
    let status = match helper.wait() {
        Ok(status) => status.to_string(),
        Err(e) => e.to_string(),
    };
    let said = errors
        .and_then(|reader| reader.join().ok())
        .flatten()
        .map_or_else(String::new, |line| format!(": {line}"));
    StreamEnd::Helper(anyhow::anyhow!("the helper program ended ({status}){said}"))
}

/// The first line of `text` that has anything on it, shortened, after
/// reading all of it.
fn first_line(text: impl std::io::BufRead) -> Option<String> {
    const MAX_CHARS: usize = 200;
    let mut first = None;
    for line in text.lines() {
        let Ok(line) = line else { break };
        if first.is_none() && !line.trim().is_empty() {
            first = Some(line.trim().chars().take(MAX_CHARS).collect());
        }
    }
    first
}

/// How often, and how many times, the transaction codes are asked for
/// before giving up. The first attempts fail while the system is starting.
const CATALOG_RETRY: Duration = Duration::from_secs(30);
const CATALOG_ATTEMPTS: u32 = 20;
const CATALOG_TIMEOUT: Duration = Duration::from_secs(10);

/// Look up the device's transaction codes without holding up recording.
/// A retry and its subprocess wait are both cancellable at shutdown.
pub(super) fn spawn_catalog_loader() -> tokio::task::JoinHandle<Option<Catalog>> {
    tokio::spawn(async {
        for attempt in 1..=CATALOG_ATTEMPTS {
            let error = match tokio::time::timeout(CATALOG_TIMEOUT, binder::load()).await {
                Ok(Ok(catalog)) => return Some(catalog),
                Ok(Err(e)) => e,
                Err(e) => anyhow::Error::new(e).context("reading the transaction catalog"),
            };
            if attempt == CATALOG_ATTEMPTS {
                eprintln!("bbd: warning: not recording service calls: {error:#}");
                break;
            }
            tokio::time::sleep(CATALOG_RETRY).await;
        }
        None
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_failing_helper_is_restarted_less_and_less_often() {
        let delays: Vec<u64> = (1..=10)
            .map(|failures| APPOPS_BACKOFF.delay(failures).as_secs())
            .collect();
        assert_eq!(delays, [30, 60, 120, 240, 480, 960, 1920, 3600, 3600, 3600]);
        assert_eq!(APPOPS_BACKOFF.delay(u32::MAX), Duration::from_secs(3600));
    }

    #[test]
    fn a_helper_failure_is_described_by_its_first_line() {
        let said = "\n  java.lang.IllegalStateException: no service\n\tat x.y(Z.java:1)\n";
        assert_eq!(
            first_line(said.as_bytes()).as_deref(),
            Some("java.lang.IllegalStateException: no service")
        );
        assert_eq!(first_line(&b""[..]), None);
    }
}
