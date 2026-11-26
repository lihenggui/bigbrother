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

//! Decode kernel events and accumulate records for the daemon's writer.

mod attribution;
mod decode;
mod netrec;
mod provider;
#[cfg(test)]
mod tests;

use std::{
    collections::{HashMap, HashSet},
    time::{Duration, Instant},
};

use aya::maps::{MapData, RingBuf};
use bb_common::{
    EV_BINDER, EV_CONNECT, EV_EXEC, EV_FILE, EV_NETLINK, EV_PATH, EV_PROCESS, EV_RESULT,
    EV_TRAFFIC, EventHeader, FIRST_APPLICATION_UID, read_event,
};

use crate::{
    appops,
    binder::Catalog,
    clock::{clock_ns, now},
    commands,
    log::{Limited, Policy, RECURRING_INTERVAL},
    mounts::Mounts,
    net::{self, Listeners},
    packages::{Packages, app_id_of},
    proc::process_alive,
    record::{
        Category, MODE_CALL, MODE_CONNECT, MODE_DIR, MODE_EXEC, MODE_PROBE, MODE_QUERY, MODE_READ,
        MODE_VIA_PROVIDER, MODE_WATCH, MODE_WRITE, Mode,
    },
    sandbox, selftest, sensors,
    spool::{self, Spool},
    store::{Counters, Writer},
};

/// A destination of the network records: a name, an address and a port.
type NetDestination = (String, String, u16);

/// Turns the times the kernel gives events, nanoseconds since boot, into
/// unix time.
struct EventClock {
    /// Unix time at boot, in nanoseconds.
    boot_unix_ns: i64,
}

impl EventClock {
    /// Taken anew for each batch of events, so that the wall clock being set
    /// carries over.
    fn now() -> Self {
        Self {
            boot_unix_ns: clock_ns(libc::CLOCK_REALTIME) - clock_ns(libc::CLOCK_BOOTTIME),
        }
    }

    fn unix_secs(&self, boot_ns: u64) -> i64 {
        (self.boot_unix_ns + boot_ns as i64).div_euclid(1_000_000_000)
    }
}

/// A file the kernel opened, held until the syscall's return value arrives.
struct Opened {
    uid: u32,
    pid: u32,
    path: String,
    mode: Mode,
    ts: i64,
}

/// Something an app did, held until its syscall's return value arrives.
enum Held {
    File(Opened),
    /// A program it started; `subject` is what to store (see `commands`).
    Command {
        uid: u32,
        pid: u32,
        subject: String,
        /// [`crate::record::MODE_ISOLATED`] if an isolated process started it.
        origin: Mode,
        ts: i64,
    },
    /// Anything else: what to store is already decided, only the outcome is
    /// missing.
    Record {
        uid: u32,
        cat: Category,
        subject: String,
        mode: Mode,
        ts: i64,
    },
}

impl Held {
    fn ts(&self) -> i64 {
        match self {
            Self::File(opened) => opened.ts,
            Self::Command { ts, .. } | Self::Record { ts, .. } => *ts,
        }
    }
}

pub(super) struct Tracer {
    pub(super) packages: Packages,
    mounts: Mounts,
    spool: Spool,
    listeners: Listeners,
    /// Ports each app has contacted, to recognise a scan.
    scans: net::Scans,
    /// What the sandbox refuses of the network, for the connections
    /// recorded of its apps.
    pub(super) net: Option<sandbox::destinations::View>,
    pub(super) refused_lookups: Option<sandbox::names::RefusedLookups>,
    /// The destinations each app has rows of in the hour (see `net_key`).
    net_listed: (i64, HashMap<u32, HashSet<NetDestination>>),
    /// Commands being looked for along `PATH`.
    searches: commands::Searches,
    /// The service methods being watched; `None` until they are known.
    pub(super) catalog: Option<Catalog>,
    /// The sensors apps have on.
    pub(super) sensors: sensors::Sensors,
    /// Sensor sessions not kept for being over the limits, as last written
    /// (`sensor_sessions_dropped` in `bbd stats`), and its log.
    sensors_dropped: u64,
    sensors_dropped_log: Limited,
    pub(super) writer: Writer,
    pub(super) write_errors: Limited,
    record_drops: Limited,
    pub(super) counters: Counters,
    /// Keyed by thread id.
    awaiting_result: HashMap<u32, Vec<Held>>,
    /// (pid, isolated uid) -> the app it was attributed to, and when last seen.
    isolated_owners: HashMap<(u32, u32), (u32, Instant)>,
    verbose: bool,
    explain_discards: bool,
    ingest_provider_events: bool,
    /// The self-check's events (see `selftest`), which are not recorded.
    pub(super) selftest: selftest::Seen,
}

impl Tracer {
    pub(super) fn new(
        packages_list: &str,
        writer: Writer,
        sensors: sensors::Sensors,
        verbose: bool,
        explain_discards: bool,
        ingest_provider_events: bool,
    ) -> Self {
        Self {
            packages: Packages::new(packages_list),
            mounts: Mounts::new(),
            spool: Spool::new(),
            listeners: Listeners::new(),
            scans: net::Scans::default(),
            net: None,
            refused_lookups: None,
            net_listed: (0, HashMap::new()),
            searches: commands::Searches::default(),
            catalog: None,
            sensors,
            sensors_dropped: 0,
            sensors_dropped_log: Limited::new(RECURRING_INTERVAL, Policy::ResetOnChange),
            writer,
            write_errors: Limited::new(RECURRING_INTERVAL, Policy::ResetOnChange),
            record_drops: Limited::new(RECURRING_INTERVAL, Policy::Shared),
            counters: Counters::default(),
            awaiting_result: HashMap::new(),
            isolated_owners: HashMap::new(),
            verbose,
            explain_discards,
            ingest_provider_events,
            selftest: selftest::Seen::default(),
        }
    }

    pub(super) fn write_coverage(&mut self, key: &str, covered: i64) -> bool {
        match self.writer.set_meta(key, covered) {
            Ok(()) => true,
            Err(e) => {
                self.write_errors
                    .log(format_args!("bbd: could not write {key}: {e:#}"));
                false
            }
        }
    }

    pub(super) fn flush(&mut self, query: bool, at: i64) -> anyhow::Result<()> {
        let dropped = if query {
            self.writer.flush_for_query(&self.counters, at)?
        } else {
            self.writer.flush(&self.counters, at)?
        };
        if dropped {
            self.record_drops.log(format_args!(
                "bbd: warning: dropping buffered records to preserve space for sandbox recovery"
            ));
        }
        Ok(())
    }

    /// Yield to control requests even when the kernel keeps producing.
    pub(super) fn drain_batch(&mut self, ring: &mut RingBuf<MapData>) -> bool {
        let clock = EventClock::now();
        for _ in 0..1024 {
            let Some(item) = ring.next() else { return true };
            self.on_event(&item, &clock);
        }
        false
    }

    #[cfg(test)]
    pub(crate) fn on_test_event(&mut self, bytes: &[u8], boot_unix: i64) {
        self.on_event(
            bytes,
            &EventClock {
                boot_unix_ns: boot_unix * 1_000_000_000,
            },
        );
    }

    /// A flush, self-check or stop needs all records already readable when it
    /// starts. Short records can fill several batches without waking the
    /// reader. Stop at the first newer event so producers cannot hold a flush
    /// open indefinitely. Every event is stamped on the kernel's boot clock.
    pub(super) fn drain_pending(&mut self, ring: &mut RingBuf<MapData>) {
        let through = clock_ns(libc::CLOCK_BOOTTIME) as u64;
        let clock = EventClock::now();
        while let Some(item) = ring.next() {
            self.on_event(&item, &clock);
            if read_event::<EventHeader>(&item).is_some_and(|header| header.boot_ns() > through) {
                break;
            }
        }
    }

    fn hold_result(&mut self, tid: u32, event: Held) {
        if self.awaiting_result.len() < 1024 || self.awaiting_result.contains_key(&tid) {
            let waiting = self.awaiting_result.entry(tid).or_default();
            if waiting.len() < 16 {
                waiting.push(event);
                return;
            }
        }
        self.counters.unresolved += 1;
    }

    fn on_event(&mut self, bytes: &[u8], clock: &EventClock) {
        let Some(hdr) = read_event::<EventHeader>(bytes) else {
            return;
        };
        if self.selftest.claim(bytes) {
            return;
        }
        let ts = clock.unix_secs(hdr.boot_ns());
        match hdr.kind {
            EV_FILE => self.on_file(bytes, hdr, ts),
            EV_RESULT => self.on_result(bytes, hdr.tid),
            EV_PATH => self.on_path(bytes, hdr, ts),
            EV_EXEC => self.on_exec(bytes, hdr, ts),
            EV_PROCESS => self.on_process(bytes, hdr, ts),
            EV_BINDER => self.on_binder(bytes, hdr, ts),
            EV_TRAFFIC => self.on_traffic(bytes, hdr.uid, ts),
            EV_NETLINK => self.on_netlink(bytes, hdr, ts),
            EV_CONNECT => self.on_connect(bytes, hdr, ts),
            _ => {}
        }
    }

    /// Hand what is known of the sensors apps have on to the writer, after
    /// ending the sessions of processes that have ended. What waits for the
    /// sensor list is held for at most `wait`.
    pub(super) fn settle_sensors(&mut self, wait: Duration) {
        if self.sensors.any_on() {
            self.sensors.check_processes(now(), process_alive);
        }
        let settled = self.sensors.settle(Instant::now(), now(), wait);
        for (uid, mode, subject, ts) in settled.calls {
            self.store(uid, Category::Service, subject, mode, true, ts, 0);
        }
        for session in settled.sessions {
            self.writer.sensor_session(session);
        }
        // Written when it changes, which takes an app over the limits.
        let dropped = self.sensors.dropped();
        if dropped != self.sensors_dropped
            && self
                .writer
                .set_meta("sensor_sessions_dropped", dropped as i64)
                .is_ok()
        {
            self.sensors_dropped_log.log(format_args!(
                "bbd: sensor sessions not kept: an app has too many open"
            ));
            self.sensors_dropped = dropped;
        }
    }

    /// Whether nothing is recorded of `uid`: the storage providers serve
    /// shared storage to every other app. Indexing and opening user files is
    /// their function, and would account for most of the records.
    fn excluded(&mut self, uid: u32) -> bool {
        self.packages
            .for_uid(uid)
            .iter()
            .any(|p| spool::PROVIDER_PACKAGES.contains(&p.as_str()))
    }

    /// Store what the system recorded about permissions being used.
    pub(super) fn record_permission_uses(&mut self, uses: Vec<appops::Use>) {
        for used in uses {
            self.counters.seen += 1;
            if app_id_of(used.uid) < FIRST_APPLICATION_UID {
                continue;
            }
            self.store(
                used.uid,
                Category::Permission,
                used.subject,
                MODE_READ,
                used.allowed,
                used.ts,
                0,
            );
        }
    }

    fn explain(&self, source: &str) {
        if self.explain_discards {
            println!("discard {source}");
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn store(
        &mut self,
        uid: u32,
        cat: Category,
        path: String,
        mode: Mode,
        ok: bool,
        ts: i64,
        items: u32,
    ) {
        if self.excluded(uid) {
            return;
        }
        let pkg = self
            .packages
            .for_uid(uid)
            .first()
            .map_or("", String::as_str);
        self.counters.kept += 1;
        if self.verbose {
            println!(
                "cat={} how={} {}",
                cat.as_str(),
                mode_label(mode),
                if ok { "ok" } else { "failed" },
            );
        }
        self.writer.record(uid, pkg, cat, path, mode, ok, ts, items);
    }
}

fn mode_label(mode: Mode) -> &'static str {
    if mode & MODE_EXEC != 0 {
        return "exec";
    }
    if mode & MODE_CONNECT != 0 {
        return "connect";
    }
    if mode & MODE_CALL != 0 {
        return "call";
    }
    if mode & MODE_QUERY != 0 {
        return "query";
    }
    if mode & MODE_VIA_PROVIDER != 0 {
        return if mode & MODE_WRITE != 0 {
            "write-via-provider"
        } else {
            "read-via-provider"
        };
    }
    if mode & MODE_PROBE != 0 {
        return "probe";
    }
    if mode & MODE_WATCH != 0 {
        return "watch";
    }
    match (mode & MODE_DIR != 0, mode & (MODE_READ | MODE_WRITE)) {
        (true, _) => "list",
        (false, MODE_READ) => "read",
        (false, MODE_WRITE) => "write",
        (false, _) => "read+write",
    }
}
