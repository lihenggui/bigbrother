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

use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use super::*;
use crate::sandbox::{Effect, Entry, items::Settings};

struct RecordingGate(Arc<AtomicUsize>);

impl Effect for RecordingGate {
    fn name(&self) -> &'static str {
        "recording gate"
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

    fn relay_stopped(&mut self) {
        self.0.fetch_add(1, Ordering::Relaxed);
    }
}

#[test]
fn an_idle_sandbox_has_no_timer_to_arm() {
    let writer = Writer::open(Path::new(":memory:")).unwrap();
    let mut sandbox = Sandbox::load(&writer, Vec::new(), None).unwrap();
    assert_eq!(
        maintenance_deadline(&mut sandbox, Instant::now(), 100),
        None
    );
}

#[test]
fn a_pending_appops_recovery_arms_the_timer_at_startup() {
    let writer = Writer::open(Path::new(":memory:")).unwrap();
    writer
        .stage_appops(&crate::store::AppOpsUndo {
            uid: 10100,
            package: "com.example.app".into(),
            install_id: "installation".into(),
            preimage: "android:camera=default".into(),
            attempts: 0,
        })
        .unwrap();
    let mut sandbox = Sandbox::load(&writer, Vec::new(), None).unwrap();
    sandbox.resume(&writer, &mut Packages::new("/dev/null"));
    let now = Instant::now();
    let scheduled = maintenance_deadline(&mut sandbox, now, 100);
    assert_eq!(scheduled, sandbox.next_appops_recovery());
    assert!(scheduled.is_some_and(|due| due <= now));
}

struct ExpiringGate(Option<i64>);

impl Effect for ExpiringGate {
    fn name(&self) -> &'static str {
        "expiring gate"
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

    fn next_network_expiry(&self, at: Instant, wall: i64) -> Option<Instant> {
        self.0
            .map(|expiry| at + Duration::from_secs((expiry - wall).max(0) as u64))
    }

    fn expire_network(&mut self, _: &sandbox::Snapshot, _: Instant, _: i64) {
        self.0 = None;
    }
}

#[test]
fn a_network_expiry_arms_only_until_the_address_expires() {
    let writer = Writer::open(Path::new(":memory:")).unwrap();
    let mut sandbox =
        Sandbox::load(&writer, vec![Box::new(ExpiringGate(Some(102)))], None).unwrap();
    let now = Instant::now();
    let deadline = now + Duration::from_secs(2);
    assert_eq!(maintenance_deadline(&mut sandbox, now, 100), Some(deadline));
    sandbox.run_network_work(now, 100);
    assert_eq!(maintenance_deadline(&mut sandbox, now, 100), Some(deadline));
    sandbox.run_network_work(deadline, 102);
    assert_eq!(maintenance_deadline(&mut sandbox, deadline, 102), None);
}

#[test]
fn relay_loss_and_shutdown_start_the_recording_cover_cleanup_once() {
    let writer = Writer::open(Path::new(":memory:")).unwrap();
    let cleanups = Arc::new(AtomicUsize::new(0));
    let live = || LiveSandbox {
        sandbox: Sandbox::load(
            &writer,
            vec![Box::new(RecordingGate(cleanups.clone()))],
            None,
        )
        .unwrap(),
        relay: Serving::default(),
        recording_covers: true,
    };

    let mut after_loss = live();
    stop_relay(&mut after_loss, &writer);
    assert_eq!(cleanups.load(Ordering::Relaxed), 1);
    drop(after_loss);
    assert_eq!(cleanups.load(Ordering::Relaxed), 1);

    drop(live());
    assert_eq!(cleanups.load(Ordering::Relaxed), 2);
}
