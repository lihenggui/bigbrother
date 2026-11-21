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
    fmt::{Arguments, Display},
    time::{Duration, Instant},
};

pub const RECURRING_INTERVAL: Duration = Duration::from_secs(3600);
pub const SANDBOX_INTERVAL: Duration = Duration::from_secs(60);

pub enum Policy {
    /// All messages from a caller share a limit.
    Shared,
    /// A changed message starts a fresh interval.
    ResetOnChange,
}

/// The log is only rotated at boot, so a recurring failure must be bounded.
pub struct Limited {
    interval: Duration,
    logged: Option<Instant>,
    last_message: Option<String>,
    suppressed: u32,
}

impl Limited {
    pub const fn new(interval: Duration, policy: Policy) -> Self {
        Self {
            interval,
            logged: None,
            last_message: match policy {
                Policy::Shared => None,
                Policy::ResetOnChange => Some(String::new()),
            },
            suppressed: 0,
        }
    }

    pub fn log(&mut self, message: Arguments<'_>) {
        self.log_at(message, Instant::now(), |line| eprintln!("{line}"));
    }

    fn log_at(&mut self, message: Arguments<'_>, now: Instant, emit: impl FnOnce(Arguments<'_>)) {
        // Shared limits can decide whether to log without formatting the message.
        let changed = self.last_message.as_mut().is_some_and(|last| {
            let text = message.to_string();
            if text == *last {
                return false;
            }
            *last = text;
            true
        });
        if changed {
            self.suppressed = 0;
        } else if self
            .logged
            .is_some_and(|at| now.duration_since(at) < self.interval)
        {
            self.suppressed = self.suppressed.saturating_add(1);
            return;
        }
        self.logged = Some(now);
        let message: &dyn Display = match &self.last_message {
            Some(text) => text,
            None => &message,
        };
        match std::mem::take(&mut self.suppressed) {
            0 => emit(format_args!("{message}")),
            n => emit(format_args!("{message} ({n} more since the last)")),
        }
    }

    #[cfg(test)]
    pub fn last_message(&self) -> Option<&str> {
        self.logged?;
        self.last_message.as_deref()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn line(log: &mut Limited, message: &str, now: Instant) -> Option<String> {
        let mut line = None;
        log.log_at(format_args!("{message}"), now, |text| {
            line = Some(text.to_string());
        });
        line
    }

    #[test]
    fn a_recurring_message_is_logged_once_an_hour() {
        let mut log = Limited::new(RECURRING_INTERVAL, Policy::ResetOnChange);
        let start = Instant::now();
        let mut at = |message, secs| line(&mut log, message, start + Duration::from_secs(secs));
        assert_eq!(at("a", 0).as_deref(), Some("a"));
        assert_eq!(at("a", 30), None);
        assert_eq!(at("a", 90), None);
        assert_eq!(at("a", 3600).as_deref(), Some("a (2 more since the last)"));
        assert_eq!(at("a", 3630), None);
        // A different message is logged at once.
        assert_eq!(at("b", 3660).as_deref(), Some("b"));
        assert_eq!(at("a", 3690).as_deref(), Some("a"));
        // One that recurs no more than hourly is logged each time, as is.
        assert_eq!(at("a", 7290).as_deref(), Some("a"));
    }

    #[test]
    fn different_messages_share_the_same_limit() {
        let mut log = Limited::new(SANDBOX_INTERVAL, Policy::Shared);
        let start = Instant::now();
        assert_eq!(
            line(&mut log, "bbd: sandbox: a", start).as_deref(),
            Some("bbd: sandbox: a")
        );
        assert_eq!(
            line(&mut log, "bbd: sandbox: b", start + Duration::from_secs(1)),
            None
        );
        assert_eq!(
            line(&mut log, "bbd: sandbox: c", start + Duration::from_secs(59)),
            None
        );
        assert_eq!(
            line(&mut log, "bbd: sandbox: d", start + SANDBOX_INTERVAL).as_deref(),
            Some("bbd: sandbox: d (2 more since the last)")
        );
    }
}
