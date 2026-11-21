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
    os::fd::{AsRawFd, FromRawFd, OwnedFd},
};

/// A process held by a descriptor, so that a signal meant for it cannot
/// reach another process that was given its pid in the meantime.
pub struct Process(OwnedFd);

impl Process {
    /// Hold `pid` as a process leader. A reused ID belonging to a non-leader
    /// is the same as a missing leader for callers holding process IDs.
    pub fn open(pid: i32) -> io::Result<Self> {
        Self::open_pidfd(pid).map_err(|error| Self::normalize_open_error(pid, error))
    }

    fn normalize_open_error(pid: i32, error: io::Error) -> io::Error {
        // Kernels use either errno when a task has no thread-group leader at this ID.
        if pid > 0
            && matches!(error.raw_os_error(), Some(libc::EINVAL | libc::ENOENT))
            && Self::no_group_leader(pid)
        {
            io::Error::from_raw_os_error(libc::ESRCH)
        } else {
            error
        }
    }

    fn open_pidfd(pid: i32) -> io::Result<Self> {
        let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, 0) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: just opened, and owned by nothing else.
        Ok(Self(unsafe { OwnedFd::from_raw_fd(fd as i32) }))
    }

    fn no_group_leader(pid: i32) -> bool {
        match is_nonleader(pid) {
            Ok(nonleader) => nonleader,
            Err(e) if matches!(e.raw_os_error(), Some(libc::ENOENT | libc::ESRCH)) => {
                // The thread may have exited before its status was read.
                // Confirm that in the kernel before treating it as gone.
                matches!(Self::open_pidfd(pid), Err(e) if e.raw_os_error() == Some(libc::ESRCH))
            }
            Err(_) => false,
        }
    }

    /// A pidfd becomes readable when this process exits, including before it is reaped.
    pub fn exited(&self, timeout_ms: i32) -> io::Result<bool> {
        let mut pfd = libc::pollfd {
            fd: self.0.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };
        if unsafe { libc::poll(&mut pfd, 1, timeout_ms) } < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(pfd.revents & (libc::POLLIN | libc::POLLHUP) != 0)
    }

    /// Send `signal`. A process that has ended in the meantime gets none.
    pub fn signal(&self, signal: i32) -> io::Result<()> {
        let result = unsafe {
            libc::syscall(
                libc::SYS_pidfd_send_signal,
                self.0.as_raw_fd(),
                signal,
                std::ptr::null::<libc::siginfo_t>(),
                0,
            )
        };
        if result < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }
}

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

fn is_nonleader(pid: i32) -> io::Result<bool> {
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

    #[test]
    fn a_process_can_be_held_and_signalled_by_descriptor() {
        let mut child = std::process::Command::new("sleep")
            .arg("30")
            .spawn()
            .unwrap();
        let held = Process::open(child.id() as i32).unwrap();
        assert!(!held.exited(0).unwrap());
        held.signal(libc::SIGKILL).unwrap();
        assert!(held.exited(5000).unwrap());
        assert!(!child.wait().unwrap().success());
        assert!(held.exited(0).unwrap());
        // Gone: the signal goes nowhere, and a pid that is not there cannot
        // be opened.
        assert!(held.signal(libc::SIGKILL).is_err());
        assert!(Process::open(i32::MAX).is_err());
        assert_eq!(
            Process::open(0).err().and_then(|e| e.raw_os_error()),
            Some(libc::EINVAL)
        );
    }

    #[test]
    fn both_kernel_errors_for_a_nonleader_report_a_missing_process() {
        std::thread::scope(|scope| {
            let (send_tid, receive_tid) = std::sync::mpsc::channel();
            let (keep_alive, finish) = std::sync::mpsc::channel::<()>();
            scope.spawn(move || {
                send_tid.send(unsafe { libc::gettid() }).unwrap();
                let _ = finish.recv();
            });
            let tid = receive_tid.recv().unwrap();
            assert_ne!(tid as u32, std::process::id());
            for (errno, expected) in [
                (libc::EINVAL, libc::ESRCH),
                (libc::ENOENT, libc::ESRCH),
                (libc::EPERM, libc::EPERM),
            ] {
                let error = Process::normalize_open_error(tid, io::Error::from_raw_os_error(errno));
                assert_eq!(error.raw_os_error(), Some(expected), "input errno {errno}");
            }
            drop(keep_alive);
        });
    }

    #[test]
    fn a_leader_and_an_invalid_id_keep_the_original_open_error() {
        for pid in [std::process::id() as i32, 0, -1] {
            for errno in [libc::EINVAL, libc::ENOENT] {
                let error = Process::normalize_open_error(pid, io::Error::from_raw_os_error(errno));
                assert_eq!(error.raw_os_error(), Some(errno));
            }
        }
    }

    #[test]
    fn a_vanished_thread_is_confirmed_by_the_kernel() {
        for errno in [libc::EINVAL, libc::ENOENT] {
            let error =
                Process::normalize_open_error(i32::MAX, io::Error::from_raw_os_error(errno));
            assert_eq!(error.raw_os_error(), Some(libc::ESRCH));
        }
    }
}
