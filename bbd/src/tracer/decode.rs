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

//! Decode individual kernel records and resolve syscall outcomes.

use std::{fs, time::Instant};

use bb_common::{
    BinderEvent, EXEC_ARGS, EventHeader, ExecEvent, FUSE_SUPER_MAGIC, FileEvent, NetlinkEvent,
    OP_PROBE, OP_WATCH, PROC_SUPER_MAGIC, PROCESS_READ, PROCESS_WRITE, PathEvent, ProcessEvent,
    ResultEvent, SELINUX_MAGIC, SYSFS_MAGIC, read_event,
};

use super::{Held, Opened, Tracer};
use crate::{
    binder, commands, net,
    packages::user_of,
    paths,
    proc::{process_start, uid_of_pid},
    record::{
        Category, MODE_CALL, MODE_DIR, MODE_EXEC, MODE_PROBE, MODE_READ, MODE_WATCH, MODE_WRITE,
        Mode,
    },
    rules, sensors,
};

impl Tracer {
    #[inline]
    pub(super) fn on_file(&mut self, bytes: &[u8], hdr: EventHeader, ts: i64) {
        let Some((ev, components)) = FileEvent::read(bytes) else {
            return;
        };
        self.counters.seen += 1;
        // A path that didn't fit is missing its outermost
        // components; joined to the mount point it would name a
        // different, wrong file.
        if ev.truncated != 0 {
            self.counters.unresolved += 1;
            self.explain("file");
            return;
        }
        let Some(path) = self.file_path(&ev, components) else {
            self.counters.unresolved += 1;
            self.explain("file");
            return;
        };
        let opened = Opened {
            uid: hdr.uid,
            pid: hdr.pid,
            path,
            mode: access_mode(ev.flags, ev.mode),
            ts,
        };
        if ev.in_syscall != 0 {
            self.hold_result(hdr.tid, Held::File(opened));
        } else {
            self.record(&opened, true);
        }
    }

    #[inline]
    pub(super) fn on_result(&mut self, bytes: &[u8], tid: u32) {
        let Some(ev) = read_event::<ResultEvent>(bytes) else {
            return;
        };
        for held in self.awaiting_result.remove(&tid).unwrap_or_default() {
            self.release(held, Some(ev.ret));
        }
    }

    #[inline]
    pub(super) fn on_path(&mut self, bytes: &[u8], hdr: EventHeader, ts: i64) {
        let Some(ev) = read_event::<PathEvent>(bytes) else {
            return;
        };
        self.counters.seen += 1;
        if ev.path_len == 0 {
            return;
        }
        let len = (ev.path_len as usize - 1).min(ev.path.len());
        let raw = String::from_utf8_lossy(&ev.path[..len]);
        let absolute = if raw.starts_with('/') {
            raw.into_owned()
        } else {
            match resolve_relative(hdr.pid, ev.dfd, &raw) {
                Some(p) => p,
                None => {
                    self.counters.unresolved += 1;
                    self.explain("path-relative");
                    return;
                }
            }
        };
        let mode = match ev.op {
            OP_PROBE => MODE_PROBE,
            OP_WATCH => MODE_WATCH,
            _ => access_mode(ev.flags, 0),
        };
        let opened = Opened {
            uid: hdr.uid,
            pid: hdr.pid,
            path: paths::lexical_normalize(&absolute),
            mode,
            ts,
        };
        self.record(&opened, ev.ret >= 0);
    }

    #[inline]
    pub(super) fn on_exec(&mut self, bytes: &[u8], hdr: EventHeader, ts: i64) {
        let Some(ev) = read_event::<ExecEvent>(bytes) else {
            return;
        };
        self.counters.seen += 1;
        if ev.path_len == 0 {
            return;
        }
        let path = lossy(&ev.path[..(ev.path_len as usize - 1).min(ev.path.len())]);
        let args: Vec<String> = ev.args[..(ev.argc as usize).min(EXEC_ARGS)]
            .iter()
            .map(|slot| lossy(slot.split(|&b| b == 0).next().unwrap_or_default()))
            .collect();
        let Some(subject) = commands::subject(&path, &args) else {
            self.explain("exec");
            return;
        };
        let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
        self.hold_result(
            hdr.tid,
            Held::Command {
                uid,
                pid: hdr.pid,
                subject,
                origin,
                ts,
            },
        );
    }

    #[inline]
    pub(super) fn on_process(&mut self, bytes: &[u8], hdr: EventHeader, ts: i64) {
        let Some(ev) = read_event::<ProcessEvent>(bytes) else {
            return;
        };
        self.counters.seen += 1;
        let Some(subject) = rules::process_subject(ev.how) else {
            return;
        };
        // An app's own processes (a child it debugs, a helper) are
        // the app looking at itself. A process that is already gone
        // can't be attributed and is skipped.
        if uid_of_pid(ev.target).is_none_or(|owner| owner == hdr.uid) {
            self.explain("process");
            return;
        }
        let mode = match ev.how {
            PROCESS_READ => MODE_READ,
            PROCESS_WRITE => MODE_WRITE,
            _ => MODE_WATCH,
        };
        let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
        self.hold_result(
            hdr.tid,
            Held::Record {
                uid,
                cat: Category::Process,
                subject: subject.to_string(),
                mode: mode | origin,
                ts,
            },
        );
    }

    #[inline]
    pub(super) fn on_netlink(&mut self, bytes: &[u8], hdr: EventHeader, ts: i64) {
        let Some(ev) = read_event::<NetlinkEvent>(bytes) else {
            return;
        };
        self.counters.seen += 1;
        let Some(subject) = net::netlink_subject(ev.protocol, ev.msg_type) else {
            return;
        };
        let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
        self.hold_result(
            hdr.tid,
            Held::Record {
                uid,
                cat: Category::Network,
                subject: subject.to_string(),
                mode: MODE_READ | origin,
                ts,
            },
        );
    }

    #[inline]
    pub(super) fn on_binder(&mut self, bytes: &[u8], hdr: EventHeader, ts: i64) {
        let Some(ev) = read_event::<BinderEvent>(bytes) else {
            return;
        };
        self.counters.seen += 1;
        let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
        let args = &ev.args[..(ev.args_len as usize).min(ev.args.len())];
        // Sensors turned on and off are kept as sessions, and only
        // turning one on as a call; see `sensors`.
        let request = self
            .catalog
            .as_ref()
            .and_then(|catalog| catalog.sensor_request(ev.interface, ev.code, args));
        if let Some(request) = request {
            self.on_sensor_call(sensors::Call {
                uid,
                process_uid: hdr.uid,
                mode: MODE_CALL | origin,
                pid: hdr.pid,
                connection: ev.target,
                request,
                ts,
            });
        } else {
            self.on_service_call(&ev, args, uid, origin, ts);
        }
    }

    #[inline]
    fn on_sensor_call(&mut self, call: sensors::Call) {
        if self.excluded(call.uid) {
            return;
        }
        if self.verbose {
            println!(
                "uid={} pid={} connection={} sensor {:?}",
                call.uid, call.pid, call.connection, call.request
            );
        }
        let known = self.sensors.on_call(call, process_start, Instant::now());
        if let Some((uid, mode, subject, ts)) = known {
            self.store(uid, Category::Service, subject, mode, true, ts, 0);
        }
    }

    #[inline]
    fn on_service_call(&mut self, ev: &BinderEvent, args: &[u8], uid: u32, origin: Mode, ts: i64) {
        let subject = self.catalog.as_ref().and_then(|catalog| {
            catalog.subject(
                ev.interface,
                ev.code,
                args,
                &[ev.hdr.uid, uid],
                self.packages.for_uid(uid),
            )
        });
        let Some(mut subject) = subject else {
            self.explain("binder");
            return;
        };
        // Whether the app asked about is there is known only now: by
        // the time the records are read it may have been installed
        // or removed.
        if let Some(package) = binder::asked_package(&subject)
            && !self.packages.is_installed(package)
            && let Some(marked) = binder::mark_absent(&subject)
        {
            subject = marked;
        }
        // Only the request is seen, not what came back.
        self.store(
            uid,
            Category::Service,
            subject,
            MODE_CALL | origin,
            true,
            ts,
            0,
        );
    }

    /// Record something whose syscall has returned (`ret`), or whose result
    /// is no longer expected (`None`), which is taken for success.
    fn release(&mut self, held: Held, ret: Option<i32>) {
        let ok = ret.is_none_or(|ret| ret >= 0);
        match held {
            Held::File(opened) => self.record(&opened, ok),
            Held::Command {
                uid,
                pid,
                subject,
                origin,
                ts,
            } => {
                if let Some(ok) = self.searches.outcome(pid, uid, origin, &subject, ts, ret) {
                    self.store(
                        uid,
                        Category::Command,
                        subject,
                        MODE_EXEC | origin,
                        ok,
                        ts,
                        0,
                    );
                }
            }
            Held::Record {
                uid,
                cat,
                subject,
                mode,
                ts,
            } => self.store(uid, cat, subject, mode, ok, ts, 0),
        }
    }

    /// Absolute path of the file a `FileEvent` describes.
    fn file_path(&mut self, ev: &FileEvent, components: &[u8]) -> Option<String> {
        let relative = join_components(components);
        let base = match ev.magic {
            PROC_SUPER_MAGIC => "/proc",
            SYSFS_MAGIC => "/sys",
            SELINUX_MAGIC => "/sys/fs/selinux",
            FUSE_SUPER_MAGIC => match self.mounts.mount_point(ev.dev) {
                Some(mount_point) => mount_point,
                // Mount not visible from the daemon's namespace. Emulated
                // storage is rooted one level above the per-user directories.
                None if relative.split('/').next().is_some_and(is_number) => "/storage/emulated",
                None => return None,
            },
            // A block device node; these live in /dev.
            _ => "/dev",
        };
        Some(format!("{base}/{relative}"))
    }

    /// Decide whether an access seen by the kernel is worth keeping, and
    /// store it.
    fn record(&mut self, opened: &Opened, ok: bool) {
        let source = if opened.mode & (MODE_PROBE | MODE_WATCH) != 0 {
            "path"
        } else {
            "open"
        };
        let (uid, origin) = self.owner_uid(opened.uid, opened.pid);
        let pkgs = self.packages.for_uid(uid);
        let (cat, path) = match paths::canonicalize(&opened.path, user_of(uid)) {
            Some(path) if paths::is_own_dir(&path, pkgs) => {
                self.explain(source);
                return;
            }
            Some(path) => (Category::Storage, path),
            // A process reading its own /proc entries is the one that
            // opened them, so the comparison is with its own uid, which for
            // an isolated process is not its app's.
            None => match rules::classify(&opened.path, opened.uid, uid_of_pid) {
                Some(hit) => hit,
                None => {
                    self.explain(source);
                    return;
                }
            },
        };
        self.store(uid, cat, path, opened.mode | origin, ok, opened.ts, 0);
    }

    /// Record what was held for a result that never arrived (seen before
    /// `cutoff`), as successful. For a program started from a thread other
    /// than the main one this is the normal case: the exec moves the thread
    /// to the main thread's id, so its return is not matched to the call.
    pub(crate) fn expire(&mut self, cutoff: i64) {
        let stale: Vec<u32> = self
            .awaiting_result
            .iter()
            .filter(|(_, held)| held.iter().all(|h| h.ts() <= cutoff))
            .map(|(tid, _)| *tid)
            .collect();
        for tid in stale {
            for held in self.awaiting_result.remove(&tid).unwrap_or_default() {
                self.release(held, None);
            }
        }
        // Commands looked for and, the search being over, not found.
        for (uid, origin, subject, ts) in self.searches.finished(cutoff) {
            self.store(
                uid,
                Category::Command,
                subject,
                MODE_EXEC | origin,
                false,
                ts,
                0,
            );
        }
    }
}

fn lossy(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}

fn is_number(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
}

/// Turn the kernel's leaf-first, NUL-separated component list into a normal
/// relative path: `a.jpg\0Camera\0DCIM\0` -> `DCIM/Camera/a.jpg`.
fn join_components(data: &[u8]) -> String {
    let mut parts: Vec<&[u8]> = data.split(|&b| b == 0).filter(|p| !p.is_empty()).collect();
    parts.reverse();
    String::from_utf8_lossy(&parts.join(&b'/')).into_owned()
}

/// Resolve a relative path against the directory it was relative to: the
/// process's cwd, or the directory `dfd` refers to. Best effort — the process
/// may have exited or closed the fd by the time we look.
fn resolve_relative(pid: u32, dfd: i32, relative: &str) -> Option<String> {
    let link = if dfd == libc::AT_FDCWD {
        format!("/proc/{pid}/cwd")
    } else {
        format!("/proc/{pid}/fd/{dfd}")
    };
    let base = fs::read_link(link).ok()?;
    let base = base.to_str()?;
    base.starts_with('/').then(|| format!("{base}/{relative}"))
}

/// Access mode bits from open flags and, where known, the inode's mode
/// (0 if not): a directory can be opened without O_DIRECTORY.
fn access_mode(flags: u32, inode_mode: u32) -> Mode {
    let flags = flags as i32;
    let mut mode = match flags & libc::O_ACCMODE {
        libc::O_RDONLY => MODE_READ,
        libc::O_WRONLY => MODE_WRITE,
        _ => MODE_READ | MODE_WRITE,
    };
    let is_dir = inode_mode & libc::S_IFMT == libc::S_IFDIR;
    if flags & libc::O_DIRECTORY != 0 || is_dir {
        mode |= MODE_DIR;
    }
    mode
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn open_flags_and_file_type_set_the_mode() {
        assert_eq!(access_mode(libc::O_RDONLY as u32, 0), MODE_READ);
        assert_eq!(
            access_mode((libc::O_WRONLY | libc::O_CREAT) as u32, 0),
            MODE_WRITE
        );
        assert_eq!(access_mode(libc::O_RDWR as u32, 0), MODE_READ | MODE_WRITE);
        assert_eq!(
            access_mode(
                (libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC) as u32,
                0
            ),
            MODE_READ | MODE_DIR
        );
        // A directory opened without O_DIRECTORY is still a directory.
        assert_eq!(
            access_mode(libc::O_RDONLY as u32, libc::S_IFDIR | 0o755),
            MODE_READ | MODE_DIR
        );
        assert_eq!(access_mode(libc::O_RDONLY as u32, 0o100644), MODE_READ);
    }

    #[test]
    fn components_are_reversed_and_joined() {
        assert_eq!(
            join_components(b"a.jpg\0Camera\0DCIM\x000\0"),
            "0/DCIM/Camera/a.jpg"
        );
        assert_eq!(join_components(b"cpuinfo\0"), "cpuinfo");
        assert_eq!(join_components(b""), "");
        // Non-UTF-8 names don't abort; they are replaced lossily.
        assert_eq!(join_components(b"\xff\0d\0"), "d/\u{fffd}");
    }
}
