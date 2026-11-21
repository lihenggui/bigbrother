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

//! Rate limits for repeated daemon messages.

use std::{
    fmt::Arguments,
    time::{Duration, Instant},
};

const RECURRING_INTERVAL: Duration = Duration::from_secs(3600);
const SANDBOX_INTERVAL: Duration = Duration::from_secs(60);

/// The last logged message (or a unit key for messages sharing a limit) and
/// the number suppressed since. The log is only rotated at boot.
#[derive(Default)]
pub struct Limited<K> {
    last: Option<(K, Instant)>,
    unlogged: u32,
}

impl<K: PartialEq> Limited<K> {
    fn record(&mut self, key: K, now: Instant, interval: Duration) -> Option<(&K, u32)> {
        if let Some((last, logged)) = &self.last
            && *last == key
        {
            if now.duration_since(*logged) < interval {
                self.unlogged = self.unlogged.saturating_add(1);
                return None;
            }
        } else {
            self.unlogged = 0;
        }
        let (key, _) = self.last.insert((key, now));
        Some((key, std::mem::take(&mut self.unlogged)))
    }
}

impl Limited<String> {
    /// A changed message is logged immediately.
    pub fn log(&mut self, message: String) {
        if let Some(line) = self.line(message, Instant::now()) {
            eprintln!("{line}");
        }
    }

    #[cfg(test)]
    pub fn last_message(&self) -> Option<&str> {
        self.last.as_ref().map(|(message, _)| message.as_str())
    }

    fn line(&mut self, message: String, now: Instant) -> Option<String> {
        let (message, count) = self.record(message, now, RECURRING_INTERVAL)?;
        Some(match count {
            0 => message.clone(),
            n => format!("{message} ({n} more since)"),
        })
    }
}

impl Limited<()> {
    /// Distinct sandbox failures share a limit within each caller. Format
    /// the message only if it will be written.
    pub fn log_sandbox(&mut self, message: Arguments<'_>) {
        self.write_sandbox(message);
    }

    fn write_sandbox(&mut self, message: Arguments<'_>) -> bool {
        match self.record((), Instant::now(), SANDBOX_INTERVAL) {
            Some((_, 0)) => eprintln!("bbd: sandbox: {message}"),
            Some((_, n)) => eprintln!("bbd: sandbox: {message} ({n} more since the last)"),
            None => return false,
        }
        true
    }

    pub fn log_sandbox_after_write(&mut self, message: Arguments<'_>) {
        if self.write_sandbox(message) {
            self.last = Some(((), Instant::now()));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_recurring_message_is_logged_once_an_hour() {
        let mut log = Limited::default();
        let start = Instant::now();
        let at = |secs| start + Duration::from_secs(secs);
        assert_eq!(log.line("a".into(), at(0)).as_deref(), Some("a"));
        assert_eq!(log.line("a".into(), at(30)), None);
        assert_eq!(log.line("a".into(), at(90)), None);
        assert_eq!(
            log.line("a".into(), at(3600)).as_deref(),
            Some("a (2 more since)")
        );
        assert_eq!(log.line("a".into(), at(3630)), None);
        // A different message is logged at once.
        assert_eq!(log.line("b".into(), at(3660)).as_deref(), Some("b"));
        assert_eq!(log.line("a".into(), at(3690)).as_deref(), Some("a"));
        // One that recurs no more than hourly is logged each time, as is.
        assert_eq!(log.line("a".into(), at(7290)).as_deref(), Some("a"));
    }

    #[test]
    fn sandbox_failures_are_counted_between_log_lines() {
        let mut log = Limited::default();
        let mut record = |at| log.record((), at, SANDBOX_INTERVAL).map(|(_, count)| count);
        let start = Instant::now();
        assert_eq!(record(start), Some(0));
        assert_eq!(record(start + Duration::from_secs(1)), None);
        assert_eq!(record(start + Duration::from_secs(59)), None);
        assert_eq!(record(start + SANDBOX_INTERVAL), Some(2));
    }
}
