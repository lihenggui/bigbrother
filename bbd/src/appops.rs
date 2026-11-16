//! What the system itself recorded about apps using their permissions.
//!
//! Android notes every use of a sensitive permission (an "app op"): when an
//! app last used the camera, the microphone, the location, when it was last
//! refused. This is the system's own account, made where the data is handed
//! out, so unlike a watched request (`binder`) it says whether the app
//! actually got something. It also covers the camera and the microphone,
//! which nothing else here sees.
//!
//! The records are read through the app's `AppOpsDump` program, which is
//! kept running and prints them twice a minute. The system keeps only the
//! latest use and the latest refusal per app and op, so several uses between
//! two readings count as one, at the time of the last.
//!
//! A record is stored as `appop:<op name>`, e.g. `appop:android:camera`.

use std::collections::HashMap;

use crate::rules::topic;

/// Prefix of a stored record.
pub const SCHEME: &str = "appop:";

/// The ops that are read, by their public names, and what each reveals.
const OPS: &[(&str, &str)] = &[
    ("android:camera", topic::CAMERA),
    ("android:record_audio", topic::MICROPHONE),
    ("android:fine_location", topic::LOCATION),
    ("android:coarse_location", topic::LOCATION),
    ("android:body_sensors", topic::SENSORS),
    ("android:activity_recognition", topic::SENSORS),
    ("android:read_clipboard", topic::CLIPBOARD),
    ("android:read_contacts", topic::CONTACTS),
    ("android:read_call_log", topic::CALL_LOG),
    ("android:read_sms", topic::MESSAGES),
    ("android:read_calendar", topic::CALENDAR),
    ("android:get_accounts", topic::ACCOUNTS),
    ("android:read_phone_state", topic::PHONE_IDENTITY),
    ("android:read_phone_numbers", topic::PHONE_IDENTITY),
    ("android:get_usage_stats", topic::USAGE_STATS),
];

pub const HELPER_CLASS: &str = "AppOpsDump";
/// The line `AppOpsDump` prints after each reading.
pub const END_OF_READING: &str = ".";

/// The op names to ask the system about.
pub fn ops() -> impl Iterator<Item = &'static str> {
    OPS.iter().map(|(op, _)| *op)
}

/// What a stored record reveals. `None` if `subject` is not one.
pub fn topic_of(subject: &str) -> Option<&'static str> {
    let op = subject.strip_prefix(SCHEME)?;
    OPS.iter().find(|(name, _)| *name == op).map(|(_, t)| *t)
}

/// The latest use and refusal of one op by one app, as the system has them.
#[derive(Debug, PartialEq, Eq)]
pub struct Entry {
    pub uid: u32,
    op: &'static str,
    /// Unix time in seconds; 0 or less if it never happened.
    used: i64,
    refused: i64,
}

/// Parse the output of `AppOpsDump`, tab separated, one line per app and op:
/// `<uid> <package> <op name> <last use, ms> <last refusal, ms>`.
pub fn parse(output: &str) -> Vec<Entry> {
    output
        .lines()
        .filter_map(|line| {
            let mut fields = line.split('\t');
            let uid = fields.next()?.parse().ok()?;
            let _package = fields.next()?;
            let name = fields.next()?;
            let op = OPS.iter().find(|(op, _)| *op == name)?.0;
            let used: i64 = fields.next()?.parse().ok()?;
            let refused: i64 = fields.next()?.parse().ok()?;
            Some(Entry {
                uid,
                op,
                used: used / 1000,
                refused: refused / 1000,
            })
        })
        .collect()
}

/// Something to record: an app used an op, or was refused.
#[derive(Debug, PartialEq, Eq)]
pub struct Use {
    pub uid: u32,
    pub subject: String,
    pub allowed: bool,
    pub ts: i64,
}

/// Turns successive readings into the uses that happened between them.
pub struct Tracker {
    /// The (use, refusal) times already recorded, per app and op.
    recorded: HashMap<(u32, &'static str), (i64, i64)>,
    since: i64,
}

impl Tracker {
    /// `since`: uses before this time are not recorded. Set to the daemon's
    /// start, so that a restart does not record again what the previous run
    /// already did.
    pub fn new(since: i64) -> Self {
        Self {
            recorded: HashMap::new(),
            since,
        }
    }

    pub fn update(&mut self, entries: &[Entry]) -> Vec<Use> {
        let mut uses = Vec::new();
        for entry in entries {
            let seen = self
                .recorded
                .entry((entry.uid, entry.op))
                .or_insert((self.since, self.since));
            for (time, last, allowed) in [
                (entry.used, &mut seen.0, true),
                (entry.refused, &mut seen.1, false),
            ] {
                if time > *last {
                    *last = time;
                    uses.push(Use {
                        uid: entry.uid,
                        subject: format!("{SCHEME}{}", entry.op),
                        allowed,
                        ts: time,
                    });
                }
            }
        }
        uses
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const DUMP: &str = "10100\tcom.a\tandroid:camera\t1700000100000\t-1\n\
        10100\tcom.a\tandroid:record_audio\t-1\t1700000200000\n\
        10200\tcom.b\tandroid:fine_location\t1699990000000\t0\n\
        10200\tcom.b\tandroid:vibrate\t1700000100000\t0\n\
        broken\n";

    #[test]
    fn only_new_uses_are_recorded() {
        let entries = parse(DUMP);
        assert_eq!(entries.len(), 3);
        let mut tracker = Tracker::new(1_700_000_000);
        let uses = tracker.update(&entries);
        assert_eq!(
            uses,
            [
                Use {
                    uid: 10100,
                    subject: "appop:android:camera".into(),
                    allowed: true,
                    ts: 1_700_000_100,
                },
                Use {
                    uid: 10100,
                    subject: "appop:android:record_audio".into(),
                    allowed: false,
                    ts: 1_700_000_200,
                },
            ]
        );
        // The same reading again adds nothing; a later use does.
        assert!(tracker.update(&entries).is_empty());
        let later = parse("10100\tcom.a\tandroid:camera\t1700000300000\t-1\n");
        assert_eq!(tracker.update(&later)[0].ts, 1_700_000_300);
    }

    #[test]
    fn topics() {
        assert_eq!(topic_of("appop:android:camera"), Some(topic::CAMERA));
        assert_eq!(topic_of("appop:android:vibrate"), None);
        assert_eq!(topic_of("exec:su"), None);
        for op in ops() {
            assert!(topic_of(&format!("{SCHEME}{op}")).is_some());
        }
    }
}
