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

//! The cases of `testdata/protocol/`, which the app's tests read as well
//! (`ProtocolCase.kt`): the formats the daemon shares with the app, each
//! line with what it means. The format is in `testdata/protocol/README.md`.

/// One case: its type, the line, and the words of its meaning, each a
/// key and a value (empty for a word without `=`).
pub struct Case {
    pub kind: String,
    pub line: String,
    pub meaning: Vec<(String, String)>,
}

impl Case {
    /// The value of the one word `key`, if there is one.
    pub fn get<'a>(&'a self, key: &'a str) -> Option<&'a str> {
        let mut values = self.all(key);
        let value = values.next();
        assert!(values.next().is_none(), "{key} twice in {:?}", self.line);
        value
    }

    /// The values of the words `key`, in order.
    pub fn all<'a>(&'a self, key: &'a str) -> impl Iterator<Item = &'a str> {
        self.meaning
            .iter()
            .filter(move |(k, _)| k == key)
            .map(|(_, value)| value.as_str())
    }

    /// The first word, which names what the line is in some formats.
    pub fn first(&self) -> &str {
        &self.meaning[0].0
    }
}

/// The cases of a fixture's `text`, each of one of its `types` and with
/// words of its `keys` only: a case the tests would not look at, or a word
/// they would not read, is a mistake in the fixture.
pub fn cases(text: &str, types: &[&str], keys: &[&str]) -> Vec<Case> {
    let cases = parse(text);
    for case in &cases {
        assert!(
            types.contains(&case.kind.as_str()),
            "unknown type {:?} of {:?}",
            case.kind,
            case.line
        );
        for (key, _) in &case.meaning {
            assert!(
                keys.contains(&key.as_str()),
                "unknown word {key:?} in {:?}",
                case.line
            );
        }
    }
    cases
}

/// The cases of any of `types`, of which there must be at least one.
pub fn of<'a>(cases: &'a [Case], types: &[&str]) -> Vec<&'a Case> {
    let of: Vec<&Case> = cases
        .iter()
        .filter(|case| types.contains(&case.kind.as_str()))
        .collect();
    assert!(!of.is_empty(), "no case of {types:?}");
    of
}

/// `appops.txt`.
pub fn appops() -> Vec<Case> {
    cases(
        include_str!("../../testdata/protocol/appops.txt"),
        &[
            "command",
            "command-read",
            "command-bad",
            "answer",
            "answer-bad",
        ],
        &[
            "capture",
            "captured",
            "capture-failed",
            "set",
            "verify",
            "release",
            "restore",
            "apply",
            "saved",
            "restored",
            "applied",
            "failed",
            "stale",
            "verified",
            "reverted",
            "unheld",
            "kind",
            "library",
            "app",
            "unknown",
            "request",
            "package",
            "uid",
            "hold",
            "revoke",
            "revoke_unheld",
            "op",
            "permission",
            "name",
            "not_held",
        ],
    )
}

pub fn appops_transactions() -> Vec<Case> {
    cases(
        include_str!("../../testdata/protocol/appops-transaction.txt"),
        &["request", "reply"],
        &["request", "command", "token", "answer"],
    )
}

/// `spool.txt`.
pub fn spool() -> Vec<Case> {
    cases(
        include_str!("../../testdata/protocol/spool.txt"),
        &["line", "read", "bad"],
        &[
            "ts", "uid", "op", "mode", "read", "write", "items", "uri", "path",
        ],
    )
}

/// `sandbox-list.txt`.
pub fn sandbox_list() -> Vec<Case> {
    cases(
        include_str!("../../testdata/protocol/sandbox-list.txt"),
        &["line", "read", "bad"],
        &["app"],
    )
}

/// `sandbox-status.txt`.
pub fn sandbox_status() -> Vec<Case> {
    cases(
        include_str!("../../testdata/protocol/sandbox-status.txt"),
        &["line", "read"],
        &[
            "android_id",
            "app_list",
            "uri_grants",
            "ad_id",
            "api",
            "list",
            "boot",
            "ack",
            "now",
        ],
    )
}

fn parse(text: &str) -> Vec<Case> {
    text.lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(|line| {
            let mut fields = line.split('\t');
            let kind = fields.next().unwrap().to_string();
            // An empty line has nothing after its type.
            let line = unescape(fields.next().unwrap_or(""));
            let meaning = fields
                .map(|word| {
                    let (key, value) = word.split_once('=').unwrap_or((word, ""));
                    (key.to_string(), unescape(value))
                })
                .collect();
            Case {
                kind,
                line,
                meaning,
            }
        })
        .collect()
}

/// `\t`, `\n` and `\\` as the characters they stand for.
fn unescape(text: &str) -> String {
    let mut out = String::new();
    let mut chars = text.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('t') => out.push('\t'),
            Some('n') => out.push('\n'),
            Some('\\') => out.push('\\'),
            other => panic!("unknown escape \\{other:?} in {text:?}"),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;

    #[test]
    fn cli_rejections_match_the_diagnostics_read_by_the_app() {
        let cases = cases(
            include_str!("../../testdata/protocol/bbd-cli-errors.txt"),
            &[
                "unknown-subcommand",
                "unknown-option",
                "repeated-option",
                "invalid-number",
                "invalid-enum",
            ],
            &["arg"],
        );
        for case in cases {
            let args = std::iter::once("bbd").chain(case.all("arg"));
            let Err(error) = crate::Cli::try_parse_from(args) else {
                panic!("CLI accepted {}", case.kind);
            };
            assert_eq!(error.exit_code(), 2, "{}", case.kind);
            assert_eq!(error.to_string().lines().next(), Some(case.line.as_str()));
        }
    }

    #[test]
    fn a_case_is_read_with_its_escapes_and_meaning() {
        let cases = cases(
            "# comment\n\nline\ta\\tb\\n\\\\\tset\tuid=1\top=x=y\nbad\n",
            &["line", "bad"],
            &["set", "uid", "op"],
        );
        assert_eq!(cases.len(), 2);
        assert_eq!(cases[0].kind, "line");
        assert_eq!(cases[0].line, "a\tb\n\\");
        assert_eq!(cases[0].first(), "set");
        assert_eq!(cases[0].get("uid"), Some("1"));
        assert_eq!(cases[0].get("op"), Some("x=y"));
        assert_eq!(
            (cases[1].kind.as_str(), cases[1].line.as_str()),
            ("bad", "")
        );
    }

    #[test]
    #[should_panic(expected = "unknown type")]
    fn a_case_of_an_unknown_type_is_a_mistake() {
        cases("lnie\tx\n", &["line"], &[]);
    }

    #[test]
    #[should_panic(expected = "unknown word")]
    fn a_word_of_an_unknown_key_is_a_mistake() {
        cases("line\tx\tuid=1\n", &["line"], &["ui"]);
    }

    #[test]
    #[should_panic(expected = "unknown escape")]
    fn a_backslash_at_the_end_is_a_mistake() {
        parse("line\tx\\\n");
    }

    #[test]
    #[should_panic(expected = "no case")]
    fn a_type_without_cases_is_a_mistake() {
        of(&parse("line\tx\n"), &["bad"]);
    }
}
