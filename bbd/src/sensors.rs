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

//! Which sensors apps turn on, how fast, and for how long.
//!
//! An app reads sensors through a connection to the sensor service
//! (`ISensorEventConnection`, written in C++), on which it turns each sensor
//! on with the sampling period it wants, and off again. The kernel side
//! reports these calls (see `binder`), with the caller's binder handle for
//! the connection, which tells two connections of one process apart.
//!
//! Two things are kept:
//! - each time a sensor is turned on, as a call:
//!   `binder:android.gui.SensorEventConnection/enableDisable@<type>`, with
//!   `:fast` after the type when the rate asked for is high (see
//!   [`crate::record::HIGH_RATE_PERIOD_NS`]). `<type>` is the sensor type's
//!   name, such as `android.sensor.accelerometer`. An unidentified sensor
//!   has no `@<type>`. The `@` keeps the type from being read as the
//!   `#<package>` of other calls, which the app reads as another app;
//! - each period a sensor was on, as a session (`record::SensorSession`):
//!   from the call that turned it on to the one that turned it off, the
//!   connection closing, or the app's process ending.
//!
//! Sessions are keyed by process, connection and sensor. Turning on a
//! sensor that is already on, or changing its rate, with a different rate
//! ends the session and starts another at the same time, so each session
//! has one rate; within the second the session started, the rate is
//! updated instead. Turning a sensor on wakes the daemon at once, and
//! while a session is open, whether its process still runs is checked
//! every 5 seconds (`SENSOR_PROCESS_CHECK` in `trace`); the sessions of one
//! that has ended end when it was last found running or last made a call,
//! up to 5 seconds early, rather than when that was noticed, which after
//! the device slept can be hours late. A process is known by its pid and start time, so a
//! process that reuses a pid takes over none of the earlier one's sessions.
//! Each app may have 64 sessions open and 256 sensors turned on waiting to
//! be identified; beyond that, sessions are counted
//! (`sensor_sessions_dropped`) rather than kept, and calls are stored
//! without the sensor rather than held back.
//! Sessions a previous run of the daemon left open go on if their process
//! still runs and, once calls are seen again, the sensor service still has
//! the sensor on for the app; the others end when the previous run was last
//! known to be up, or when they are found off.
//!
//! The calls name sensors by handle, a number that differs from device to
//! device. Which type each handle is comes from the sensor service's dump,
//! read with `dumpsys` on a thread of its own: the first time a sensor is
//! turned on, and again, at most once a minute, when one turns up that the
//! list does not have (sensors connected later). A handle still missing
//! after that is remembered, and not looked for again. Only the list's own
//! section of the dump is read: the rest holds names apps chose.
//!
//! Only the request is seen. What the app was given can be less: Android 12
//! and later deliver at most 200 Hz to apps without the
//! HIGH_SAMPLING_RATE_SENSORS permission, sensors may batch their events,
//! and apps idle in the background get nothing from most sensors. A
//! one-shot sensor (significant motion) turns itself off once it fires,
//! which is not seen; its session lasts until it is turned off, the
//! connection closes or the process ends.

use std::{
    collections::HashMap,
    time::{Duration, Instant},
};

use crate::{
    binder::{self, CONNECTION, ENABLE_DISABLE, SENSOR_MARK, SensorRequest},
    record::{self, is_high_rate},
};

mod handles;

use handles::{Lookup, SensorList};

/// Put after the type when the rate asked for is high.
const FAST: &str = ":fast";

/// What to store for a sensor turned on: the call, with the sensor's type
/// if it is known, marked when the rate is high.
pub fn subject(sensor: Option<&Sensor>, period_ns: i64) -> String {
    let mut subject = format!("{}{CONNECTION}/{ENABLE_DISABLE}", binder::SCHEME);
    if let Some(sensor) = sensor {
        subject.push(SENSOR_MARK);
        subject.push_str(&sensor.kind);
        if is_high_rate(sensor.continuous, period_ns) {
            subject.push_str(FAST);
        }
    }
    subject
}

// ---- the device's sensors ---------------------------------------------------

/// A sensor of this device.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Sensor {
    /// Its type's name: `android.sensor.accelerometer`, a manufacturer's
    /// own, or, where the device gives none, the type's number. Only ASCII
    /// letters, digits, `.`, `_` and `-`.
    pub kind: String,
    /// Whether it reports at a steady rate, the only kind for which the
    /// rate asked for means something.
    pub continuous: bool,
}

/// Read the sensor service's dump. Run as root.
pub fn dumpsys() -> anyhow::Result<String> {
    let output = std::process::Command::new("/system/bin/dumpsys")
        // The service's own time limit, rather than the default 10 s.
        .args(["-t", "5", "sensorservice"])
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .output()?;
    anyhow::ensure!(
        output.status.success(),
        "dumpsys ended with {}",
        output.status
    );
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// A call to a sensor connection, as reported by the kernel side.
#[derive(Clone, Copy, Debug)]
pub struct Call {
    /// The app the call is recorded under.
    pub uid: u32,
    /// The calling process's own uid: for an isolated process, not its
    /// app's. The sensor service knows connections by it.
    pub process_uid: u32,
    /// What to store the call with when it is recorded (`record::MODE_*`).
    pub mode: record::Mode,
    pub pid: u32,
    /// The caller's binder handle for the connection.
    pub connection: u32,
    pub request: SensorRequest,
    pub ts: i64,
}

/// A call to store: (uid, mode, subject, ts).
pub type CallRecord = (u32, record::Mode, String, i64);

/// Most sessions open at once, and most sensors turned on waiting for the
/// list to say what they are, per app. Handles come from apps, and an app
/// going through made-up handles must neither fill the daemon's memory nor
/// crowd out other apps. Real use is a handful of each. The totals bound
/// what many apps together can hold.
const MAX_OPEN_PER_APP: usize = 64;
const MAX_WAITING_PER_APP: usize = 256;
const MAX_OPEN: usize = 4096;
const MAX_WAITING: usize = 16384;

/// One sensor on one connection of one process.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
struct Key {
    pid: u32,
    connection: u32,
    handle: i32,
}

#[derive(Clone, Debug)]
struct Session {
    row: record::SensorSession,
    /// The process's own uid (see [`Call::process_uid`]).
    process_uid: u32,
    /// When the call that started it arrived; until the list has been read
    /// since, an unknown handle may still turn up in it.
    seen: Instant,
    /// The latest time (unix) it was known to be on: its start, a later
    /// call, or a check that found its process running. A process that has
    /// ended ended its sessions at this time, as far as can be known.
    on_at: i64,
    /// What the handle stands for, once looked up: `Some(None)` if the list
    /// could not be read.
    sensor: Option<Option<Sensor>>,
    /// The device has no sensor by that handle: nothing was turned on.
    no_sensor: bool,
    /// Changed since it was last handed out to be written.
    dirty: bool,
    /// Taken over from a previous run and not yet checked with the sensor
    /// service: it may have been turned off before calls were seen again.
    taken_over: bool,
}

/// A sensor turned on, stored as a call once the sensor is known.
#[derive(Debug)]
struct TurnedOn {
    uid: u32,
    mode: record::Mode,
    handle: i32,
    period_ns: i64,
    ts: i64,
    seen: Instant,
}

/// What is ready to be written.
#[derive(Debug, Default, PartialEq)]
pub struct Settled {
    /// Each sensor turned on.
    pub calls: Vec<CallRecord>,
    /// Sessions started, changed or ended since the last time, in the order
    /// to write them: a later row of the same session overwrites an earlier.
    pub sessions: Vec<record::SensorSession>,
}

/// The sensors apps have on, and what is to be written about them.
pub struct Sensors {
    list: SensorList,
    open: HashMap<Key, Session>,
    /// Ended sessions not handed out yet.
    ended: Vec<Session>,
    turned_on: Vec<TurnedOn>,
    /// Open sessions and calls in `turned_on`, per app.
    open_per_app: HashMap<u32, usize>,
    waiting_per_app: HashMap<u32, usize>,
    /// Sessions not kept since the start because their app, or all apps
    /// together, had as many open as kept.
    dropped: u64,
    /// Since when calls are seen, while sessions taken over wait to be
    /// checked with the sensor service at the next reading.
    check_taken_over: Option<Instant>,
}

impl Sensors {
    /// `read` produces the sensor service's dump ([`dumpsys`]).
    pub fn new(read: fn() -> anyhow::Result<String>) -> Self {
        Self {
            list: SensorList::new(read),
            open: HashMap::new(),
            ended: Vec::new(),
            turned_on: Vec::new(),
            open_per_app: HashMap::new(),
            waiting_per_app: HashMap::new(),
            dropped: 0,
            check_taken_over: None,
        }
    }

    /// Whether any sensor is on: only then are processes checked.
    pub fn any_on(&self) -> bool {
        !self.open.is_empty()
    }

    /// Sessions not kept since the start, for being over the limits.
    pub fn dropped(&self) -> u64 {
        self.dropped
    }

    /// Calls are seen from `now` on. Sessions taken over from a previous run
    /// may have been turned off before; the sensor service is asked which
    /// still are, once, at the next write.
    pub fn watching(&mut self, now: Instant) {
        if self.open.values().any(|s| s.taken_over) {
            self.check_taken_over = Some(now);
        }
    }

    /// Take over the sessions a previous run left open, at `ts`. Those whose
    /// process still runs (`process_uid(pid, start time)` gives its uid) go
    /// on until [`Self::watching`] checks them; the others ended at the
    /// latest when the previous run was last known to be up, `last_up`.
    pub fn adopt(
        &mut self,
        rows: Vec<record::SensorSession>,
        process_uid: impl Fn(u32, u64) -> Option<u32>,
        last_up: i64,
        now: Instant,
        ts: i64,
    ) {
        for row in rows {
            let sensor = (!row.sensor.is_empty()).then(|| Sensor {
                kind: row.sensor.clone(),
                continuous: row.continuous,
            });
            let key = Key {
                pid: row.pid,
                connection: row.connection,
                handle: row.handle,
            };
            let running = process_uid(row.pid, row.proc_start);
            let mut session = Session {
                process_uid: running.unwrap_or(row.uid),
                seen: now,
                on_at: ts,
                sensor: Some(sensor),
                no_sensor: false,
                dirty: false,
                taken_over: true,
                row,
            };
            if running.is_some() && !self.open.contains_key(&key) {
                self.insert(key, session);
            } else {
                session.row.end_ts = Some(last_up.max(session.row.start_ts));
                session.dirty = true;
                self.ended.push(session);
            }
        }
    }

    /// Take in a call. `proc_start` gives the start time of a process,
    /// `None` if it has ended. Returns the call to store now, if it turned a
    /// sensor on that is known (or known not to exist); the others are
    /// handed out by [`Self::settle`].
    pub fn on_call(
        &mut self,
        call: Call,
        proc_start: impl Fn(u32) -> Option<u64>,
        now: Instant,
    ) -> Option<CallRecord> {
        let key = |handle| Key {
            pid: call.pid,
            connection: call.connection,
            handle,
        };
        match call.request {
            SensorRequest::Enable { handle, period_ns } => {
                return self.on_enable(&call, key(handle), period_ns, proc_start, now);
            }
            SensorRequest::Disable { handle } => self.on_disable(&call, key(handle), proc_start),
            SensorRequest::Rate { handle, period_ns } => {
                self.on_rate(&call, key(handle), period_ns, proc_start, now);
            }
            SensorRequest::Close => self.on_close(&call, proc_start),
        }
        None
    }

    fn on_enable(
        &mut self,
        call: &Call,
        key: Key,
        period_ns: i64,
        proc_start: impl Fn(u32) -> Option<u64>,
        now: Instant,
    ) -> Option<CallRecord> {
        let handle = key.handle;
        let record = self.turned_on(call, handle, period_ns, now);
        let start = proc_start(call.pid);
        self.end_if_other_process(key, start);
        if let Some(session) = self.open.get_mut(&key) {
            // Turning on a sensor that is on sets its rate.
            session.on_at = session.on_at.max(call.ts);
            session.taken_over = false;
            self.set_rate(key, period_ns, call.ts, now);
            return record;
        }
        if self.list.known(handle) == Lookup::NotFound {
            return record;
        }
        let per_app = self.open_per_app.get(&call.uid).copied().unwrap_or(0);
        if per_app >= MAX_OPEN_PER_APP || self.open.len() >= MAX_OPEN {
            self.dropped += 1;
            return record;
        }
        self.insert(
            key,
            Session {
                row: record::SensorSession {
                    uid: call.uid,
                    pid: call.pid,
                    // A process gone already has 0, which no
                    // process has: its sessions end at the next
                    // check, when it was last known on.
                    proc_start: start.unwrap_or(0),
                    connection: call.connection,
                    handle,
                    sensor: String::new(),
                    continuous: false,
                    period_ns,
                    start_ts: call.ts,
                    end_ts: None,
                },
                process_uid: call.process_uid,
                seen: now,
                on_at: call.ts,
                sensor: None,
                no_sensor: false,
                dirty: true,
                taken_over: false,
            },
        );
        record
    }

    fn on_disable(&mut self, call: &Call, key: Key, proc_start: impl Fn(u32) -> Option<u64>) {
        if self.open.contains_key(&key) && !self.end_if_other_process(key, proc_start(call.pid)) {
            self.end(key, call.ts);
        }
    }

    fn on_rate(
        &mut self,
        call: &Call,
        key: Key,
        period_ns: i64,
        proc_start: impl Fn(u32) -> Option<u64>,
        now: Instant,
    ) {
        if self.open.contains_key(&key) && !self.end_if_other_process(key, proc_start(call.pid)) {
            if let Some(session) = self.open.get_mut(&key) {
                session.on_at = session.on_at.max(call.ts);
            }
            self.set_rate(key, period_ns, call.ts, now);
        }
    }

    fn on_close(&mut self, call: &Call, proc_start: impl Fn(u32) -> Option<u64>) {
        let closed: Vec<Key> = self
            .open
            .keys()
            .filter(|k| k.pid == call.pid && k.connection == call.connection)
            .copied()
            .collect();
        // Read the process start once, and only if there is a session to end.
        let mut started = None;
        for key in closed {
            let start = *started.get_or_insert_with(|| proc_start(call.pid));
            if !self.end_if_other_process(key, start) {
                self.end(key, call.ts);
            }
        }
    }

    /// The call for a sensor turned on: at once if what the sensor is is
    /// known, otherwise once the list has been read. An app over its share
    /// of calls waiting has the call stored at once without the sensor,
    /// rather than not at all.
    fn turned_on(
        &mut self,
        call: &Call,
        handle: i32,
        period_ns: i64,
        now: Instant,
    ) -> Option<CallRecord> {
        let record = |sensor: Option<&Sensor>| {
            Some((call.uid, call.mode, subject(sensor, period_ns), call.ts))
        };
        match self.list.known(handle) {
            Lookup::Found(sensor) => return record(Some(&sensor)),
            Lookup::NotFound => return record(None),
            _ => {}
        }
        let waiting = self.waiting_per_app.entry(call.uid).or_default();
        if *waiting >= MAX_WAITING_PER_APP || self.turned_on.len() >= MAX_WAITING {
            return record(None);
        }
        *waiting += 1;
        self.turned_on.push(TurnedOn {
            uid: call.uid,
            mode: call.mode,
            handle,
            period_ns,
            ts: call.ts,
            seen: now,
        });
        None
    }

    /// If the session at `key` is of an earlier process whose pid the caller
    /// (which started at `start`) has taken over, end it when it was last
    /// known on: that process has ended. Returns whether it did.
    fn end_if_other_process(&mut self, key: Key, start: Option<u64>) -> bool {
        let Some(session) = self.open.get(&key) else {
            return false;
        };
        // A caller gone already can't be told apart; it made the call, so
        // it is taken for the same process.
        if start.is_none_or(|start| start == session.row.proc_start) {
            return false;
        }
        let on_at = session.on_at;
        self.end(key, on_at);
        true
    }

    /// A new rate for an open session: it ends, and another starts.
    fn set_rate(&mut self, key: Key, period_ns: i64, ts: i64, now: Instant) {
        let Some(session) = self.open.get_mut(&key) else {
            return;
        };
        // A call to it shows it is on.
        session.taken_over = false;
        if session.row.period_ns == period_ns {
            return;
        }
        if session.row.start_ts >= ts {
            // Within its first second: the same session, at the new rate.
            session.row.period_ns = period_ns;
            session.dirty = true;
            return;
        }
        let mut next = session.clone();
        next.row.period_ns = period_ns;
        next.row.start_ts = ts;
        next.seen = now;
        next.dirty = true;
        self.end(key, ts);
        self.insert(key, next);
    }

    fn insert(&mut self, key: Key, session: Session) {
        *self.open_per_app.entry(session.row.uid).or_default() += 1;
        if let Some(replaced) = self.open.insert(key, session) {
            self.forget(&replaced);
        }
    }

    fn remove(&mut self, key: Key) -> Option<Session> {
        let session = self.open.remove(&key)?;
        self.forget(&session);
        Some(session)
    }

    /// Count an open session out of its app's.
    fn forget(&mut self, session: &Session) {
        if let Some(count) = self.open_per_app.get_mut(&session.row.uid) {
            *count = count.saturating_sub(1);
            if *count == 0 {
                self.open_per_app.remove(&session.row.uid);
            }
        }
    }

    fn end(&mut self, key: Key, ts: i64) {
        if let Some(mut session) = self.remove(key) {
            session.row.end_ts = Some(ts.max(session.row.start_ts));
            session.dirty = true;
            self.ended.push(session);
        }
    }

    /// Check at `ts` that the processes of open sessions still run
    /// (`alive(pid, start time)`). The sessions of one that has ended end
    /// when they were last known on, not when it was noticed, which after
    /// the device slept can be hours later.
    pub fn check_processes(&mut self, ts: i64, alive: impl Fn(u32, u64) -> bool) {
        let mut checked: HashMap<(u32, u64), bool> = HashMap::new();
        let mut gone = Vec::new();
        for (key, session) in &mut self.open {
            let process = (session.row.pid, session.row.proc_start);
            if *checked
                .entry(process)
                .or_insert_with(|| alive(process.0, process.1))
            {
                session.on_at = session.on_at.max(ts);
            } else {
                gone.push((*key, session.on_at));
            }
        }
        for (key, on_at) in gone {
            self.end(key, on_at);
        }
    }

    /// What can be written now (`now`, `ts` in unix time): calls and
    /// sessions whose sensor has been looked up. A reading of the sensor
    /// list under way is waited for at most `wait`; what still waits for it
    /// is handed out next time.
    pub fn settle(&mut self, now: Instant, ts: i64, wait: Duration) -> Settled {
        self.wait_for_handles(now, ts, wait);
        self.end_taken_over();
        Settled {
            calls: self.settle_calls(now, ts),
            sessions: self.settle_sessions(now, ts),
        }
    }

    fn wait_for_handles(&mut self, now: Instant, ts: i64, wait: Duration) {
        let mut waiting = false;
        if let Some(since) = self.check_taken_over
            && !self.list.read_since(since)
        {
            // Once per run, so not held back by how often the list is read.
            if !self.list.is_reading() {
                self.list.start(now, ts);
            }
            waiting = true;
        }
        let handles: Vec<(i32, Instant)> = self
            .turned_on
            .iter()
            .map(|t| (t.handle, t.seen))
            .chain(
                self.open
                    .values()
                    .chain(&self.ended)
                    .filter(|s| s.sensor.is_none())
                    .map(|s| (s.row.handle, s.seen)),
            )
            .collect();
        for (handle, seen) in handles {
            waiting |= self.list.lookup(handle, seen, now, ts) == Lookup::Wait;
        }
        if waiting {
            self.list.poll(wait, now);
        }
    }

    fn settle_calls(&mut self, now: Instant, ts: i64) -> Vec<CallRecord> {
        let mut calls = Vec::new();
        let list = &mut self.list;
        let waiting_per_app = &mut self.waiting_per_app;
        let mut lookup = |handle: i32, seen: Instant| list.lookup(handle, seen, now, ts);
        self.turned_on.retain(|t| {
            let sensor = match lookup(t.handle, t.seen) {
                Lookup::Found(sensor) => Some(sensor),
                Lookup::NotFound | Lookup::Unreadable => None,
                Lookup::Wait => return true,
            };
            calls.push((t.uid, t.mode, subject(sensor.as_ref(), t.period_ns), t.ts));
            if let Some(count) = waiting_per_app.get_mut(&t.uid) {
                *count = count.saturating_sub(1);
            }
            false
        });
        waiting_per_app.retain(|_, count| *count > 0);
        calls
    }

    fn settle_sessions(&mut self, now: Instant, ts: i64) -> Vec<record::SensorSession> {
        for session in self.open.values_mut().chain(self.ended.iter_mut()) {
            if session.sensor.is_none() {
                match self.list.lookup(session.row.handle, session.seen, now, ts) {
                    Lookup::Found(sensor) => session.sensor = Some(Some(sensor)),
                    Lookup::Unreadable => session.sensor = Some(None),
                    Lookup::NotFound => session.no_sensor = true,
                    Lookup::Wait => {}
                }
            }
        }
        // The call is kept, but no sensor was on.
        let no_sensor: Vec<Key> = self
            .open
            .iter()
            .filter(|(_, s)| s.no_sensor)
            .map(|(key, _)| *key)
            .collect();
        for key in no_sensor {
            self.remove(key);
        }
        self.ended.retain(|session| !session.no_sensor);
        let mut sessions = Vec::new();
        // Ended sessions first: one turned off and on again within a second
        // is the same row, and must be written as on.
        self.ended.retain_mut(|session| match session.ready() {
            Some(row) => {
                sessions.push(row);
                false
            }
            None => true,
        });
        for session in self.open.values_mut() {
            if let Some(row) = session.ready() {
                sessions.push(row);
            }
        }
        sessions
    }

    /// Once the sensor service has been read since calls are seen: end the
    /// sessions taken over that it does not have on, when it was read. If
    /// it could not be read, they are left on, as their processes run.
    fn end_taken_over(&mut self) {
        let Some(since) = self.check_taken_over else {
            return;
        };
        let Some(read_at) = self.list.ended_since(since) else {
            return;
        };
        self.check_taken_over = None;
        // The service knows a connection by its process's own uid.
        let stale: Vec<Key> = self
            .open
            .iter()
            .filter(|(_, s)| s.taken_over)
            .filter(|(_, s)| {
                self.list
                    .active
                    .as_ref()
                    .is_some_and(|active| !active.contains(&(s.process_uid, s.row.handle)))
            })
            .map(|(key, _)| *key)
            .collect();
        for key in stale {
            self.end(key, read_at);
        }
        for session in self.open.values_mut() {
            session.taken_over = false;
        }
    }
}

impl Session {
    /// The row to write, if it has changed and its sensor has been looked up.
    fn ready(&mut self) -> Option<record::SensorSession> {
        let sensor = self.sensor.as_ref()?;
        if !self.dirty {
            return None;
        }
        self.dirty = false;
        let mut row = self.row.clone();
        (row.sensor, row.continuous) = match sensor {
            Some(sensor) => (sensor.kind.clone(), sensor.continuous),
            None => (String::new(), false),
        };
        Some(row)
    }
}

#[cfg(test)]
mod tests;
