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

//! Resolve sensor handles from the sensor service's dump.

use std::{
    collections::{HashMap, HashSet},
    sync::mpsc::{Receiver, RecvTimeoutError},
    time::{Duration, Instant},
};

use super::Sensor;

/// Heading of the list of sensors in the sensor service's dump.
const LIST_HEADING: &str = "Sensor List:";

/// The sensors in the output of `dumpsys sensorservice`, by handle: the
/// `Sensor List:` section, in which each sensor has a line such as
///
/// ```text
/// 0x00000001) BMI160 Accelerometer | Bosch | ver: 1 | type: android.sensor.accelerometer(1) | perm: n/a | flags: 0x00000050
/// ```
///
/// (without `flags` before Android 9; handle 0 is printed `0000000000`),
/// followed by lines that start with a tab, the first with its reporting
/// mode: `continuous | minRate=...`. The section ends at the next heading.
/// Nothing after it is read: later sections hold the names apps gave their
/// connections, which an app could make look like a sensor's line. Android 6
/// printed the list differently; it is not read.
fn parse_list(dump: &str) -> HashMap<i32, Sensor> {
    let mut sensors = HashMap::new();
    let mut lines = dump
        .lines()
        .skip_while(|line| line.trim_end() != LIST_HEADING)
        .skip(1)
        .peekable();
    while let Some(line) = lines.next() {
        if line.starts_with(char::is_whitespace) {
            continue;
        }
        // The next section's heading, such as `Fusion States:`.
        if line.trim_end().ends_with(':') {
            break;
        }
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

/// Fields the sensor service writes after a connection's package: four, and
/// `has sensor access` from Android 16.
const CONNECTION_FIELDS: usize = 5;

/// The sensors each app has on, by the sensor service's own account, as
/// (uid, handle). In the dump, each connection has a line with its package
/// and uid, `\t <package> | WakeLockRefCount 0 | uid 10123 | cache size 0 |
/// max cache size 0`, then one per sensor on:
/// `\t <sensor name> 0x00000001 | status: active | ...`
/// (`| first flush pending: false |` from Android 16). The uid is that of
/// the process, which for an isolated one is not its app's.
///
/// The package is whatever the app passed when it connected, so the uid is
/// taken from the fields the service writes after it. A package that holds
/// a line break can still add lines of its own; at worst they keep a session
/// taken over from a previous run on (see [`super::Sensors::watching`]) until it is
/// turned off or its process ends.
fn parse_active(dump: &str) -> HashSet<(u32, i32)> {
    let mut active = HashSet::new();
    let mut uid = None;
    for line in dump.lines() {
        // The fields the service writes after the package, last first: the
        // uid follows the wake lock count. A line break in the package does
        // not keep this part from being found.
        let trailing: Vec<&str> = line
            .rsplit(" | ")
            .take(CONNECTION_FIELDS)
            .map(str::trim)
            .collect();
        if let Some(at) = trailing.iter().position(|field| field.starts_with("uid "))
            && trailing
                .get(at + 1)
                .is_some_and(|field| field.starts_with("WakeLockRefCount "))
        {
            uid = trailing[at]["uid ".len()..].trim().parse().ok();
            continue;
        }
        // The sensors on are listed on lines that start with a tab.
        if !line.starts_with('\t') {
            uid = None;
            continue;
        }
        let first = line.split(" | ").next().unwrap_or_default().trim();
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

/// How soon the list is read again for a sensor it does not have. After a
/// reading fails, the wait doubles with each failure, up to
/// `READ_AGAIN_MAX`.
pub(super) const READ_AGAIN: Duration = Duration::from_secs(60);
const READ_AGAIN_MAX: Duration = Duration::from_secs(3600);
/// A reading that has taken this long is given up on.
const READING_TIMEOUT: Duration = Duration::from_secs(30);
/// Most handles remembered as not on the device. Beyond them, a handle
/// asked for again is looked up again, as often as the list may be read.
const MAX_MISSING: usize = 256;

/// The sensors by handle, and those on (see [`parse_active`]).
type Reading = anyhow::Result<(HashMap<i32, Sensor>, HashSet<(u32, i32)>)>;

/// What is known of a handle.
#[derive(Debug, PartialEq, Eq)]
pub(super) enum Lookup {
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
pub(super) struct SensorList {
    sensors: HashMap<i32, Sensor>,
    /// Handles a reading did not have. Turning one of these on again does
    /// not have the list read again, as an app going through handles that
    /// do not exist would otherwise have it read once a minute for good.
    missing: HashSet<i32>,
    read: fn() -> anyhow::Result<String>,
    /// A reading under way, and when it started (also as unix time).
    reading: Option<(Receiver<Reading>, Instant, i64)>,
    /// When the latest reading started.
    pub(super) started: Option<Instant>,
    /// When the latest reading that has ended, well or not, started (also
    /// as unix time).
    ended: Option<(Instant, i64)>,
    /// The sensors on at that reading; `None` if it failed.
    pub(super) active: Option<HashSet<(u32, i32)>>,
    /// Readings that failed in a row.
    failures: u32,
}

impl SensorList {
    pub(super) fn new(read: fn() -> anyhow::Result<String>) -> Self {
        Self {
            sensors: HashMap::new(),
            missing: HashSet::new(),
            read,
            reading: None,
            started: None,
            ended: None,
            active: None,
            failures: 0,
        }
    }

    /// What is already known of `handle`, without reading the list:
    /// `Found`, `NotFound`, or `Wait` for a handle not known either way.
    pub(super) fn known(&self, handle: i32) -> Lookup {
        if let Some(sensor) = self.sensors.get(&handle) {
            Lookup::Found(sensor.clone())
        } else if self.missing.contains(&handle) {
            Lookup::NotFound
        } else {
            Lookup::Wait
        }
    }

    /// The sensor `handle` stands for, for a call that arrived at `seen`.
    pub(super) fn lookup(&mut self, handle: i32, seen: Instant, now: Instant, ts: i64) -> Lookup {
        let known = self.known(handle);
        if known != Lookup::Wait {
            return known;
        }
        if self.reading.is_some() {
            return Lookup::Wait;
        }
        if self.read_since(seen) {
            if self.failures > 0 {
                return Lookup::Unreadable;
            }
            if self.missing.len() < MAX_MISSING {
                self.missing.insert(handle);
            }
            return Lookup::NotFound;
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
    pub(super) fn read_since(&self, since: Instant) -> bool {
        self.ended_since(since).is_some()
    }

    pub(super) fn ended_since(&self, since: Instant) -> Option<i64> {
        self.ended
            .filter(|(started, _)| *started >= since)
            .map(|(_, ts)| ts)
    }

    pub(super) fn is_reading(&self) -> bool {
        self.reading.is_some()
    }

    pub(super) fn start(&mut self, now: Instant, ts: i64) {
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
    pub(super) fn poll(&mut self, wait: Duration, now: Instant) {
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
                // A sensor connected since is no longer missing.
                self.missing.retain(|handle| !sensors.contains_key(handle));
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

#[cfg(test)]
mod tests {
    use super::super::tests::{ANDROID_15, UID, sensor};
    use super::*;

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

    /// A package name an app chose for its connection, made to look like
    /// a sensor of the list and another app's uid.
    const HOSTILE: &str =
        "x | uid 1000\n0x00000001) Fake | x | ver: 1 | type: android.sensor.light(5) | perm: n/a";

    #[test]
    fn what_apps_call_themselves_does_not_change_the_list_or_the_uid() {
        let dump = ANDROID_15
            .replace("com.example.a.Listener", HOSTILE)
            .replace("package=com.example.a", &format!("package={HOSTILE}"));
        let sensors = parse_list(&dump);
        assert_eq!(sensors, parse_list(ANDROID_15));
        assert_eq!(sensors[&1], sensor("android.sensor.gyroscope", true));
        // The gyroscope is still this app's, not uid 1000's.
        assert!(parse_active(&dump).contains(&(UID, 1)));
        assert!(!parse_active(&dump).contains(&(1000, 1)));
    }
}
