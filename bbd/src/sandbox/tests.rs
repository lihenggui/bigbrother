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

use std::{collections::HashMap, path::PathBuf};

use crate::{
    packages::Packages,
    store::Writer,
    test_sync::{Rc, RefCell},
};

use super::{Effect, Entry, Resumed, Sandbox, Status, items::Settings};

pub(super) struct Fixture {
    pub(super) dir: PathBuf,
    pub(super) writer: Writer,
    pub(super) packages: Packages,
}

impl Fixture {
    pub(super) fn new(name: &str) -> Self {
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

    pub(super) fn sandbox(&self) -> Sandbox {
        self.sandbox_with(Vec::new())
    }

    pub(super) fn sandbox_with(&self, effects: Vec<Box<dyn Effect>>) -> Sandbox {
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
pub(super) struct Recorder {
    name: &'static str,
    log: Rc<RefCell<Vec<String>>>,
    pub(super) fail_enable: bool,
    pub(super) fail_disable: bool,
    pub(super) fail_update: bool,
}

impl Recorder {
    pub(super) fn new(name: &'static str, log: &Rc<RefCell<Vec<String>>>) -> Box<Self> {
        Box::new(Self {
            name,
            log: log.clone(),
            fail_enable: false,
            fail_disable: false,
            fail_update: false,
        })
    }
}

impl Effect for Recorder {
    fn name(&self) -> &'static str {
        self.name
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        anyhow::ensure!(!self.fail_enable, "refused");
        self.log.borrow_mut().push(format!("+{}", self.name));
        entry.saved_ops = format!("{}=1", self.name);
        Ok(())
    }

    fn disable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        anyhow::ensure!(!self.fail_disable, "refused");
        self.log.borrow_mut().push(format!("-{}", self.name));
        Ok(())
    }

    fn update(
        &mut self,
        _: &mut Entry,
        _: &[String],
        _: &Settings,
        _: &Settings,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(!self.fail_update, "refused");
        self.log.borrow_mut().push(format!("*{}", self.name));
        Ok(())
    }

    fn resume(&mut self, apps: &[Resumed]) {
        for app in apps {
            self.log
                .borrow_mut()
                .push(format!("~{} {}", self.name, app.entry.uid));
        }
    }

    fn report(&self, status: &mut Status) {
        status.gate = true;
        status.gated_uids.insert(10100);
    }

    /// Finds every entry's permission granted.
    fn found(&mut self, entries: &mut HashMap<u32, Entry>) -> Vec<u32> {
        let mut changed = Vec::new();
        let granted = format!("{}=granted", self.name);
        for entry in entries.values_mut() {
            if entry.saved_permissions != granted {
                entry.saved_permissions.clone_from(&granted);
                changed.push(entry.uid);
            }
        }
        changed
    }
}

pub(super) fn enabled(sandbox: &Sandbox) -> Vec<u32> {
    let mut uids: Vec<u32> = sandbox.snapshot_read().keys().copied().collect();
    uids.sort();
    uids
}
