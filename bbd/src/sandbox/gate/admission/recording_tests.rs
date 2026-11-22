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

use std::process::{Child, Command};

use super::{
    super::{resolver_cover, watchdog::Completion},
    tests::{keeper, keeper_with_relay, new_app_start},
    *,
};
use crate::{
    sandbox::{
        Effect, Entry, Status,
        items::{Mounts, Settings},
        tests::Fixture,
    },
    sandbox_item::{Item, Mode},
    sync::write,
};

const RESPONSE_WAIT: Duration = Duration::from_secs(2);

fn recording_queue() -> (Worker, mpsc::Receiver<Work>) {
    let (starts, _) = mpsc::sync_channel(1);
    let (_, results) = async_mpsc::unbounded_channel();
    let (recording, covers) = mpsc::sync_channel(1);
    (
        Worker {
            starts,
            results,
            recording: Some(recording),
        },
        covers,
    )
}

fn mount_policy(resolver: Resolver) -> Arc<Gated> {
    Arc::new(Gated {
        package: "com.example.app".into(),
        install_id: "install".into(),
        packages: Vec::new(),
        mounts: Mounts {
            storage: true,
            cpu_info: false,
        },
        resolver,
        health: Default::default(),
        relay_failed: Some(Arc::default()),
    })
}

fn held_start(shared: &Shared) -> (Child, Work, Done) {
    let child = Command::new("sleep").arg("30").spawn().unwrap();
    let process = Process::open(child.id() as i32).unwrap();
    process.signal(libc::SIGSTOP).unwrap();
    wait_stopped(child.id() as i32).unwrap();
    let mut event = new_app_start(child.id());
    event.kind = bb_common::GATE_REQUIRED;
    let prior = mount_policy(Resolver::None);
    write(&shared.apps).insert(event.uid, prior.clone());
    let work = Work {
        event,
        policy: Some(prior),
    };
    let done = Done {
        process: Some(process),
        held: Ok(Held::Sandboxed),
        cause: Part::MountSetupFailed,
        lost: false,
    };
    (child, work, done)
}

fn resumed(mut child: Child) -> bool {
    let running = fs::read_to_string(format!("/proc/{}/stat", child.id()))
        .ok()
        .and_then(|stat| parse_stat(&stat)?.state_and_parent())
        .is_some_and(|(state, _)| !matches!(state, 'T' | 'Z' | 'X'));
    child.kill().unwrap();
    child.wait().unwrap();
    running
}

#[test]
fn recording_toggled_on_does_not_strand_a_held_start() {
    let (_, shared, _) = keeper(Path::new("/unused"));
    let mut completion = Completion::new(shared.clone());
    let (child, work, done) = held_start(&shared);
    let mut items = Settings::default();
    items.set(Item::NetworkRecord, Some(Mode::On)).unwrap();
    let resolver = resolver_cover(&items, true, false, true);
    write(&shared.apps).insert(work.event.uid, mount_policy(resolver));

    let (worker, covers) = recording_queue();
    let (send, result) = mpsc::channel();
    std::thread::spawn(move || {
        let work = covers.recv_timeout(RESPONSE_WAIT).unwrap();
        assert_eq!(work.event.kind, GATE_RECORD);
        let relay = Serving::listening((1, 2));
        let covered = cover_recording(
            &relay,
            |serving| serving,
            |_| Ok(()),
            |_| panic!("the active cover must stay in place"),
        )
        .unwrap();
        send.send(covered).unwrap();
    });
    let finished = completion.complete_work(&worker, work, done);
    let covered = result.recv_timeout(RESPONSE_WAIT);
    assert!(resumed(child));
    finished.unwrap();
    assert!(shared.open.load(Ordering::Acquire));
    let stats = lock(&shared.stats);
    assert_eq!((stats.admitted, stats.failed), (1, 0));
    assert!(covered.unwrap());
}

struct ReadGateStatus(Arc<Shared>);

impl Effect for ReadGateStatus {
    fn name(&self) -> &'static str {
        "gate"
    }

    fn enable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        Ok(())
    }

    fn disable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        Ok(())
    }

    fn update(
        &mut self,
        _: &mut Entry,
        _: &[String],
        _: &Settings,
        _: &Settings,
    ) -> anyhow::Result<()> {
        Ok(())
    }

    fn report(&self, status: &mut Status) {
        self.0.report_gate(status);
    }
}

#[test]
fn status_command_answers_while_recording_bind_is_pending() {
    let relay = Serving::listening((1, 2));
    let (_, shared, _) = keeper_with_relay(Path::new("/unused"), relay.clone());

    let (binding, pending) = mpsc::channel();
    let (release, finish) = mpsc::channel();
    let (uncovered, removed) = mpsc::channel();
    let mount = std::thread::spawn(move || {
        cover_recording(
            &relay,
            |serving| serving,
            |_| {
                binding.send(()).unwrap();
                finish.recv().unwrap();
                Ok(())
            },
            |id| {
                uncovered.send(id).unwrap();
                Ok(())
            },
        )
        .unwrap()
    });
    pending.recv_timeout(RESPONSE_WAIT).unwrap();

    let (child, work, done) = held_start(&shared);
    let mut completion = Completion::new(shared.clone());
    let (worker, _) = recording_queue();
    let (disposed, disposition) = mpsc::channel();
    std::thread::spawn(move || {
        disposed
            .send(completion.complete_work(&worker, work, done))
            .unwrap();
    });
    let (answered, reply) = mpsc::channel();
    std::thread::spawn(move || {
        let mut f = Fixture::new("status-during-recording-bind");
        let mut sandbox = f.sandbox_with(vec![Box::new(ReadGateStatus(shared.clone()))]);
        let answer = sandbox.answer(r#"{"cmd":"sandbox.status"}"#, &f.writer, &mut f.packages);
        assert!(shared.relay.stop());
        answered.send(answer).unwrap();
    });
    let response = reply.recv_timeout(RESPONSE_WAIT);
    let finished = disposition.recv_timeout(RESPONSE_WAIT);
    let running = resumed(child);
    release.send(()).unwrap();
    finished.unwrap().unwrap();
    assert!(running);
    let response: serde_json::Value = serde_json::from_str(&response.unwrap()).unwrap();
    assert_eq!(response["ok"], true);
    assert_eq!(response["gate"], true);
    assert_eq!(response["dns_names"], true);
    assert_eq!(removed.recv_timeout(RESPONSE_WAIT).unwrap(), (1, 2));
    assert!(!mount.join().unwrap());
}
