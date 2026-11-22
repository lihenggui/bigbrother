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
    collections::{BTreeMap, BTreeSet, HashMap},
    net::IpAddr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
};

use anyhow::Context as _;
use aya::maps::{HashMap as BpfHashMap, MapData};
use bb_common::NetRuleKey;

use super::{
    Effect, Entry, Resumed, Sandbox, Status,
    items::Settings,
    items_of,
    names::{Names, Refuses, Serves},
    network::Listed,
    target::Target,
};
use crate::{
    clock,
    store::{Writer, net::NameAddress},
    sync::{lock, read},
};

/// The destinations each uid is kept from, as the user named them: shared
/// between what changes them (the commands) and what carries them out.
pub type Book = Arc<Mutex<HashMap<u32, BTreeSet<Target>>>>;

pub(super) fn has_name_rules(book: &Book, uid: u32) -> bool {
    lock(book).get(&uid).is_some_and(|targets| {
        targets
            .iter()
            .any(|target| !matches!(target, Target::Ip(..)))
    })
}

impl Sandbox {
    pub(crate) fn name_policy(&self) -> (Serves, Refuses) {
        let refuses: Refuses = {
            let (snapshot, book) = (self.snapshot(), self.book());
            Arc::new(move |uid, host: Option<&str>| {
                // Only of an app in the sandbox: taken out, it is kept from
                // nothing.
                read(&snapshot).contains_key(&uid)
                    && lock(&book).get(&uid).is_some_and(|targets| {
                        targets.iter().any(|target| match host {
                            Some(host) => target.covers_name(host),
                            // A name that could not be read: refused to
                            // an app kept from any name.
                            None => !matches!(target, Target::Ip(..)),
                        })
                    })
            })
        };
        let serves: Serves = {
            let (snapshot, global, book) = (self.snapshot(), self.global(), self.book());
            Arc::new(move |uid| {
                // The apps given the daemon's socket, and no other: the socket
                // can be found by any process, and an app kept from the
                // network is to have no resolver at all.
                let snapshot = read(&snapshot);
                let entry = snapshot.get(&uid)?;
                let items = items_of(entry, &read(&global));
                (!items.network_refused()
                    && (items.network_recorded() || has_name_rules(&book, uid)))
                .then(|| Arc::from(entry.install_id.as_str()))
            })
        };
        (serves, refuses)
    }
}

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
        let snapshot = read(&self.snapshot);
        let Some(entry) = snapshot.get(&uid) else {
            return false;
        };
        let global = read(&self.global);
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
        let book = lock(&self.book);
        book.get(&uid).is_some_and(|targets| {
            targets.contains(&Target::Ip(to.ip(), None))
                || targets.contains(&Target::Ip(to.ip(), Some(to.port())))
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
pub trait RuleMap: Send {
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
    /// was given for the name. Kept until the rule or answer expires,
    /// even if the app later received the address for another name.
    with_names: HashMap<(u32, Target), SavedAddresses>,
    staged_name: Option<StagedName>,
    next_expiry: Option<i64>,
    recording_available: bool,
}

struct StagedName {
    key: (u32, Target),
    previous: Option<SavedAddresses>,
}

#[derive(Clone)]
struct SavedAddresses {
    install_id: String,
    addresses: BTreeMap<IpAddr, i64>,
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
            staged_name: None,
            next_expiry: None,
            recording_available: true,
        }
    }

    /// Have the addresses an app was given for a name it is kept from
    /// refused with the name: the daemon hears the lookups (`names`).
    pub fn with_names(&mut self, names: Arc<Names>) {
        self.names = Some(names);
    }

    pub fn load_addresses(&mut self, writer: &Writer) -> anyhow::Result<()> {
        let now = clock::now();
        for row in writer.name_addresses(now)? {
            let (Some(target), Ok(ip)) = (Target::parse(&row.target), row.address.parse()) else {
                continue;
            };
            self.save_address(row.uid, &row.install_id, &target, ip, row.expires);
        }
        Ok(())
    }

    fn save_address(
        &mut self,
        uid: u32,
        install_id: &str,
        target: &Target,
        ip: IpAddr,
        expires: i64,
    ) {
        let saved = self
            .with_names
            .entry((uid, target.clone()))
            .or_insert_with(|| SavedAddresses {
                install_id: install_id.to_owned(),
                addresses: BTreeMap::new(),
            });
        if saved.install_id != install_id {
            saved.install_id = install_id.to_owned();
            saved.addresses.clear();
        }
        let expiry = saved.addresses.entry(ip).or_insert(expires);
        *expiry = (*expiry).max(expires);
        self.next_expiry = Some(self.next_expiry.map_or(*expiry, |old| old.min(*expiry)));
    }

    pub fn with_recording(&mut self, available: bool) {
        self.recording_available = available;
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
    fn wanted(&mut self, entry: &Entry) -> BTreeSet<Address> {
        let uid = entry.uid;
        let now = clock::now();
        let targets: BTreeSet<Target> = {
            let book = lock(&self.book);
            book.get(&uid).cloned().unwrap_or_default()
        };
        let mut wanted = BTreeSet::new();
        let mut with_names = BTreeSet::new();
        for target in targets {
            if let Target::Ip(ip, port) = target {
                wanted.insert((ip, port));
                continue;
            }
            if let Some(saved) = self.with_names.get(&(uid, target))
                && saved.install_id == entry.install_id
            {
                with_names.extend(
                    saved
                        .addresses
                        .iter()
                        .filter_map(|(&ip, &expires)| (expires > now).then_some(ip)),
                );
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

    fn apply_addresses(&mut self, uid: u32, wanted: BTreeSet<Address>) -> anyhow::Result<()> {
        // What the kernel side has of them, kept as each step is done: a
        // step that fails leaves it as far as the steps before got.
        let mut has = self.listed.remove(&uid).unwrap_or_default();
        let result = self.settle(uid, &wanted, &mut has);
        if !has.is_empty() {
            self.listed.insert(uid, has);
        }
        self.any.store(!self.listed.is_empty(), Ordering::Relaxed);
        result
    }

    /// Give the kernel side what holds for `uid`: its rules while it is in
    /// the sandbox, and whether its connections are recorded.
    fn carry_out(
        &mut self,
        uid: u32,
        wanted: BTreeSet<Address>,
        recorded: bool,
    ) -> anyhow::Result<()> {
        self.apply_addresses(uid, wanted)?;
        if recorded && self.recording_available {
            self.recorded.insert(uid)?;
            self.recording.insert(uid);
        } else {
            self.recorded.remove(uid)?;
            self.recording.remove(&uid);
        }
        Ok(())
    }

    fn apply_policy(&mut self, entry: &Entry, items: &Settings) -> anyhow::Result<()> {
        let wanted = self.wanted(entry);
        self.carry_out(entry.uid, wanted, items.network_recorded())
    }

    fn release(&mut self, uid: u32) -> anyhow::Result<()> {
        if let Some(names) = &self.names {
            names.keep_refused(uid, BTreeSet::new());
        }
        self.carry_out(uid, BTreeSet::new(), false)
    }
}

impl Effect for Destinations {
    fn name(&self) -> &'static str {
        "network destinations"
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        self.apply_policy(entry, items)
    }

    fn disable(&mut self, entry: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
        self.release(entry.uid)?;
        if let Some(names) = &self.names {
            names.forget(entry.uid);
        }
        Ok(())
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        _: &[String],
        _: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        self.apply_policy(entry, after)
    }

    fn refresh(&mut self, entry: &Entry, items: &Settings) -> anyhow::Result<()> {
        self.apply_policy(entry, items)
    }

    fn stage_name_rule(&mut self, entry: &Entry, target: &Target) {
        let Some(names) = &self.names else { return };
        let given = names.addresses_of(entry.uid, &entry.install_id, clock::now(), |host| {
            target.covers_name(host)
        });
        if given.is_empty() {
            return;
        }
        let key = (entry.uid, target.clone());
        self.staged_name = Some(StagedName {
            previous: self.with_names.get(&key).cloned(),
            key,
        });
        for (ip, expires) in given {
            self.save_address(entry.uid, &entry.install_id, target, ip, expires);
        }
    }

    fn cancel_name_rule(&mut self) {
        if let Some(staged) = self.staged_name.take() {
            if let Some(previous) = staged.previous {
                self.with_names.insert(staged.key, previous);
            } else {
                self.with_names.remove(&staged.key);
            }
        }
    }

    fn name_addresses(&self) -> Vec<NameAddress> {
        let Some(staged) = &self.staged_name else {
            return Vec::new();
        };
        let (uid, target) = &staged.key;
        let saved = &self.with_names[&staged.key];
        saved
            .addresses
            .iter()
            .map(|(ip, &expires)| NameAddress {
                uid: *uid,
                target: target.to_text(),
                install_id: saved.install_id.clone(),
                address: ip.to_string(),
                expires,
            })
            .collect()
    }

    fn retry_network(&mut self, snapshot: &super::Snapshot) {
        let now = clock::now();
        if self.next_expiry.is_none_or(|expiry| expiry > now) {
            return;
        }
        let expired: BTreeSet<_> = self
            .with_names
            .iter()
            .filter_map(|((uid, _), saved)| {
                saved
                    .addresses
                    .values()
                    .any(|&expires| expires <= now)
                    .then_some(*uid)
            })
            .collect();
        let active = read(snapshot);
        let mut failed = BTreeSet::new();
        for uid in expired {
            if let Some(entry) = active.get(&uid)
                && self.listed.contains_key(&uid)
            {
                // Expiry can withdraw addresses. Only normal policy application
                // may add them after verifying the installation.
                let mut wanted = self.wanted(entry);
                wanted.retain(|address| self.listed[&uid].contains(address));
                if self.apply_addresses(uid, wanted).is_err() {
                    failed.insert(uid);
                }
            }
        }
        self.next_expiry = None;
        self.with_names.retain(|(uid, _), saved| {
            if !failed.contains(uid) {
                saved.addresses.retain(|_, expires| *expires > now);
            }
            for &expiry in saved.addresses.values() {
                self.next_expiry = Some(self.next_expiry.map_or(expiry, |old| old.min(expiry)));
            }
            !saved.addresses.is_empty()
        });
    }

    fn finish_refresh(&mut self, uid: u32) {
        self.staged_name = None;
        // A rule being removed may still be restored if its removal fails.
        // Keep its addresses until that is known, even if the app since
        // looked up another name that resolves to the same address.
        let book = lock(&self.book);
        let targets = book.get(&uid);
        self.with_names
            .retain(|(of, target), _| *of != uid || targets.is_some_and(|t| t.contains(target)));
    }

    fn resume(&mut self, apps: &[Resumed]) {
        for app in apps {
            if let Err(e) = self.apply_policy(&app.entry, &app.items) {
                eprintln!(
                    "bbd: warning: the destinations uid {} is kept from are not all in place: {e:#}",
                    app.entry.uid
                );
            }
        }
    }

    fn forget(&mut self, uid: u32) {
        self.with_names.retain(|(owner, _), _| *owner != uid);
        if let Err(e) = self.release(uid) {
            eprintln!("bbd: warning: uid {uid} is still kept from destinations: {e:#}");
        }
        if let Some(names) = &self.names {
            names.forget(uid);
        }
    }

    fn report(&self, status: &mut Status) {
        status.net_record = self.recording_available;
        if status.net_record {
            status.recording_uids.extend(
                self.recording
                    .iter()
                    .copied()
                    .filter(|uid| matches!(self.recorded.contains(*uid), Ok(true))),
            );
        }
        status.traffic = self.traffic;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        sandbox::{Sandbox, tests::Fixture as SandboxFixture},
        sandbox_item::{Item, Mode},
        store::{
            Counters, Reader, Writer,
            net::{NetKey, NetSeen},
        },
        test_sync::{Cell, Rc, RefCell},
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
        fn contains(&self, uid: u32) -> anyhow::Result<bool> {
            Ok(self.0.borrow().recorded.contains(&uid))
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
        fn contains(&self, uid: u32) -> anyhow::Result<bool> {
            Ok(self.0.borrow().ruled.contains(&uid))
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

    struct NameGate;

    impl Effect for NameGate {
        fn name(&self) -> &'static str {
            "name gate"
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
            status.dns_names = true;
        }
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
        sandbox.add_effect(Box::new(NameGate));
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        (sandbox, kernel)
    }

    fn record_connection(writer: &mut Writer, ip: IpAddr, uid: u32, host: &str) {
        writer.net_record(
            NetKey {
                uid,
                hour: 0,
                host: host.into(),
                addr: ip.to_string(),
                port: 443,
            },
            NetSeen {
                conn: 1,
                ..NetSeen::default()
            },
            1000,
        );
    }

    fn seed_network_history(writer: &mut Writer, ip: IpAddr) {
        record_connection(writer, ip, 10100, "saved.example");
        record_connection(writer, ip, 10200, "other.example");
        writer.flush(&Counters::default(), 1000).unwrap();
        writer.set_net_recording(&[10100, 10200], 1000).unwrap();
        record_connection(writer, ip, 10100, "buffered.example");
    }

    #[test]
    fn name_rules_are_saved_when_the_gate_cannot_cover_the_resolver() {
        let mut f = SandboxFixture::new("sandbox-net-no-gate");
        f.writer
            .put_sandbox_global(&[("storage", "off"), ("cpu_info", "off")])
            .unwrap();
        let mut sandbox = f.sandbox();
        let Fixture {
            mut effect, kernel, ..
        } = fixture_with(sandbox.book());
        let names = Arc::new(Names::default());
        let ip = "203.0.113.7".parse().unwrap();
        names.learn(10100, Arc::from("com.app"), "example.com", &[ip]);
        effect.with_names(names);
        sandbox.add_effect(Box::new(effect));
        sandbox.add_effect(Box::new(crate::sandbox::gate::Unavailable(sandbox.book())));
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
            .unwrap();
        sandbox
            .net_block(
                &f.writer,
                &mut f.packages,
                "com.app",
                0,
                "host:example.com",
                true,
            )
            .unwrap();

        let answer = sandbox.answer(r#"{"cmd":"sandbox.list"}"#, &f.writer, &mut f.packages);
        let answer: serde_json::Value = serde_json::from_str(&answer).unwrap();
        assert_eq!(answer["apps"][0]["name_rules_not_in_effect"], 1);
        assert_eq!(f.writer.net_rules().unwrap().len(), 1);
        assert!(
            kernel
                .borrow()
                .rules
                .contains(&bytes(&key(10100, (ip, None))))
        );
        assert!(!sandbox.status().dns_names);
    }

    #[test]
    fn learned_name_addresses_survive_restart_and_are_bound_to_the_installation() {
        let mut f = SandboxFixture::new("sandbox-net-name-restart");
        let names = Arc::new(Names::default());
        let ip = "203.0.113.7".parse().unwrap();
        names.learn(10100, Arc::from("com.app"), "blocked.example", &[ip]);
        let (mut sandbox, kernel) = sandbox_with_filter(&mut f, names);
        let target = "domain:blocked.example";
        let rule_key = bytes(&key(10100, (ip, None)));
        sandbox
            .net_block(&f.writer, &mut f.packages, "com.app", 0, target, true)
            .unwrap();
        assert!(kernel.borrow().rules.contains(&rule_key));
        let db = f.dir.join("bb.db");
        let writer = Writer::open(&db).unwrap();
        let restore = |sandbox: &mut Sandbox| {
            let Fixture {
                mut effect, kernel, ..
            } = fixture_with(sandbox.book());
            effect.with_names(Arc::new(Names::default()));
            effect.load_addresses(&writer).unwrap();
            sandbox.add_effect(Box::new(effect));
            kernel
        };
        let mut restarted = f.sandbox();
        let kernel = restore(&mut restarted);
        restarted.resume(&writer, &mut f.packages);
        assert!(kernel.borrow().rules.contains(&rule_key));

        let db = rusqlite::Connection::open(db).unwrap();
        db.execute("UPDATE net_name_address SET expires = 0", [])
            .unwrap();
        let mut expired = f.sandbox();
        let kernel = restore(&mut expired);
        expired.resume(&writer, &mut f.packages);
        assert!(!kernel.borrow().rules.contains(&rule_key));

        db.execute(
            "UPDATE net_name_address SET expires = ?1",
            [clock::now() + 60],
        )
        .unwrap();
        db.execute(
            "UPDATE sandbox SET install_id = 'replacement' WHERE uid = 10100",
            [],
        )
        .unwrap();
        let mut replacement = f.sandbox();
        let kernel = restore(&mut replacement);
        replacement.resume(&writer, &mut f.packages);
        assert!(!kernel.borrow().rules.contains(&rule_key));
        assert_eq!(writer.net_rules().unwrap().len(), 1);
    }

    #[test]
    fn recording_status_requires_probe_coverage_and_uid_membership() {
        let mut f = fixture();
        let on = items(Mode::On);
        f.effect.with_recording(false);
        f.effect.enable(&mut entry(10001), &[], &on).unwrap();
        let mut status = Status::default();
        f.effect.report(&mut status);
        assert!(!status.net_record);
        assert!(status.recording_uids.is_empty());

        f.effect.with_recording(true);
        f.effect.enable(&mut entry(10001), &[], &on).unwrap();
        let mut status = Status::default();
        f.effect.report(&mut status);
        assert_eq!(status.recording_uids, BTreeSet::from([10001]));

        f.kernel.borrow_mut().recorded.clear();
        let mut status = Status::default();
        f.effect.report(&mut status);
        assert!(status.recording_uids.is_empty());
    }

    #[test]
    fn a_failed_network_cleanup_keeps_uid_retirement_retryable() {
        let mut f = SandboxFixture::new("sandbox-retire-net");
        let names = Arc::new(Names::default());
        let ip = "203.0.113.7".parse().unwrap();
        names.learn(10100, Arc::from("com.app"), "blocked.example", &[ip]);
        let (mut sandbox, kernel) = sandbox_with_filter(&mut f, names.clone());
        let target = "domain:blocked.example";
        let key = bytes(&key(10100, (ip, None)));
        sandbox
            .net_block(&f.writer, &mut f.packages, "com.app", 0, target, true)
            .unwrap();
        let rule = f.writer.net_rules().unwrap();
        let original = f.writer.sandbox_entry(10100).unwrap().unwrap();
        seed_network_history(&mut f.writer, ip);

        let db = f.dir.join("bb.db");
        let connection = rusqlite::Connection::open(&db).unwrap();
        connection
            .execute_batch(
                "CREATE TRIGGER fail_retired_rule_delete BEFORE DELETE ON net_rule
             BEGIN SELECT RAISE(FAIL, 'injected rule delete failure'); END;",
            )
            .unwrap();
        std::fs::write(
            f.dir.join("packages.list"),
            "com.successor 10100 0 /d x\ncom.shared.one 10200 0 /d x\n",
        )
        .unwrap();
        sandbox.release_reassigned(&f.writer, &mut f.packages);
        let reloaded = Writer::open(&db).unwrap();
        assert_eq!(
            reloaded.sandbox_entry(10100).unwrap(),
            Some(original.clone())
        );
        assert!(sandbox.snapshot().read().unwrap().contains_key(&10100));
        assert!(kernel.borrow().rules.contains(&key));
        assert_eq!(reloaded.net_rules().unwrap(), rule);
        assert_eq!(reloaded.net_recording().unwrap().get(&10100), Some(&1000));
        let reader = Reader::open(&db).unwrap();
        assert_eq!(reader.net_rows(10100, 0, 3600).unwrap().len(), 1);
        f.writer.flush(&Counters::default(), 1001).unwrap();
        assert_eq!(reader.net_rows(10100, 0, 3600).unwrap().len(), 2);
        record_connection(&mut f.writer, ip, 10100, "pending.example");

        connection
            .execute_batch("DROP TRIGGER fail_retired_rule_delete")
            .unwrap();
        sandbox.release_reassigned(&f.writer, &mut f.packages);
        let retired = Entry {
            enabled: false,
            ..original
        };
        assert_eq!(reloaded.sandbox_entry(10100).unwrap(), Some(retired));
        assert!(reloaded.net_rules().unwrap().is_empty());
        assert!(!reloaded.net_recording().unwrap().contains_key(&10100));
        assert_eq!(reloaded.net_recording().unwrap().get(&10200), Some(&1000));
        assert!(!kernel.borrow().rules.contains(&key));
        assert!(!names.refused(10100, ip));
        f.writer.flush(&Counters::default(), 1002).unwrap();
        assert!(reader.net_rows(10100, 0, 3600).unwrap().is_empty());
        assert_eq!(reader.net_rows(10200, 0, 3600).unwrap().len(), 1);

        let mut restarted = f.sandbox();
        let Fixture {
            mut effect, kernel, ..
        } = fixture_with(restarted.book());
        effect.with_names(names.clone());
        restarted.add_effect(Box::new(effect));
        names.learn(10100, Arc::from("com.app"), "blocked.example", &[ip]);
        restarted
            .enable(&f.writer, &mut f.packages, "com.successor", 0, 1003)
            .unwrap();
        assert!(!kernel.borrow().rules.contains(&key));
    }

    #[test]
    fn disabling_the_same_installation_keeps_its_rules_for_the_next_enable() {
        let mut f = SandboxFixture::new("sandbox-disable-net");
        let names = Arc::new(Names::default());
        let ip = "203.0.113.7".parse().unwrap();
        names.learn(10100, Arc::from("com.app"), "blocked.example", &[ip]);
        let (mut sandbox, kernel) = sandbox_with_filter(&mut f, names);
        let key = bytes(&key(10100, (ip, None)));
        sandbox
            .net_block(
                &f.writer,
                &mut f.packages,
                "com.app",
                0,
                "domain:blocked.example",
                true,
            )
            .unwrap();
        let rules = f.writer.net_rules().unwrap();
        sandbox
            .disable(&f.writer, &mut f.packages, "com.app", 0)
            .unwrap();
        assert!(!kernel.borrow().rules.contains(&key));
        assert_eq!(f.writer.net_rules().unwrap(), rules);
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 1001)
            .unwrap();
        assert!(kernel.borrow().rules.contains(&key));
        assert_eq!(f.writer.net_rules().unwrap(), rules);
    }

    #[test]
    fn a_failed_rule_delete_keeps_the_destination_refused_live_and_after_reload() {
        let mut f = SandboxFixture::new("sandbox-net-delete");
        let names = Arc::new(Names::default());
        let ip = "203.0.113.7".parse().unwrap();
        names.learn(10100, Arc::from("com.app"), "blocked.example", &[ip]);
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
        names.learn(10100, Arc::from("com.app"), "blocked.example", &[ip]);
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
        names.learn(10100, Arc::from("com.app"), "blocked.example", &[ip]);
        let (mut sandbox, kernel) = sandbox_with_filter(&mut f, names.clone());
        let target = "domain:blocked.example";
        let key = bytes(&key(10100, (ip, None)));
        sandbox
            .net_block(&f.writer, &mut f.packages, "com.app", 0, target, true)
            .unwrap();
        names.learn(10100, Arc::from("com.app"), "different.example", &[ip]);
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
        names.learn(
            10001,
            Arc::from(""),
            "t.adnet.example",
            &["203.0.113.50".parse().unwrap()],
        );
        names.learn(
            10001,
            Arc::from(""),
            "api.example.com",
            &["203.0.113.10".parse().unwrap()],
        );
        names.learn(
            10002,
            Arc::from(""),
            "t.adnet.example",
            &["203.0.113.51".parse().unwrap()],
        );
        f.rule(10001, "domain:adnet.example");
        let domain = Target::parse("domain:adnet.example").unwrap();
        f.effect.stage_name_rule(&entry(10001), &domain);
        f.effect.finish_refresh(10001);
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
            Arc::from(""),
            "www.example.net",
            &["203.0.113.50".parse().unwrap()],
        );
        view.names.as_ref().unwrap().learn(
            10001,
            Arc::from(""),
            "u.adnet.example",
            &["203.0.113.52".parse().unwrap()],
        );
        f.effect.stage_name_rule(&entry(10001), &domain);
        f.effect.refresh(&entry(10001), &items(Mode::Off)).unwrap();
        f.effect.finish_refresh(10001);
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
