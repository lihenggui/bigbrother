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

//! The network kept from a sandboxed app.
//!
//! The kernel's packet filter refuses what the sockets of the app's uid
//! send, for IPv4 and IPv6 alike. The `OUTPUT` chain jumps first to a chain
//! of the sandbox's own, whose one rule asks an eBPF program
//! (`network_refused` in bb-ebpf, pinned for the rule to name) whether the
//! packet's socket belongs to a uid listed in a map, and has a match
//! refused: with a TCP reset, or an ICMP error for anything else, so that
//! a connection fails at once rather than timing out. What the app sends
//! to the device itself, over the loopback interface, is left alone: apps
//! talk to their own processes that way.
//!
//! The uid of a socket is that of the process that made it, whichever
//! process sends on it later, and the filter is asked for every packet: a
//! change takes effect on the connections the app already has, and none of
//! its processes needs to be ended.
//!
//! One rule and one map lookup, whatever the number of apps: a rule for
//! each uid would be gone through, for every packet of every app, up to
//! the last. With no app kept from the network the chains and the jump
//! are removed, and the packets of the device pass no rule of the sandbox.
//! Taking the network from an app, or giving it back, changes the map
//! only; the rules are written when the first app is listed and removed
//! with the last.
//!
//! The rules stay when the daemon stops or dies, with the program and the
//! map they hold on to, and the daemon that starts next has them name its
//! own program, with the uids of the database. They are not kept across
//! a restart of the device: until the daemon has started, an app has the
//! network.
//!
//! netd, which owns the other chains `OUTPUT` jumps to, adds and removes
//! only its own jumps, also when it starts again.
//!
//! The rules are about the app's own sockets. A name it looks up through
//! the system's resolver is looked up by netd, on a socket of netd's own
//! uid, which they do not match: the resolver is taken from the app with
//! the network, and given back with it.

use std::{
    cell::Cell,
    collections::BTreeSet,
    io::Write as _,
    path::Path,
    process::{Command, Stdio},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
};

use anyhow::Context as _;
use aya::{
    maps::{HashMap as BpfHashMap, MapData, MapError},
    programs::SocketFilter,
};

use super::{Effect, Entry, Resumed, Status, items::Settings};
use crate::log::{Limited, Policy, SANDBOX_INTERVAL};

/// The sandbox's chain in the `filter` table, which `OUTPUT` jumps to.
const CHAIN: &str = "bb_sandbox";

/// The chain a matched packet is refused in.
const REFUSE: &str = "bb_refuse";

/// Where the program is pinned for the rule to name it.
const PIN: &str = "/sys/fs/bpf/bb_network";

/// Where the system keeps the tools of the packet filter.
const TOOLS: &str = "/system/bin";

/// How long a tool waits for the lock on the tables, in seconds, which
/// netd holds while it changes its own rules. Commands run on the main
/// loop: this bounds what writing the rules can hold it up for.
const LOCK_WAIT: &str = "2";

/// What the tools exit with when the chain or rule asked about is not
/// there. (A rule that names a chain that is not there is another error,
/// the one of a command that cannot be read.)
const NOT_THERE: i32 = 1;

/// The two address families, each with tables of its own.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Family {
    V4,
    V6,
}

impl Family {
    const BOTH: [Family; 2] = [Family::V4, Family::V6];

    /// The tool that changes single rules of the family.
    fn tool(self) -> &'static str {
        match self {
            Family::V4 => "iptables",
            Family::V6 => "ip6tables",
        }
    }

    /// The tool that replaces whole chains of the family.
    fn restore(self) -> &'static str {
        match self {
            Family::V4 => "iptables-restore",
            Family::V6 => "ip6tables-restore",
        }
    }
}

/// Runs the tools of the packet filter.
pub trait Tools: Send {
    fn available(&self, _family: Family) -> anyhow::Result<bool> {
        Ok(true)
    }

    /// Run `tool` with `args`, giving it `input`; what it exited with.
    fn run(&self, tool: &str, args: &[&str], input: &str) -> anyhow::Result<i32>;
}

/// The tools of the device.
struct System;

impl Tools for System {
    fn available(&self, family: Family) -> anyhow::Result<bool> {
        Ok(Path::new(TOOLS).join(family.tool()).try_exists()?)
    }

    fn run(&self, tool: &str, args: &[&str], input: &str) -> anyhow::Result<i32> {
        let mut child = Command::new(Path::new(TOOLS).join(tool))
            .args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .with_context(|| format!("running {tool}"))?;
        // Dropped once written, so that the tool sees the end of its input.
        if let Some(mut stdin) = child.stdin.take() {
            // A tool that reads no input may have ended already.
            let _ = stdin.write_all(input.as_bytes());
        }
        let status = child
            .wait()
            .with_context(|| format!("waiting for {tool}"))?;
        // Ended by a signal: not an answer.
        status
            .code()
            .with_context(|| format!("{tool} did not finish"))
    }
}

/// The uids the program refuses.
pub trait Listed: Send {
    fn insert(&mut self, uid: u32) -> anyhow::Result<()>;
    fn contains(&self, uid: u32) -> anyhow::Result<bool>;
    /// Does nothing for a uid that is not listed.
    fn remove(&mut self, uid: u32) -> anyhow::Result<()>;
}

impl Listed for BpfHashMap<MapData, u32, u32> {
    fn insert(&mut self, uid: u32) -> anyhow::Result<()> {
        BpfHashMap::insert(self, &uid, &1, 0).context("listing the uid")
    }

    fn contains(&self, uid: u32) -> anyhow::Result<bool> {
        match self.get(&uid, 0) {
            Ok(_) => Ok(true),
            Err(MapError::KeyNotFound) => Ok(false),
            Err(e) => Err(e).context("reading the uid list"),
        }
    }

    fn remove(&mut self, uid: u32) -> anyhow::Result<()> {
        // Only a uid known not to be listed is left at that: any other
        // failure to look it up may be of one that is.
        if matches!(self.get(&uid, 0), Err(MapError::KeyNotFound)) {
            return Ok(());
        }
        BpfHashMap::remove(self, &uid).context("taking the uid off the list")
    }
}

/// The sandbox's chains, as `iptables-restore` reads them. A chain is
/// named before its rules, which empties it: with `--noflush` the chains
/// not named are left as they are.
fn chains() -> String {
    format!(
        "*filter\n\
         :{CHAIN} - [0:0]\n\
         :{REFUSE} - [0:0]\n\
         -A {CHAIN} -o lo -j RETURN\n\
         -A {CHAIN} -m bpf --object-pinned {PIN} -j {REFUSE}\n\
         -A {REFUSE} -p tcp -j REJECT --reject-with tcp-reset\n\
         -A {REFUSE} -j REJECT\n\
         COMMIT\n"
    )
}

/// Run `tool` on one rule or chain.
fn single(tools: &dyn Tools, tool: &str, action: &[&str]) -> anyhow::Result<i32> {
    let args: Vec<&str> = ["-w", LOCK_WAIT].iter().chain(action).copied().collect();
    tools.run(tool, &args, "")
}

/// Write the chains of `family` and have `OUTPUT` jump to them. Chains
/// that are there are replaced in one step, and from then on name the
/// program pinned now.
fn install(tools: &dyn Tools, family: Family) -> anyhow::Result<()> {
    let (tool, restore) = (family.tool(), family.restore());
    let code = tools.run(restore, &["--noflush", "-w", LOCK_WAIT], &chains())?;
    anyhow::ensure!(code == 0, "{restore} failed ({code})");
    // First in `OUTPUT`, ahead of netd's chains.
    match single(tools, tool, &["-C", "OUTPUT", "-j", CHAIN])? {
        0 => Ok(()),
        NOT_THERE => {
            let code = single(tools, tool, &["-I", "OUTPUT", "-j", CHAIN])?;
            anyhow::ensure!(code == 0, "{tool} -I failed ({code})");
            Ok(())
        }
        code => anyhow::bail!("{tool} -C failed ({code})"),
    }
}

/// Remove the jump before touching either chain, including after a partial
/// install or an interrupted removal.
fn uninstall(tools: &dyn Tools, family: Family) -> anyhow::Result<()> {
    let tool = family.tool();
    if chain_exists(tools, tool, CHAIN)? {
        // Android's firewall managers can preserve duplicate jumps.
        let mut removed = false;
        for _ in 0..64 {
            match single(tools, tool, &["-D", "OUTPUT", "-j", CHAIN])? {
                0 => {}
                NOT_THERE => {
                    removed = true;
                    break;
                }
                code => anyhow::bail!("{tool} -D failed ({code})"),
            }
        }
        anyhow::ensure!(removed, "{tool} still has sandbox jumps");
        remove_chain(tools, tool, CHAIN)?;
    }
    if chain_exists(tools, tool, REFUSE)? {
        remove_chain(tools, tool, REFUSE)?;
    }
    Ok(())
}

fn chain_exists(tools: &dyn Tools, tool: &str, chain: &str) -> anyhow::Result<bool> {
    match single(tools, tool, &["-S", chain])? {
        0 => Ok(true),
        NOT_THERE => Ok(false),
        code => anyhow::bail!("{tool} -S failed ({code})"),
    }
}

fn remove_chain(tools: &dyn Tools, tool: &str, chain: &str) -> anyhow::Result<()> {
    for action in ["-F", "-X"] {
        let code = single(tools, tool, &[action, chain])?;
        anyhow::ensure!(code == 0, "{tool} {action} failed ({code})");
    }
    Ok(())
}

pub fn remove_filter() -> anyhow::Result<()> {
    uninstall_all(&System)?;
    match std::fs::remove_file(PIN) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => {
            Err(e).context("removing the pinned packet filter")
        }
        _ => Ok(()),
    }
}

fn uninstall_all(tools: &dyn Tools) -> anyhow::Result<()> {
    both(|family| {
        if tools.available(family)? {
            uninstall(tools, family)?;
        }
        Ok(())
    })
}

fn both(mut apply: impl FnMut(Family) -> anyhow::Result<()>) -> anyhow::Result<()> {
    let v4 = apply(Family::V4);
    let v6 = apply(Family::V6);
    v4.and(v6)
}

/// What is known of the sandbox's rules in the kernel.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Rules {
    /// Not written by this daemon yet, or not as meant.
    Unknown,
    Absent,
    Present,
}

/// The effect that keeps the apps with the network item on from the
/// network.
pub struct Network {
    tools: Box<dyn Tools>,
    listed: Box<dyn Listed>,
    rollback_errors: Limited,
    /// The uids the saved policy says to refuse.
    refused: BTreeSet<u32>,
    rules: Rules,
    repair: Cell<bool>,
    pending_map: BTreeSet<u32>,
    /// Whether the apps sandboxed when the daemon started have been taken
    /// up again ([`Effect::resume`]). Until then the rules are left as the
    /// daemon before wrote them, with the uids it listed: written for the
    /// few this one has listed so far, or removed, they would let the
    /// others through.
    resumed: bool,
    /// Destination rules need the packet filter even when every app has
    /// the network item off.
    destinations: Arc<AtomicBool>,
}

impl Network {
    /// Load the program, pin it in place of the one a daemon before this
    /// one pinned, and take its map. The rules that daemon left go on
    /// naming its program, with the uids it listed, until they are
    /// written again ([`Effect::resume`]).
    pub fn start(ebpf: &mut aya::Ebpf, destinations: Arc<AtomicBool>) -> anyhow::Result<Self> {
        anyhow::ensure!(
            Family::BOTH
                .iter()
                .all(|family| Path::new(TOOLS).join(family.restore()).exists()),
            "this device has no packet filter tools"
        );
        let program: &mut SocketFilter = ebpf
            .program_mut("network_refused")
            .context("program network_refused missing from object")?
            .try_into()?;
        program
            .load()
            .context("verifier rejected network_refused")?;
        match std::fs::remove_file(PIN) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => {
                return Err(e).context("removing the program pinned before");
            }
            _ => {}
        }
        program.pin(PIN).context("pinning network_refused")?;
        let map = ebpf
            .take_map("NETWORK_REFUSED")
            .context("NETWORK_REFUSED map missing")?;
        let listed: BpfHashMap<MapData, u32, u32> = BpfHashMap::try_from(map)?;
        Ok(Self::with(Box::new(System), Box::new(listed), destinations))
    }

    fn with(tools: Box<dyn Tools>, listed: Box<dyn Listed>, destinations: Arc<AtomicBool>) -> Self {
        Self {
            tools,
            listed,
            rollback_errors: Limited::new(SANDBOX_INTERVAL, Policy::Shared),
            refused: BTreeSet::new(),
            rules: Rules::Unknown,
            repair: Cell::new(false),
            pending_map: BTreeSet::new(),
            resumed: false,
            destinations,
        }
    }

    /// Whether the rules are needed: an app is kept from the network, or
    /// from a destination.
    fn wanted(&self) -> bool {
        !self.refused.is_empty() || self.destinations.load(Ordering::Relaxed)
    }

    /// Have the rules there or gone as they are needed now, once the apps
    /// have been taken up again.
    fn settle(&mut self) -> anyhow::Result<()> {
        if self.resumed {
            self.write_rules(self.wanted())?;
        }
        Ok(())
    }

    /// Have the rules there if `wanted`, and gone if not, for both
    /// families. A failure leaves them unknown.
    fn write_rules(&mut self, wanted: bool) -> anyhow::Result<()> {
        let as_wanted = if wanted {
            Rules::Present
        } else {
            Rules::Absent
        };
        if self.rules == as_wanted && !self.repair.get() {
            return Ok(());
        }
        self.rules = Rules::Unknown;
        self.repair.set(true);
        both(|family| {
            if wanted {
                install(self.tools.as_ref(), family)
            } else {
                uninstall(self.tools.as_ref(), family)
            }
        })?;
        self.rules = as_wanted;
        self.repair.set(false);
        Ok(())
    }

    /// Refuse `uid` the network from now on.
    fn refuse(&mut self, uid: u32) -> anyhow::Result<()> {
        self.listed.insert(uid)?;
        if self.resumed
            && let Err(e) = self.write_rules(true)
        {
            if !self.refused.contains(&uid)
                && let Err(rollback) = self.listed.remove(uid)
            {
                self.pending_map.insert(uid);
                self.rollback_errors.log(format_args!(
                    "bbd: sandbox: cannot roll back a network map entry: {rollback:#}"
                ));
            }
            return Err(e);
        }
        self.refused.insert(uid);
        self.pending_map.remove(&uid);
        Ok(())
    }

    /// A previous generation's rules may refer to a map we cannot change.
    fn allow(&mut self, uid: u32) -> anyhow::Result<()> {
        self.listed.remove(uid)?;
        let was_refused = self.refused.remove(&uid);
        if let Err(e) = self.settle() {
            if was_refused {
                // The enclosing settings change failed and keeps its old policy.
                self.refused.insert(uid);
                if self.listed.insert(uid).is_err() {
                    self.pending_map.insert(uid);
                }
            }
            return Err(e);
        }
        self.pending_map.remove(&uid);
        Ok(())
    }

    fn retry(&mut self) -> anyhow::Result<()> {
        for uid in std::mem::take(&mut self.pending_map) {
            let result = if self.refused.contains(&uid) {
                self.listed.insert(uid)
            } else {
                self.listed.remove(uid)
            };
            if result.is_err() {
                self.pending_map.insert(uid);
            }
        }
        self.settle()
    }
}

impl Effect for Network {
    fn name(&self) -> &'static str {
        "network"
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        if items.network_refused() {
            self.refuse(entry.uid)?;
        } else if self.refused.contains(&entry.uid) || self.pending_map.contains(&entry.uid) {
            // Retry a removal that failed while retiring the previous
            // installation before admitting its successor.
            self.allow(entry.uid)?;
        }
        // An app kept from single destinations needs the rules too.
        self.settle()
    }

    fn refresh(&mut self, _: &Entry, _: &Settings) -> anyhow::Result<()> {
        self.settle()
    }

    fn disable(&mut self, entry: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        self.allow(entry.uid)
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        _: &[String],
        before: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        match (before.network_refused(), after.network_refused()) {
            (false, true) => self.refuse(entry.uid),
            (true, false) => self.allow(entry.uid),
            _ => self.settle(),
        }
    }

    fn resume(&mut self, apps: &[Resumed]) {
        self.resumed = true;
        for app in apps.iter().filter(|app| app.items.network_refused()) {
            let uid = app.entry.uid;
            self.refused.insert(uid);
            if let Err(e) = self.listed.insert(uid) {
                self.pending_map.insert(uid);
                eprintln!("bbd: warning: uid {uid} has the network: {e:#}");
            } else {
                self.pending_map.remove(&uid);
            }
        }
        // Written also where they are: the rules of the daemon before
        // name its program. With no app listed, what it left is removed.
        if let Err(e) = self.write_rules(self.wanted()) {
            eprintln!(
                "bbd: warning: {} sandboxed apps may have the network: {e:#}",
                self.refused.len()
            );
        }
    }

    fn forget(&mut self, uid: u32) {
        self.refused.remove(&uid);
        self.pending_map.remove(&uid);
        if let Err(e) = self.listed.remove(uid) {
            self.pending_map.insert(uid);
            eprintln!("bbd: sandbox: cannot clear the retired uid {uid}'s network rule: {e:#}");
        }
        // The destinations it was kept from went before this: without any
        // left, the rules go too.
        if let Err(e) = self.settle() {
            eprintln!("bbd: warning: the packet filter's rules are not as meant: {e:#}");
        }
    }

    fn retry_network(&mut self, _: &super::Snapshot) {
        if let Err(e) = self.retry() {
            self.rollback_errors.log(format_args!(
                "bbd: sandbox: cannot reconcile the packet filter: {e:#}"
            ));
        }
    }

    fn report(&self, status: &mut Status) {
        status.network = match self.rules {
            Rules::Absent => true,
            Rules::Unknown => false,
            Rules::Present => {
                let intact = Family::BOTH.into_iter().all(|family| {
                    matches!(
                        single(
                            self.tools.as_ref(),
                            family.tool(),
                            &["-C", "OUTPUT", "-j", CHAIN]
                        ),
                        Ok(0)
                    )
                });
                self.repair.set(!intact);
                intact
            }
        };
        if self.rules == Rules::Present && status.network {
            status.network_uids.extend(
                self.refused
                    .iter()
                    .copied()
                    .filter(|uid| matches!(self.listed.contains(*uid), Ok(true))),
            );
        }
    }
}

/// Stands in for [`Network`] where the program cannot be loaded or the
/// device has no tools for the packet filter: no app can be kept from the
/// network there.
pub struct Unavailable {
    tools: Box<dyn Tools>,
    cleanup_pending: bool,
    cleanup_errors: Limited,
}

impl Unavailable {
    pub fn new() -> Self {
        Self {
            tools: Box::new(System),
            cleanup_pending: true,
            cleanup_errors: Limited::new(SANDBOX_INTERVAL, Policy::Shared),
        }
    }
}

impl Effect for Unavailable {
    fn name(&self) -> &'static str {
        "network"
    }

    fn enable(&mut self, _: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        anyhow::ensure!(
            !items.network_refused(),
            "the packet filter is not available; the network cannot be sandboxed"
        );
        Ok(())
    }

    fn disable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        self.cleanup()
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        packages: &[String],
        before: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        // Only turning the item on is refused: an app that had it on when
        // the packet filter went can still have its other items changed.
        match (before.network_refused(), after.network_refused()) {
            (true, false) => self.cleanup(),
            (true, true) => Ok(()),
            _ => self.enable(entry, packages, after),
        }
    }

    fn resume(&mut self, apps: &[Resumed]) {
        // What a daemon before left would go on refusing the apps it
        // listed, with no way to give one the network back.
        self.retry_cleanup();
        let refused = apps
            .iter()
            .filter(|app| app.items.network_refused())
            .count();
        if refused > 0 {
            eprintln!(
                "bbd: warning: {refused} sandboxed apps have the network: the packet filter is not available"
            );
        }
    }
    fn retry_network(&mut self, _: &super::Snapshot) {
        self.retry_cleanup();
    }
}

impl Unavailable {
    fn retry_cleanup(&mut self) {
        if let Err(e) = self.cleanup() {
            self.cleanup_errors.log(format_args!(
                "bbd: warning: the packet filter's rules were not removed: {e:#}"
            ));
        }
    }

    fn cleanup(&mut self) -> anyhow::Result<()> {
        if self.cleanup_pending {
            uninstall_all(self.tools.as_ref())?;
            self.cleanup_pending = false;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashSet;

    use crate::test_sync::{Rc, RefCell};

    use super::*;
    use crate::sandbox_item::{Item, Mode};

    /// The kernel: the uids listed, and the tables of both families.
    #[derive(Default)]
    struct Kernel {
        listed: BTreeSet<u32>,
        /// The chains there are, as (family, chain).
        chains: HashSet<(&'static str, String)>,
        /// The families whose `OUTPUT` jumps to the sandbox's chain.
        jumps: Vec<&'static str>,
        /// The tools that fail, by name.
        failing: Vec<&'static str>,
        /// Whether the list cannot be changed.
        list_fails: bool,
        fail_insert: Option<u32>,
        runs: u32,
        /// Whether an app is kept from single destinations.
        destinations: Arc<AtomicBool>,
    }

    #[derive(Clone, Default)]
    struct Fake(Rc<RefCell<Kernel>>);

    impl Tools for Fake {
        fn run(&self, tool: &str, args: &[&str], input: &str) -> anyhow::Result<i32> {
            let mut kernel = self.0.borrow_mut();
            kernel.runs += 1;
            if kernel.failing.contains(&tool) {
                return Ok(4);
            }
            let family = if tool.starts_with("ip6") { "v6" } else { "v4" };
            if tool.ends_with("-restore") {
                assert_eq!(args, ["--noflush", "-w", LOCK_WAIT]);
                assert_eq!(input, chains());
                for chain in [CHAIN, REFUSE] {
                    kernel.chains.insert((family, chain.to_string()));
                }
                return Ok(0);
            }
            assert_eq!(&args[..2], ["-w", LOCK_WAIT]);
            let has =
                |kernel: &Kernel, chain: &str| kernel.chains.contains(&(family, chain.to_string()));
            let jumps = kernel.jumps.iter().filter(|j| **j == family).count();
            if matches!(args[2], "-C" | "-I" | "-D") {
                assert_eq!(&args[3..], ["OUTPUT", "-j", CHAIN]);
                // A rule that jumps to a chain that is not there cannot be
                // read.
                if !has(&kernel, CHAIN) {
                    return Ok(2);
                }
                return Ok(match args[2] {
                    "-C" if jumps > 0 => 0,
                    "-I" => {
                        kernel.jumps.push(family);
                        0
                    }
                    "-D" if jumps > 0 => {
                        let at = kernel.jumps.iter().position(|j| *j == family).unwrap();
                        kernel.jumps.remove(at);
                        0
                    }
                    _ => NOT_THERE,
                });
            }
            let chain = args[3];
            if !has(&kernel, chain) {
                return Ok(NOT_THERE);
            }
            if args[2] == "-X" {
                // A chain another still jumps to cannot be removed.
                if chain == REFUSE && has(&kernel, CHAIN) {
                    return Ok(4);
                }
                kernel.chains.remove(&(family, chain.to_string()));
            }
            Ok(0)
        }
    }

    impl Listed for Fake {
        fn insert(&mut self, uid: u32) -> anyhow::Result<()> {
            let mut kernel = self.0.borrow_mut();
            anyhow::ensure!(
                !kernel.list_fails && kernel.fail_insert != Some(uid),
                "the list is full"
            );
            kernel.listed.insert(uid);
            Ok(())
        }

        fn contains(&self, uid: u32) -> anyhow::Result<bool> {
            Ok(self.0.borrow().listed.contains(&uid))
        }

        fn remove(&mut self, uid: u32) -> anyhow::Result<()> {
            let mut kernel = self.0.borrow_mut();
            anyhow::ensure!(!kernel.list_fails, "the list cannot be changed");
            kernel.listed.remove(&uid);
            Ok(())
        }
    }

    impl Fake {
        /// A daemon that has just started.
        fn network(&self) -> Network {
            Network::with(
                Box::new(self.clone()),
                Box::new(self.clone()),
                self.0.borrow().destinations.clone(),
            )
        }

        /// One that found no app sandboxed when it started.
        fn started(&self) -> Network {
            let mut network = self.network();
            network.resume(&[]);
            network
        }

        /// Whether both families have the chains, each jumped to once.
        fn has_rules(&self) -> bool {
            let kernel = self.0.borrow();
            let whole = |family: &'static str| {
                let chains = [CHAIN, REFUSE]
                    .iter()
                    .filter(|chain| kernel.chains.contains(&(family, chain.to_string())))
                    .count();
                let jumps = kernel.jumps.iter().filter(|j| **j == family).count();
                assert!(
                    (chains, jumps) == (2, 1) || (chains, jumps) == (0, 0),
                    "{family}: {chains} chains, {jumps} jumps"
                );
                chains == 2
            };
            let (v4, v6) = (whole("v4"), whole("v6"));
            assert_eq!(v4, v6);
            v4
        }

        /// The uids the kernel refuses: those listed, with the rules there.
        fn refused(&self) -> Vec<u32> {
            if !self.has_rules() {
                return Vec::new();
            }
            self.0.borrow().listed.iter().copied().collect()
        }

        fn runs(&self) -> u32 {
            self.0.borrow().runs
        }
    }

    fn entry(uid: u32) -> Entry {
        Entry {
            uid,
            package: format!("com.example.app{uid}"),
            enabled: true,
            android_id: String::new(),
            saved_ops: String::new(),
            saved_permissions: String::new(),
            created: 0,
            settings: String::new(),
            install_id: String::new(),
        }
    }

    fn items(network: Mode) -> Settings {
        let mut own = Settings::default();
        own.set(Item::Network, Some(network)).unwrap();
        own.over(&Settings::default())
    }

    fn in_place(network: &Network) -> bool {
        let mut status = Status::default();
        network.report(&mut status);
        status.network
    }

    #[test]
    fn the_chains_ask_the_program_once_and_leave_loopback() {
        assert_eq!(
            chains(),
            "*filter\n\
             :bb_sandbox - [0:0]\n\
             :bb_refuse - [0:0]\n\
             -A bb_sandbox -o lo -j RETURN\n\
             -A bb_sandbox -m bpf --object-pinned /sys/fs/bpf/bb_network -j bb_refuse\n\
             -A bb_refuse -p tcp -j REJECT --reject-with tcp-reset\n\
             -A bb_refuse -j REJECT\n\
             COMMIT\n"
        );
    }

    #[test]
    fn apps_are_refused_while_the_network_item_is_on() {
        let fake = Fake::default();
        let mut network = fake.started();
        let (on, off) = (items(Mode::On), items(Mode::Off));

        // Left to the app: nothing is run at all.
        let runs = fake.runs();
        network.enable(&mut entry(10001), &[], &off).unwrap();
        assert_eq!(fake.runs(), runs);

        network.enable(&mut entry(10002), &[], &on).unwrap();
        assert_eq!(fake.refused(), [10002]);
        assert!(in_place(&network));

        // The rules are written for the first app; the others are listed.
        let runs = fake.runs();
        network.enable(&mut entry(10003), &[], &on).unwrap();
        network.update(&mut entry(10001), &[], &off, &on).unwrap();
        network.update(&mut entry(10002), &[], &on, &off).unwrap();
        network.update(&mut entry(10003), &[], &on, &on).unwrap();
        assert_eq!(fake.refused(), [10001, 10003]);
        assert_eq!(fake.runs(), runs);

        // The last one out takes the chains and the jump with it.
        network.disable(&mut entry(10001), &[], &on).unwrap();
        assert!(fake.has_rules());
        network.disable(&mut entry(10003), &[], &on).unwrap();
        assert!(!fake.has_rules());
        assert!(fake.0.borrow().listed.is_empty());
        assert!(in_place(&network));
    }

    #[test]
    fn a_retired_installation_leaves_no_network_policy_for_its_successor() {
        let fake = Fake::default();
        let mut network = fake.started();
        let on = items(Mode::On);
        network.enable(&mut entry(10001), &[], &on).unwrap();
        network.enable(&mut entry(10002), &[], &on).unwrap();

        network.forget(10001);
        let off = items(Mode::Off);
        network.enable(&mut entry(10001), &[], &off).unwrap();
        assert_eq!(fake.refused(), [10002]);

        network.forget(10002);
        assert!(fake.refused().is_empty());
        assert!(!fake.has_rules());
    }

    #[test]
    fn a_daemon_that_starts_writes_the_rules_again() {
        let fake = Fake::default();
        let resumed = |uid, network| Resumed {
            entry: entry(uid),
            packages: Vec::new(),
            items: items(network),
        };

        let mut network = fake.network();
        assert!(!in_place(&network));
        network.resume(&[resumed(10001, Mode::On), resumed(10002, Mode::Off)]);
        assert_eq!(fake.refused(), [10001]);
        assert!(in_place(&network));

        // The rules a daemon before left are written again, so that they
        // name the program of this one.
        let runs = fake.runs();
        let mut network = fake.network();
        network.resume(&[resumed(10001, Mode::On)]);
        assert!(fake.runs() > runs);
        assert!(fake.has_rules());

        // With no app listed they are removed.
        fake.0.borrow_mut().listed.clear();
        let mut network = fake.network();
        network.resume(&[resumed(10002, Mode::Off)]);
        assert!(!fake.has_rules());
        assert!(in_place(&network));
    }

    #[test]
    fn the_rules_a_daemon_left_stay_until_the_next_has_listed_its_apps() {
        let fake = Fake::default();
        let resumed = |uid| Resumed {
            entry: entry(uid),
            packages: Vec::new(),
            items: items(Mode::On),
        };
        fake.network().resume(&[resumed(10001), resumed(10009)]);

        // An app taken out as the daemon starts, its uid having gone to
        // another: the rules, which refuse the others, are not touched.
        let mut network = fake.network();
        let runs = fake.runs();
        network
            .disable(&mut entry(10009), &[], &items(Mode::On))
            .unwrap();
        // Nor are they by one put back in when taking it out failed.
        network
            .enable(&mut entry(10009), &[], &items(Mode::On))
            .unwrap();
        network
            .disable(&mut entry(10009), &[], &items(Mode::On))
            .unwrap();
        assert_eq!(fake.runs(), runs);
        assert!(fake.has_rules());
        network.resume(&[resumed(10001)]);
        assert_eq!(fake.refused(), [10001]);
    }

    #[test]
    fn rules_not_known_to_be_as_meant_are_written_again_with_a_change() {
        let fake = Fake::default();
        let resumed = |uid| Resumed {
            entry: entry(uid),
            packages: Vec::new(),
            items: items(Mode::On),
        };
        fake.network().resume(&[resumed(10001), resumed(10002)]);

        // The next daemon cannot write them: those of the one before stay.
        fake.0.borrow_mut().failing = vec!["iptables-restore"];
        let mut network = fake.network();
        network.resume(&[resumed(10001), resumed(10002)]);
        assert!(!in_place(&network));

        // Giving one app the network back writes them, for the other.
        fake.0.borrow_mut().failing.clear();
        let runs = fake.runs();
        network
            .disable(&mut entry(10001), &[], &items(Mode::On))
            .unwrap();
        assert!(fake.runs() > runs);
        assert_eq!(fake.refused(), [10002]);
        assert!(in_place(&network));
    }

    #[test]
    fn the_rules_are_there_for_an_app_kept_from_single_destinations() {
        let fake = Fake::default();
        let mut network = fake.started();
        let off = items(Mode::Off);
        let destinations = fake.0.borrow().destinations.clone();

        // The app has the network, and a destination it is kept from.
        destinations.store(true, Ordering::Relaxed);
        network.enable(&mut entry(10001), &[], &off).unwrap();
        assert!(fake.has_rules());
        assert!(fake.0.borrow().listed.is_empty());

        // The last such destination gone, and the app refreshed.
        destinations.store(false, Ordering::Relaxed);
        network.refresh(&entry(10001), &off).unwrap();
        assert!(!fake.has_rules());

        // Kept from the network as well, then only from a destination.
        network
            .update(&mut entry(10001), &[], &off, &items(Mode::On))
            .unwrap();
        destinations.store(true, Ordering::Relaxed);
        network
            .update(&mut entry(10001), &[], &items(Mode::On), &off)
            .unwrap();
        assert!(fake.has_rules());
        destinations.store(false, Ordering::Relaxed);
        network.disable(&mut entry(10001), &[], &off).unwrap();
        assert!(!fake.has_rules());
    }

    #[test]
    fn rules_that_cannot_be_written_fail_the_change() {
        let fake = Fake::default();
        let mut network = fake.started();
        let on = items(Mode::On);

        // IPv4 takes the rules and IPv6 does not: the app is not listed,
        // and the rules that are there match nothing.
        fake.0.borrow_mut().failing = vec!["ip6tables-restore"];
        assert!(network.enable(&mut entry(10001), &[], &on).is_err());
        assert!(fake.0.borrow().listed.is_empty());
        assert!(!in_place(&network));

        // Written again once they can be.
        fake.0.borrow_mut().failing.clear();
        network.enable(&mut entry(10001), &[], &on).unwrap();
        assert_eq!(fake.refused(), [10001]);
        assert!(in_place(&network));

        // An app that cannot be listed is not taken in.
        fake.0.borrow_mut().list_fails = true;
        assert!(network.enable(&mut entry(10002), &[], &on).is_err());
        // Nor let go while it cannot be taken off the list.
        assert!(network.disable(&mut entry(10001), &[], &on).is_err());
        fake.0.borrow_mut().list_fails = false;
        assert_eq!(fake.refused(), [10001]);

        // A failed withdrawal leaves the settings unchanged and is retryable.
        fake.0.borrow_mut().failing = vec!["iptables"];
        assert!(network.disable(&mut entry(10001), &[], &on).is_err());
        assert_eq!(fake.0.borrow().listed, BTreeSet::from([10001]));
        assert!(!in_place(&network));
        fake.0.borrow_mut().failing.clear();
        network.retry().unwrap();
        network.disable(&mut entry(10001), &[], &on).unwrap();
        assert!(!fake.has_rules());
        assert!(in_place(&network));
    }

    #[test]
    fn a_failed_resume_insert_is_reported_for_its_uid_only() {
        let fake = Fake::default();
        fake.0.borrow_mut().fail_insert = Some(10002);
        let mut network = fake.network();
        network.resume(&[10001, 10002].map(|uid| Resumed {
            entry: entry(uid),
            packages: Vec::new(),
            items: items(Mode::On),
        }));
        let mut status = Status::default();
        network.report(&mut status);
        assert!(status.network);
        assert_eq!(status.network_uids, BTreeSet::from([10001]));

        fake.0.borrow_mut().listed.remove(&10001);
        let mut status = Status::default();
        network.report(&mut status);
        assert!(status.network_uids.is_empty());
    }

    #[test]
    fn losing_one_output_jump_is_visible_and_repaired() {
        let fake = Fake::default();
        let mut network = fake.started();
        network
            .enable(&mut entry(10001), &[], &items(Mode::On))
            .unwrap();
        fake.0.borrow_mut().jumps.retain(|family| *family == "v4");
        assert!(!in_place(&network));

        network.retry().unwrap();
        assert_eq!(fake.refused(), [10001]);
        assert!(in_place(&network));
    }

    #[test]
    fn stale_generations_cannot_acknowledge_a_failed_withdrawal() {
        let fake = Fake::default();
        let on = items(Mode::On);
        let mut network = fake.started();
        network.enable(&mut entry(10001), &[], &on).unwrap();
        fake.0.borrow_mut().failing = vec!["iptables-restore"];
        let mut restarted = fake.network();
        restarted.resume(&[10001, 10002].map(|uid| Resumed {
            entry: entry(uid),
            packages: Vec::new(),
            items: on.clone(),
        }));

        assert!(restarted.disable(&mut entry(10001), &[], &on).is_err());
        fake.0.borrow_mut().failing.clear();
        restarted.retry().unwrap();
        restarted.disable(&mut entry(10001), &[], &on).unwrap();
        assert_eq!(fake.refused(), [10002]);
    }

    #[test]
    fn offline_cleanup_removes_duplicate_jumps_and_orphaned_chains() {
        let fake = Fake::default();
        fake.started()
            .enable(&mut entry(10001), &[], &items(Mode::On))
            .unwrap();
        fake.0.borrow_mut().jumps.push("v4");
        fake.0.borrow_mut().jumps.retain(|family| *family == "v4");
        fake.0.borrow_mut().chains.remove(&("v6", CHAIN.to_owned()));
        fake.0
            .borrow_mut()
            .chains
            .insert(("v6", "other_firewall".into()));

        uninstall_all(&fake).unwrap();
        uninstall_all(&fake).unwrap();
        assert!(fake.0.borrow().jumps.is_empty());
        assert_eq!(
            fake.0.borrow().chains,
            HashSet::from([("v6", "other_firewall".into())])
        );
    }

    #[test]
    fn without_the_packet_filter_the_network_cannot_be_sandboxed() {
        let fake = Fake::default();
        // What a daemon that had the packet filter left is removed.
        fake.started()
            .enable(&mut entry(10009), &[], &items(Mode::On))
            .unwrap();
        let mut network = Unavailable {
            tools: Box::new(fake.clone()),
            cleanup_pending: true,
            cleanup_errors: Limited::new(SANDBOX_INTERVAL, Policy::Shared),
        };
        network.resume(&[]);
        assert!(!fake.has_rules());
        // An app that had the item on can have its other items changed.
        network
            .update(&mut entry(10009), &[], &items(Mode::On), &items(Mode::On))
            .unwrap();
        assert!(
            network
                .enable(&mut entry(10001), &[], &items(Mode::On))
                .is_err()
        );
        assert!(
            network
                .update(&mut entry(10001), &[], &items(Mode::Off), &items(Mode::On))
                .is_err()
        );
        // An app that leaves the network alone is taken in and out.
        network
            .enable(&mut entry(10002), &[], &items(Mode::Off))
            .unwrap();
        network
            .disable(&mut entry(10002), &[], &items(Mode::Off))
            .unwrap();
        let mut status = Status::default();
        network.report(&mut status);
        assert!(!status.network);
    }
}
