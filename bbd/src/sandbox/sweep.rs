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

//! Ending the processes that are not as the sandbox says.
//!
//! A sandbox is given to a process when it starts, and cannot be given or
//! taken afterwards. So when an app is put in the sandbox, the processes it
//! already has are ended, and when it is taken out, those that have one. The
//! system starts an app again when it is next wanted, and then it starts as
//! it should. Changing an app's items ends its processes if what is mounted
//! for it changes, or an item that was off is turned on: what the app read
//! before, and may keep (the ANDROID_ID, for one), goes with them. Turning
//! items off takes effect in the running process.
//!
//! The same is done when the daemon starts, for what started while it was
//! not there to hold it: early in boot, or between a crash and the restart.
//!
//! Processes are ended with a signal rather than through the activity
//! manager's "force stop": that would start a Java runtime for each change,
//! and leaves the app in the stopped state, where it gets no broadcasts
//! until the user opens it.

use std::{fs, os::unix::fs::MetadataExt as _, path::Path};

use super::{
    Effect, Entry, Resumed, dirs,
    gate::Process,
    items::{Item, Mode, Mounts, Settings},
    mounts,
};

/// Ends an app's processes as it enters or leaves the sandbox. The last of
/// the effects: by then everything the next start will find is in place.
pub struct Restart;

impl Effect for Restart {
    fn name(&self) -> &'static str {
        "restarting the app"
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        // What the app read before is gone with the processes that hold it.
        // Those that started since the gate held them have their mounts.
        if items.mounts().any() {
            end(entry.uid, Which::Lacking(items.mounts()));
        } else if items.any_on() {
            end(entry.uid, Which::All);
        }
        Ok(())
    }

    fn disable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        if items.any_on() {
            end(entry.uid, Which::All);
        }
        Ok(())
    }

    fn update(
        &mut self,
        entry: &mut Entry,
        _: &[String],
        before: &Settings,
        after: &Settings,
    ) -> anyhow::Result<()> {
        let turned_on = Item::ALL
            .into_iter()
            .any(|item| before.mode(item) == Mode::Off && after.mode(item) != Mode::Off);
        if before.mounts() != after.mounts() || turned_on {
            end(entry.uid, Which::All);
        }
        Ok(())
    }

    fn resume(&mut self, apps: &[Resumed]) {
        for app in apps.iter().filter(|app| app.items.mounts().any()) {
            end(app.entry.uid, Which::Lacking(app.items.mounts()));
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Which {
    All,
    /// Those without the given mounts: started without the gate holding
    /// them.
    Lacking(Mounts),
}

/// Most passes over the processes for one uid, and the pause between two.
const PASSES: u32 = 4;
const PASS_PAUSE: std::time::Duration = std::time::Duration::from_millis(20);

/// End processes of `uid`, looking again until a pass finds none to end:
/// one may fork while the others are being ended, or have been about to
/// take the uid when it was looked for.
fn end(uid: u32, which: Which) {
    // Looked up once: it is the same for every process of the uid. If it
    // cannot be, a process with its sandbox is ended too, and gets it again
    // as it starts.
    let sandboxes = dirs::sandboxes_name(Path::new(&mounts::media_of(uid))).unwrap_or_else(|e| {
        eprintln!("bbd: warning: looking up the sandbox directory: {e}");
        None
    });
    for pass in 0..PASSES {
        if pass > 0 {
            std::thread::sleep(PASS_PAUSE);
        }
        // Twice at least: what was just about to take the uid is not seen
        // the first time.
        if end_once(uid, which, sandboxes.as_deref()) == 0 && pass > 0 {
            break;
        }
    }
}

/// One pass; returns how many were ended. A process that cannot be looked
/// at has ended already. `sandboxes` is the name of the directory of the
/// sandboxes, if it could be found.
fn end_once(uid: u32, which: Which, sandboxes: Option<&str>) -> u32 {
    let Ok(processes) = fs::read_dir("/proc") else {
        return 0;
    };
    let mut ended = 0;
    for process in processes.flatten() {
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
        // Held open, so that the signal cannot reach another process given
        // the pid in between; then looked at again, as it may have been
        // given already.
        let Ok(held) = Process::open(pid) else {
            continue;
        };
        if !runs_as(uid) {
            continue;
        }
        let to_end = match which {
            Which::All => true,
            Which::Lacking(wanted) => fs::read_to_string(format!("/proc/{pid}/mountinfo"))
                .map_or(true, |mountinfo| {
                    !has_mounts(&mountinfo, uid, sandboxes, wanted)
                }),
        };
        if to_end {
            held.signal(libc::SIGKILL);
            ended += 1;
        }
    }
    ended
}

/// Whether a process with these mounts (`/proc/<pid>/mountinfo`) of `uid`
/// has those the sandbox gives it as `wanted` says. What else it has is
/// not looked at: a mount on `/proc/cpuinfo` may be the system's, or
/// another module's, in every app.
fn has_mounts(mountinfo: &str, uid: u32, sandboxes: Option<&str>, wanted: Mounts) -> bool {
    (!wanted.storage || has_sandbox(mountinfo, uid, sandboxes))
        && (!wanted.cpu_info || has_cpu_info(mountinfo))
}

/// Whether a process with these mounts has something mounted on
/// `/proc/cpuinfo`.
fn has_cpu_info(mountinfo: &str) -> bool {
    mountinfo
        .lines()
        .any(|line| line.split(' ').nth(4) == Some(mounts::CPUINFO))
}

/// Whether a process with these mounts (`/proc/<pid>/mountinfo`) was given
/// the sandbox of `uid`: its shared storage is the sandbox directory, in
/// the directory of the sandboxes named `sandboxes`, or the read-only empty
/// stand-in for one, told by its size. Nothing else is mounted on the root
/// of a user's shared storage: the system mounts its storage on
/// `/storage/emulated`, a level above.
///
/// A line is `<id> <parent> <dev> <root> <mount point> <options> ... -
/// <type> <source> ...`, where the root is the mounted directory's path in
/// its own filesystem.
fn has_sandbox(mountinfo: &str, uid: u32, sandboxes: Option<&str>) -> bool {
    let storage = mounts::storage_root_of(uid);
    let directory = sandboxes.map(|name| format!("/{name}/{uid}"));
    mountinfo.lines().any(|line| {
        let mut fields = line.split(' ');
        let (Some(root), Some(mount_point), Some(options)) =
            (fields.nth(3), fields.next(), fields.next())
        else {
            return false;
        };
        if mount_point != storage {
            return false;
        }
        let mut after_separator = fields.skip_while(|field| *field != "-").skip(1);
        let (fstype, super_options) = (after_separator.next(), after_separator.nth(1));
        let stand_in = fstype == Some("tmpfs")
            && options.split(',').any(|option| option == "ro")
            && super_options.is_some_and(|super_options| {
                super_options
                    .split(',')
                    .any(|option| option == mounts::STAND_IN_SIZE)
            });
        stand_in
            || directory
                .as_deref()
                .is_some_and(|directory| root.ends_with(directory))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const ZYGOTE_MOUNTS: &str = "\
        2100 2099 0:57 / /storage rw,nosuid,nodev,noexec,relatime master:5 - tmpfs tmpfs rw,seclabel,mode=755,gid=1000\n\
        2101 2100 0:96 / /storage/emulated rw,nosuid,nodev,noexec,noatime master:30 - fuse /dev/fuse rw,lazytime,user_id=0,group_id=0,allow_other\n\
        2102 2101 254:48 /media/0/Android/data/com.app /storage/emulated/0/Android/data/com.app rw,nosuid,nodev,noatime master:1 - ext4 /dev/block/dm-48 rw,seclabel\n";

    const SANDBOXES: Option<&str> = Some(".5f0c9a2e");

    #[test]
    fn a_process_with_the_sandbox_over_its_storage_is_sandboxed() {
        let sandboxed = format!(
            "{ZYGOTE_MOUNTS}2200 2101 254:48 /media/0/.5f0c9a2e/10100 /storage/emulated/0 rw,nosuid,nodev,noatime - ext4 /dev/block/dm-48 rw,seclabel\n"
        );
        assert!(has_sandbox(&sandboxed, 10100, SANDBOXES));
        // Another app's sandbox is not this one's, nor another user's.
        assert!(!has_sandbox(&sandboxed, 10101, SANDBOXES));
        assert!(!has_sandbox(&sandboxed, 1_010_100, SANDBOXES));
        // Nor is a directory elsewhere, or one not known to be the daemon's.
        assert!(!has_sandbox(&sandboxed, 10100, Some(".0000beef")));
        assert!(!has_sandbox(&sandboxed, 10100, None));
        assert!(!has_sandbox(ZYGOTE_MOUNTS, 10100, SANDBOXES));
        assert!(!has_sandbox("", 10100, SANDBOXES));
        assert!(!has_sandbox("garbage\n1 2\n", 10100, SANDBOXES));
    }

    #[test]
    fn a_process_has_its_mounts_if_it_has_those_wanted() {
        let cpu = "2300 2099 0:139 /cpuinfo /proc/cpuinfo rw,relatime - tmpfs tmpfs rw,size=64k\n";
        let storage = "2200 2101 254:48 /media/0/.5f0c9a2e/10100 /storage/emulated/0 rw - ext4 /dev/block/dm-48 rw\n";
        let both = format!("{ZYGOTE_MOUNTS}{storage}{cpu}");
        let only_cpu = format!("{ZYGOTE_MOUNTS}{cpu}");
        let wanted = |storage, cpu_info| Mounts { storage, cpu_info };
        assert!(has_mounts(&both, 10100, SANDBOXES, wanted(true, true)));
        assert!(!has_mounts(&only_cpu, 10100, SANDBOXES, wanted(true, true)));
        assert!(has_mounts(&only_cpu, 10100, SANDBOXES, wanted(false, true)));
        assert!(!has_mounts(
            ZYGOTE_MOUNTS,
            10100,
            SANDBOXES,
            wanted(false, true)
        ));
        // A /proc/cpuinfo of somebody else's is not in the way.
        assert!(has_mounts(&both, 10100, SANDBOXES, wanted(true, false)));
        assert!(has_mounts(
            ZYGOTE_MOUNTS,
            10100,
            SANDBOXES,
            wanted(false, false)
        ));
    }

    #[test]
    fn the_empty_stand_in_counts_as_the_sandbox() {
        // Before the user unlocks the device, when the directory of the
        // sandboxes cannot be found either.
        let locked = format!(
            "{ZYGOTE_MOUNTS}2200 2101 0:120 / /storage/emulated/0 ro,relatime - tmpfs tmpfs ro,seclabel,size=4k,mode=555\n"
        );
        assert!(has_sandbox(&locked, 10100, None));
        // Another tmpfs there is not one, read-only or not.
        for other in [
            "ro,relatime - tmpfs tmpfs ro,seclabel",
            "rw,relatime - tmpfs tmpfs rw,seclabel,size=4k",
        ] {
            let other = format!("{ZYGOTE_MOUNTS}2200 2101 0:120 / /storage/emulated/0 {other}\n");
            assert!(!has_sandbox(&other, 10100, None), "{other}");
        }
        // Nor is the stand-in over something else (a hidden volume).
        let volume = format!(
            "{ZYGOTE_MOUNTS}2200 2100 0:120 / /storage/1A2B-3C4D ro,relatime - tmpfs tmpfs ro\n"
        );
        assert!(!has_sandbox(&volume, 10100, None));
    }
}
