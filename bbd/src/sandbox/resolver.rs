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

//! The system's resolver kept from an app that is kept from the network.
//!
//! An app does not look names up itself: it asks netd, over a socket
//! (`/dev/socket/dnsproxyd`), and netd sends the query on a socket of its
//! own, which the packet filter's rule for the app's uid does not match.
//! A name of the app's choosing would still leave
//! the device, and with it whatever the app wrote into the name.
//!
//! So the socket is covered in the app's mount namespaces: `/dev/null` is
//! mounted over it, the app cannot connect, and its lookups fail without a
//! packet being sent. Every app process has a mount namespace of its own,
//! from which mounts do not spread to others.
//!
//! An app connects anew for each lookup, so this needs no process to be
//! ended: the socket is covered in the processes the app has when the
//! network is taken from it, and uncovered in them when it is given back.
//! A process that starts in between is given the cover at the gate
//! with the mounts of the sandbox: the gate is told
//! first, either way, and the processes the app has are gone through
//! after.
//!
//! An app whose connections are recorded, or that is kept from names, is
//! given the daemon's own socket in place of netd's. The daemon passes
//! its lookups on.
//!
//! A connection the app opened before and kept is not closed by this.
//!
//! netd makes the socket anew when it starts again, and a mount on the
//! one before goes with it. Android 15 was seen to end every app when
//! netd is ended, and the processes that start then are covered at the
//! gate.
//!
//! The mount shows in the app's own mounts as `/null` of the system's
//! `/dev`, which names nothing of the sandbox.

use std::{
    collections::HashSet,
    ffi::CString,
    fs::{self, File},
    io,
    os::{fd::AsRawFd as _, unix::fs::MetadataExt as _},
    path::Path,
};

use anyhow::Context as _;

use super::{Entry, dirs};
use crate::proc::Process;

/// Where netd listens for the lookups of apps.
pub const SOCKET: &str = "/dev/socket/dnsproxyd";

/// Where the daemon listens for lookups. Beside netd's, in the filesystem
/// every mount namespace has; the name says nothing of the sandbox, as an
/// app can read its own mounts.
pub(super) const RELAY_SOCKET: &str = "/dev/socket/dnsproxyd_relay";

/// What is mounted over the socket: there in every namespace, of the same
/// filesystem as the socket, and nothing can be connected to it.
pub const STAND_IN: &str = "/dev/null";

/// Most passes over the processes of one uid, and the pause between two:
/// a process may fork, or have been about to take the uid, while the
/// others were gone through.
const PASSES: u32 = 4;
const PASS_PAUSE: std::time::Duration = std::time::Duration::from_millis(20);

/// The mount point and the optional fields of a line of
/// `/proc/<pid>/mountinfo`: `<id> <parent> <device> <root> <mount point>
/// <options> <optional fields>... - <type> <source> <options>`.
fn mount_point(line: &str) -> Option<(&str, impl Iterator<Item = &str>)> {
    let mut fields = line.split(' ');
    let point = fields.nth(4)?;
    fields.next()?;
    Some((point, fields.take_while(|field| *field != "-")))
}

/// What an app finds at the resolver's socket.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Cover {
    /// netd's socket, as the system has it.
    #[default]
    None,
    /// Nothing to connect to: its lookups fail.
    Null,
    /// The daemon's socket, which passes its lookups on (`names`).
    Relay,
}

impl Cover {
    /// What is mounted over the socket.
    fn source(self) -> Option<&'static str> {
        match self {
            Cover::None => None,
            Cover::Null => Some(STAND_IN),
            Cover::Relay => Some(RELAY_SOCKET),
        }
    }
}

/// What is on the socket in a namespace with these mounts: `Some` of a
/// cover (none included), or `None` for a mount that is none of them, as
/// the socket of a daemon that has ended is.
pub fn found(mountinfo: &str) -> Option<Cover> {
    // The last mount on the point is the one in view.
    let Some(root) = mountinfo
        .lines()
        .filter(|line| mount_point(line).is_some_and(|(point, _)| point == SOCKET))
        .filter_map(|line| line.split(' ').nth(3))
        .next_back()
    else {
        return Some(Cover::None);
    };
    // The root of a mount of one file is the file's path in its
    // filesystem, which for both is the one mounted at `/dev`.
    [Cover::Null, Cover::Relay]
        .into_iter()
        .find(|cover| cover.source().and_then(|path| path.strip_prefix("/dev")) == Some(root))
}

/// Whether a mount made on the socket in a namespace with these mounts
/// would show in other namespaces too: whether the mount the socket is in
/// (the last listed of those deepest in its path) is shared. `None` if the
/// socket is in no mount listed.
pub fn spreads(mountinfo: &str) -> Option<bool> {
    let mut holder: Option<(usize, bool)> = None;
    for (point, mut optional) in mountinfo.lines().filter_map(mount_point) {
        let within = point == "/"
            || SOCKET
                .strip_prefix(point)
                .is_some_and(|rest| rest.starts_with('/'));
        if within && holder.is_none_or(|(depth, _)| point.len() >= depth) {
            holder = Some((
                point.len(),
                optional.any(|field| field.starts_with("shared:")),
            ));
        }
    }
    holder.map(|(_, shared)| shared)
}

/// Have every process the app of `entry` has find `cover` at the socket.
///
/// On a thread of its own, which ends with the change: a thread can move
/// to another mount namespace only if it shares its root and working
/// directory with no other, and one that cannot come back is left behind
/// rather than returned to the daemon.
pub fn set(entry: &Entry, cover: Cover) -> anyhow::Result<()> {
    let entry = entry.clone();
    std::thread::Builder::new()
        .name("resolver".into())
        .spawn(move || in_namespaces(&entry, cover))
        .context("starting the thread")?
        .join()
        .map_err(|_| anyhow::anyhow!("the thread ended early"))?
}

/// The device and inode of a namespace: what tells two apart.
fn identity(namespace: &File) -> io::Result<(u64, u64)> {
    let meta = namespace.metadata()?;
    Ok((meta.dev(), meta.ino()))
}

fn in_namespaces(entry: &Entry, cover: Cover) -> anyhow::Result<()> {
    if unsafe { libc::unshare(libc::CLONE_FS) } != 0 {
        return Err(io::Error::last_os_error()).context("unshare(CLONE_FS)");
    }
    let home =
        File::open("/proc/thread-self/ns/mnt").context("opening the daemon's mount namespace")?;
    // Never to be mounted in: the daemon's own, and the one every process
    // that is not an app starts in.
    let kept = [
        identity(&home)?,
        identity(&File::open("/proc/1/ns/mnt").context("opening the first namespace")?)?,
    ];
    // The uid may have gone to another app since.
    if !dirs::matches_install(
        Path::new("/data/user_de"),
        &entry.package,
        entry.uid,
        &entry.install_id,
    )? {
        return Ok(());
    }
    for pass in 0..PASSES {
        if pass > 0 {
            std::thread::sleep(PASS_PAUSE);
        }
        // Twice at least: what was just about to take the uid is not seen
        // the first time.
        if pass_once(entry.uid, cover, &home, &kept)? == 0 && pass > 0 {
            break;
        }
    }
    Ok(())
}

/// One pass over the processes of `uid`; how many namespaces were changed.
/// A process that cannot be looked at has ended.
fn pass_once(uid: u32, cover: Cover, home: &File, kept: &[(u64, u64)]) -> anyhow::Result<u32> {
    let mut seen = HashSet::new();
    let mut changed = 0;
    for process in fs::read_dir("/proc")
        .context("listing the processes")?
        .flatten()
    {
        let Some(pid) = process
            .file_name()
            .to_str()
            .and_then(|name| name.parse::<i32>().ok())
        else {
            continue;
        };
        // The directory of a process belongs to the user it runs as.
        let runs_as = |uid: u32| process.metadata().is_ok_and(|meta| meta.uid() == uid);
        if !runs_as(uid) {
            continue;
        }
        // Held open, then looked at again: what is read of the pid from
        // here on is of this process, or it has ended.
        let Ok(held) = Process::open(pid) else {
            continue;
        };
        if !runs_as(uid) {
            continue;
        }
        let Ok(theirs) = File::open(format!("/proc/{pid}/ns/mnt")) else {
            continue;
        };
        let Ok(mountinfo) = fs::read_to_string(format!("/proc/{pid}/mountinfo")) else {
            continue;
        };
        if held.signal(0).is_err() {
            continue;
        }
        let namespace = identity(&theirs).context("looking at a namespace")?;
        // Not an app's process (a shell run as the app), or one that
        // shares the namespace of another already gone through.
        if kept.contains(&namespace) || !seen.insert(namespace) {
            continue;
        }
        let has = found(&mountinfo);
        if has == Some(cover) {
            continue;
        }
        anyhow::ensure!(
            spreads(&mountinfo) == Some(false),
            "a process of the app shares its mounts with others"
        );
        enter(&theirs).context("entering a namespace")?;
        let done = (|| -> io::Result<()> {
            if has != Some(Cover::None) {
                uncover_here()?;
            }
            cover_here(cover)
        })();
        // A thread that cannot come back ends here, with the change.
        enter(home).context("returning from a namespace")?;
        done.context("changing what is at the resolver's socket")?;
        changed += 1;
    }
    Ok(changed)
}

fn enter(namespace: &File) -> io::Result<()> {
    if unsafe { libc::setns(namespace.as_raw_fd(), libc::CLONE_NEWNS) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Put `cover` on the socket in the namespace the calling thread is in,
/// where nothing is on it.
pub fn cover_here(cover: Cover) -> io::Result<()> {
    let Some(source) = cover.source() else {
        return Ok(());
    };
    let (source, socket) = (CString::new(source)?, CString::new(SOCKET)?);
    let rc = unsafe {
        libc::mount(
            source.as_ptr(),
            socket.as_ptr(),
            std::ptr::null(),
            libc::MS_BIND,
            std::ptr::null(),
        )
    };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Uncover it. Detached rather than refused while the app holds the
/// stand-in open: the app could keep its own lookups from coming back.
fn uncover_here() -> io::Result<()> {
    let socket = CString::new(SOCKET)?;
    if unsafe { libc::umount2(socket.as_ptr(), libc::MNT_DETACH) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The mounts of an app process, as Android 15 lists them, cut short.
    const APP: &str = "\
14610 14609 254:5 / / ro,relatime master:1 - erofs /dev/block/dm-5 ro,seclabel
16380 14610 0:18 / /dev rw,nosuid,relatime master:2 - tmpfs tmpfs rw,seclabel,mode=755
16381 16380 0:19 / /dev/pts rw,relatime master:3 - devpts devpts rw,seclabel
16400 14610 0:21 / /proc rw,relatime master:9 - proc proc rw,gid=3009
";

    #[test]
    fn what_is_on_the_socket_is_told_by_what_is_mounted_there() {
        assert_eq!(found(APP), Some(Cover::None));
        assert_eq!(found(""), Some(Cover::None));
        let with = |root: &str| {
            format!(
                "{APP}19010 16380 0:18 {root} /dev/socket/dnsproxyd rw,nosuid,relatime master:2 - tmpfs tmpfs rw,seclabel\n"
            )
        };
        assert_eq!(found(&with("/null")), Some(Cover::Null));
        assert_eq!(found(&with("/socket/dnsproxyd_relay")), Some(Cover::Relay));
        // The socket of a daemon that has ended, or anything else.
        assert_eq!(found(&with("/socket/dnsproxyd_relay//deleted")), None);
        assert_eq!(found(&with("/zero")), None);
        // Another socket, or a directory that only begins the same.
        let other = format!(
            "{APP}19010 16380 0:18 /null /dev/socket/dnsproxyd2 rw master:2 - tmpfs tmpfs rw\n"
        );
        assert_eq!(found(&other), Some(Cover::None));
        // The last mounted there is the one in view.
        let twice = format!("{}{}", with("/null"), with("/socket/dnsproxyd_relay"))
            .replacen(APP, "", 1)
            .replacen(APP, "", 1);
        assert_eq!(found(&twice), Some(Cover::Relay));
    }

    #[test]
    fn a_mount_spreads_from_a_shared_mount_only() {
        // An app's namespace receives mounts and passes none on.
        assert_eq!(spreads(APP), Some(false));
        // The first namespace, and a shell's: `/dev` is shared.
        let first = APP.replace("master:2", "shared:2");
        assert_eq!(spreads(&first), Some(true));
        // Shared and receiving at once.
        let both = APP.replace("master:2", "shared:7 master:2");
        assert_eq!(spreads(&both), Some(true));
        // It is the mount the socket is in that counts, not one above it
        // or beside it.
        let root_only = APP.replace("master:1", "shared:1");
        assert_eq!(spreads(&root_only), Some(false));
        let beside = APP.replace("master:3", "shared:3");
        assert_eq!(spreads(&beside), Some(false));
        // A mount over `/dev/socket` itself holds the socket.
        let deeper = format!("{APP}16390 16380 0:30 / /dev/socket rw shared:12 - tmpfs tmpfs rw\n");
        assert_eq!(spreads(&deeper), Some(true));
        // The last mounted on a point is the one in view.
        let over = format!("{first}16999 16380 0:40 / /dev rw master:2 - tmpfs tmpfs rw\n");
        assert_eq!(spreads(&over), Some(false));
        // Without `/dev`, the root holds it.
        assert_eq!(
            spreads("1 0 254:5 / / ro shared:1 - erofs /dev/block/dm-5 ro\n"),
            Some(true)
        );
        assert_eq!(spreads(""), None);
        assert_eq!(spreads("not a line of mounts\n"), None);
    }
}
