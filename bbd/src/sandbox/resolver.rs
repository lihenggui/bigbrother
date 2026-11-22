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
//! An app kept from names or whose connections are recorded can use the
//! daemon's socket. When recording is the only reason for the cover,
//! an unavailable relay leaves netd's socket in place.
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
    collections::{HashMap, HashSet},
    ffi::CString,
    fs::{self, File},
    io,
    os::{fd::AsRawFd as _, unix::fs::MetadataExt as _},
    path::Path,
};

use anyhow::Context as _;

use super::{Entry, dirs, items::Settings, items_of, names::Serving};
use crate::{proc::Process, store::Reader};

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

pub(super) type SocketId = (u64, u64);

pub(super) fn socket_id(path: &str) -> io::Result<SocketId> {
    let metadata = fs::metadata(path)?;
    Ok((metadata.dev(), metadata.ino()))
}

/// The old relay's file may still be linked if its replacement could not
/// start. Both forms of the mount belong to us.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Mounted {
    Known(Cover),
    StaleRelay,
    Other,
}

fn found(mountinfo: &str) -> Mounted {
    // The last mount on the point is the one in view.
    let Some(root) = mountinfo
        .lines()
        .filter(|line| mount_point(line).is_some_and(|(point, _)| point == SOCKET))
        .filter_map(|line| line.split(' ').nth(3))
        .next_back()
    else {
        return Mounted::Known(Cover::None);
    };
    let dev_path = |path: &'static str| path.strip_prefix("/dev");
    if dev_path(STAND_IN) == Some(root) {
        Mounted::Known(Cover::Null)
    } else if dev_path(RELAY_SOCKET) == Some(root) {
        Mounted::Known(Cover::Relay)
    } else if dev_path(RELAY_SOCKET) == root.strip_suffix("//deleted") {
        Mounted::StaleRelay
    } else {
        Mounted::Other
    }
}

enum Change {
    Set(Cover),
    Record(Serving),
    Names(Serving),
    RemoveRelay,
    RemoveCovers,
}

impl Change {
    /// If the namespace needs a change, whether to detach the old mount
    /// first and what to put in its place.
    fn plan(
        &self,
        mounted: Mounted,
        serving: Option<SocketId>,
        covered: Option<SocketId>,
    ) -> anyhow::Result<Option<(bool, Cover)>> {
        let cover = match *self {
            Self::Set(cover) if mounted == Mounted::Known(cover) => return Ok(None),
            Self::Set(cover) => cover,
            Self::Record(_) | Self::Names(_) if serving.is_some() && serving == covered => {
                return Ok(None);
            }
            Self::Record(_) | Self::Names(_) if serving.is_some() => Cover::Relay,
            Self::Names(_) if mounted == Mounted::Known(Cover::Null) => return Ok(None),
            Self::Names(_) => Cover::Null,
            Self::Record(_) if mounted == Mounted::Known(Cover::None) => return Ok(None),
            Self::Record(_) => Cover::None,
            Self::RemoveCovers => {
                return Ok(matches!(
                    mounted,
                    Mounted::Known(Cover::Null | Cover::Relay) | Mounted::StaleRelay
                )
                .then_some((true, Cover::None)));
            }
            Self::RemoveRelay => {
                return Ok(
                    matches!(mounted, Mounted::Known(Cover::Relay) | Mounted::StaleRelay)
                        .then_some((true, Cover::None)),
                );
            }
        };
        anyhow::ensure!(
            mounted != Mounted::Other,
            "a different mount covers the resolver's socket"
        );
        Ok(Some((mounted != Mounted::Known(Cover::None), cover)))
    }
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
    change(vec![(entry.clone(), Change::Set(cover))]).map(|_| ())
}

/// A failed optional bind leaves Android's socket in place. Failure to
/// remove the previous cover is returned to the caller separately.
pub(super) fn record(entry: &Entry, relay: &Serving) -> anyhow::Result<bool> {
    change(vec![(entry.clone(), Change::Record(relay.clone()))])
}

pub(super) fn serve_names(entry: &Entry, relay: &Serving) -> anyhow::Result<bool> {
    change(vec![(entry.clone(), Change::Names(relay.clone()))])
        .map(|covered| covered && relay.alive())
}

pub(super) fn optional_cover(items: &Settings, has_name_rules: bool) -> bool {
    !items.network_refused() && !has_name_rules
}

/// Recover DNS after the supervisor has reaped the daemon. Only the
/// committed policies can still require a resolver cover.
pub fn detach_recording_covers(db: &Path) -> anyhow::Result<()> {
    if !db.try_exists().context("checking for sandbox policy")? {
        return Ok(());
    }
    let entries = offline_recording_covers(db).map_err(|_| {
        anyhow::anyhow!("cannot read committed sandbox policy for resolver cleanup")
    })?;
    remove_old_relays(entries.into_iter())
}

/// The daemon is stopped before uninstall. Include disabled entries because
/// a failed release may have left a cover in a running process.
pub fn detach_all_covers(db: &Path) -> anyhow::Result<()> {
    if !db.try_exists().context("checking for sandbox policy")? {
        return Ok(());
    }
    let entries = Reader::open(db)
        .and_then(|reader| reader.sandbox_entries())
        .map_err(|_| anyhow::anyhow!("cannot read sandbox policy for resolver cleanup"))?;
    change(
        entries
            .into_iter()
            .map(|entry| (entry, Change::RemoveCovers))
            .collect(),
    )?;
    Ok(())
}

fn offline_recording_covers(db: &Path) -> anyhow::Result<Vec<Entry>> {
    let reader = Reader::open(db)?;
    let global = Settings::parse(&reader.sandbox_global()?);
    let protected = reader.net_uids_with_non_address_rules()?;
    Ok(reader
        .sandbox_entries()?
        .into_iter()
        .filter(|entry| {
            entry.enabled
                && optional_cover(&items_of(entry, &global), protected.contains(&entry.uid))
        })
        .collect())
}

pub(super) fn remove_old_relays(entries: impl Iterator<Item = Entry>) -> anyhow::Result<()> {
    change(entries.map(|entry| (entry, Change::RemoveRelay)).collect()).map(|_| ())
}

fn change(entries: Vec<(Entry, Change)>) -> anyhow::Result<bool> {
    if entries.is_empty() {
        return Ok(true);
    }
    std::thread::Builder::new()
        .name("resolver".into())
        .spawn(move || in_namespaces(entries))
        .context("starting the thread")?
        .join()
        .map_err(|_| anyhow::anyhow!("the thread ended early"))?
}

/// The device and inode of a namespace: what tells two apart.
fn identity(namespace: &File) -> io::Result<(u64, u64)> {
    let meta = namespace.metadata()?;
    Ok((meta.dev(), meta.ino()))
}

fn in_namespaces(entries: Vec<(Entry, Change)>) -> anyhow::Result<bool> {
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
    let mut changes = HashMap::new();
    let mut verify_error = None;
    for (entry, change) in entries {
        // The uid may have gone to another app since.
        match dirs::matches_install(
            Path::new("/data/user_de"),
            &entry.package,
            entry.uid,
            &entry.install_id,
        ) {
            Ok(true) => {
                changes.insert(entry.uid, change);
            }
            Ok(false) => {}
            Err(e) if matches!(change, Change::RemoveRelay) => {
                eprintln!("bbd: sandbox: cannot verify installation for resolver cleanup: {e}");
            }
            Err(e) if matches!(change, Change::RemoveCovers) => {
                verify_error.get_or_insert(e);
            }
            Err(e) => return Err(e.into()),
        }
    }
    if changes.is_empty() {
        return verify_error.map_or(Ok(false), |e| Err(e.into()));
    }
    let mut complete = true;
    for pass in 0..PASSES {
        if pass > 0 {
            std::thread::sleep(PASS_PAUSE);
        }
        // Twice at least: what was just about to take the uid is not seen
        // the first time.
        let (changed, recorded) = pass_once(&changes, &home, &kept)?;
        complete &= recorded;
        if changed == 0 && pass > 0 {
            break;
        }
    }
    verify_error.map_or(Ok(complete), |e| Err(e.into()))
}

/// One pass over the processes of the selected UIDs. A process that
/// cannot be looked at has ended.
fn pass_once(
    changes: &HashMap<u32, Change>,
    home: &File,
    kept: &[(u64, u64)],
) -> anyhow::Result<(u32, bool)> {
    let mut seen = HashSet::new();
    let mut changed = 0;
    let mut complete = true;
    let mut sweep_error = None;
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
        // Non-dumpable app processes can have root-owned proc directories.
        let owner = || {
            let uid = process.metadata().ok()?.uid();
            if uid != 0 {
                return Some(uid);
            }
            crate::proc::real_uid(&fs::read(format!("/proc/{pid}/status")).ok()?)
        };
        let Some(uid) = owner() else { continue };
        let Some(change) = changes.get(&uid) else {
            continue;
        };
        // Held open, then looked at again: what is read of the pid from
        // here on is of this process, or it has ended.
        let Ok(held) = Process::open(pid) else {
            continue;
        };
        if owner() != Some(uid) {
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
        let mounted = found(&mountinfo);
        let relay = match change {
            Change::Record(relay) | Change::Names(relay) => Some(relay),
            _ => None,
        };
        let plan = if relay.is_some() {
            // The visible socket's inode is read after entering the namespace.
            None
        } else {
            let Some(plan) = change.plan(mounted, None, None)? else {
                continue;
            };
            Some(plan)
        };
        let enter_target = || -> anyhow::Result<()> {
            anyhow::ensure!(
                spreads(&mountinfo) == Some(false),
                "a process of the app shares its mounts with others"
            );
            enter(&theirs).context("entering a namespace")
        };
        let done = match enter_target() {
            Ok(()) => {
                let mounted = (|| -> anyhow::Result<bool> {
                    if let Some(relay) = relay {
                        let serving = relay.socket();
                        let covered = socket_id(SOCKET).ok();
                        complete &= serving.is_some();
                        let Some((detach, cover)) = change.plan(mounted, *serving, covered)? else {
                            return Ok(false);
                        };
                        if detach {
                            uncover_here()?;
                        }
                        if let Some(id) = serving.filter(|_| cover == Cover::Relay)
                            && record_here(id).is_err()
                        {
                            complete = false;
                            if matches!(change, Change::Names(_)) {
                                cover_here(Cover::Null)?;
                                return Ok(true);
                            }
                            return Ok(detach);
                        }
                        if cover == Cover::Null {
                            cover_here(cover)?;
                        }
                    } else if let Some((detach, cover)) = plan {
                        if detach {
                            uncover_here()?;
                        }
                        cover_here(cover)?;
                    }
                    Ok(true)
                })();
                // A thread that cannot come back must stop visiting namespaces.
                enter(home).context("returning from a namespace")?;
                mounted.context("changing what is at the resolver's socket")
            }
            Err(e) => Err(e),
        };
        match done {
            Ok(used) => changed += u32::from(used),
            Err(e) if relay.is_some() || matches!(change, Change::RemoveCovers) => {
                sweep_error.get_or_insert(e);
            }
            Err(e) if matches!(change, Change::RemoveRelay) => {
                eprintln!("bbd: sandbox: cannot remove an old resolver relay: {e:#}");
            }
            Err(e) => return Err(e),
        }
    }
    sweep_error.map_or(Ok((changed, complete)), Err)
}

fn enter(namespace: &File) -> io::Result<()> {
    if unsafe { libc::setns(namespace.as_raw_fd(), libc::CLONE_NEWNS) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

pub(super) fn record_here(serving: SocketId) -> io::Result<()> {
    if socket_id(RELAY_SOCKET)? != serving {
        return Err(io::Error::other("the relay socket changed"));
    }
    if socket_id(SOCKET).ok() == Some(serving) {
        return Ok(());
    }
    cover_here(Cover::Relay)
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
    fn offline_removal_detaches_sandbox_covers_and_leaves_foreign_mounts() {
        let cleanup = Change::RemoveCovers;
        for cover in [
            Mounted::Known(Cover::Null),
            Mounted::Known(Cover::Relay),
            Mounted::StaleRelay,
        ] {
            assert_eq!(
                cleanup.plan(cover, None, None).unwrap(),
                Some((true, Cover::None))
            );
        }
        for cover in [Mounted::Known(Cover::None), Mounted::Other] {
            assert_eq!(cleanup.plan(cover, None, None).unwrap(), None);
        }
    }

    #[test]
    fn name_rules_replace_a_relay_cover_that_is_not_the_serving_socket() {
        let names = Change::Names(Serving::default());
        let cover = Mounted::Known(Cover::Relay);
        let current = Some((1, 2));
        assert_eq!(
            names.plan(cover, current, Some((1, 3))).unwrap(),
            Some((true, Cover::Relay))
        );
        assert_eq!(names.plan(cover, current, current).unwrap(), None);
        assert_eq!(
            names.plan(cover, None, current).unwrap(),
            Some((true, Cover::Null))
        );
    }

    #[test]
    fn what_is_on_the_socket_is_told_by_what_is_mounted_there() {
        assert_eq!(found(APP), Mounted::Known(Cover::None));
        assert_eq!(found(""), Mounted::Known(Cover::None));
        let with = |root: &str| {
            format!(
                "{APP}19010 16380 0:18 {root} /dev/socket/dnsproxyd rw,nosuid,relatime master:2 - tmpfs tmpfs rw,seclabel\n"
            )
        };
        assert_eq!(found(&with("/null")), Mounted::Known(Cover::Null));
        assert_eq!(
            found(&with("/socket/dnsproxyd_relay")),
            Mounted::Known(Cover::Relay)
        );
        // The socket of a daemon that has ended, or anything else.
        assert_eq!(
            found(&with("/socket/dnsproxyd_relay//deleted")),
            Mounted::StaleRelay
        );
        assert_eq!(found(&with("/zero")), Mounted::Other);
        // Another socket, or a directory that only begins the same.
        let other = format!(
            "{APP}19010 16380 0:18 /null /dev/socket/dnsproxyd2 rw master:2 - tmpfs tmpfs rw\n"
        );
        assert_eq!(found(&other), Mounted::Known(Cover::None));
        // The last mounted there is the one in view.
        let twice = format!("{}{}", with("/null"), with("/socket/dnsproxyd_relay"))
            .replacen(APP, "", 1)
            .replacen(APP, "", 1);
        assert_eq!(found(&twice), Mounted::Known(Cover::Relay));
    }

    #[test]
    fn offline_cleanup_preserves_covers_required_by_saved_rules() {
        use super::super::tests::Fixture;
        use crate::store::net::RuleChange;

        let fixture = Fixture::new("offline-recording-covers");
        let writer = &fixture.writer;
        writer
            .put_sandbox_global(&[("network", "on"), ("network_record", "on")])
            .unwrap();
        for uid in 10100..=10107 {
            let settings = match uid {
                10105 => "",
                10107 => "network=off,network_record=off",
                _ => "network=off",
            };
            writer
                .put_sandbox(&Entry {
                    package: format!("com.example.app{uid}"),
                    install_id: format!("installation-{uid}"),
                    uid,
                    enabled: uid != 10104,
                    android_id: format!("{uid:016x}"),
                    saved_ops: String::new(),
                    saved_permissions: String::new(),
                    created: 1,
                    settings: settings.into(),
                })
                .unwrap();
        }
        for (uid, target) in [
            (10101, "host:t.example.com"),
            (10102, "domain:example.com"),
            (10103, "ip:203.0.113.7:443"),
            (10106, "future:example.com"),
        ] {
            writer
                .change_net_rule(
                    uid,
                    target,
                    RuleChange::Block {
                        created: 1,
                        manual: true,
                    },
                    || Ok(Vec::new()),
                )
                .unwrap();
        }

        let selected = offline_recording_covers(&fixture.dir.join("bb.db")).unwrap();
        assert_eq!(
            selected.iter().map(|entry| entry.uid).collect::<Vec<_>>(),
            [10100, 10103, 10107]
        );
        assert_eq!(
            Change::RemoveRelay
                .plan(Mounted::Known(Cover::Relay), None, None)
                .unwrap(),
            Some((true, Cover::None))
        );
    }

    #[test]
    fn restart_uncovers_linked_and_deleted_relays_without_a_listener() {
        let plan = Change::RemoveRelay;
        for root in [
            "/socket/dnsproxyd_relay",
            "/socket/dnsproxyd_relay//deleted",
        ] {
            let mounts = format!("{APP}19010 16380 0:18 {root} {SOCKET} rw - tmpfs tmpfs rw\n");
            assert_eq!(
                plan.plan(found(&mounts), None, None).unwrap(),
                Some((true, Cover::None))
            );
        }
        for root in ["/null", "/zero", "/socket/dnsproxyd_relay_other"] {
            let mounts = format!("{APP}19010 16380 0:18 {root} {SOCKET} rw - tmpfs tmpfs rw\n");
            assert_eq!(plan.plan(found(&mounts), None, None).unwrap(), None);
        }
        assert_eq!(plan.plan(found(APP), None, None).unwrap(), None);
    }

    #[test]
    fn recording_keeps_only_the_listener_of_this_generation() {
        let record = Change::Record(Serving::default());
        let this = Some((1, 2));
        let previous = Some((1, 1));
        assert_eq!(
            record
                .plan(Mounted::Known(Cover::Relay), this, this)
                .unwrap(),
            None
        );
        assert_eq!(
            record
                .plan(Mounted::Known(Cover::Relay), this, previous)
                .unwrap(),
            Some((true, Cover::Relay))
        );
        assert_eq!(
            record
                .plan(Mounted::Known(Cover::Relay), None, previous)
                .unwrap(),
            Some((true, Cover::None))
        );
        assert_eq!(
            record.plan(Mounted::StaleRelay, None, previous).unwrap(),
            Some((true, Cover::None))
        );
    }

    #[test]
    fn restart_replaces_a_deleted_relay_when_names_are_still_blocked() {
        let plan = Change::Set(Cover::Relay);
        assert_eq!(
            plan.plan(Mounted::StaleRelay, None, None).unwrap(),
            Some((true, Cover::Relay))
        );
        assert_eq!(
            plan.plan(Mounted::Known(Cover::Relay), None, None).unwrap(),
            None
        );
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
