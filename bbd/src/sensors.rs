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
//!   [`HIGH_RATE_PERIOD_NS`]). `<type>` is the sensor type's name, such as
//!   `android.sensor.accelerometer`. A sensor that could not be identified
//!   has no `@<type>`. The `@` keeps the type from being read as the
//!   `#<package>` of other calls, which older apps would count as apps;
//! - each period a sensor was on, as a session (`store::SensorSession`):
//!   from the call that turned it on to the one that turned it off, the
//!   connection closing, or the app's process ending.
//!
//! Sessions are keyed by process, connection and sensor. Turning on a
//! sensor that is already on, or changing its rate, with a different rate
//! ends the session and starts another at the same time, so each session
//! has one rate; within the second the session started, the rate is
//! updated instead. A process that ends is noticed when the daemon next
//! writes, up to 30 seconds late, and its sessions end then. A process is
//! known by its pid and start time, so a reused pid is not taken for it.
//! Sessions a previous run of the daemon left open go on if their process
//! still runs and, once calls are seen again, the sensor service still has
//! the sensor on for the app; the others end when the previous run was last
//! known to be up, or when they are found off.
//!
//! The calls name sensors by handle, a number that differs from device to
//! device. Which type each handle is comes from the sensor service's dump,
//! read with `dumpsys` on a thread of its own: the first time a sensor is
//! turned on, and again, at most once a minute, when one turns up that the
//! list does not have (sensors connected later).
//!
//! Only the request is seen. What the app was given can be less: Android 12
//! and later deliver at most 200 Hz to apps without the
//! HIGH_SAMPLING_RATE_SENSORS permission, sensors may batch their events,
//! and apps idle in the background get nothing from most sensors. A
//! one-shot sensor (significant motion) turns itself off once it fires,
//! which is not seen; its session lasts until it is turned off, the
//! connection closes or the process ends.

use std::{
    collections::{HashMap, HashSet},
    sync::mpsc::{Receiver, RecvTimeoutError},
    time::{Duration, Instant},
};

use crate::{binder, store};

/// The sensor connection's interface.
pub const CONNECTION: &str = "android.gui.SensorEventConnection";
/// `enableDisable(handle, enabled, samplingPeriodNs, maxBatchReportLatencyNs, reservedFlags)`.
pub const ENABLE_DISABLE: &str = "enableDisable";
/// `setEventRate(handle, samplingPeriodNs)`.
pub const SET_EVENT_RATE: &str = "setEventRate";
/// `destroy()`, sent when the app lets go of the connection.
pub const DESTROY: &str = "destroy";

/// Put before the sensor's type in a stored call.
pub const SENSOR_MARK: char = '@';
/// Put after the type when the rate asked for is high.
const FAST: &str = ":fast";

/// Sampling periods at or below this are a high rate: 5 ms, 200 Hz. It is
/// the rate Android 12 and later hold apps to unless they have the
/// HIGH_SAMPLING_RATE_SENSORS permission, since motion sampled faster can
/// pick up speech from the device's own speaker and what is typed on its
/// screen. Asking for the fastest rate the sensor has (a period of 0, which
/// `SENSOR_DELAY_FASTEST` sends) is a high rate too; `SENSOR_DELAY_GAME`
/// (20 ms, 50 Hz) is not.
pub const HIGH_RATE_PERIOD_NS: i64 = 5_000_000;

/// Whether a sampling period asked of a sensor is a high rate. Only
/// sensors that report at a steady rate have one; the others report on
/// change or once, whatever period they are given.
pub fn is_high_rate(continuous: bool, period_ns: i64) -> bool {
    continuous && period_ns <= HIGH_RATE_PERIOD_NS
}

// ---- calls ------------------------------------------------------------------

/// What a call to a sensor connection asks for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Request {
    /// Turn a sensor on, with one sample every `period_ns` (0: as fast as
    /// the sensor goes).
    Enable {
        handle: i32,
        period_ns: i64,
    },
    Disable {
        handle: i32,
    },
    /// Change the rate of a sensor that is on.
    Rate {
        handle: i32,
        period_ns: i64,
    },
    /// The connection closes, and every sensor on it with it.
    Close,
}

/// The request in a call to `method`, from its arguments: 32-bit and 64-bit
/// little-endian integers, one after the other. `None` if they can't be
/// read.
pub fn request(method: &str, args: &[u8]) -> Option<Request> {
    let int = |at: usize| Some(i32::from_le_bytes(args.get(at..at + 4)?.try_into().ok()?));
    let long = |at: usize| Some(i64::from_le_bytes(args.get(at..at + 8)?.try_into().ok()?));
    match method {
        ENABLE_DISABLE => {
            let handle = int(0)?;
            if int(4)? == 0 {
                return Some(Request::Disable { handle });
            }
            Some(Request::Enable {
                handle,
                period_ns: long(8)?.max(0),
            })
        }
        SET_EVENT_RATE => Some(Request::Rate {
            handle: int(0)?,
            period_ns: long(4)?.max(0),
        }),
        DESTROY => Some(Request::Close),
        _ => None,
    }
}

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

/// The sensors in the output of `dumpsys sensorservice`, by handle. Each has
/// a line such as
///
/// ```text
/// 0x00000001) BMI160 Accelerometer | Bosch | ver: 1 | type: android.sensor.accelerometer(1) | perm: n/a | flags: 0x00000050
/// ```
///
/// (without `flags` before Android 9; handle 0 is printed `0000000000`),
/// followed by a line that starts with a tab and its reporting mode:
/// `continuous | minRate=...`. Lines of other sections, some of which also
/// start with a handle, have no type and are skipped. Android 6 printed the
/// list differently; it is not read.
pub fn parse_list(dump: &str) -> HashMap<i32, Sensor> {
    let mut sensors = HashMap::new();
    let mut lines = dump.lines().peekable();
    while let Some(line) = lines.next() {
        let Some((handle, kind)) = sensor_line(line) else {
            continue;
        };
        let continuous = lines.peek().is_some_and(|next| {
            next.starts_with(char::is_whitespace)
                && next.trim_start().split(" | ").next() == Some("continuous")
        });
        sensors.insert(handle, Sensor { kind, continuous });
    }
    sensors
}

/// The handle and type of a sensor's line in the list.
fn sensor_line(line: &str) -> Option<(i32, String)> {
    let (handle, rest) = line.split_once(") ")?;
    let handle = handle.trim();
    let digits = handle.strip_prefix("0x").unwrap_or(handle);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    // The bits as printed; handles are signed in the calls.
    let handle = u32::from_str_radix(digits, 16).ok()? as i32;
    // The name and vendor come first and could hold anything, so the type
    // is looked for by its label.
    let field = rest
        .split(" | ")
        .find_map(|field| field.trim().strip_prefix("type:"))?;
    let (name, number) = field.trim().strip_suffix(')')?.rsplit_once('(')?;
    let number: i32 = number.parse().ok()?;
    let name: String = name
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
        .collect();
    Some((
        handle,
        if name.is_empty() {
            number.to_string()
        } else {
            name
        },
    ))
}

/// The sensors each app has on, by the sensor service's own account, as
/// (uid, handle). In the dump, each connection has a line with its package
/// and uid, `\t <package> | WakeLockRefCount 0 | uid 10123 | ...`, then one
/// per sensor on: `\t <sensor name> 0x00000001 | status: active | ...`
/// (`| first flush pending: false |` from Android 16). The uid is that of
/// the process, which for an isolated one is not its app's.
pub fn parse_active(dump: &str) -> HashSet<(u32, i32)> {
    let mut active = HashSet::new();
    let mut uid = None;
    for line in dump.lines() {
        // Each connection's lines start with a tab.
        if !line.starts_with('\t') {
            uid = None;
            continue;
        }
        let mut fields = line.split(" | ");
        let first = fields.next().unwrap_or_default().trim();
        if let Some(found) = fields.find_map(|field| field.trim().strip_prefix("uid ")) {
            uid = found.trim().parse().ok();
            continue;
        }
        let handle = first
            .rsplit(' ')
            .next()
            .and_then(|last| last.strip_prefix("0x"))
            .and_then(|digits| u32::from_str_radix(digits, 16).ok());
        if let (Some(uid), Some(handle)) = (uid, handle) {
            active.insert((uid, handle as i32));
        }
    }
    active
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

/// How soon the list is read again for a sensor it does not have. After a
/// reading fails, the wait doubles with each failure, up to
/// `READ_AGAIN_MAX`.
const READ_AGAIN: Duration = Duration::from_secs(60);
const READ_AGAIN_MAX: Duration = Duration::from_secs(3600);
/// A reading that has taken this long is given up on.
const READING_TIMEOUT: Duration = Duration::from_secs(30);

/// The sensors by handle, and those on (see [`parse_active`]).
type Reading = anyhow::Result<(HashMap<i32, Sensor>, HashSet<(u32, i32)>)>;

/// What is known of a handle.
#[derive(Debug, PartialEq, Eq)]
enum Lookup {
    Found(Sensor),
    /// The list was read after the handle was seen and does not have it:
    /// there is no such sensor, and the sensor service turned nothing on.
    NotFound,
    /// The list can't be read.
    Unreadable,
    /// A reading is under way or due.
    Wait,
}

/// The device's sensors by handle, read when needed.
struct SensorList {
    sensors: HashMap<i32, Sensor>,
    read: fn() -> anyhow::Result<String>,
    /// A reading under way, and when it started (also as unix time).
    reading: Option<(Receiver<Reading>, Instant, i64)>,
    /// When the latest reading started.
    started: Option<Instant>,
    /// When the latest reading that has ended, well or not, started (also
    /// as unix time).
    ended: Option<(Instant, i64)>,
    /// The sensors on at that reading; `None` if it failed.
    active: Option<HashSet<(u32, i32)>>,
    /// Readings that failed in a row.
    failures: u32,
}

impl SensorList {
    fn new(read: fn() -> anyhow::Result<String>) -> Self {
        Self {
            sensors: HashMap::new(),
            read,
            reading: None,
            started: None,
            ended: None,
            active: None,
            failures: 0,
        }
    }

    /// The sensor `handle` stands for, for a call that arrived at `seen`.
    fn lookup(&mut self, handle: i32, seen: Instant, now: Instant, ts: i64) -> Lookup {
        if let Some(sensor) = self.sensors.get(&handle) {
            return Lookup::Found(sensor.clone());
        }
        if self.reading.is_some() {
            return Lookup::Wait;
        }
        if self.read_since(seen) {
            return if self.failures == 0 {
                Lookup::NotFound
            } else {
                Lookup::Unreadable
            };
        }
        if self.may_read(now) {
            self.start(now, ts);
            return Lookup::Wait;
        }
        // Waiting out failures would hold records back for long.
        if self.failures > 0 {
            Lookup::Unreadable
        } else {
            Lookup::Wait
        }
    }

    fn may_read(&self, now: Instant) -> bool {
        let wait = READ_AGAIN
            .saturating_mul(1 << self.failures.saturating_sub(1).min(16))
            .min(READ_AGAIN_MAX);
        self.started
            .is_none_or(|started| now.duration_since(started) >= wait)
    }

    /// Whether a reading that started at `since` or later has ended.
    fn read_since(&self, since: Instant) -> bool {
        self.ended.is_some_and(|(started, _)| started >= since)
    }

    fn start(&mut self, now: Instant, ts: i64) {
        let (sender, receiver) = std::sync::mpsc::channel();
        let read = self.read;
        std::thread::spawn(move || {
            let reading = read().and_then(|dump| {
                let sensors = parse_list(&dump);
                anyhow::ensure!(
                    !sensors.is_empty(),
                    "no sensors in the sensor service's dump"
                );
                Ok((sensors, parse_active(&dump)))
            });
            let _ = sender.send(reading);
        });
        self.reading = Some((receiver, now, ts));
        self.started = Some(now);
    }

    /// Take in a reading under way, waiting for it at most `wait`.
    fn poll(&mut self, wait: Duration, now: Instant) {
        let Some((receiver, started, started_ts)) = &self.reading else {
            return;
        };
        let (started, started_ts) = (*started, *started_ts);
        let reading = match receiver.recv_timeout(wait) {
            Ok(reading) => reading,
            Err(RecvTimeoutError::Timeout) if now.duration_since(started) < READING_TIMEOUT => {
                return;
            }
            Err(RecvTimeoutError::Timeout) => Err(anyhow::anyhow!("dumpsys did not answer")),
            Err(RecvTimeoutError::Disconnected) => Err(anyhow::anyhow!("the reader ended")),
        };
        self.reading = None;
        self.ended = Some((started, started_ts));
        match reading {
            Ok((sensors, active)) => {
                self.sensors = sensors;
                self.active = Some(active);
                self.failures = 0;
            }
            Err(e) => {
                // Logged at most once a minute: readings are that far apart.
                eprintln!("bbd: could not read the sensor list: {e:#}");
                self.active = None;
                self.failures += 1;
            }
        }
    }
}

// ---- sessions ---------------------------------------------------------------

/// A call to a sensor connection, as reported by the kernel side.
#[derive(Clone, Copy, Debug)]
pub struct Call {
    /// The app the call is recorded under.
    pub uid: u32,
    /// What to store the call with when it is recorded (`store::MODE_*`).
    pub mode: store::Mode,
    pub pid: u32,
    /// The caller's binder handle for the connection.
    pub connection: u32,
    pub request: Request,
    pub ts: i64,
}

/// Most sessions open at once, and most sensors turned on waiting to be
/// looked up. Handles come from apps; one going through made-up handles
/// must not fill the daemon's memory. Real use is a few dozen of each.
const MAX_OPEN: usize = 1024;
const MAX_WAITING: usize = 4096;

/// One sensor on one connection of one process.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
struct Key {
    pid: u32,
    connection: u32,
    handle: i32,
}

#[derive(Clone, Debug)]
struct Session {
    row: store::SensorSession,
    /// When the call that started it arrived; until the list has been read
    /// since, an unknown handle may still turn up in it.
    seen: Instant,
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
    mode: store::Mode,
    handle: i32,
    period_ns: i64,
    ts: i64,
    seen: Instant,
}

/// What is ready to be written.
#[derive(Debug, Default, PartialEq)]
pub struct Settled {
    /// (uid, mode, subject, ts) of each sensor turned on.
    pub calls: Vec<(u32, store::Mode, String, i64)>,
    /// Sessions started, changed or ended since the last time.
    pub sessions: Vec<store::SensorSession>,
}

/// The sensors apps have on, and what is to be written about them.
pub struct Sensors {
    list: SensorList,
    open: HashMap<Key, Session>,
    /// Ended sessions not handed out yet.
    ended: Vec<Session>,
    turned_on: Vec<TurnedOn>,
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
            check_taken_over: None,
        }
    }

    /// Whether any sensor is on: only then are processes checked.
    pub fn any_on(&self) -> bool {
        !self.open.is_empty()
    }

    /// Calls are seen from `now` on. Sessions taken over from a previous run
    /// may have been turned off before; the sensor service is asked which
    /// still are, once, at the next write.
    pub fn watching(&mut self, now: Instant) {
        if self.open.values().any(|s| s.taken_over) {
            self.check_taken_over = Some(now);
        }
    }

    /// Take over the sessions a previous run left open. Those whose process
    /// still runs (`alive(pid, start time)`) go on until [`Self::watching`]
    /// checks them; the others ended at the latest when the previous run was
    /// last known to be up, `last_up`.
    pub fn adopt(
        &mut self,
        rows: Vec<store::SensorSession>,
        alive: impl Fn(u32, u64) -> bool,
        last_up: i64,
        now: Instant,
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
            let mut session = Session {
                seen: now,
                sensor: Some(sensor),
                no_sensor: false,
                dirty: false,
                taken_over: true,
                row,
            };
            if alive(session.row.pid, session.row.proc_start) {
                self.open.insert(key, session);
            } else {
                session.row.end_ts = Some(last_up.max(session.row.start_ts));
                session.dirty = true;
                self.ended.push(session);
            }
        }
    }

    /// Take in a call. `proc_start` gives the start time of a process,
    /// `None` if it has ended.
    pub fn on_call(&mut self, call: Call, proc_start: impl Fn(u32) -> Option<u64>, now: Instant) {
        let key = |handle| Key {
            pid: call.pid,
            connection: call.connection,
            handle,
        };
        match call.request {
            Request::Enable { handle, period_ns } => {
                if self.turned_on.len() < MAX_WAITING {
                    self.turned_on.push(TurnedOn {
                        uid: call.uid,
                        mode: call.mode,
                        handle,
                        period_ns,
                        ts: call.ts,
                        seen: now,
                    });
                }
                // Turning on a sensor that is on sets its rate.
                if self.open.contains_key(&key(handle)) {
                    self.set_rate(key(handle), period_ns, call.ts, now);
                    return;
                }
                if self.open.len() >= MAX_OPEN {
                    return;
                }
                // A process gone already has 0, which no process has: its
                // sessions end at the next check.
                let proc_start = proc_start(call.pid).unwrap_or(0);
                self.open.insert(
                    key(handle),
                    Session {
                        row: store::SensorSession {
                            uid: call.uid,
                            pid: call.pid,
                            proc_start,
                            connection: call.connection,
                            handle,
                            sensor: String::new(),
                            continuous: false,
                            period_ns,
                            start_ts: call.ts,
                            end_ts: None,
                        },
                        seen: now,
                        sensor: None,
                        no_sensor: false,
                        dirty: true,
                        taken_over: false,
                    },
                );
            }
            Request::Disable { handle } => self.end(key(handle), call.ts),
            Request::Rate { handle, period_ns } => {
                if self.open.contains_key(&key(handle)) {
                    self.set_rate(key(handle), period_ns, call.ts, now);
                }
            }
            Request::Close => {
                let closed: Vec<Key> = self
                    .open
                    .keys()
                    .filter(|k| k.pid == call.pid && k.connection == call.connection)
                    .copied()
                    .collect();
                for key in closed {
                    self.end(key, call.ts);
                }
            }
        }
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
        self.open.insert(key, next);
    }

    fn end(&mut self, key: Key, ts: i64) {
        if let Some(mut session) = self.open.remove(&key) {
            session.row.end_ts = Some(ts.max(session.row.start_ts));
            session.dirty = true;
            self.ended.push(session);
        }
    }

    /// End the sessions of processes that have ended (`alive(pid, start
    /// time)` false), at `ts`: when it was noticed.
    pub fn check_processes(&mut self, ts: i64, alive: impl Fn(u32, u64) -> bool) {
        let mut checked: HashMap<(u32, u64), bool> = HashMap::new();
        let gone: Vec<Key> = self
            .open
            .iter()
            .filter(|(_, s)| {
                let process = (s.row.pid, s.row.proc_start);
                !*checked
                    .entry(process)
                    .or_insert_with(|| alive(process.0, process.1))
            })
            .map(|(key, _)| *key)
            .collect();
        for key in gone {
            self.end(key, ts);
        }
    }

    /// What can be written now (`now`, `ts` in unix time): calls and
    /// sessions whose sensor has been looked up. A reading of the sensor
    /// list under way is waited for at most `wait`; what still waits for it
    /// is handed out next time.
    pub fn settle(&mut self, now: Instant, ts: i64, wait: Duration) -> Settled {
        let mut waiting = false;
        if let Some(since) = self.check_taken_over
            && !self.list.read_since(since)
        {
            // Once per run, so not held back by how often the list is read.
            if self.list.reading.is_none() {
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
        self.end_taken_over();

        let mut settled = Settled::default();
        let list = &mut self.list;
        let mut lookup = |handle: i32, seen: Instant| list.lookup(handle, seen, now, ts);
        self.turned_on.retain(|t| {
            let sensor = match lookup(t.handle, t.seen) {
                Lookup::Found(sensor) => Some(sensor),
                Lookup::NotFound | Lookup::Unreadable => None,
                Lookup::Wait => return true,
            };
            settled
                .calls
                .push((t.uid, t.mode, subject(sensor.as_ref(), t.period_ns), t.ts));
            false
        });
        for session in self.open.values_mut().chain(self.ended.iter_mut()) {
            if session.sensor.is_none() {
                match lookup(session.row.handle, session.seen) {
                    Lookup::Found(sensor) => session.sensor = Some(Some(sensor)),
                    Lookup::Unreadable => session.sensor = Some(None),
                    Lookup::NotFound => session.no_sensor = true,
                    Lookup::Wait => {}
                }
            }
        }
        // The call is kept, but no sensor was on.
        self.open.retain(|_, session| !session.no_sensor);
        self.ended.retain(|session| !session.no_sensor);
        for session in self.open.values_mut() {
            if let Some(row) = session.ready() {
                settled.sessions.push(row);
            }
        }
        self.ended.retain_mut(|session| match session.ready() {
            Some(row) => {
                settled.sessions.push(row);
                false
            }
            None => true,
        });
        settled
    }

    /// Once the sensor service has been read since calls are seen: end the
    /// sessions taken over that it does not have on, when it was read. If
    /// it could not be read, they are left on, as their processes run.
    fn end_taken_over(&mut self) {
        let Some(since) = self.check_taken_over else {
            return;
        };
        let Some((_, read_at)) = self.list.ended.filter(|_| self.list.read_since(since)) else {
            return;
        };
        self.check_taken_over = None;
        let stale: Vec<Key> = self
            .open
            .iter()
            .filter(|(_, s)| s.taken_over)
            .filter(|(_, s)| {
                self.list
                    .active
                    .as_ref()
                    .is_some_and(|active| !active.contains(&(s.row.uid, s.row.handle)))
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
    fn ready(&mut self) -> Option<store::SensorSession> {
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

// ---- processes --------------------------------------------------------------

/// When the process `pid` started, in clock ticks after boot; `None` if
/// there is no such process.
pub fn process_start(pid: u32) -> Option<u64> {
    parse_start(&std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?)
}

/// Whether the process `pid` that started at `start` still runs.
pub fn process_alive(pid: u32, start: u64) -> bool {
    process_start(pid) == Some(start)
}

/// The start time from `/proc/<pid>/stat`: the 22nd field. The second, the
/// command name, is in parentheses and may hold spaces and parentheses
/// itself, so the fields are counted from the last `)`.
fn parse_start(stat: &str) -> Option<u64> {
    let (_, rest) = stat.rsplit_once(')')?;
    rest.split_ascii_whitespace().nth(22 - 3)?.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The list of an emulator running Android 15.
    const ANDROID_15: &str = "Captured at: 15:30:06.721
Sensor Device:
Total 11 h/w sensors, 11 running 0 disabled clients:
0x00000000) active-count = 2; sampling_period(ms) = {200.0, 66.7}, selected = 66.67 ms; batching_period(ms) = {2000.0, 100.0}, selected = 100.00 ms
Sensor List:
0000000000) Goldfish 3-axis Accelerometer | The Android Open Source Project | ver: 1 | type: android.sensor.accelerometer(1) | perm: n/a | flags: 0x00000050
\tcontinuous | minRate=2.00Hz | maxRate=100.00Hz | no batching | non-wakeUp | data-injection, has-additional-info,
0x00000001) Goldfish 3-axis Gyroscope | The Android Open Source Project | ver: 1 | type: android.sensor.gyroscope(4) | perm: n/a | flags: 0x00000050
\tcontinuous | minRate=2.00Hz | maxRate=100.00Hz | no batching | non-wakeUp | data-injection, has-additional-info,
0x00000005) Goldfish Proximity sensor | The Android Open Source Project | ver: 1 | type: android.sensor.proximity(8) | perm: n/a | flags: 0x00000013
\ton-change | maxDelay=0us | minDelay=0us | no batching | wakeUp | data-injection,
0x5f636779) Corrected Gyroscope Sensor | AOSP            | ver: 1 | type: android.sensor.gyroscope(4) | perm: n/a | flags: 0x00000000
\tcontinuous | maxDelay=0us | maxRate=100.00Hz | no batching | non-wakeUp |
Fusion States:
9-axis fusion disabled (0 clients), gyro-rate= 200.00Hz, q=< 0.677207, 2.60163e-06, 2.57113e-05, 0.735793 > (1), b=< 7.43013e-15, 2.06122e-14, 1.13625e-14 >
Recent Sensor events:
Linear Acceleration Sensor: last 10 events
\t 1 (ts=7617.261443600, wall=05:59:36.262) 0.00, 0.00, 0.00,
Active sensors:
Goldfish 3-axis Accelerometer (handle=0x00000000, connections=2)
2 active connections
Connection Number: 0
\tOperating Mode: NORMAL
\t com.android.server.power.FaceDownDetector | WakeLockRefCount 0 | uid 1000 | cache size 0 | max cache size 0
\t Goldfish 3-axis Accelerometer 0x00000000 | status: active | pending flush events 0
Connection Number: 1
\tOperating Mode: NORMAL
\t com.example.a.Listener | WakeLockRefCount 0 | uid 10100 | cache size 0 | max cache size 984
\t Goldfish 3-axis Gyroscope 0x00000001 | status: active | pending flush events 0
0 direct connections
Previous Registrations:
15:19:59 + 0x5f726f76 pid= 8866 uid=10134 package=com.example.a samplingPeriod=20000us batchingPeriod=0us
";

    /// Android 7 and 8 print no flags.
    const ANDROID_8: &str = "Sensor List:
0x00000001) BMI160 accelerometer      | Bosch           | ver: 1 | type: android.sensor.accelerometer(1) | perm: n/a
\tcontinuous | minRate=1.00Hz | maxRate=200.00Hz | FIFO (max,reserved) = (5000, 3000) events | non-wakeUp |
0x00000013) Step Counter            | Google          | ver: 1 | type: android.sensor.step_counter(19) | perm: android.permission.ACTIVITY_RECOGNITION
\ton-change | maxDelay=0us | minDelay=0us | FIFO (max,reserved) = (0, 0) events | wakeUp |
0x00010001) Pocket sensor | Vendor | ver: 1 | type: com.vendor.sensor.pocket mode!(65537) | perm: n/a
\tspecial-trigger | maxDelay=0us | minDelay=0us | no batching | wakeUp |
0x00010002) Unnamed | Vendor | ver: 1 | type:                     (65538) | perm: n/a
\tcontinuous | minRate=1.00Hz | maxRate=50.00Hz | no batching | non-wakeUp |
";

    /// Android 6, which listed sensors in another layout.
    const ANDROID_6: &str = "Sensor List:
BMI160 accelerometer| Bosch     | version=1 |android.sensor.accelerometer| 0x00000001 | \"\" | type=1 | continuous | minRate=1.00Hz | maxRate=200.00Hz | FifoMax=5000 events | non-wakeUp |
";

    fn sensor(kind: &str, continuous: bool) -> Sensor {
        Sensor {
            kind: kind.into(),
            continuous,
        }
    }

    #[test]
    fn the_list_gives_each_handles_type_and_reporting_mode() {
        let sensors = parse_list(ANDROID_15);
        assert_eq!(sensors.len(), 4, "{sensors:?}");
        assert_eq!(sensors[&0], sensor("android.sensor.accelerometer", true));
        assert_eq!(sensors[&1], sensor("android.sensor.gyroscope", true));
        assert_eq!(sensors[&5], sensor("android.sensor.proximity", false));
        // Handles of virtual sensors use all 32 bits.
        assert_eq!(
            sensors[&0x5f63_6779],
            sensor("android.sensor.gyroscope", true)
        );
    }

    #[test]
    fn older_lists_without_flags_are_read_too() {
        let sensors = parse_list(ANDROID_8);
        assert_eq!(sensors[&1], sensor("android.sensor.accelerometer", true));
        assert_eq!(sensors[&0x13], sensor("android.sensor.step_counter", false));
        // Odd characters are dropped; a type without a name is its number.
        assert_eq!(
            sensors[&0x10001],
            sensor("com.vendor.sensor.pocketmode", false)
        );
        assert_eq!(sensors[&0x10002], sensor("65538", true));
        assert!(parse_list(ANDROID_6).is_empty());
        assert!(parse_list("Permission Denial: can't dump SensorService").is_empty());
    }

    #[test]
    fn calls_are_read_from_their_arguments() {
        let args = |values: &[&[u8]]| values.concat();
        let enable = args(&[
            &7i32.to_le_bytes(),
            &1i32.to_le_bytes(),
            &20_000_000i64.to_le_bytes(),
            &0i64.to_le_bytes(),
            &0i32.to_le_bytes(),
        ]);
        assert_eq!(
            request(ENABLE_DISABLE, &enable),
            Some(Request::Enable {
                handle: 7,
                period_ns: 20_000_000
            })
        );
        let disable = args(&[&7i32.to_le_bytes(), &0i32.to_le_bytes(), &[0; 20]]);
        assert_eq!(
            request(ENABLE_DISABLE, &disable),
            Some(Request::Disable { handle: 7 })
        );
        let rate = args(&[&(-2i32).to_le_bytes(), &5_000_000i64.to_le_bytes()]);
        assert_eq!(
            request(SET_EVENT_RATE, &rate),
            Some(Request::Rate {
                handle: -2,
                period_ns: 5_000_000
            })
        );
        assert_eq!(request(DESTROY, &[]), Some(Request::Close));
        // Too short to hold the period.
        assert_eq!(request(ENABLE_DISABLE, &enable[..12]), None);
        assert_eq!(request("flush", &enable), None);
    }

    #[test]
    fn subjects_name_the_type_and_mark_a_high_rate() {
        let accelerometer = sensor("android.sensor.accelerometer", true);
        let light = sensor("android.sensor.light", false);
        let base = "binder:android.gui.SensorEventConnection/enableDisable";
        // SENSOR_DELAY_NORMAL, _GAME, 200 Hz, _FASTEST.
        assert_eq!(
            subject(Some(&accelerometer), 200_000_000),
            format!("{base}@android.sensor.accelerometer")
        );
        assert_eq!(
            subject(Some(&accelerometer), 20_000_000),
            format!("{base}@android.sensor.accelerometer")
        );
        assert_eq!(
            subject(Some(&accelerometer), 5_000_000),
            format!("{base}@android.sensor.accelerometer:fast")
        );
        assert_eq!(
            subject(Some(&accelerometer), 0),
            format!("{base}@android.sensor.accelerometer:fast")
        );
        // A sensor that reports on change has no rate to speak of.
        assert_eq!(
            subject(Some(&light), 0),
            format!("{base}@android.sensor.light")
        );
        assert_eq!(subject(None, 0), base);
        // Recorded under sensors, and not taken for a package asked about.
        for subject in [
            format!("{base}@android.sensor.accelerometer:fast"),
            base.to_string(),
        ] {
            assert_eq!(
                binder::topic_of(&subject),
                Some(crate::rules::topic::SENSORS)
            );
            assert_eq!(binder::asked_package(&subject), None);
        }
    }

    #[test]
    fn the_start_time_is_the_22nd_field_after_the_name() {
        let stat =
            "4242 (my (odd) app) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654 20 21";
        assert_eq!(parse_start(stat), Some(987_654));
        assert_eq!(parse_start("4242 (short) S 1"), None);
    }

    // ---- sessions ----

    const UID: u32 = 10_100;
    const PID: u32 = 4242;
    const START: u64 = 777;

    fn list() -> anyhow::Result<String> {
        Ok(ANDROID_15.to_string())
    }

    fn failing_list() -> anyhow::Result<String> {
        anyhow::bail!("no dumpsys")
    }

    fn call(connection: u32, request: Request, ts: i64) -> Call {
        Call {
            uid: UID,
            mode: store::MODE_CALL,
            pid: PID,
            connection,
            request,
            ts,
        }
    }

    fn running(_pid: u32) -> Option<u64> {
        Some(START)
    }

    /// (sensor, period, start, end) of each session handed out.
    fn spans(settled: &Settled) -> Vec<(String, i64, i64, Option<i64>)> {
        let mut spans: Vec<_> = settled
            .sessions
            .iter()
            .map(|s| (s.sensor.clone(), s.period_ns, s.start_ts, s.end_ts))
            .collect();
        spans.sort();
        spans
    }

    const WAIT: Duration = Duration::from_secs(5);

    #[test]
    fn a_sensor_turned_on_and_off_is_one_session() {
        let mut sensors = Sensors::new(list);
        let now = Instant::now();
        let on = Request::Enable {
            handle: 1,
            period_ns: 0,
        };
        sensors.on_call(call(9, on, 1000), running, now);
        let settled = sensors.settle(now, 5000, WAIT);
        assert_eq!(
            settled.calls,
            [(
                UID,
                store::MODE_CALL,
                "binder:android.gui.SensorEventConnection/enableDisable@android.sensor.gyroscope:fast"
                    .to_string(),
                1000
            )]
        );
        // Written while it is on, then again when it ends.
        assert_eq!(
            spans(&settled),
            [("android.sensor.gyroscope".into(), 0, 1000, None)]
        );
        assert!(settled.sessions[0].continuous);
        assert_eq!(sensors.settle(now, 5000, WAIT), Settled::default());
        sensors.on_call(call(9, Request::Disable { handle: 1 }, 1060), running, now);
        assert_eq!(
            spans(&sensors.settle(now, 5000, WAIT)),
            [("android.sensor.gyroscope".into(), 0, 1000, Some(1060))]
        );
        assert!(!sensors.any_on());
        // Turning off what is not on changes nothing.
        sensors.on_call(call(9, Request::Disable { handle: 1 }, 1070), running, now);
        assert_eq!(sensors.settle(now, 5000, WAIT), Settled::default());
    }

    #[test]
    fn closing_a_connection_ends_its_sessions_only() {
        let mut sensors = Sensors::new(list);
        let now = Instant::now();
        for (connection, handle) in [(9, 0), (9, 1), (12, 0)] {
            let on = Request::Enable {
                handle,
                period_ns: 200_000_000,
            };
            sensors.on_call(call(connection, on, 1000), running, now);
        }
        assert_eq!(sensors.settle(now, 5000, WAIT).sessions.len(), 3);
        sensors.on_call(call(9, Request::Close, 1100), running, now);
        let ended = sensors.settle(now, 5000, WAIT);
        assert_eq!(
            spans(&ended),
            [
                (
                    "android.sensor.accelerometer".into(),
                    200_000_000,
                    1000,
                    Some(1100)
                ),
                (
                    "android.sensor.gyroscope".into(),
                    200_000_000,
                    1000,
                    Some(1100)
                ),
            ]
        );
        // The other connection's accelerometer is still on.
        assert!(sensors.any_on());
        // A closing call to a connection not known is someone else's.
        sensors.on_call(call(33, Request::Close, 1200), running, now);
        assert_eq!(sensors.settle(now, 5000, WAIT), Settled::default());
    }

    #[test]
    fn a_new_rate_starts_a_new_session() {
        let mut sensors = Sensors::new(list);
        let now = Instant::now();
        let enable = |period_ns| Request::Enable {
            handle: 0,
            period_ns,
        };
        sensors.on_call(call(9, enable(200_000_000), 1000), running, now);
        // Within the first second, the rate is updated in place.
        sensors.on_call(call(9, enable(20_000_000), 1000), running, now);
        sensors.on_call(call(9, enable(20_000_000), 1010), running, now);
        let rate = Request::Rate {
            handle: 0,
            period_ns: 66_667_000,
        };
        sensors.on_call(call(9, rate, 1030), running, now);
        sensors.on_call(call(9, Request::Disable { handle: 0 }, 1090), running, now);
        let settled = sensors.settle(now, 5000, WAIT);
        let accelerometer = || "android.sensor.accelerometer".to_string();
        assert_eq!(
            spans(&settled),
            [
                (accelerometer(), 20_000_000, 1000, Some(1030)),
                (accelerometer(), 66_667_000, 1030, Some(1090)),
            ]
        );
        // Each call that turned it on is recorded.
        assert_eq!(settled.calls.len(), 3);
    }

    #[test]
    fn sessions_end_when_their_process_does() {
        let mut sensors = Sensors::new(list);
        let now = Instant::now();
        let on = Request::Enable {
            handle: 0,
            period_ns: 20_000_000,
        };
        sensors.on_call(call(9, on, 1000), running, now);
        // Gone by the time the call is taken in.
        let other = Call {
            pid: 5555,
            ..call(4, on, 1001)
        };
        sensors.on_call(other, |_| None, now);
        sensors.settle(now, 5000, WAIT);
        sensors.check_processes(1030, |pid, start| pid == PID && start == START);
        assert_eq!(
            spans(&sensors.settle(now, 5000, WAIT)),
            [(
                "android.sensor.accelerometer".into(),
                20_000_000,
                1001,
                Some(1030)
            )]
        );
        // A pid taken over by another process is not the same process.
        sensors.check_processes(1060, |pid, start| pid == PID && start == START + 1);
        assert_eq!(
            spans(&sensors.settle(now, 5000, WAIT)),
            [(
                "android.sensor.accelerometer".into(),
                20_000_000,
                1000,
                Some(1060)
            )]
        );
        assert!(!sensors.any_on());
    }

    #[test]
    fn sessions_left_open_go_on_while_their_process_runs() {
        let mut sensors = Sensors::new(list);
        let now = Instant::now();
        let row = |pid, start_ts| store::SensorSession {
            uid: UID,
            pid,
            proc_start: START,
            connection: 9,
            handle: 1,
            sensor: "android.sensor.gyroscope".into(),
            continuous: true,
            period_ns: 0,
            start_ts,
            end_ts: None,
        };
        // The accelerometer was turned off while no calls were seen: the
        // sensor service has only the gyroscope on for this app.
        let accelerometer = store::SensorSession {
            connection: 12,
            handle: 0,
            sensor: "android.sensor.accelerometer".into(),
            ..row(PID, 1000)
        };
        sensors.adopt(
            vec![
                row(PID, 1000),
                accelerometer,
                row(5555, 1000),
                row(6666, 3000),
            ],
            |pid, _| pid == PID,
            2000,
            now,
        );
        // Ended when the previous run was last up, and not before they began.
        assert_eq!(
            spans(&sensors.settle(now, 5000, WAIT)),
            [
                ("android.sensor.gyroscope".into(), 0, 1000, Some(2000)),
                ("android.sensor.gyroscope".into(), 0, 3000, Some(3000)),
            ]
        );
        // Once calls are seen again, the service is asked which are still on.
        sensors.watching(now);
        assert_eq!(
            spans(&sensors.settle(now, 5100, WAIT)),
            [("android.sensor.accelerometer".into(), 0, 1000, Some(5100))]
        );
        sensors.on_call(call(9, Request::Disable { handle: 1 }, 6000), running, now);
        assert_eq!(
            spans(&sensors.settle(now, 6000, WAIT)),
            [("android.sensor.gyroscope".into(), 0, 1000, Some(6000))]
        );
    }

    #[test]
    fn the_dump_says_which_sensors_each_app_has_on() {
        assert_eq!(
            parse_active(ANDROID_15),
            HashSet::from([(1000, 0), (UID, 1)])
        );
        // Android 16 says whether the first flush is pending instead.
        let android_16 = "2 active connections
Connection Number: 0
\tOperating Mode: NORMAL
\t com.example.b.Listener | WakeLockRefCount 0 | uid 10200 | cache size 0 | max cache size 0 | has sensor access: true
\t Pressure Sensor 0x0000000b | first flush pending: false | pending flush events 0
\t Step Counter 0x5f737463 | first flush pending: false | pending flush events 0
0 direct connections
";
        assert_eq!(
            parse_active(android_16),
            HashSet::from([(10200, 0xb), (10200, 0x5f73_7463)])
        );
        assert!(parse_active(ANDROID_8).is_empty());
    }

    #[test]
    fn a_handle_the_list_lacks_is_a_call_without_a_type_and_no_session() {
        let mut sensors = Sensors::new(list);
        let now = Instant::now();
        let on = Request::Enable {
            handle: 99,
            period_ns: 0,
        };
        sensors.on_call(call(9, on, 1000), running, now);
        let settled = sensors.settle(now, 5000, WAIT);
        assert_eq!(
            settled.calls[0].2,
            "binder:android.gui.SensorEventConnection/enableDisable"
        );
        // The sensor service turns on no sensor it does not have.
        assert!(settled.sessions.is_empty());
        assert!(!sensors.any_on());
        // Another unknown one soon after waits for the list to be read
        // again, which is not done more than once a minute.
        let later = now + Duration::from_secs(10);
        sensors.on_call(
            call(
                9,
                Request::Enable {
                    handle: 98,
                    period_ns: 0,
                },
                1010,
            ),
            running,
            later,
        );
        assert!(sensors.settle(later, 5000, WAIT).calls.is_empty());
        let minute = now + READ_AGAIN;
        assert_eq!(sensors.settle(minute, 5000, WAIT).calls.len(), 1);
    }

    #[test]
    fn a_list_that_cannot_be_read_does_not_hold_records_back() {
        let mut sensors = Sensors::new(failing_list);
        let now = Instant::now();
        let on = Request::Enable {
            handle: 0,
            period_ns: 0,
        };
        sensors.on_call(call(9, on, 1000), running, now);
        let settled = sensors.settle(now, 5000, WAIT);
        assert_eq!(settled.calls.len(), 1);
        assert_eq!(spans(&settled), [(String::new(), 0, 1000, None)]);
        // And it is not tried again at once.
        sensors.on_call(
            call(
                9,
                Request::Enable {
                    handle: 1,
                    period_ns: 0,
                },
                1001,
            ),
            running,
            now,
        );
        assert_eq!(sensors.settle(now, 5000, WAIT).calls.len(), 1);
        assert!(sensors.list.reading.is_none());
    }

    #[test]
    fn made_up_handles_do_not_fill_memory() {
        let mut sensors = Sensors::new(failing_list);
        let now = Instant::now();
        for handle in 0..10_000 {
            let on = Request::Enable {
                handle,
                period_ns: 0,
            };
            sensors.on_call(call(9, on, 1000), running, now);
        }
        assert_eq!(sensors.open.len(), MAX_OPEN);
        assert_eq!(sensors.turned_on.len(), MAX_WAITING);
    }
}
