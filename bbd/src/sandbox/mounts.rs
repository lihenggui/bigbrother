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

//! What a sandboxed app sees in place of the device's storage and of a few
//! files of kernel information: the mounts made in its namespace before it
//! runs.
//!
//! - Shared storage (`/storage/emulated/<user>`) becomes the app's sandbox
//!   directory, empty when first used and the app's to write in.
//! - The app's own directories under `Android/` stay what they were. They
//!   are taken from the filesystem underneath rather than through the media
//!   provider's FUSE mount, which answers every lookup with a round trip.
//! - Other volumes (SD cards) are hidden.
//! - `/proc/cpuinfo` is replaced by a file that does not name the chip.
//!
//! [`apply`] is the order of the steps; [`Sys`] is what carries them out, so
//! that the order can be tested without being root on a device.

use std::{
    ffi::CString,
    fs, io,
    os::fd::{AsRawFd, FromRawFd, OwnedFd},
    path::{Path, PathBuf},
};

use anyhow::Context as _;

use super::dirs::{self, Dir};
use crate::packages::PER_USER_RANGE;

/// The app's own directories on shared storage, by what they are for.
const OWN_KINDS: &[&str] = &["data", "obb", "media"];

/// The entries of `/storage` that are not volumes to hide: the shared
/// storage of each user, and the links to the current user's.
const NOT_VOLUMES: &[&str] = &["emulated", "self"];

/// Named as the source of the sandbox's own tmpfs mounts, which is how the
/// processes that have them are told from those that do not (`sweep`).
pub const TMPFS_SOURCE: &str = "bb_sandbox";

/// The mounts as carried out. On a device this is [`Kernel`].
pub trait Sys {
    /// A mount not attached anywhere yet.
    type Tree;
    type Dir;

    /// Stop mounts made later by the system under `path` from reaching this
    /// namespace: a storage volume mounted again would land on the sandbox.
    fn make_private(&mut self, path: &str) -> io::Result<()>;
    /// A copy of the mount of the directory at `path`, with what is mounted
    /// in it; `None` if there is no such directory.
    fn clone_tree(&mut self, path: &str) -> io::Result<Option<Self::Tree>>;
    /// Mount the sandbox directory of `uid` over `onto`. `NotFound` if
    /// there is none.
    fn bind_sandbox(&mut self, uid: u32, onto: &str) -> io::Result<()>;
    /// An empty, read-only directory in place of `onto`.
    fn empty(&mut self, onto: &str) -> io::Result<()>;
    fn open_dir(&mut self, path: &str) -> io::Result<Self::Dir>;
    /// The directory `name` in `parent`, made if missing and given to `uid`.
    /// Never through a symbolic link: `parent` is the app's to write in.
    fn make_dir(&mut self, parent: &Self::Dir, name: &str, uid: u32) -> io::Result<Self::Dir>;
    fn attach(&mut self, tree: Self::Tree, onto: &Self::Dir) -> io::Result<()>;
    /// The names in `/storage`.
    fn storage_entries(&mut self) -> io::Result<Vec<String>>;
    /// Put a file with `content` over each `target`.
    fn replace_files(&mut self, files: &[(&str, &str)]) -> io::Result<()>;
}

/// Where a user's shared storage is, as apps see it and underneath.
fn storage_of(user: u32) -> (String, String) {
    (
        format!("/storage/emulated/{user}"),
        format!("/data/media/{user}"),
    )
}

/// The sandbox directory of `uid`.
#[cfg(test)]
fn sandbox_of(uid: u32) -> String {
    let (_, media) = storage_of(uid / PER_USER_RANGE);
    format!("{media}/{}/{uid}", dirs::SANDBOXES)
}

/// Where the sandbox of `uid` is mounted.
pub fn storage_root_of(uid: u32) -> String {
    storage_of(uid / PER_USER_RANGE).0
}

/// Give the namespace `sys` works in the sandbox of `uid`, under which run
/// `packages`. `files` are the kernel information files to replace, each
/// with its content.
///
/// The errors name the step, never a path: paths have package names in
/// them, which are not to be logged.
pub fn apply<S: Sys>(
    sys: &mut S,
    uid: u32,
    packages: &[String],
    files: &[(&str, &str)],
) -> anyhow::Result<()> {
    let (root, media) = storage_of(uid / PER_USER_RANGE);
    sys.make_private("/storage")
        .context("detaching /storage from the system's mounts")?;

    // Taken before the sandbox covers them.
    let mut own = Vec::new();
    for kind in OWN_KINDS {
        for package in packages {
            let tree = sys
                .clone_tree(&format!("{media}/Android/{kind}/{package}"))
                .with_context(|| format!("taking the app's own {kind} directory"))?;
            if let Some(tree) = tree {
                own.push((*kind, package.as_str(), tree));
            }
        }
    }

    match sys.bind_sandbox(uid, &root) {
        Ok(()) => {
            let top = sys.open_dir(&root).context("opening the sandbox")?;
            for (kind, package, tree) in own {
                let mut dir = sys
                    .make_dir(&top, "Android", uid)
                    .context("making Android in the sandbox")?;
                for name in [kind, package] {
                    dir = sys
                        .make_dir(&dir, name, uid)
                        .with_context(|| format!("making the app's own {kind} directory"))?;
                }
                sys.attach(tree, &dir)
                    .with_context(|| format!("mounting the app's own {kind} directory"))?;
            }
        }
        // No sandbox directory: the user's storage is still locked after a
        // restart, when only apps that ask to run that early are started,
        // or the directory was removed. The app gets nothing rather than
        // the real storage.
        Err(e) if e.kind() == io::ErrorKind::NotFound || e.raw_os_error() == Some(libc::ENOKEY) => {
            match sys.empty(&root) {
                // Nor is there any storage to hide.
                Err(e) if e.kind() == io::ErrorKind::NotFound => {}
                done => done.context("hiding shared storage")?,
            }
        }
        Err(e) => return Err(e).context("mounting the sandbox"),
    }

    for name in sys.storage_entries().context("listing /storage")? {
        if !NOT_VOLUMES.contains(&name.as_str()) {
            sys.empty(&format!("/storage/{name}"))
                .context("hiding a storage volume")?;
        }
    }
    sys.replace_files(files)
        .context("replacing kernel information files")
}

// ---- kernel information ----------------------------------------------------

/// The file replaced. An app must have been able to read the real one: a
/// file that was unreadable and now has content gives the sandbox away. Not
/// `/proc/sys/kernel/random/boot_id`: apps do not start with a made-up one.
pub const CPUINFO: &str = "/proc/cpuinfo";

/// The lines of `/proc/cpuinfo` that are kept, by their key: how many
/// processors there are and what instructions they have, which programs
/// need. Everything that names the chip (model, vendor, part, revision,
/// hardware, serial) is left out.
const CPUINFO_KEPT: &[&str] = &[
    "processor",
    "BogoMIPS",
    "Features",
    "flags",
    "CPU architecture",
];

/// `/proc/cpuinfo` without what identifies the chip.
pub fn cpuinfo_template(real: &str) -> String {
    let mut out = String::new();
    for line in real.lines() {
        let key = line.split(':').next().unwrap_or("").trim();
        let blank = line.trim().is_empty();
        // One empty line between processors, as in the real file.
        if (blank && !out.is_empty() && !out.ends_with("\n\n")) || CPUINFO_KEPT.contains(&key) {
            out.push_str(line);
            out.push('\n');
        }
    }
    out
}

// ---- on the device ---------------------------------------------------------

const OPEN_TREE_CLONE: libc::c_uint = 1;
const AT_RECURSIVE: libc::c_uint = 0x8000;
const MOVE_MOUNT_F_EMPTY_PATH: libc::c_uint = 0x4;
const MOVE_MOUNT_T_EMPTY_PATH: libc::c_uint = 0x40;

/// Labelled as files on shared storage are, which apps may read; the label
/// a tmpfs has by default they may not.
const TMPFS_LABEL: &str = "context=u:object_r:fuse:s0";

/// The mounts made with system calls, in the mount namespace of the calling
/// thread.
pub struct Kernel {
    /// An empty directory of the daemon's, where the replaced files are
    /// assembled before each is mounted in its place.
    scratch: PathBuf,
}

impl Kernel {
    pub fn new(scratch: &Path) -> Self {
        Self {
            scratch: scratch.to_path_buf(),
        }
    }
}

fn c(text: &str) -> io::Result<CString> {
    CString::new(text).map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))
}

fn checked(rc: libc::c_long) -> io::Result<libc::c_long> {
    if rc < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(rc)
    }
}

fn mount(
    source: Option<&str>,
    target: &str,
    fstype: Option<&str>,
    flags: libc::c_ulong,
    data: Option<&str>,
) -> io::Result<()> {
    let source = source.map(c).transpose()?;
    let fstype = fstype.map(c).transpose()?;
    let data = data.map(c).transpose()?;
    let target = c(target)?;
    let ptr = |s: &Option<CString>| s.as_ref().map_or(std::ptr::null(), |s| s.as_ptr());
    checked(libc::c_long::from(unsafe {
        libc::mount(
            ptr(&source),
            target.as_ptr(),
            ptr(&fstype),
            flags,
            ptr(&data).cast(),
        )
    }))
    .map(drop)
}

impl Sys for Kernel {
    type Tree = OwnedFd;
    type Dir = Dir;

    fn make_private(&mut self, path: &str) -> io::Result<()> {
        mount(None, path, None, libc::MS_REC | libc::MS_PRIVATE, None)
    }

    fn clone_tree(&mut self, path: &str) -> io::Result<Option<OwnedFd>> {
        let flags = OPEN_TREE_CLONE
            | AT_RECURSIVE
            | libc::AT_SYMLINK_NOFOLLOW as libc::c_uint
            | libc::O_CLOEXEC as libc::c_uint;
        let path = c(path)?;
        let fd =
            unsafe { libc::syscall(libc::SYS_open_tree, libc::AT_FDCWD, path.as_ptr(), flags) };
        match checked(fd) {
            // SAFETY: just opened, and owned by nothing else.
            Ok(fd) => Ok(Some(unsafe { OwnedFd::from_raw_fd(fd as i32) })),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e),
        }
    }

    fn bind_sandbox(&mut self, uid: u32, onto: &str) -> io::Result<()> {
        // Opened and checked first, then mounted by its descriptor: what is
        // mounted is what was checked, whatever is done to the path since.
        let (_, media) = storage_of(uid / PER_USER_RANGE);
        let sandbox = dirs::open_sandbox(Path::new(&media), uid)?;
        let by_descriptor = format!("/proc/thread-self/fd/{}", sandbox.as_raw_fd());
        mount(Some(&by_descriptor), onto, None, libc::MS_BIND, None)
    }

    fn empty(&mut self, onto: &str) -> io::Result<()> {
        mount(
            Some(TMPFS_SOURCE),
            onto,
            Some("tmpfs"),
            libc::MS_RDONLY,
            Some(&format!("size=4k,mode=0555,{TMPFS_LABEL}")),
        )
    }

    fn open_dir(&mut self, path: &str) -> io::Result<Dir> {
        Dir::open(Path::new(path))
    }

    fn make_dir(&mut self, parent: &Dir, name: &str, uid: u32) -> io::Result<Dir> {
        Ok(parent.make_shared(name, uid, dirs::MEDIA_RW)?.0)
    }

    fn attach(&mut self, tree: OwnedFd, onto: &Dir) -> io::Result<()> {
        let flags = MOVE_MOUNT_F_EMPTY_PATH | MOVE_MOUNT_T_EMPTY_PATH;
        checked(unsafe {
            libc::syscall(
                libc::SYS_move_mount,
                tree.as_raw_fd(),
                c"".as_ptr(),
                onto.as_raw_fd(),
                c"".as_ptr(),
                flags,
            )
        })
        .map(drop)
    }

    fn storage_entries(&mut self) -> io::Result<Vec<String>> {
        fs::read_dir("/storage")?
            .map(|entry| Ok(entry?.file_name().to_string_lossy().into_owned()))
            .collect()
    }

    fn replace_files(&mut self, files: &[(&str, &str)]) -> io::Result<()> {
        if files.is_empty() {
            return Ok(());
        }
        let scratch = self.scratch.to_string_lossy().into_owned();
        mount(
            Some(TMPFS_SOURCE),
            &scratch,
            Some("tmpfs"),
            0,
            Some(&format!("size=64k,mode=0755,{TMPFS_LABEL}")),
        )?;
        let replaced = files.iter().try_for_each(|(target, content)| {
            let name = Path::new(target)
                .file_name()
                .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?;
            let file = self.scratch.join(name);
            fs::write(&file, content)?;
            mount(
                Some(&file.to_string_lossy()),
                target,
                None,
                libc::MS_BIND,
                None,
            )
        });
        // The files stay mounted where they were bound; the directory they
        // were made in is not left behind in the app's namespace.
        let scratch = c(&scratch)?;
        let detached = checked(libc::c_long::from(unsafe {
            libc::umount2(scratch.as_ptr(), libc::MNT_DETACH)
        }));
        replaced.and(detached.map(drop))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Records the steps instead of carrying them out.
    #[derive(Default)]
    struct Recorder {
        steps: Vec<String>,
        /// The directories that exist, for `clone_tree`.
        existing: Vec<&'static str>,
        storage: Vec<&'static str>,
        /// What binding the sandbox fails with, if it is to fail.
        bind_error: Option<i32>,
        symlink_at: Option<&'static str>,
    }

    impl Sys for Recorder {
        type Tree = String;
        type Dir = String;

        fn make_private(&mut self, path: &str) -> io::Result<()> {
            self.steps.push(format!("private {path}"));
            Ok(())
        }

        fn clone_tree(&mut self, path: &str) -> io::Result<Option<String>> {
            if !self.existing.contains(&path) {
                return Ok(None);
            }
            self.steps.push(format!("clone {path}"));
            Ok(Some(path.to_string()))
        }

        fn bind_sandbox(&mut self, uid: u32, onto: &str) -> io::Result<()> {
            if let Some(errno) = self.bind_error {
                return Err(io::Error::from_raw_os_error(errno));
            }
            self.steps.push(format!("bind {} {onto}", sandbox_of(uid)));
            Ok(())
        }

        fn empty(&mut self, onto: &str) -> io::Result<()> {
            self.steps.push(format!("empty {onto}"));
            Ok(())
        }

        fn open_dir(&mut self, path: &str) -> io::Result<String> {
            Ok(path.to_string())
        }

        fn make_dir(&mut self, parent: &String, name: &str, uid: u32) -> io::Result<String> {
            if self.symlink_at == Some(name) {
                return Err(io::Error::from_raw_os_error(libc::ELOOP));
            }
            let dir = format!("{parent}/{name}");
            self.steps.push(format!("mkdir {dir} {uid}"));
            Ok(dir)
        }

        fn attach(&mut self, tree: String, onto: &String) -> io::Result<()> {
            self.steps.push(format!("attach {tree} {onto}"));
            Ok(())
        }

        fn storage_entries(&mut self) -> io::Result<Vec<String>> {
            Ok(self.storage.iter().map(|name| name.to_string()).collect())
        }

        fn replace_files(&mut self, files: &[(&str, &str)]) -> io::Result<()> {
            for (target, _) in files {
                self.steps.push(format!("replace {target}"));
            }
            Ok(())
        }
    }

    const FILES: &[(&str, &str)] = &[(CPUINFO, "cpu")];

    #[test]
    fn the_sandbox_replaces_storage_and_keeps_the_apps_own_directories() {
        let mut sys = Recorder {
            existing: vec![
                "/data/media/10/Android/data/com.a",
                "/data/media/10/Android/media/com.b",
            ],
            storage: vec!["emulated", "self", "1A2B-3C4D"],
            ..Default::default()
        };
        let packages = ["com.a".to_string(), "com.b".to_string()];
        apply(&mut sys, 1_010_100, &packages, FILES).unwrap();
        assert_eq!(
            sys.steps,
            [
                "private /storage",
                // Before the sandbox is mounted over them.
                "clone /data/media/10/Android/data/com.a",
                "clone /data/media/10/Android/media/com.b",
                "bind /data/media/10/.bb_sandbox/1010100 /storage/emulated/10",
                "mkdir /storage/emulated/10/Android 1010100",
                "mkdir /storage/emulated/10/Android/data 1010100",
                "mkdir /storage/emulated/10/Android/data/com.a 1010100",
                "attach /data/media/10/Android/data/com.a /storage/emulated/10/Android/data/com.a",
                "mkdir /storage/emulated/10/Android 1010100",
                "mkdir /storage/emulated/10/Android/media 1010100",
                "mkdir /storage/emulated/10/Android/media/com.b 1010100",
                "attach /data/media/10/Android/media/com.b /storage/emulated/10/Android/media/com.b",
                "empty /storage/1A2B-3C4D",
                "replace /proc/cpuinfo",
            ]
        );
    }

    #[test]
    fn an_app_without_a_sandbox_directory_gets_empty_storage() {
        for errno in [libc::ENOENT, libc::ENOKEY] {
            let mut sys = Recorder {
                // Not reachable either while the storage is locked, but
                // never mounted over nothing if it were.
                existing: vec!["/data/media/0/Android/data/com.a"],
                storage: vec!["emulated", "self"],
                bind_error: Some(errno),
                ..Default::default()
            };
            apply(&mut sys, 10100, &["com.a".to_string()], FILES).unwrap();
            assert_eq!(
                sys.steps,
                [
                    "private /storage",
                    "clone /data/media/0/Android/data/com.a",
                    "empty /storage/emulated/0",
                    "replace /proc/cpuinfo",
                ]
            );
        }
    }

    #[test]
    fn a_failed_step_fails_the_whole_and_names_no_path() {
        let mut sys = Recorder {
            bind_error: Some(libc::EACCES),
            ..Default::default()
        };
        let e = apply(&mut sys, 10100, &["com.a".to_string()], FILES).unwrap_err();
        assert_eq!(e.to_string(), "mounting the sandbox");

        // A symbolic link where the app's own directory is to be mounted.
        let mut sys = Recorder {
            existing: vec!["/data/media/0/Android/data/com.a"],
            symlink_at: Some("data"),
            ..Default::default()
        };
        let e = apply(&mut sys, 10100, &["com.a".to_string()], FILES).unwrap_err();
        assert_eq!(e.to_string(), "making the app's own data directory");
        assert!(!format!("{e:#}").contains("com.a"));
        assert!(!sys.steps.iter().any(|step| step.starts_with("attach")));
    }

    #[test]
    fn cpuinfo_keeps_the_processors_and_their_features_only() {
        let arm = "processor\t: 0\nBogoMIPS\t: 38.40\nFeatures\t: fp asimd\n\
                   CPU implementer\t: 0x41\nCPU architecture: 8\nCPU variant\t: 0x1\n\
                   CPU part\t: 0xd05\nCPU revision\t: 0\n\n\
                   processor\t: 1\nBogoMIPS\t: 38.40\nFeatures\t: fp asimd\n\
                   CPU implementer\t: 0x41\nCPU architecture: 8\n\n\
                   Hardware\t: Qualcomm Technologies, Inc SM8550\nSerial\t\t: 0123456789abcdef\n";
        assert_eq!(
            cpuinfo_template(arm),
            "processor\t: 0\nBogoMIPS\t: 38.40\nFeatures\t: fp asimd\nCPU architecture: 8\n\n\
             processor\t: 1\nBogoMIPS\t: 38.40\nFeatures\t: fp asimd\nCPU architecture: 8\n\n"
        );
        let x86 = "processor\t: 0\nvendor_id\t: GenuineIntel\nmodel name\t: Some CPU @ 3GHz\n\
                   flags\t\t: fpu sse2\ncache size\t: 512 KB\n\n";
        assert_eq!(
            cpuinfo_template(x86),
            "processor\t: 0\nflags\t\t: fpu sse2\n\n"
        );
        assert_eq!(cpuinfo_template(""), "");
    }
}
