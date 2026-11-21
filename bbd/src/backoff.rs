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

//! Capped exponential delays for work that has to be tried again.

use std::time::Duration;

pub struct Backoff {
    pub first: Duration,
    pub max: Duration,
    pub healthy: Option<Duration>,
}

impl Backoff {
    /// A long enough run starts a new streak when it ends.
    pub fn failures_after_run(&self, failures: u32, elapsed: Duration) -> u32 {
        if self.healthy.is_some_and(|healthy| elapsed >= healthy) {
            1
        } else {
            failures.saturating_add(1)
        }
    }

    pub fn delay(&self, failures: u32) -> Duration {
        let doublings = failures.saturating_sub(1).min(16);
        self.first.saturating_mul(1 << doublings).min(self.max)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_healthy_run_restarts_the_failure_count() {
        let healthy = Duration::from_secs(600);
        let backoff = Backoff {
            first: Duration::from_secs(10),
            max: Duration::from_secs(1800),
            healthy: Some(healthy),
        };
        assert_eq!(backoff.failures_after_run(2, Duration::from_secs(599)), 3);
        assert_eq!(backoff.failures_after_run(2, healthy), 1);
    }
}
