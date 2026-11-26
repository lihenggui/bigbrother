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

use std::{
    os::unix::process::CommandExt as _,
    process::{ChildStdin, Command, Stdio},
    sync::mpsc as std_mpsc,
};

use super::*;
use crate::sandbox::{
    Effect, Entry, Resumed, Status, appops::AppOps, auto::Libraries as _, items::Settings,
};

const TOKEN: &str = "0123456789abcdef0123456789abcdef";
const ROW: &str = "10100\tcom.example.app\tandroid:camera\t101000\t-1";

fn shell_helper(script: &str) -> Child {
    Command::new("sh")
        .process_group(0)
        .args(["-c", script])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap()
}

fn resumed() -> AppOps {
    let mut appops = AppOps::new();
    appops.resume(&[Resumed {
        entry: Entry {
            uid: 10100,
            package: "com.example.app".into(),
            install_id: TOKEN.into(),
            enabled: true,
            android_id: String::new(),
            saved_ops: String::new(),
            saved_permissions: String::new(),
            created: 0,
            settings: String::new(),
        },
        packages: vec![],
        items: Settings::all_on(),
    }]);
    appops
}

#[test]
fn readiness_publishes_each_capability_in_order() {
    let bridge = Bridge::new();
    let appops = AppOps::new();
    let guard = appops.guard();
    let (_, input) = io::pipe().unwrap();
    let pipe = Pipe::new(ChildStdin::from(std::os::fd::OwnedFd::from(input))).unwrap();
    let send = |line| {
        bridge
            .0
            .receive(&pipe, Some(&*guard), &thread::current(), line)
    };
    let guard_ready = || {
        let mut status = Status::default();
        appops.report(&mut status);
        status.appops
    };
    send("ready v1 catalog");
    assert!(bridge.0.ready.borrow().catalog);
    assert!(!bridge.0.ready.borrow().usage);
    assert!(!guard_ready());
    send("ready v1 usage");
    assert!(bridge.0.ready.borrow().usage);
    assert!(!guard_ready());
    send("ready v1 guard");
    assert!(guard_ready());
}

#[test]
fn kills_a_child_that_never_announces_catalog_readiness() {
    let bridge = Bridge::new();
    let mut child = shell_helper("while IFS= read -r line; do :; done");
    let outcome = supervise_child(&bridge.0, None, &mut child, Duration::from_millis(20));
    assert!(outcome.to_string().contains("did not become ready"));
    assert!(child.try_wait().unwrap().is_some());
    assert!(!bridge.0.ready.borrow().catalog);
}

#[tokio::test]
async fn interleaved_responses_and_usage_errors_leave_guard_healthy() {
    let bridge = Bridge::new();
    let (read, input) = io::pipe().unwrap();
    let pipe = Pipe::new(ChildStdin::from(std::os::fd::OwnedFd::from(input))).unwrap();
    let appops = resumed();
    let guard = appops.guard();
    let send = |line: &str| {
        bridge
            .0
            .receive(&pipe, Some(&*guard), &thread::current(), line)
    };
    send("ready v1 catalog");
    send("ready v1 usage");
    send("ready v1 guard");
    let (lines, mut commands) = mpsc::channel(4);
    let reading = thread::spawn(move || {
        for line in BufReader::new(read).lines().map_while(Result::ok) {
            if lines.blocking_send(line).is_err() {
                break;
            }
        }
    });
    assert!(commands.recv().await.unwrap().starts_with("guard bound "));
    let route = async {
        let mut requests = [
            commands.recv().await.unwrap(),
            commands.recv().await.unwrap(),
        ];
        requests.sort_unstable();
        let catalog = requests[0].split_ascii_whitespace().nth(1).unwrap();
        let configure = requests[1].split_ascii_whitespace().nth(1).unwrap();
        send(&format!("usage {configure} ok"));
        let poll = commands.recv().await.unwrap();
        let poll = poll.split_ascii_whitespace().nth(1).unwrap();
        send(&format!("catalog {catalog} row i\t1\tmethod"));
        send(&format!("usage {poll} row {ROW}"));
        send(&format!("guard report {TOKEN} unheld 10100 -"));
        send(&format!("guard report {TOKEN} verified 10100"));
        send(&format!("usage {poll} err IllegalStateException"));
        send(&format!("catalog {catalog} end"));
        send("usage event dirty");
    };
    let (catalog, usage, ()) = tokio::join!(
        bridge.load_catalog(["i"].into_iter(), Duration::from_secs(1)),
        bridge.poll(Duration::from_secs(1)),
        route,
    );
    assert_eq!(catalog.unwrap(), "i\t1\tmethod\n");
    assert!(usage.is_err());
    assert!(bridge.dirty());
    let mut status = Status::default();
    appops.report(&mut status);
    assert!(status.appops && status.held_ops.contains_key(&10100));
    assert!(guard.watchdog().is_ok());
    guard.disconnected(false);
    bridge.0.reset();
    drop(pipe);
    reading.join().unwrap();
}

fn script(answer_kind: bool) -> String {
    let kind = if answer_kind {
        "printf 'guard app %s %s\n' \"$3\" \"$4\""
    } else {
        ":"
    };
    format!(
        r#"printf 'ready v1 catalog\nready v1 usage\nready v1 guard\n'
while IFS= read -r line; do
    set -- $line
    case "$1:$3" in
        usage:configure) printf 'usage %s ok\n' "$2" ;;
        usage:poll) printf 'usage %s row {ROW}\nusage %s end\n' "$2" "$2" ;;
        guard:com.example.app)
            printf 'guard report {TOKEN} unheld 10100 -\nguard report {TOKEN} verified 10100\n' ;;
    esac
    if [ "$1:$2" = guard:kind ]; then {kind}; fi
done"#
    )
}

#[tokio::test]
async fn guard_timeout_restarts_the_child_and_replays_both_domains() {
    let appops = resumed();
    let librarian = appops.librarian();
    let (replayed, replay) = std_mpsc::channel();
    appops.on_restart(move || replayed.send(()).unwrap());
    let (pids, pid) = std_mpsc::channel();
    let mut launches = 0;
    let (supervisor, _, mut initial) = Supervisor::start_with(
        Some(appops.guard()),
        Backoff {
            first: Duration::from_millis(10),
            ..RESTART_BACKOFF
        },
        move || {
            let child = shell_helper(&script(launches != 0));
            launches += 1;
            pids.send(child.id() as i32).unwrap();
            Ok(child)
        },
    );
    let first = pid.recv_timeout(Duration::from_secs(1)).unwrap();
    let process = crate::proc::Process::open(first).unwrap();
    let first_reading = timeout_reading(&mut initial).await;
    assert_eq!(first_reading, appops::parse(ROW));
    assert_eq!(
        librarian.is_library(
            10100,
            "com.example.app",
            Instant::now() + Duration::from_millis(40)
        ),
        None
    );
    let second = pid.recv_timeout(Duration::from_secs(2)).unwrap();
    assert_ne!(first, second);
    assert!(process.exited(1000).unwrap());
    replay.recv_timeout(Duration::from_secs(1)).unwrap();
    assert_eq!(timeout_reading(&mut initial).await, appops::parse(ROW));
    assert_eq!(
        librarian.is_library(
            10100,
            "com.example.app",
            Instant::now() + Duration::from_secs(1)
        ),
        Some(false)
    );
    let process = crate::proc::Process::open(second).unwrap();
    drop(supervisor);
    assert!(process.exited(1000).unwrap());
}

async fn timeout_reading(initial: &mut InitialUsage) -> Vec<appops::Entry> {
    tokio::time::timeout(Duration::from_secs(2), initial.recv())
        .await
        .unwrap()
        .unwrap()
        .unwrap()
}

#[tokio::test]
async fn an_explicit_guard_command_cuts_short_restart_backoff() {
    let appops = AppOps::new();
    let librarian = appops.librarian();
    let (start, launches) = std_mpsc::channel();
    let mut first_launch = true;
    let (_supervisor, _, mut initial) = Supervisor::start_with(
        Some(appops.guard()),
        Backoff {
            first: Duration::from_secs(60),
            ..RESTART_BACKOFF
        },
        move || {
            let child = shell_helper(&script(!first_launch));
            first_launch = false;
            start.send(child.id() as i32).unwrap();
            Ok(child)
        },
    );
    let first = launches.recv_timeout(Duration::from_secs(2)).unwrap();
    let process = crate::proc::Process::open(first).unwrap();
    assert_eq!(timeout_reading(&mut initial).await, appops::parse(ROW));
    assert_eq!(
        librarian.is_library(
            10100,
            "com.example.app",
            Instant::now() + Duration::from_millis(40),
        ),
        None,
    );
    assert!(process.exited(2000).unwrap());

    assert_eq!(
        librarian.is_library(
            10100,
            "com.example.app",
            Instant::now() + Duration::from_secs(2),
        ),
        Some(false),
    );
    assert_ne!(launches.try_recv().unwrap(), first);
}

#[test]
fn repeated_helper_failures_back_off_until_the_delay_caps() {
    let delays = (1..=6)
        .map(|failure| RESTART_BACKOFF.delay(failure).as_secs())
        .collect::<Vec<_>>();
    assert_eq!(delays, [10, 20, 40, 60, 60, 60]);
}

#[tokio::test]
async fn timed_out_usage_keeps_the_late_snapshot_and_limits_in_flight_commands() {
    let bridge = Bridge::new();
    let (read, input) = io::pipe().unwrap();
    let pipe = Pipe::new(ChildStdin::from(std::os::fd::OwnedFd::from(input))).unwrap();
    bridge
        .0
        .receive(&pipe, None, &thread::current(), "ready v1 catalog");
    bridge
        .0
        .receive(&pipe, None, &thread::current(), "ready v1 usage");
    let (send, mut commands) = mpsc::channel(8);
    let reading = thread::spawn(move || {
        for line in BufReader::new(read).lines().map_while(Result::ok) {
            if send.blocking_send(line).is_err() {
                break;
            }
        }
    });
    let poll = |wait| {
        let bridge = bridge.clone();
        tokio::spawn(async move { bridge.poll(wait).await })
    };
    let first = poll(Duration::from_millis(250));
    let configure = next_command(&mut commands).await;
    let (_, id, _) = command_fields(&configure);
    bridge
        .0
        .receive(&pipe, None, &thread::current(), &format!("usage {id} ok"));
    let request = next_command(&mut commands).await;
    let (domain, id, action) = command_fields(&request);
    assert_eq!((domain, action), ("usage", "poll"));
    assert!(first.await.unwrap().is_err());
    assert!(poll(Duration::from_millis(10)).await.unwrap().is_err());
    bridge.0.receive(
        &pipe,
        None,
        &thread::current(),
        &format!("usage {id} row {ROW}"),
    );
    bridge
        .0
        .receive(&pipe, None, &thread::current(), &format!("usage {id} end"));
    assert_eq!(bridge.take_late_usage(), Some(appops::parse(ROW)));
    bridge.0.reset();
    drop(pipe);
    reading.join().unwrap();
    assert!(commands.try_recv().is_err());
}

async fn next_command(commands: &mut mpsc::Receiver<String>) -> String {
    tokio::time::timeout(Duration::from_secs(1), commands.recv())
        .await
        .unwrap()
        .unwrap()
}

fn command_fields(line: &str) -> (&str, &str, &str) {
    let mut fields = line.split_ascii_whitespace();
    (
        fields.next().unwrap(),
        fields.next().unwrap(),
        fields.next().unwrap(),
    )
}
