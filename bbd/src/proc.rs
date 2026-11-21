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

//! Read process fields the kernel exposes in `/proc`.

use std::{
    fs::{self, File},
    io::{self, Read as _},
};

pub struct Stat<'a> {
    fields: &'a str,
}

impl Stat<'_> {
    /// State and parent pid are needed while a process waits at the gate.
    #[inline]
    pub fn state_and_parent(&self) -> Option<(char, i32)> {
        let mut fields = self.fields.strip_prefix(' ')?.split(' ');
        let state = fields.next()?.chars().next()?;
        Some((state, fields.next()?.parse().ok()?))
    }

    /// Field 22, in clock ticks after boot. The suffix starts at field 3.
    fn start_time(&self) -> Option<u64> {
        self.fields
            .split_ascii_whitespace()
            .nth(22 - 3)?
            .parse()
            .ok()
    }
}

#[inline]
pub fn parse_stat(stat: &str) -> Option<Stat<'_>> {
    // The command name can itself contain spaces and closing parentheses.
    let (_, fields) = stat.rsplit_once(')')?;
    Some(Stat { fields })
}

/// When the process `pid` started, or `None` if its start time is unavailable.
pub fn process_start(pid: u32) -> Option<u64> {
    let stat = fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    parse_stat(&stat)?.start_time()
}

/// Whether the process `pid` that started at `start` still runs.
pub fn process_alive(pid: u32, start: u64) -> bool {
    process_start(pid) == Some(start)
}

/// The real uid that owns `pid`, or `None` if unavailable. The directory
/// owner is root for non-dumpable processes, which most Android apps are.
pub fn uid_of_pid(pid: u32) -> Option<u32> {
    let status = fs::read_to_string(format!("/proc/{pid}/status")).ok()?;
    real_uid(status.as_bytes())
}

#[inline]
pub fn real_uid(status: &[u8]) -> Option<u32> {
    first_status_number(status, b"Uid:")
}

pub fn parent_pid(status: &[u8]) -> Option<u32> {
    first_status_number(status, b"PPid:")
}

#[inline]
fn first_status_number(status: &[u8], field: &[u8]) -> Option<u32> {
    status_value(status, field)?
        .split_ascii_whitespace()
        .next()?
        .parse()
        .ok()
}

fn status_value<'a>(status: &'a [u8], field: &[u8]) -> Option<&'a str> {
    // The name can contain non-UTF-8 bytes even when the numeric fields are valid.
    let value = status
        .split(|&byte| byte == b'\n')
        .find_map(|line| line.strip_prefix(field))?;
    std::str::from_utf8(value).ok()
}

pub fn is_nonleader(pid: i32) -> io::Result<bool> {
    let mut status = Vec::new();
    File::open(format!("/proc/{pid}/status"))?
        .take(4096)
        .read_to_end(&mut status)?;
    // Read the IDs even when the thread's name includes non-UTF-8 bytes.
    let field = |prefix: &[u8]| {
        status_value(&status, prefix)?
            .trim_ascii()
            .parse::<i32>()
            .ok()
    };
    Ok(field(b"Pid:") == Some(pid) && field(b"Tgid:").is_some_and(|tgid| tgid > 0 && tgid != pid))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_state_and_parent_are_read_past_any_process_name() {
        let state_and_parent = |stat| parse_stat(stat)?.state_and_parent();
        assert_eq!(
            state_and_parent("4321 (main) T 612 612 0 0 -1 4194624"),
            Some(('T', 612))
        );
        // A name is whatever the process set.
        assert_eq!(
            state_and_parent("4321 (a) R 1 (b c) S 99 0) R 612 612 0"),
            Some(('R', 612))
        );
        assert_eq!(
            state_and_parent("4321 (usap64) S 700 700"),
            Some(('S', 700))
        );
        assert_eq!(state_and_parent(""), None);
        assert_eq!(state_and_parent("4321 (main"), None);
        assert_eq!(state_and_parent("4321 (main) T"), None);
        assert_eq!(state_and_parent("4321 (main) T x"), None);
    }

    #[test]
    fn the_start_time_is_the_22nd_field_after_the_name() {
        let start = |stat| parse_stat(stat)?.start_time();
        let stat =
            "4242 (my (odd) app) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654 20 21";
        assert_eq!(start(stat), Some(987_654));
        assert_eq!(start("4242 (short) S 1"), None);
        assert_eq!(
            start(
                "4242 (my) S 2 (odd name)) T 9 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 123456"
            ),
            Some(123_456)
        );
    }

    #[test]
    fn status_uid_is_the_real_uid() {
        let status =
            b"Name:\tcom.x\nPid:\t500\nPPid:\t7870\nUid:\t10100\t10101\t10102\t10103\nGid:\t10100\n";
        assert_eq!(real_uid(status), Some(10100));
        assert_eq!(real_uid(b"Name:\tx\n"), None);
        assert_eq!(parent_pid(status), Some(7870));
    }

    #[test]
    fn numeric_status_fields_are_read_past_a_non_utf8_name() {
        let status = b"Name:\t\xff\nPPid:\t7870\nUid:\t10100\t10101\n";
        assert_eq!(real_uid(status), Some(10100));
        assert_eq!(parent_pid(status), Some(7870));
    }
}
