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

//! Encode commands and read replies from AppOpsGuard.

use std::collections::{BTreeSet, HashMap};

use crate::sandbox::{
    Entry,
    items::{OpMode, Settings},
};

/// The helper's answer to a command about one uid.
#[derive(Debug, PartialEq, Eq)]
pub(super) enum Answer {
    /// To `capture`: the original values, read without changing the uid.
    Captured(u64, Option<String>),
    /// To `set`: the modes the ops had and the states of the permissions
    /// taken, as `restore` takes them.
    Saved(String),
    Restored,
    /// To `apply`: the states of the permissions it took anew.
    Applied(String),
    /// To `kind`: whether the package is a library rather than an app, if
    /// the helper could tell, with the request number it was given.
    Kind(u64, Option<bool>),
    /// The captured state changed before any write.
    Stale,
    Failed,
}

/// A line the helper printed: the uid it is about, and the answer if it is
/// one. `reverted` and `verified` lines report what the helper did by
/// itself and answer nothing.
pub(super) fn parse_line(line: &str) -> Option<(u32, Option<Answer>)> {
    let mut words = line.split_ascii_whitespace();
    let kind = words.next()?;
    let uid = words.next()?.parse().ok()?;
    let answer = match kind {
        "captured" | "capture-failed" => {
            let request = words.next()?.parse().ok()?;
            let values = if kind == "captured" {
                Some(words.next().unwrap_or("").to_owned())
            } else {
                None
            };
            if words.next().is_some() {
                return None;
            }
            Some(Answer::Captured(request, values))
        }
        "saved" => Some(Answer::Saved(words.next().unwrap_or("").to_string())),
        "restored" => Some(Answer::Restored),
        "applied" => Some(Answer::Applied(words.next().unwrap_or("").to_string())),
        "stale" if words.next().is_none() => Some(Answer::Stale),
        "failed" => Some(Answer::Failed),
        "library" | "app" | "unknown" => {
            let request = words.next()?.parse().ok()?;
            if words.next().is_some() {
                return None;
            }
            let library = match kind {
                "library" => Some(true),
                "app" => Some(false),
                _ => None,
            };
            Some(Answer::Kind(request, library))
        }
        "reverted" | "verified" | "unheld" => None,
        _ => return None,
    };
    Some((uid, answer))
}

pub(super) fn request_line(request: u64, command: &str) -> String {
    format!("request {request} {command}")
}

/// The installation binding and request number must match before a mutation can settle.
pub(super) fn parse_reply(line: &str) -> Option<(u32, u64, &str, Answer)> {
    let mut words = line.splitn(4, ' ');
    if words.next()? != "reply" {
        return None;
    }
    let request = words.next()?.parse().ok()?;
    let token = words.next()?;
    let (uid, answer) = parse_line(words.next()?)?;
    Some((uid, request, token, answer?))
}

/// `unheld <uid> <op>,...` (or `-` for none): the ops of a guarded uid that
/// the system did not keep at the mode the uid is held to, after the
/// helper set them. Ops this Android version does not have are not named.
pub(super) fn parse_unheld(line: &str) -> Option<(u32, BTreeSet<String>)> {
    let mut words = line.split_ascii_whitespace();
    if words.next()? != "unheld" {
        return None;
    }
    let uid = words.next()?.parse().ok()?;
    let ops = match words.next() {
        None | Some("-") => BTreeSet::new(),
        Some(ops) => ops.split(',').map(str::to_string).collect(),
    };
    Some((uid, ops))
}

/// What a `reverted` line reports of a permission the helper found changed
/// by the user while it held it revoked, and took back: the uid, and
/// `<permission>=<state>`, the state it was found in.
pub(super) fn found_in(line: &str) -> Option<(u32, String)> {
    let mut words = line.split_ascii_whitespace();
    if words.next()? != "reverted" {
        return None;
    }
    let uid = words.next()?.parse().ok()?;
    let found = words.next()?;
    let (name, state) = found.split_once('=')?;
    (is_permission(name) && !state.is_empty() && words.next().is_none())
        .then(|| (uid, found.to_string()))
}

/// The framework's number for an op's mode, as the helper takes it.
fn mode_number(mode: OpMode) -> u8 {
    match mode {
        // `AppOpsManager.MODE_IGNORED`: the app is given an empty answer,
        // with its permission still shown as granted.
        OpMode::Ignored => 1,
        // `AppOpsManager.MODE_FOREGROUND`.
        OpMode::Foreground => 4,
    }
}

/// What the helper holds a permission to: revoked, and not to be asked for
/// again.
const REVOKED: &str = "revoked";

/// In a `verify`, in place of [`REVOKED`]: held revoked all the same, but
/// last reported unheld (`super::State::unsure`).
const UNHELD: &str = "unheld";

/// `targets`, as [`targets_of`] writes them, for a `verify`: the
/// permissions among `unsure` written as [`UNHELD`].
pub(super) fn marking_unheld(targets: &str, unsure: Option<&BTreeSet<String>>) -> String {
    let Some(unsure) = unsure.filter(|unsure| !unsure.is_empty()) else {
        return targets.to_string();
    };
    pairs(targets)
        .map(|(name, value)| {
            let value = if unsure.contains(name) { UNHELD } else { value };
            format!("{name}={value}")
        })
        .collect::<Vec<_>>()
        .join(",")
}

/// What `restore` and `apply` are given for an op or a permission whose
/// state before is not known: an op loses its uid mode, a permission the
/// mark that keeps the app from asking for it.
pub(super) const NOT_KNOWN: &str = "default";

/// What to hold an app to, as the helper takes it: `<op>=<mode>` for each
/// op, with the mode's number, then `<permission>=revoked` for each
/// permission taken, joined by commas. Empty if nothing.
pub(super) fn targets_of(items: &Settings) -> String {
    targets_text(&items.op_targets(), &items.taken_permissions())
}

/// `ops` at their modes and `permissions` revoked, as [`targets_of`]
/// writes them.
fn targets_text(ops: &[(&str, OpMode)], permissions: &[&str]) -> String {
    let ops = ops
        .iter()
        .map(|(op, mode)| format!("{op}={}", mode_number(*mode)));
    let permissions = permissions
        .iter()
        .map(|permission| format!("{permission}={REVOKED}"));
    ops.chain(permissions).collect::<Vec<_>>().join(",")
}

/// A line the daemon tells the helper (`parseCommand` in
/// `AppOpsGuardProtocol.kt`): targets as [`targets_of`] writes them,
/// values as [`saved_values_of`] does.
pub(super) enum Command<'a> {
    Capture(u32, u64, &'a str),
    Set(u32, &'a str),
    Verify(u32, &'a str),
    #[cfg(test)]
    Release(u32),
    Undo(u32, &'a str),
    CheckUndo(u32, &'a str),
    Restore(u32, &'a str),
    Kind(u32, u64, &'a str),
    /// The targets, then what to set back; nothing to set back is `-`.
    Apply(u32, &'a str, &'a str),
}

impl std::fmt::Display for Command<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match *self {
            Self::Capture(uid, request, targets) => write!(f, "capture {uid} {request} {targets}"),
            Self::Set(uid, targets) => write!(f, "set {uid} {targets}"),
            Self::Verify(uid, targets) => write!(f, "verify {uid} {targets}"),
            #[cfg(test)]
            Self::Release(uid) => write!(f, "release {uid}"),
            Self::Undo(uid, values) | Self::CheckUndo(uid, values) => {
                let verb = if matches!(self, Self::Undo(..)) {
                    "undo"
                } else {
                    "check-undo"
                };
                write!(
                    f,
                    "{verb} {uid} {}",
                    if values.is_empty() { "-" } else { values }
                )
            }
            Self::Restore(uid, values) => write!(f, "restore {uid} {values}"),
            Self::Kind(uid, request, package) => write!(f, "kind {uid} {request} {package}"),
            Self::Apply(uid, targets, resets) => {
                let resets = if resets.is_empty() { "-" } else { resets };
                write!(f, "apply {uid} {targets} {resets}")
            }
        }
    }
}

/// Whether `name`, in what the helper is told, is a permission's rather
/// than an op's (`android:camera`).
pub(super) fn is_permission(name: &str) -> bool {
    name.starts_with("android.permission.")
}

/// The pairs of a list of `<name>=<value>,...`.
pub(super) fn pairs(list: &str) -> impl Iterator<Item = (&str, &str)> {
    list.split(',').filter_map(|pair| pair.split_once('='))
}

/// The names in a list of `<name>=<value>,...`, such as [`targets_of`]
/// writes.
pub(super) fn names_in(list: &str) -> Vec<&str> {
    pairs(list).map(|(name, _)| name).collect()
}

/// What `entry` saved for each of `names`, as `restore` and `apply` take
/// it: an op's mode from `saved_ops`, a permission's state from
/// `saved_permissions`, `default` for what it saved nothing for.
pub(super) fn saved_values_of(entry: &Entry, names: &[&str]) -> String {
    let saved: HashMap<&str, &str> = pairs(&entry.saved_ops)
        .chain(pairs(&entry.saved_permissions))
        .collect();
    names
        .iter()
        .map(|name| format!("{name}={}", saved.get(name).copied().unwrap_or(NOT_KNOWN)))
        .collect::<Vec<_>>()
        .join(",")
}

/// The ops and the permissions of the helper's `saved` answer: what goes in
/// an entry's `saved_ops`, and the states it found of the permissions.
pub(super) fn split_saved(saved: &str) -> (String, String) {
    let (permissions, ops): (Vec<&str>, Vec<&str>) = saved
        .split(',')
        .filter(|pair| !pair.is_empty())
        .partition(|pair| is_permission(pair.split('=').next().unwrap_or("")));
    (ops.join(","), permissions.join(","))
}

#[cfg(test)]
mod tests {
    use super::super::tests::{FINE, only, sandboxed};
    use super::*;

    /// The `<name>=<value>` of a value word of a case (`op=` or
    /// `permission=`), as the helper writes it and the daemon keeps it and
    /// hands it back unread: an op's mode, `default` for none, then `/`
    /// and its grant's mode; a permission's state with its flags joined by
    /// `+`, `default` where it is not known.
    fn value_of(word: &str) -> (&str, String) {
        let mut parts = word.split('/');
        let name = parts.next().unwrap();
        let first = match parts.next().unwrap() {
            "none" | "unknown" => NOT_KNOWN,
            value => value,
        };
        let rest: Vec<&str> = std::iter::once(first).chain(parts).collect();
        let value = rest.join(if is_permission(name) { "+" } else { "/" });
        (name, value)
    }

    /// The values a case names, in order.
    fn values_of(case: &crate::protocol_cases::Case) -> Vec<(&str, String)> {
        case.meaning
            .iter()
            .filter(|(key, _)| key == "op" || key == "permission")
            .map(|(_, word)| value_of(word))
            .collect()
    }

    fn joined(values: &[(&str, String)], permissions: bool) -> String {
        values
            .iter()
            .filter(|(name, _)| is_permission(name) == permissions)
            .map(|(name, value)| format!("{name}={value}"))
            .collect::<Vec<_>>()
            .join(",")
    }

    #[test]
    fn every_command_is_written_as_the_helper_reads_it() {
        let cases = crate::protocol_cases::appops();
        for case in crate::protocol_cases::of(&cases, &["command"]) {
            let uid: u32 = case.get("uid").unwrap().parse().unwrap();
            let ops: Vec<(&str, OpMode)> = case
                .all("hold")
                .map(|word| {
                    let (op, mode) = word.split_once('/').unwrap();
                    let mode = match mode {
                        "1" => OpMode::Ignored,
                        "4" => OpMode::Foreground,
                        other => panic!("no op is held at {other}"),
                    };
                    (op, mode)
                })
                .collect();
            let permissions: Vec<&str> = case
                .all("revoke")
                .chain(case.all("revoke_unheld"))
                .collect();
            let unsure: BTreeSet<String> = case.all("revoke_unheld").map(str::to_string).collect();
            let targets = marking_unheld(&targets_text(&ops, &permissions), Some(&unsure));
            // What the entry saved; a value not known is not in it, and
            // is sent as not known all the same.
            let values = values_of(case);
            let known: Vec<(&str, String)> = values
                .iter()
                .filter(|(_, value)| value != NOT_KNOWN)
                .cloned()
                .collect();
            let entry = Entry {
                saved_ops: joined(&known, false),
                saved_permissions: joined(&known, true),
                ..sandboxed(uid)
            };
            let names: Vec<&str> = values.iter().map(|(name, _)| *name).collect();
            let saved = saved_values_of(&entry, &names);
            let line = match case.first() {
                "capture" => {
                    Command::Capture(uid, case.get("request").unwrap().parse().unwrap(), &targets)
                }
                "set" => Command::Set(uid, &targets),
                "verify" => Command::Verify(uid, &targets),
                "release" => Command::Release(uid),
                "restore" => Command::Restore(uid, &saved),
                "apply" => Command::Apply(uid, &targets, &saved),
                "kind" => Command::Kind(
                    uid,
                    case.get("request").unwrap().parse().unwrap(),
                    case.get("package").unwrap(),
                ),
                other => panic!("no command {other}"),
            }
            .to_string();
            assert_eq!(line, case.line);
        }
    }

    #[test]
    fn mutating_requests_and_replies_keep_the_request_and_installation() {
        let cases = crate::protocol_cases::appops_transactions();
        for case in crate::protocol_cases::of(&cases, &["request"]) {
            let request = case.get("request").unwrap().parse().unwrap();
            let command = case.get("command").unwrap();
            assert_eq!(request_line(request, command), case.line);
        }
        for case in crate::protocol_cases::of(&cases, &["reply"]) {
            let request = case.get("request").unwrap().parse().unwrap();
            let token = case.get("token").unwrap();
            let (uid, answer) = parse_line(case.get("answer").unwrap()).unwrap();
            assert_eq!(
                parse_reply(&case.line),
                Some((uid, request, token, answer.unwrap()))
            );
        }
        assert!(parse_reply("restored 10100").is_none());
    }

    #[test]
    fn every_answer_and_report_is_read_as_the_helper_writes_it() {
        let cases = crate::protocol_cases::appops();
        for case in crate::protocol_cases::of(&cases, &["answer"]) {
            let line = case.line.as_str();
            let uid: u32 = case.get("uid").unwrap().parse().unwrap();
            let values = values_of(case);
            let text = values
                .iter()
                .map(|(name, value)| format!("{name}={value}"))
                .collect::<Vec<_>>()
                .join(",");
            let answer = match case.first() {
                "captured" | "capture-failed" => Some(Answer::Captured(
                    case.get("request").unwrap().parse().unwrap(),
                    (case.first() == "captured").then(|| text.clone()),
                )),
                "saved" => Some(Answer::Saved(text.clone())),
                "restored" => Some(Answer::Restored),
                "applied" => Some(Answer::Applied(text.clone())),
                "stale" => Some(Answer::Stale),
                "failed" => Some(Answer::Failed),
                "library" | "app" | "unknown" => {
                    let request = case.get("request").unwrap().parse().unwrap();
                    let library = match case.first() {
                        "library" => Some(true),
                        "app" => Some(false),
                        _ => None,
                    };
                    Some(Answer::Kind(request, library))
                }
                "verified" | "reverted" | "unheld" => None,
                other => panic!("no answer {other}"),
            };
            assert_eq!(parse_line(line), Some((uid, answer)), "{line}");
            if case.first() == "saved" {
                assert_eq!(
                    split_saved(&text),
                    (joined(&values, false), joined(&values, true)),
                    "{line}"
                );
            }
            let unheld = (case.first() == "unheld")
                .then(|| (uid, case.all("not_held").map(str::to_string).collect()));
            assert_eq!(parse_unheld(line), unheld, "{line}");
            let found = (case.first() == "reverted" && !values.is_empty()).then_some((uid, text));
            assert_eq!(found_in(line), found, "{line}");
        }
        for case in crate::protocol_cases::of(&cases, &["answer-bad"]) {
            let line = case.line.as_str();
            assert_eq!(parse_line(line), None, "{line:?}");
            assert_eq!(parse_unheld(line), None, "{line:?}");
            assert_eq!(found_in(line), None, "{line:?}");
        }
    }

    #[test]
    fn the_helper_is_told_the_ops_of_the_items_on() {
        use crate::sandbox_item::{Item, Mode};
        let items = only(&[(Item::Camera, Mode::On), (Item::Contacts, Mode::On)]);
        assert_eq!(
            targets_of(&items),
            "android:read_contacts=1,android:write_contacts=1,android:camera=1"
        );
        let entry = Entry {
            saved_ops: "android:camera=4,android:read_contacts=default".into(),
            saved_permissions: format!("{FINE}=denied+user_set"),
            ..sandboxed(10100)
        };
        assert_eq!(
            saved_values_of(
                &entry,
                &[
                    "android:camera",
                    "android:read_contacts",
                    "android:record_audio",
                    FINE,
                    "android.permission.CAMERA",
                ]
            ),
            "android:camera=4,android:read_contacts=default,android:record_audio=default,\
             android.permission.ACCESS_FINE_LOCATION=denied+user_set,\
             android.permission.CAMERA=default"
        );
        assert_eq!(
            names_in(&targets_of(&items)),
            [
                "android:read_contacts",
                "android:write_contacts",
                "android:camera"
            ]
        );
        assert!(names_in("").is_empty());
    }

    #[test]
    fn location_made_coarse_takes_the_precise_permission_and_no_location_op() {
        use crate::sandbox_item::{Item, Mode};
        assert_eq!(
            targets_of(&only(&[
                (Item::Location, Mode::Coarse),
                (Item::Camera, Mode::On)
            ])),
            "android:camera=1,android.permission.ACCESS_FINE_LOCATION=revoked"
        );
        assert_eq!(
            targets_of(&only(&[(Item::Location, Mode::Coarse)])),
            "android.permission.ACCESS_FINE_LOCATION=revoked"
        );
        // What the helper saved goes where it belongs.
        assert_eq!(
            split_saved(&format!(
                "android:camera=4,{FINE}=granted,android:read_sms=default"
            )),
            (
                "android:camera=4,android:read_sms=default".to_string(),
                format!("{FINE}=granted")
            )
        );
        assert_eq!(split_saved(""), (String::new(), String::new()));
    }

    #[test]
    fn a_permission_found_granted_again_is_read_from_the_helpers_lines() {
        assert_eq!(
            found_in(&format!("reverted 10100 {FINE}=granted+user_set")),
            Some((10100, format!("{FINE}=granted+user_set")))
        );
        // Answers nothing.
        assert_eq!(
            parse_line(&format!("reverted 10100 {FINE}=granted")),
            Some((10100, None))
        );
        for line in [
            format!("reverted 10100 {FINE}"),
            "reverted 10100 android:camera".to_string(),
            "reverted 10100 android:camera=1".to_string(),
            format!("reverted 10100 {FINE}="),
            format!("verified 10100 {FINE}=granted"),
            format!("reverted x {FINE}=granted"),
        ] {
            assert_eq!(found_in(&line), None, "{line}");
        }
    }

    #[test]
    fn a_verify_names_the_permissions_last_reported_unheld() {
        let targets = targets_text(&[("android:camera", OpMode::Ignored)], &[FINE]);
        assert_eq!(marking_unheld(&targets, None), targets);
        assert_eq!(marking_unheld(&targets, Some(&BTreeSet::new())), targets);
        let unsure = BTreeSet::from([FINE.to_string()]);
        assert_eq!(
            marking_unheld(&targets, Some(&unsure)),
            format!("android:camera=1,{FINE}=unheld")
        );
    }
}
