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

//! The destinations a sandboxed app is kept from, and the recording of
//! where it connects.
//!
//! Both are lists the kernel side reads. The packet filter's program
//! ([`super::network`]) looks the destination of a packet up among the
//! addresses its app is kept from, only for an app that has any; the
//! probes that report connections report those to the internet only of
//! the apps listed as recorded.
//!
//! The user's rules are kept for an app whether it is in the sandbox or
//! not ([`Book`]), and are in the kernel only while it is: taken out, an
//! app is kept from nothing.

use std::{
    collections::{BTreeSet, HashMap},
    net::IpAddr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
};

use anyhow::Context as _;
use aya::maps::{HashMap as BpfHashMap, MapData};
use bb_common::NetRuleKey;

use super::{Effect, Entry, Resumed, Status, items::Settings, names::Names, network::Listed};
use crate::netctl::Target;

/// The destinations each uid is kept from, as the user named them: shared
/// between what changes them (the commands) and what carries them out.
pub type Book = Arc<Mutex<HashMap<u32, BTreeSet<Target>>>>;

/// What tells, of a connection an app tried, whether the sandbox refused
/// it: the app is kept from the network, or from that destination.
pub struct View {
    pub snapshot: super::Snapshot,
    pub global: super::Global,
    pub book: Book,
    /// The names apps were given addresses for, where they are known.
    pub names: Option<Arc<Names>>,
}

impl View {
    pub fn refuses(&self, uid: u32, to: std::net::SocketAddr) -> bool {
        let snapshot = self.snapshot.read().unwrap_or_else(|e| e.into_inner());
        let Some(entry) = snapshot.get(&uid) else {
            return false;
        };
        let global = self.global.read().unwrap_or_else(|e| e.into_inner());
        if super::items_of(entry, &global).network_refused() {
            return true;
        }
        // An address refused with a name the app is kept from, whatever
        // name it was given for since.
        if self
            .names
            .as_ref()
            .is_some_and(|names| names.refused(uid, to.ip()))
        {
            return true;
        }
        // The name the app was given the address for, if it is kept from
        // that name: the addresses of such a name are refused with it.
        let host = self
            .names
            .as_ref()
            .and_then(|names| names.host_of(uid, to.ip()));
        let book = self.book.lock().unwrap_or_else(|e| e.into_inner());
        book.get(&uid).is_some_and(|targets| {
            targets.contains(&Target::Ip(to.ip(), None))
                || targets.contains(&Target::Ip(to.ip(), Some(to.port())))
                || host
                    .as_deref()
                    .is_some_and(|host| targets.iter().any(|target| target.covers_name(host)))
        })
    }
}

/// An address with a port, or with every port: what the kernel side is
/// given of a rule.
type Address = (IpAddr, Option<u16>);

/// The key the program looks a packet up by.
pub fn key(uid: u32, (ip, port): Address) -> NetRuleKey {
    let mut addr = [0u8; 16];
    match ip.to_canonical() {
        IpAddr::V4(v4) => addr[..4].copy_from_slice(&v4.octets()),
        IpAddr::V6(v6) => addr = v6.octets(),
    }
    NetRuleKey {
        uid,
        port: u32::from(port.unwrap_or(0)),
        addr,
    }
}

/// The bytes of a key, as the map holds it: the struct has no padding.
fn bytes(key: &NetRuleKey) -> [u8; 24] {
    let mut out = [0u8; 24];
    out[..4].copy_from_slice(&key.uid.to_ne_bytes());
    out[4..8].copy_from_slice(&key.port.to_ne_bytes());
    out[8..].copy_from_slice(&key.addr);
    out
}

/// The destinations the program refuses.
pub trait RuleMap {
    fn insert(&mut self, key: NetRuleKey) -> anyhow::Result<()>;
    fn remove(&mut self, key: NetRuleKey) -> anyhow::Result<()>;
}

impl RuleMap for BpfHashMap<MapData, [u8; 24], u32> {
    fn insert(&mut self, key: NetRuleKey) -> anyhow::Result<()> {
        BpfHashMap::insert(self, &bytes(&key), &1, 0).context("listing the destination")
    }

    fn remove(&mut self, key: NetRuleKey) -> anyhow::Result<()> {
        let key = bytes(&key);
        if matches!(self.get(&key, 0), Err(aya::maps::MapError::KeyNotFound)) {
            return Ok(());
        }
        BpfHashMap::remove(self, &key).context("taking the destination off the list")
    }
}

/// The effect that gives the kernel side the destinations the apps in the
/// sandbox are kept from, and the apps whose connections are recorded.
pub struct Destinations {
    recorded: Box<dyn Listed>,
    ruled: Box<dyn Listed>,
    rules: Box<dyn RuleMap>,
    book: Book,
    /// What the kernel side has, by uid.
    listed: HashMap<u32, BTreeSet<Address>>,
    /// The uids listed as recorded.
    recording: BTreeSet<u32>,
    /// Whether any app is kept from a destination: the packet filter's
    /// rule is needed then ([`super::network::Network`] reads it).
    any: Arc<AtomicBool>,
    /// Whether the bytes of the connections recorded are counted.
    traffic: bool,
    /// The names apps were given addresses for, where they are known.
    names: Option<Arc<Names>>,
    /// The addresses refused with each name an app is kept from: those it
    /// was given for the name. Kept for as long as the rule is, whatever
    /// becomes of what is remembered of the names.
    with_names: HashMap<(u32, Target), BTreeSet<IpAddr>>,
}

impl Destinations {
    /// On the maps of the loaded programs.
    pub fn start(ebpf: &mut aya::Ebpf, book: Book, any: Arc<AtomicBool>) -> anyhow::Result<Self> {
        let mut map = |name: &str| {
            ebpf.take_map(name)
                .with_context(|| format!("{name} map missing"))
        };
        let recorded: BpfHashMap<MapData, u32, u32> =
            BpfHashMap::try_from(map("NETWORK_RECORDED")?)?;
        let ruled: BpfHashMap<MapData, u32, u32> = BpfHashMap::try_from(map("NETWORK_RULED")?)?;
        let rules: BpfHashMap<MapData, [u8; 24], u32> =
            BpfHashMap::try_from(map("NETWORK_RULES")?)?;
        Ok(Self::with(
            Box::new(recorded),
            Box::new(ruled),
            Box::new(rules),
            book,
            any,
        ))
    }

    fn with(
        recorded: Box<dyn Listed>,
        ruled: Box<dyn Listed>,
        rules: Box<dyn RuleMap>,
        book: Book,
        any: Arc<AtomicBool>,
    ) -> Self {
        Self {
            recorded,
            ruled,
            rules,
            book,
            listed: HashMap::new(),
            recording: BTreeSet::new(),
            any,
            traffic: false,
            names: None,
            with_names: HashMap::new(),
        }
    }

    /// Have the addresses an app was given for a name it is kept from
    /// refused with the name: the daemon hears the lookups (`names`).
    pub fn with_names(&mut self, names: Arc<Names>) {
        self.names = Some(names);
    }

    /// Say whether the bytes of the connections recorded are counted on
    /// this device, for the status.
    pub fn with_traffic(&mut self, counted: bool) {
        self.traffic = counted;
    }

    /// The addresses of the book's rules for `uid`: those named, and those
    /// the app was given for a name it is kept from. A name is refused
    /// where it is looked up; a connection to an address the app already
    /// has for it would otherwise go on.
    fn wanted(&mut self, uid: u32) -> BTreeSet<Address> {
        let targets: BTreeSet<Target> = {
            let book = self.book.lock().unwrap_or_else(|e| e.into_inner());
            book.get(&uid).cloned().unwrap_or_default()
        };
        let mut wanted = BTreeSet::new();
        let mut with_names = BTreeSet::new();
        for target in targets {
            match (&target, &self.names) {
                (Target::Ip(ip, port), _) => {
                    wanted.insert((*ip, *port));
                }
                (name, Some(names)) => {
                    let given = names.addresses_of(uid, |host| name.covers_name(host));
                    let refused = self.with_names.entry((uid, target)).or_default();
                    refused.extend(given);
                    with_names.extend(refused.iter().copied());
                }
                (_, None) => {}
            }
        }
        wanted.extend(with_names.iter().map(|ip| (*ip, None)));
        if let Some(names) = &self.names {
            names.keep_refused(uid, with_names);
        }
        wanted
    }

    /// Bring what the kernel side `has` of the addresses `uid` is kept
    /// from to those `wanted`. The new ones first, and the uid marked
    /// before any is looked for: a change never lets through what both the
    /// old and the new refuse.
    fn settle(
        &mut self,
        uid: u32,
        wanted: &BTreeSet<Address>,
        has: &mut BTreeSet<Address>,
    ) -> anyhow::Result<()> {
        for address in wanted {
            if !has.contains(address) {
                self.rules.insert(key(uid, *address))?;
                has.insert(*address);
            }
        }
        if !wanted.is_empty() {
            self.ruled.insert(uid)?;
        }
        for address in has.clone().difference(wanted) {
            self.rules.remove(key(uid, *address))?;
            has.remove(address);
        }
        if wanted.is_empty() {
            self.ruled.remove(uid)?;
        }
        Ok(())
    }

    /// Give the kernel side what holds for `uid`: its rules while it is in
    /// the sandbox, and whether its connections are recorded.
    fn carry_out(&mut self, uid: u32, sandboxed: bool, recorded: bool) -> anyhow::Result<()> {
        let wanted = if sandboxed {
            self.wanted(uid)
        } else {
            self.with_names.retain(|(of, _), _| *of != uid);
            if let Some(names) = &self.names {
                names.keep_refused(uid, BTreeSet::new());
            }
            BTreeSet::new()
        };
        // What the kernel side has of them, kept as each step is done: a
        // step that fails leaves it as far as the steps before got.
        let mut has = self.listed.remove(&uid).unwrap_or_default();
        let result = self.settle(uid, &wanted, &mut has);
        if !has.is_empty() {
            self.listed.insert(uid, has);
        }
        self.any.store(!self.listed.is_empty(), Ordering::Relaxed);
        result?;

        if recorded && sandboxed {
            self.recorded.insert(uid)?;
            self.recording.insert(uid);
        } else {
            self.recorded.remove(uid)?;
            self.recording.remove(&uid);
        }
        Ok(())
    }
}

impl Effect for Destinations {
    fn name(&self) -> &'static str {
        "network destinations"
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        self.carry_out(entry.uid, true, items.network_recorded())
    }

    fn disable(&mut self, entry: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        self.carry_out(entry.uid, false, false)
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        _: &[String],
        _: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        self.carry_out(entry.uid, true, after.network_recorded())
    }

    fn refresh(&mut self, entry: &Entry, items: &Settings) -> anyhow::Result<()> {
        self.carry_out(entry.uid, true, items.network_recorded())
    }

    fn finish_refresh(&mut self, uid: u32) {
        // A rule being removed may still be restored if its removal fails.
        // Keep its addresses until that is known, even if the app since
        // looked up another name that resolves to the same address.
        let book = self.book.lock().unwrap_or_else(|e| e.into_inner());
        let targets = book.get(&uid);
        self.with_names
            .retain(|(of, target), _| *of != uid || targets.is_some_and(|t| t.contains(target)));
    }

    fn resume(&mut self, apps: &[Resumed]) {
        for app in apps {
            if let Err(e) = self.carry_out(app.entry.uid, true, app.items.network_recorded()) {
                eprintln!(
                    "bbd: warning: the destinations uid {} is kept from are not all in place: {e:#}",
                    app.entry.uid
                );
            }
        }
    }

    fn forget(&mut self, uid: u32) {
        if let Err(e) = self.carry_out(uid, false, false) {
            eprintln!("bbd: warning: uid {uid} is still kept from destinations: {e:#}");
        }
        if let Some(names) = &self.names {
            names.forget(uid);
        }
    }

    fn report(&self, status: &mut Status) {
        status.net_record = true;
        status.traffic = self.traffic;
        status.dns_names = self.names.is_some();
    }
}

#[cfg(test)]
mod tests {
    use std::{
        cell::{Cell, RefCell},
        rc::Rc,
    };

    use super::*;
    use crate::{
        sandbox::{
            Sandbox,
            items::{Item, Mode},
            tests::Fixture as SandboxFixture,
        },
        store::Writer,
    };

    #[derive(Default)]
    struct Kernel {
        recorded: BTreeSet<u32>,
        ruled: BTreeSet<u32>,
        rules: BTreeSet<[u8; 24]>,
        /// Whether a destination can be neither listed nor taken off.
        rules_fail: bool,
        fail_insert: Option<[u8; 24]>,
    }

    #[derive(Clone)]
    struct Recorded(Rc<RefCell<Kernel>>);
    #[derive(Clone)]
    struct Ruled(Rc<RefCell<Kernel>>);
    #[derive(Clone)]
    struct Rules(Rc<RefCell<Kernel>>);

    impl Listed for Recorded {
        fn insert(&mut self, uid: u32) -> anyhow::Result<()> {
            self.0.borrow_mut().recorded.insert(uid);
            Ok(())
        }
        fn remove(&mut self, uid: u32) -> anyhow::Result<()> {
            self.0.borrow_mut().recorded.remove(&uid);
            Ok(())
        }
    }

    impl Listed for Ruled {
        fn insert(&mut self, uid: u32) -> anyhow::Result<()> {
            self.0.borrow_mut().ruled.insert(uid);
            Ok(())
        }
        fn remove(&mut self, uid: u32) -> anyhow::Result<()> {
            self.0.borrow_mut().ruled.remove(&uid);
            Ok(())
        }
    }

    impl RuleMap for Rules {
        fn insert(&mut self, key: NetRuleKey) -> anyhow::Result<()> {
            let mut kernel = self.0.borrow_mut();
            let key = bytes(&key);
            anyhow::ensure!(
                !kernel.rules_fail && kernel.fail_insert != Some(key),
                "the list is full"
            );
            kernel.rules.insert(key);
            Ok(())
        }
        fn remove(&mut self, key: NetRuleKey) -> anyhow::Result<()> {
            let mut kernel = self.0.borrow_mut();
            anyhow::ensure!(!kernel.rules_fail, "the list cannot be changed");
            kernel.rules.remove(&bytes(&key));
            Ok(())
        }
    }

    struct Fixture {
        kernel: Rc<RefCell<Kernel>>,
        book: Book,
        any: Arc<AtomicBool>,
        effect: Destinations,
    }

    fn fixture() -> Fixture {
        fixture_with(Book::default())
    }

    fn fixture_with(book: Book) -> Fixture {
        let kernel = Rc::new(RefCell::new(Kernel::default()));
        let any = Arc::new(AtomicBool::new(false));
        let effect = Destinations::with(
            Box::new(Recorded(kernel.clone())),
            Box::new(Ruled(kernel.clone())),
            Box::new(Rules(kernel.clone())),
            book.clone(),
            any.clone(),
        );
        Fixture {
            kernel,
            book,
            any,
            effect,
        }
    }

    impl Fixture {
        fn rule(&self, uid: u32, target: &str) {
            self.book
                .lock()
                .unwrap()
                .entry(uid)
                .or_default()
                .insert(Target::parse(target).unwrap());
        }

        fn unrule(&self, uid: u32, target: &str) {
            let mut book = self.book.lock().unwrap();
            book.get_mut(&uid)
                .unwrap()
                .remove(&Target::parse(target).unwrap());
        }

        fn has(&self, uid: u32, target: &str) -> bool {
            let Some(Target::Ip(ip, port)) = Target::parse(target) else {
                panic!("not an address")
            };
            self.kernel
                .borrow()
                .rules
                .contains(&bytes(&key(uid, (ip, port))))
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

    fn items(record: Mode) -> Settings {
        let mut own = Settings::default();
        own.set(Item::NetworkRecord, Some(record)).unwrap();
        own.over(&Settings::default())
    }

    fn sandbox_with_filter(
        f: &mut SandboxFixture,
        names: Arc<Names>,
    ) -> (Sandbox, Rc<RefCell<Kernel>>) {
        let mut sandbox = f.sandbox();
        let Fixture {
            mut effect, kernel, ..
        } = fixture_with(sandbox.book());
        effect.with_names(names);
        sandbox.add_effect(Box::new(effect));
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        (sandbox, kernel)
    }

    #[test]
    fn a_failed_rule_delete_keeps_the_destination_refused_live_and_after_reload() {
        let mut f = SandboxFixture::new("sandbox-net-delete");
        let names = Arc::new(Names::default());
        let ip = "203.0.113.7".parse().unwrap();
        names.learn(10100, "blocked.example", &[ip]);
        let (mut sandbox, kernel) = sandbox_with_filter(&mut f, names.clone());
        let target = "domain:blocked.example";
        let key = bytes(&key(10100, (ip, None)));
        sandbox
            .net_block(&f.writer, &mut f.packages, "com.app", 0, target, true)
            .unwrap();
        assert!(kernel.borrow().rules.contains(&key));
        let rules = f.writer.net_rules().unwrap();

        let db = f.dir.join("bb.db");
        let connection = rusqlite::Connection::open(&db).unwrap();
        connection
            .execute_batch(
                "CREATE TRIGGER fail_rule_delete BEFORE DELETE ON net_rule
                 BEGIN SELECT RAISE(FAIL, 'injected rule delete failure'); END;",
            )
            .unwrap();
        let error = sandbox
            .net_unblock(&f.writer, &mut f.packages, "com.app", 0, target)
            .unwrap_err();
        assert!(format!("{error:#}").contains("injected rule delete failure"));
        assert!(kernel.borrow().rules.contains(&key));
        assert!(names.refused(10100, ip));
        let reloaded = Writer::open(&db).unwrap();
        assert_eq!(reloaded.net_rules().unwrap(), rules);
        let reloaded = Sandbox::load(&reloaded, Vec::new(), None).unwrap();
        assert_eq!(
            reloaded.book().lock().unwrap().get(&10100),
            sandbox.book().lock().unwrap().get(&10100),
        );

        connection
            .execute_batch("DROP TRIGGER fail_rule_delete")
            .unwrap();
        sandbox
            .net_unblock(&f.writer, &mut f.packages, "com.app", 0, target)
            .unwrap();
        assert!(!kernel.borrow().rules.contains(&key));
        assert!(!names.refused(10100, ip));
        assert!(f.writer.net_rules().unwrap().is_empty());
    }

    #[test]
    fn a_failed_rule_apply_does_not_leave_a_rule_to_reload() {
        let mut f = SandboxFixture::new("sandbox-net-apply");
        let names = Arc::new(Names::default());
        let ip = "203.0.113.7".parse().unwrap();
        names.learn(10100, "blocked.example", &[ip]);
        let (mut sandbox, kernel) = sandbox_with_filter(&mut f, names.clone());
        kernel.borrow_mut().rules_fail = true;

        let db = f.dir.join("bb.db");
        let connection = rusqlite::Connection::open(&db).unwrap();
        connection
            .execute_batch(
                "CREATE TRIGGER reject_compensating_delete BEFORE DELETE ON net_rule
             BEGIN SELECT RAISE(FAIL, 'injected rule delete failure'); END;",
            )
            .unwrap();
        let target = "domain:blocked.example";
        let error = sandbox
            .net_block(&f.writer, &mut f.packages, "com.app", 0, target, false)
            .unwrap_err();
        assert!(format!("{error:#}").contains("the list is full"));
        assert!(kernel.borrow().rules.is_empty());
        assert!(!names.refused(10100, ip));
        assert!(
            sandbox
                .book()
                .lock()
                .unwrap()
                .get(&10100)
                .is_none_or(BTreeSet::is_empty)
        );
        assert!(Writer::open(&db).unwrap().net_rules().unwrap().is_empty());
    }

    struct RefreshWitness(Rc<Cell<usize>>);

    impl Effect for RefreshWitness {
        fn name(&self) -> &'static str {
            "refresh witness"
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
            self.0.set(self.0.get() + 1);
            Ok(())
        }
    }

    #[test]
    fn a_failed_rule_commit_restores_previously_learned_addresses_and_retries_failed_effects() {
        let mut f = SandboxFixture::new("sandbox-net-commit");
        let names = Arc::new(Names::default());
        let ip = "203.0.113.7".parse().unwrap();
        names.learn(10100, "blocked.example", &[ip]);
        let (mut sandbox, kernel) = sandbox_with_filter(&mut f, names.clone());
        let target = "domain:blocked.example";
        let key = bytes(&key(10100, (ip, None)));
        sandbox
            .net_block(&f.writer, &mut f.packages, "com.app", 0, target, true)
            .unwrap();
        names.learn(10100, "different.example", &[ip]);
        let rules = f.writer.net_rules().unwrap();

        // The DELETE is accepted, but COMMIT fails after the effects ran.
        let db = f.dir.join("bb.db");
        let connection = rusqlite::Connection::open(&db).unwrap();
        connection
            .execute_batch(
                "CREATE TABLE commit_parent (id INTEGER PRIMARY KEY);
             CREATE TABLE commit_child (parent INTEGER REFERENCES commit_parent(id)
                                        DEFERRABLE INITIALLY DEFERRED);
             CREATE TRIGGER fail_rule_commit AFTER DELETE ON net_rule
             BEGIN INSERT INTO commit_child VALUES (1); END;",
            )
            .unwrap();
        let error = sandbox
            .net_unblock(&f.writer, &mut f.packages, "com.app", 0, target)
            .unwrap_err();
        assert!(format!("{error:#}").contains("FOREIGN KEY constraint failed"));
        assert!(kernel.borrow().rules.contains(&key));
        assert!(names.refused(10100, ip));

        let refreshed = Rc::new(Cell::new(0));
        sandbox.add_effect(Box::new(RefreshWitness(refreshed.clone())));
        kernel.borrow_mut().fail_insert = Some(key);
        let error = sandbox
            .net_unblock(&f.writer, &mut f.packages, "com.app", 0, target)
            .unwrap_err();
        assert_eq!(
            error.to_string(),
            "could not restore the previous network policy (network destinations)"
        );
        assert!(format!("{error:#}").contains("FOREIGN KEY constraint failed"));
        assert_eq!(refreshed.get(), 2);
        assert!(!kernel.borrow().rules.contains(&key));
        assert_eq!(Writer::open(&db).unwrap().net_rules().unwrap(), rules);

        kernel.borrow_mut().fail_insert = None;
        sandbox.retry_net_refresh();
        sandbox.retry_net_refresh();
        assert_eq!(refreshed.get(), 3);
        assert!(kernel.borrow().rules.contains(&key));
        assert!(names.refused(10100, ip));

        connection
            .execute_batch("DROP TRIGGER fail_rule_commit")
            .unwrap();
        sandbox
            .net_unblock(&f.writer, &mut f.packages, "com.app", 0, target)
            .unwrap();
        sandbox
            .net_block(&f.writer, &mut f.packages, "com.app", 0, target, true)
            .unwrap();
        assert!(!kernel.borrow().rules.contains(&key));
        assert!(!names.refused(10100, ip));
    }

    #[test]
    fn keys_hold_the_address_as_packets_carry_it() {
        let v4 = key(10001, ("203.0.113.7".parse().unwrap(), Some(8080)));
        assert_eq!((v4.uid, v4.port), (10001, 8080));
        assert_eq!(
            v4.addr,
            [203, 0, 113, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
        );
        // An IPv4 address written as an IPv6 one is sent to as IPv4.
        assert_eq!(
            key(1, ("::ffff:203.0.113.7".parse().unwrap(), None)).addr,
            v4.addr
        );
        let v6 = key(1, ("2001:db8::5".parse().unwrap(), None));
        assert_eq!(v6.port, 0);
        assert_eq!(v6.addr[..4], [0x20, 0x01, 0x0d, 0xb8]);
        assert_eq!(v6.addr[15], 5);
        assert_eq!(bytes(&v4).len(), size_of::<NetRuleKey>());
    }

    #[test]
    fn an_apps_rules_are_in_the_kernel_while_it_is_in_the_sandbox() {
        let mut f = fixture();
        f.rule(10001, "ip:203.0.113.7");
        f.rule(10001, "ip:203.0.113.8:8080");
        // Names are refused where they are looked up, not here.
        f.rule(10001, "domain:example.com");
        f.rule(10002, "ip:203.0.113.7");
        let off = items(Mode::Off);

        // Not in the sandbox: nothing is listed.
        assert!(f.kernel.borrow().rules.is_empty());
        assert!(!f.any.load(Ordering::Relaxed));

        f.effect.enable(&mut entry(10001), &[], &off).unwrap();
        assert!(f.has(10001, "ip:203.0.113.7") && f.has(10001, "ip:203.0.113.8:8080"));
        assert!(!f.has(10002, "ip:203.0.113.7"));
        assert_eq!(f.kernel.borrow().rules.len(), 2);
        assert_eq!(f.kernel.borrow().ruled, BTreeSet::from([10001]));
        assert!(f.any.load(Ordering::Relaxed));

        // A rule added or removed is carried out when the app is refreshed.
        f.rule(10001, "ip:2001:db8::5");
        f.unrule(10001, "ip:203.0.113.7");
        f.effect.refresh(&entry(10001), &off).unwrap();
        assert!(f.has(10001, "ip:2001:db8::5") && !f.has(10001, "ip:203.0.113.7"));

        // An app without rules is not looked into.
        f.effect.enable(&mut entry(10003), &[], &off).unwrap();
        assert_eq!(f.kernel.borrow().ruled, BTreeSet::from([10001]));

        f.effect.disable(&mut entry(10001), &[], &off).unwrap();
        assert!(f.kernel.borrow().rules.is_empty());
        assert!(f.kernel.borrow().ruled.is_empty());
        assert!(!f.any.load(Ordering::Relaxed));
        // The rules are the app's still, for when it is put back.
        assert_eq!(f.book.lock().unwrap()[&10001].len(), 3);
    }

    #[test]
    fn connections_are_recorded_of_the_apps_with_the_item_on() {
        let mut f = fixture();
        let (on, off) = (items(Mode::On), items(Mode::Off));
        f.effect.enable(&mut entry(10001), &[], &on).unwrap();
        f.effect.enable(&mut entry(10002), &[], &off).unwrap();
        assert_eq!(f.kernel.borrow().recorded, BTreeSet::from([10001]));
        f.effect.update(&mut entry(10002), &[], &off, &on).unwrap();
        f.effect.update(&mut entry(10001), &[], &on, &off).unwrap();
        assert_eq!(f.kernel.borrow().recorded, BTreeSet::from([10002]));
        f.effect.disable(&mut entry(10002), &[], &on).unwrap();
        assert!(f.kernel.borrow().recorded.is_empty());

        let resumed = |uid, record| Resumed {
            entry: entry(uid),
            packages: Vec::new(),
            items: items(record),
        };
        f.rule(10005, "ip:203.0.113.9");
        f.effect
            .resume(&[resumed(10004, Mode::On), resumed(10005, Mode::Off)]);
        assert_eq!(f.kernel.borrow().recorded, BTreeSet::from([10004]));
        assert!(f.has(10005, "ip:203.0.113.9"));
        // A uid that went to another app is neither recorded nor refused.
        f.effect.forget(10004);
        f.effect.forget(10005);
        assert!(f.kernel.borrow().recorded.is_empty() && f.kernel.borrow().rules.is_empty());
    }

    #[test]
    fn the_addresses_given_for_a_name_an_app_is_kept_from_are_refused_too() {
        let mut f = fixture();
        let names = Arc::new(Names::default());
        f.effect.with_names(names.clone());
        names.learn(10001, "t.adnet.example", &["203.0.113.50".parse().unwrap()]);
        names.learn(10001, "api.example.com", &["203.0.113.10".parse().unwrap()]);
        names.learn(10002, "t.adnet.example", &["203.0.113.51".parse().unwrap()]);
        f.rule(10001, "domain:adnet.example");
        f.effect
            .enable(&mut entry(10001), &[], &items(Mode::Off))
            .unwrap();
        assert!(f.has(10001, "ip:203.0.113.50"));
        // Not what it was given for other names, nor what another app was.
        assert!(!f.has(10001, "ip:203.0.113.10") && !f.has(10001, "ip:203.0.113.51"));

        let view = View {
            snapshot: Default::default(),
            global: Default::default(),
            book: f.book.clone(),
            names: Some(names),
        };
        // Not in the sandbox: nothing is refused.
        assert!(!view.refuses(10001, "203.0.113.50:443".parse().unwrap()));
        view.snapshot.write().unwrap().insert(10001, entry(10001));
        assert!(view.refuses(10001, "203.0.113.50:443".parse().unwrap()));
        assert!(!view.refuses(10001, "203.0.113.10:443".parse().unwrap()));

        // They stay refused when the name is remembered for other
        // addresses since, and another address given for it joins them.
        view.names.as_ref().unwrap().learn(
            10001,
            "www.example.net",
            &["203.0.113.50".parse().unwrap()],
        );
        view.names.as_ref().unwrap().learn(
            10001,
            "u.adnet.example",
            &["203.0.113.52".parse().unwrap()],
        );
        f.effect.refresh(&entry(10001), &items(Mode::Off)).unwrap();
        assert!(f.has(10001, "ip:203.0.113.50") && f.has(10001, "ip:203.0.113.52"));
        // And a connection to one is told as refused, under the other name.
        assert!(view.refuses(10001, "203.0.113.50:443".parse().unwrap()));

        // With the rule gone the addresses are let through again.
        f.unrule(10001, "domain:adnet.example");
        f.effect.refresh(&entry(10001), &items(Mode::Off)).unwrap();
        assert!(!f.has(10001, "ip:203.0.113.50") && !f.has(10001, "ip:203.0.113.52"));
    }

    #[test]
    fn rules_that_cannot_be_listed_fail_the_change_and_are_tried_again() {
        let mut f = fixture();
        f.rule(10001, "ip:203.0.113.7");
        f.kernel.borrow_mut().rules_fail = true;
        assert!(
            f.effect
                .enable(&mut entry(10001), &[], &items(Mode::Off))
                .is_err()
        );
        f.kernel.borrow_mut().rules_fail = false;
        f.effect
            .enable(&mut entry(10001), &[], &items(Mode::Off))
            .unwrap();
        assert!(f.has(10001, "ip:203.0.113.7"));
        assert_eq!(f.kernel.borrow().ruled, BTreeSet::from([10001]));
    }
}
