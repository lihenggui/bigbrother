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
//! for it changes, or an item that was off is turned on, to any mode but
//! off: what the app read before, and may keep (the ANDROID_ID, for one),
//! goes with them. Turning items off, and changing one between the modes
//! that keep something (on, coarse, foreground), takes effect in the
//! running process: the app ops and permissions are looked up as the app
//! asks. The network is refused packet by packet, and no process is ended
//! for it.
//!
//! Location made coarse takes the precise location permission away, and
//! Android ends the processes of an app whenever it revokes a runtime
//! permission the app had: the app starts again with the approximate
//! location only, and nothing is ended for it here. An app that did not
//! have the permission keeps running, as nothing changes for it, and one
//! given the permission back keeps running too.
//!
//! The same is done when the daemon starts, for what started while it was
//! not there to hold it: early in boot, or between a crash and the restart.
//!
//! Processes are ended with a signal rather than through the activity
//! manager's "force stop": that would start a Java runtime for each change,
//! and leaves the app in the stopped state, where it gets no broadcasts
//! until the user opens it.

use std::{collections::HashMap, fs, os::unix::fs::MetadataExt as _, path::Path};

use super::{
    Effect, Entry, Resumed, dirs,
    gate::MountReadiness,
    items::{Mounts, Settings},
    mounts,
};
use crate::{
    proc::{Process, parse_stat, uid_of_pid},
    sandbox_item::{Item, Mode},
};

/// Ends an app's processes as it enters or leaves the sandbox. The last of
/// the effects: by then everything the next start will find is in place.
#[derive(Default)]
pub struct Restart {
    gate: Option<MountReadiness>,
    resumed: HashMap<u32, (String, Mounts)>,
}

impl Restart {
    pub fn new(gate: MountReadiness) -> Self {
        Self {
            gate: Some(gate),
            resumed: HashMap::new(),
        }
    }

    fn ready(&self, entry: &Entry, mounts: Mounts) -> bool {
        self.gate
            .as_ref()
            .is_some_and(|gate| gate.ready(entry, mounts))
    }

    pub(super) fn resume_with(&mut self, apps: &[Resumed], mut restart: impl FnMut(&Entry, Which)) {
        for app in apps {
            let entry = &app.entry;
            let mounts = app.items.mounts();
            if !mounts.any() || !self.ready(entry, mounts) {
                continue;
            }
            let policy = (entry.install_id.clone(), mounts);
            if self.resumed.get(&entry.uid) != Some(&policy) {
                self.resumed.insert(entry.uid, policy);
                restart(entry, Which::Lacking(mounts));
            }
        }
    }
}

impl Effect for Restart {
    fn name(&self) -> &'static str {
        "restarting the app"
    }

    fn enable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        // What the app read before is gone with the processes that hold it.
        // Those that started since the gate held them have their mounts.
        let mounts = items.mounts();
        if mounts.any() {
            if self.ready(entry, mounts) {
                end(entry, Which::Lacking(mounts), || self.ready(entry, mounts));
                self.resumed
                    .insert(entry.uid, (entry.install_id.clone(), mounts));
            }
        } else if items.any_held_by_a_process() {
            end(entry, Which::All, || true);
        }
        Ok(())
    }

    fn disable(&mut self, entry: &mut Entry, _: &[String], items: &Settings) -> anyhow::Result<()> {
        self.resumed.remove(&entry.uid);
        if items.any_held_by_a_process() {
            end(entry, Which::All, || true);
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
        let turned_on = Item::ALL.into_iter().any(|item| {
            item.held_by_a_process()
                && before.mode(item) == Mode::Off
                && after.mode(item) != Mode::Off
        });
        let mounts = after.mounts();
        if (before.mounts() != mounts || turned_on) && (!mounts.any() || self.ready(entry, mounts))
        {
            end(entry, Which::All, || {
                !mounts.any() || self.ready(entry, mounts)
            });
            self.resumed
                .insert(entry.uid, (entry.install_id.clone(), mounts));
        }
        Ok(())
    }

    fn resume(&mut self, apps: &[Resumed]) {
        let gate = self.gate.clone();
        self.resume_with(apps, |entry, which| {
            let Which::Lacking(mounts) = which else {
                return;
            };
            end(entry, which, || {
                gate.as_ref().is_some_and(|gate| gate.ready(entry, mounts))
            });
        });
    }

    fn storage_ready(&mut self, apps: &[Resumed]) {
        for app in apps {
            let mounts = app.items.mounts();
            if mounts.storage && self.ready(&app.entry, mounts) {
                end(&app.entry, Which::StandIn, || {
                    self.ready(&app.entry, mounts)
                });
            }
        }
    }

    fn forget(&mut self, uid: u32) {
        self.resumed.remove(&uid);
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) enum Which {
    All,
    /// Those without the given mounts: started without the gate holding
    /// them.
    Lacking(Mounts),
    StandIn,
}

/// Most passes over the processes for one uid, and the pause between two.
const PASSES: u32 = 4;
const PASS_PAUSE: std::time::Duration = std::time::Duration::from_millis(20);

/// End processes of `uid`, looking again until a pass finds none to end:
/// one may fork while the others are being ended, or have been about to
/// take the uid when it was looked for.
fn end(entry: &Entry, which: Which, ready: impl Fn() -> bool) {
    let uid = entry.uid;
    let media = mounts::media_of(uid);
    let media = Path::new(&media);
    let real_storage = matches!(
        which,
        Which::StandIn | Which::Lacking(Mounts { storage: true, .. })
    ) && dirs::open_sandbox(media, uid, &format!("{uid}-{}", entry.install_id))
        .is_ok();
    if which == Which::StandIn && !real_storage {
        return;
    }
    let sandboxes = if matches!(which, Which::Lacking(Mounts { storage: true, .. })) {
        dirs::sandboxes_name(media).unwrap_or_else(|e| {
            eprintln!("bbd: warning: looking up the sandbox directory: {e}");
            None
        })
    } else {
        None
    };
    let passes = if which == Which::StandIn { 1 } else { PASSES };
    for pass in 0..passes {
        if !ready() {
            break;
        }
        if pass > 0 {
            std::thread::sleep(PASS_PAUSE);
        }
        // A process about to take the UID may have been missed in the
        // first pass of a policy change.
        if end_once(entry, which, sandboxes.as_deref(), real_storage, &ready) == 0 && pass > 0 {
            break;
        }
    }
}

/// One pass; returns how many were ended. A process that cannot be looked
/// at has ended already. `sandboxes` is the name of the directory of the
/// sandboxes, if it could be found.
fn end_once(
    entry: &Entry,
    which: Which,
    sandboxes: Option<&str>,
    real_storage: bool,
    ready: &impl Fn() -> bool,
) -> u32 {
    let uid = entry.uid;
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
        let runs_as = |uid: u32| {
            process.metadata().is_ok_and(|meta| {
                meta.uid() == uid || (meta.uid() == 0 && uid_of_pid(pid as u32) == Some(uid))
            })
        };
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
        if matches!(which, Which::Lacking(_))
            && fs::read_to_string(format!("/proc/{pid}/stat"))
                .ok()
                .and_then(|stat| parse_stat(&stat)?.state_and_parent())
                .is_some_and(|(state, _)| state == 'T')
        {
            // The gate may still be installing this start's mounts.
            continue;
        }
        let to_end = which == Which::All
            || fs::read_to_string(format!("/proc/{pid}/mountinfo")).is_ok_and(|mountinfo| {
                needs_restart(&mountinfo, entry, which, sandboxes, real_storage)
            });
        if to_end
            && matches!(
                dirs::matches_install(
                    Path::new("/data/user_de"),
                    &entry.package,
                    uid,
                    &entry.install_id
                ),
                Ok(true)
            )
            && ready()
            && held.signal(libc::SIGKILL).is_ok()
        {
            ended += 1;
        }
    }
    ended
}

fn needs_restart(
    mountinfo: &str,
    entry: &Entry,
    which: Which,
    sandboxes: Option<&str>,
    real_storage: bool,
) -> bool {
    match which {
        Which::All => true,
        Which::Lacking(wanted) => {
            (wanted.storage
                && !has_sandbox(
                    mountinfo,
                    entry.uid,
                    sandboxes,
                    &entry.install_id,
                    !real_storage,
                ))
                || (wanted.cpu_info && !has_cpu_info(mountinfo))
        }
        Which::StandIn => {
            real_storage && has_sandbox(mountinfo, entry.uid, None, &entry.install_id, true)
        }
    }
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
fn has_sandbox(
    mountinfo: &str,
    uid: u32,
    sandboxes: Option<&str>,
    installation: &str,
    allow_stand_in: bool,
) -> bool {
    let storage = mounts::storage_root_of(uid);
    let directory = sandboxes.map(|name| format!("/{name}/{uid}-{installation}"));
    mountinfo
        .lines()
        .rev()
        .find_map(|line| {
            let mut fields = line.split(' ');
            let (Some(root), Some(mount_point), Some(options)) =
                (fields.nth(3), fields.next(), fields.next())
            else {
                return None;
            };
            if mount_point != storage {
                return None;
            }
            let mut after_separator = fields.skip_while(|field| *field != "-").skip(1);
            let (fstype, super_options) = (after_separator.next(), after_separator.nth(1));
            let stand_in = root == "/"
                && fstype == Some("tmpfs")
                && options.split(',').any(|option| option == "ro")
                && super_options.is_some_and(|super_options| {
                    super_options
                        .split(',')
                        .any(|option| option == mounts::STAND_IN_SIZE)
                });
            Some(
                (allow_stand_in && stand_in)
                    || directory
                        .as_deref()
                        .is_some_and(|directory| root.ends_with(directory)),
            )
        })
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    const ZYGOTE_MOUNTS: &str = "\
        2100 2099 0:57 / /storage rw,nosuid,nodev,noexec,relatime master:5 - tmpfs tmpfs rw,seclabel,mode=755,gid=1000\n\
        2101 2100 0:96 / /storage/emulated rw,nosuid,nodev,noexec,noatime master:30 - fuse /dev/fuse rw,lazytime,user_id=0,group_id=0,allow_other\n\
        2102 2101 254:48 /media/0/Android/data/com.app /storage/emulated/0/Android/data/com.app rw,nosuid,nodev,noatime master:1 - ext4 /dev/block/dm-48 rw,seclabel\n";

    fn has_sandbox(mountinfo: &str, uid: u32, sandboxes: Option<&str>, installation: &str) -> bool {
        super::has_sandbox(mountinfo, uid, sandboxes, installation, true)
    }

    fn has_mounts(
        mountinfo: &str,
        uid: u32,
        sandboxes: Option<&str>,
        installation: &str,
        wanted: Mounts,
    ) -> bool {
        (!wanted.storage || has_sandbox(mountinfo, uid, sandboxes, installation))
            && (!wanted.cpu_info || has_cpu_info(mountinfo))
    }

    const INSTALLATION: &str = "a03066efd0d34a02991a2cc106c71680";
    const SANDBOXES: Option<&str> = Some(".5f0c9a2e");

    fn storage_app() -> Resumed {
        Resumed {
            entry: Entry {
                uid: 10100,
                package: "com.example.app".into(),
                install_id: INSTALLATION.into(),
                enabled: true,
                android_id: String::new(),
                saved_ops: String::new(),
                saved_permissions: String::new(),
                created: 0,
                settings: String::new(),
            },
            packages: Vec::new(),
            items: Settings::default(),
        }
    }

    #[test]
    fn resuming_with_an_unavailable_gate_never_restarts_a_mounted_app() {
        let app = storage_app();
        assert!(app.items.mounts().any());
        let mut restarted = Vec::new();
        Restart::default().resume_with(&[app], |entry, _| restarted.push(entry.uid));
        assert!(restarted.is_empty());
    }

    #[test]
    fn unlocking_restarts_only_a_verified_stand_in_when_real_storage_is_ready() {
        let entry = storage_app().entry;
        let stand_in = format!(
            "{ZYGOTE_MOUNTS}2200 2101 0:120 / /storage/emulated/0 ro,relatime - tmpfs tmpfs ro,size=4k,mode=555\n"
        );
        let real = format!(
            "{ZYGOTE_MOUNTS}2200 2101 254:48 /media/0/.5f0c9a2e/10100-{INSTALLATION} /storage/emulated/0 rw - ext4 /dev/block/dm-48 rw\n"
        );
        let needed =
            |mountinfo, ready| needs_restart(mountinfo, &entry, Which::StandIn, SANDBOXES, ready);
        assert!(!needed(&stand_in, false));
        assert!(needed(&stand_in, true));
        assert!(!needed(&real, true));
        assert!(!needed(ZYGOTE_MOUNTS, true));
        let startup = Which::Lacking(Mounts {
            storage: true,
            cpu_info: false,
        });
        assert!(!needs_restart(&stand_in, &entry, startup, None, false));
        assert!(needs_restart(&stand_in, &entry, startup, SANDBOXES, true));
    }

    #[test]
    fn a_process_with_the_sandbox_over_its_storage_is_sandboxed() {
        let sandboxed = format!(
            "{ZYGOTE_MOUNTS}2200 2101 254:48 /media/0/.5f0c9a2e/10100-a03066efd0d34a02991a2cc106c71680 /storage/emulated/0 rw,nosuid,nodev,noatime - ext4 /dev/block/dm-48 rw,seclabel\n"
        );
        assert!(has_sandbox(&sandboxed, 10100, SANDBOXES, INSTALLATION));
        assert!(!has_sandbox(
            &sandboxed,
            10100,
            SANDBOXES,
            "5e153234809d4082b8b1cd64ce419972"
        ));
        assert!(!has_sandbox(
            &sandboxed.replace(&format!("-{INSTALLATION}"), ""),
            10100,
            SANDBOXES,
            INSTALLATION
        ));
        // Another app's sandbox is not this one's, nor another user's.
        assert!(!has_sandbox(&sandboxed, 10101, SANDBOXES, INSTALLATION));
        assert!(!has_sandbox(&sandboxed, 1_010_100, SANDBOXES, INSTALLATION));
        // Nor is a directory elsewhere, or one not known to be the daemon's.
        assert!(!has_sandbox(
            &sandboxed,
            10100,
            Some(".0000beef"),
            INSTALLATION
        ));
        assert!(!has_sandbox(&sandboxed, 10100, None, INSTALLATION));
        assert!(!has_sandbox(ZYGOTE_MOUNTS, 10100, SANDBOXES, INSTALLATION));
        assert!(!has_sandbox("", 10100, SANDBOXES, INSTALLATION));
        assert!(!has_sandbox(
            "garbage\n1 2\n",
            10100,
            SANDBOXES,
            INSTALLATION
        ));
    }

    #[test]
    fn a_process_has_its_mounts_if_it_has_those_wanted() {
        let cpu = "2300 2099 0:139 /cpuinfo /proc/cpuinfo rw,relatime - tmpfs tmpfs rw,size=64k\n";
        let storage = "2200 2101 254:48 /media/0/.5f0c9a2e/10100-a03066efd0d34a02991a2cc106c71680 /storage/emulated/0 rw - ext4 /dev/block/dm-48 rw\n";
        let both = format!("{ZYGOTE_MOUNTS}{storage}{cpu}");
        let only_cpu = format!("{ZYGOTE_MOUNTS}{cpu}");
        let wanted = |storage, cpu_info| Mounts { storage, cpu_info };
        assert!(has_mounts(
            &both,
            10100,
            SANDBOXES,
            INSTALLATION,
            wanted(true, true)
        ));
        assert!(!has_mounts(
            &only_cpu,
            10100,
            SANDBOXES,
            INSTALLATION,
            wanted(true, true)
        ));
        assert!(has_mounts(
            &only_cpu,
            10100,
            SANDBOXES,
            INSTALLATION,
            wanted(false, true)
        ));
        assert!(!has_mounts(
            ZYGOTE_MOUNTS,
            10100,
            SANDBOXES,
            INSTALLATION,
            wanted(false, true)
        ));
        // A /proc/cpuinfo of somebody else's is not in the way.
        assert!(has_mounts(
            &both,
            10100,
            SANDBOXES,
            INSTALLATION,
            wanted(true, false)
        ));
        assert!(has_mounts(
            ZYGOTE_MOUNTS,
            10100,
            SANDBOXES,
            INSTALLATION,
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
        assert!(has_sandbox(&locked, 10100, None, INSTALLATION));
        // Another tmpfs there is not one, read-only or not.
        for other in [
            "ro,relatime - tmpfs tmpfs ro,seclabel",
            "rw,relatime - tmpfs tmpfs rw,seclabel,size=4k",
        ] {
            let other = format!("{ZYGOTE_MOUNTS}2200 2101 0:120 / /storage/emulated/0 {other}\n");
            assert!(!has_sandbox(&other, 10100, None, INSTALLATION), "{other}");
        }
        // Nor is the stand-in over something else (a hidden volume).
        let volume = format!(
            "{ZYGOTE_MOUNTS}2200 2100 0:120 / /storage/1A2B-3C4D ro,relatime - tmpfs tmpfs ro\n"
        );
        assert!(!has_sandbox(&volume, 10100, None, INSTALLATION));
    }
}
