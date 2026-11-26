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

use std::{collections::VecDeque, path::PathBuf, time::Duration};

use super::*;
use crate::{
    sandbox::{Effect, Entry, destinations::Book, items::Settings, tests::Fixture},
    test_sync::{Rc, RefCell},
};
use rusqlite::Connection;

const UID: u32 = 10100;
const NAME: &str = "domain:example.com";

#[derive(Default)]
struct RefreshState {
    failures: VecDeque<bool>,
    attempted: Vec<bool>,
    blocked: bool,
}

struct NetworkEffect {
    db: PathBuf,
    book: Book,
    state: Rc<RefCell<RefreshState>>,
}

impl Effect for NetworkEffect {
    fn name(&self) -> &'static str {
        "network witness"
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

    fn refresh(&mut self, _: &Entry, _: &Settings) -> anyhow::Result<()> {
        // A separate connection must be able to take the write lock while
        // the sandbox is applying or restoring rules.
        let db = Connection::open(&self.db)?;
        db.busy_timeout(Duration::ZERO)?;
        db.execute_batch("BEGIN IMMEDIATE; ROLLBACK;")?;
        let wanted = lock(&self.book)
            .get(&UID)
            .is_some_and(|rules| !rules.is_empty());
        let mut state = self.state.borrow_mut();
        state.attempted.push(wanted);
        anyhow::ensure!(
            state.failures.pop_front() != Some(true),
            "injected refresh failure"
        );
        state.blocked = wanted;
        Ok(())
    }
}

fn sandbox_with_network(f: &mut Fixture) -> (Sandbox, Rc<RefCell<RefreshState>>) {
    let mut sandbox = f.sandbox();
    let state = Rc::new(RefCell::new(RefreshState::default()));
    sandbox.add_effect(Box::new(NetworkEffect {
        db: f.dir.join("bb.db"),
        book: sandbox.book(),
        state: state.clone(),
    }));
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 0)
        .unwrap();
    (sandbox, state)
}

fn block(sandbox: &mut Sandbox, writer: &Writer) -> anyhow::Result<()> {
    sandbox.change_net_rule(
        writer,
        UID,
        &Target::parse(NAME).unwrap(),
        RuleChange::Block {
            created: 7,
            manual: true,
        },
    )
}

fn unblock(sandbox: &mut Sandbox, writer: &Writer) -> anyhow::Result<()> {
    sandbox.change_net_rule(
        writer,
        UID,
        &Target::parse(NAME).unwrap(),
        RuleChange::Unblock,
    )
}

#[test]
fn block_and_unblock_apply_outside_the_database_write_lock() {
    let mut f = Fixture::new("rule-write-lock");
    let (mut sandbox, state) = sandbox_with_network(&mut f);
    block(&mut sandbox, &f.writer).unwrap();
    assert!(state.borrow().blocked);
    let rules = f.writer.net_rules().unwrap();
    assert_eq!(rules.len(), 1);
    assert_eq!(rules[0].target, NAME);
    assert_eq!(rules[0].created, 7);
    assert!(rules[0].manual);
    unblock(&mut sandbox, &f.writer).unwrap();
    assert!(f.writer.net_rules().unwrap().is_empty());
    assert!(!state.borrow().blocked);
    assert_eq!(state.borrow().attempted, [true, false]);
}

#[test]
fn an_apply_failure_restores_the_previous_rule_book_and_leaves_disk_unchanged() {
    let mut f = Fixture::new("rule-apply-rollback");
    let (mut sandbox, state) = sandbox_with_network(&mut f);
    state.borrow_mut().failures.push_back(true);
    let error = block(&mut sandbox, &f.writer).unwrap_err();
    assert!(format!("{error:#}").contains("injected refresh failure"));
    assert!(f.writer.net_rules().unwrap().is_empty());
    assert_eq!(state.borrow().attempted, [true, false]);
    assert!(!state.borrow().blocked);
    assert!(
        lock(&sandbox.book())
            .get(&UID)
            .is_none_or(|rules| rules.is_empty())
    );
    assert!(sandbox.pending_net_refresh.is_empty());
}

#[test]
fn failed_restoration_retries_the_committed_policy_until_it_succeeds() {
    let mut f = Fixture::new("rule-refresh-retry");
    let (mut sandbox, state) = sandbox_with_network(&mut f);
    block(&mut sandbox, &f.writer).unwrap();
    let existing = f.writer.net_rules().unwrap();
    let db = Connection::open(f.dir.join("bb.db")).unwrap();
    db.execute_batch(
        "CREATE TRIGGER reject_unblock BEFORE DELETE ON net_rule
         BEGIN SELECT RAISE(ABORT, 'injected commit failure'); END;",
    )
    .unwrap();
    state.borrow_mut().failures.extend([false, true, true]);
    let error = unblock(&mut sandbox, &f.writer).unwrap_err();
    assert!(format!("{error:#}").contains("injected commit failure"));
    assert!(!state.borrow().blocked);
    assert_eq!(f.writer.net_rules().unwrap(), existing);
    assert_eq!(sandbox.pending_net_refresh, [UID]);

    sandbox.retry_net_refresh();
    assert_eq!(sandbox.pending_net_refresh, [UID]);
    sandbox.retry_net_refresh();
    assert!(state.borrow().blocked);
    assert!(sandbox.pending_net_refresh.is_empty());
    sandbox.retry_net_refresh();
    assert_eq!(state.borrow().attempted, [true, false, true, true, true]);
}
