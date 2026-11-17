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

//! Directories reached without trusting what is in them.
//!
//! A sandbox directory is the sandboxed app's to write in, and the daemon
//! works in it as root. A path walked there by name could be turned, by a
//! symbolic link the app put in it, into any file on the device. So every
//! directory is opened by one name at a time from its parent's descriptor,
//! never through a link, and is changed through its own descriptor.

use std::{
    ffi::CString,
    io,
    os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd},
    path::Path,
};

/// Group of the files on shared storage (`media_rw`).
pub const MEDIA_RW: u32 = 1023;

/// Under `/data/media/<user>`: the sandboxes of that user's apps. Owned by
/// root and closed to everyone else, so that an app cannot replace its own
/// sandbox, or reach another's.
pub const SANDBOXES: &str = ".bb_sandbox";

/// What Android creates on shared storage and apps expect to be there:
/// writing a file to one that is missing fails.
const STANDARD: &[&str] = &[
    "Download",
    "Pictures",
    "DCIM",
    "Documents",
    "Music",
    "Movies",
];

/// The mode of a directory on shared storage: its owner and `media_rw`, and
/// what is created in it keeps the group.
const SHARED_MODE: libc::mode_t = 0o2770;

/// An open directory.
pub struct Dir(OwnedFd);

impl Dir {
    /// Open the directory at `path`, which is trusted up to its last name:
    /// that one must not be a symbolic link.
    pub fn open(path: &Path) -> io::Result<Self> {
        let path = CString::new(path.as_os_str().as_encoded_bytes())
            .map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))?;
        Self::open_in(libc::AT_FDCWD, &path)
    }

    fn open_in(parent: RawFd, name: &CString) -> io::Result<Self> {
        let flags = libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC;
        let fd = unsafe { libc::openat(parent, name.as_ptr(), flags) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: just opened, and owned by nothing else.
        Ok(Self(unsafe { OwnedFd::from_raw_fd(fd) }))
    }

    /// The directory `name` in this one, created if it is not there, and
    /// whether it was. Anything else by that name (a symbolic link, a file)
    /// is an error.
    pub fn make(&self, name: &str) -> io::Result<(Self, bool)> {
        let name = component(name)?;
        let created = match unsafe { libc::mkdirat(self.0.as_raw_fd(), name.as_ptr(), 0o700) } {
            0 => true,
            _ => {
                let e = io::Error::last_os_error();
                if e.kind() != io::ErrorKind::AlreadyExists {
                    return Err(e);
                }
                false
            }
        };
        Ok((Self::open_in(self.0.as_raw_fd(), &name)?, created))
    }

    /// The directory `name` in this one, which must be there. Anything else
    /// by that name (a symbolic link, a file) is an error.
    pub fn open_child(&self, name: &str) -> io::Result<Self> {
        Self::open_in(self.0.as_raw_fd(), &component(name)?)
    }

    /// The directory's owner and its permission bits.
    fn owner_and_mode(&self) -> io::Result<(u32, libc::mode_t)> {
        let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
        if unsafe { libc::fstat(self.0.as_raw_fd(), stat.as_mut_ptr()) } != 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: filled in by the successful call.
        let stat = unsafe { stat.assume_init() };
        Ok((stat.st_uid, stat.st_mode & 0o7777))
    }

    /// Give the directory to `uid` and `gid`, with `mode`.
    pub fn own(&self, uid: u32, gid: u32, mode: libc::mode_t) -> io::Result<()> {
        let fd = self.0.as_raw_fd();
        if unsafe { libc::fchown(fd, uid, gid) } != 0 || unsafe { libc::fchmod(fd, mode) } != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    /// As [`Self::make`], for a directory the app is to use: its own, with
    /// the group and mode of shared storage.
    pub fn make_shared(&self, name: &str, uid: u32, gid: u32) -> io::Result<(Self, bool)> {
        let (dir, created) = self.make(name)?;
        dir.own(uid, gid, SHARED_MODE)?;
        Ok((dir, created))
    }
}

impl AsRawFd for Dir {
    fn as_raw_fd(&self) -> RawFd {
        self.0.as_raw_fd()
    }
}

/// One name of a path: not empty, not `.` or `..`, and without a slash.
fn component(name: &str) -> io::Result<CString> {
    if name.is_empty() || name == "." || name == ".." || name.contains('/') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "not a single file name",
        ));
    }
    CString::new(name).map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))
}

/// Make sure the sandbox of `uid` exists under `media`, the directory behind
/// a user's shared storage (`/data/media/<user>`): what the app will see as
/// the root of shared storage, owned by it and `gid`. The directories apps
/// expect are put in a new one only: an existing sandbox is the app's, and
/// is left as the app has it.
pub fn prepare(media: &Path, uid: u32, gid: u32) -> io::Result<()> {
    prepare_under(media, (0, 0), uid, gid)
}

/// [`prepare`], with the directory of the sandboxes given to `keeper`
/// rather than root, which only root can give it to.
fn prepare_under(media: &Path, keeper: (u32, u32), uid: u32, gid: u32) -> io::Result<()> {
    let (sandboxes, created) = Dir::open(media)?.make(SANDBOXES)?;
    // One that is there and another's is not taken over: whoever made it
    // may hold it open still. The daemon's own is given its owner and mode
    // again, which it lacks if the daemon stopped right after making it.
    if !created {
        kept_by(&sandboxes, keeper.0)?;
    }
    sandboxes.own(keeper.0, keeper.1, 0o700)?;
    let (sandbox, created) = sandboxes.make_shared(&uid.to_string(), uid, gid)?;
    if created {
        for name in STANDARD {
            sandbox.make_shared(name, uid, gid)?;
        }
    }
    Ok(())
}

/// The sandbox of `uid` under `media`, as [`prepare`] made it.
///
/// `media` is writable by the media provider, through which an app with
/// access to all files can rename what is in it. A sandbox directory put
/// there in place of the daemon's would be mounted as another app's
/// storage, for whoever made it to read. So the directory of the sandboxes
/// must be root's and closed to others, which nobody else can make it, and
/// the sandbox the app's own.
pub fn open_sandbox(media: &Path, uid: u32) -> io::Result<Dir> {
    open_sandbox_kept_by(media, 0, uid)
}

fn open_sandbox_kept_by(media: &Path, keeper: u32, uid: u32) -> io::Result<Dir> {
    let sandboxes = Dir::open(media)?.open_child(SANDBOXES)?;
    kept_by(&sandboxes, keeper)?;
    let sandbox = sandboxes.open_child(&uid.to_string())?;
    if sandbox.owner_and_mode()?.0 != uid {
        return Err(not_the_daemons());
    }
    Ok(sandbox)
}

/// Check that the directory of the sandboxes belongs to `keeper` alone:
/// its own, and closed to its group and to others. (The directory it is
/// made in passes its group on, with the bit that says so.)
fn kept_by(sandboxes: &Dir, keeper: u32) -> io::Result<()> {
    let (owner, mode) = sandboxes.owner_and_mode()?;
    if owner != keeper || mode & 0o077 != 0 {
        return Err(not_the_daemons());
    }
    Ok(())
}

fn not_the_daemons() -> io::Error {
    io::Error::new(
        io::ErrorKind::PermissionDenied,
        "the sandbox directory is not as the daemon made it",
    )
}

#[cfg(test)]
mod tests {
    use std::{fs, os::unix::fs::MetadataExt as _, path::PathBuf};

    use super::*;

    fn test_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("bbd-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn a_directory_is_made_once_and_opened_after() {
        let dir = test_dir("dirs-make");
        let top = Dir::open(&dir).unwrap();
        assert!(top.make("a").unwrap().1);
        let (a, created) = top.make("a").unwrap();
        assert!(!created);
        a.make("b").unwrap();
        assert!(dir.join("a/b").is_dir());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_symbolic_link_is_not_followed() {
        let dir = test_dir("dirs-link");
        fs::create_dir(dir.join("real")).unwrap();
        std::os::unix::fs::symlink(dir.join("real"), dir.join("link")).unwrap();
        let top = Dir::open(&dir).unwrap();
        assert!(top.make("link").is_err());
        assert!(Dir::open(&dir.join("link")).is_err());
        // Nor one further down, when it is walked a name at a time.
        let (real, _) = top.make("real").unwrap();
        std::os::unix::fs::symlink("/", dir.join("real/out")).unwrap();
        assert!(real.make("out").is_err());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_file_in_place_of_a_directory_is_an_error() {
        let dir = test_dir("dirs-file");
        fs::write(dir.join("file"), "").unwrap();
        assert!(Dir::open(&dir).unwrap().make("file").is_err());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn only_single_names_are_taken() {
        let dir = test_dir("dirs-names");
        let top = Dir::open(&dir).unwrap();
        for name in ["", ".", "..", "a/b", "/etc", "a\0b"] {
            assert!(top.make(name).is_err(), "{name:?}");
        }
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_new_sandbox_gets_the_directories_apps_expect() {
        let dir = test_dir("dirs-prepare");
        let (uid, gid) = unsafe { (libc::getuid(), libc::getgid()) };
        prepare_under(&dir, (uid, gid), uid, gid).unwrap();
        let mode = |path: &Path| fs::metadata(path).unwrap().mode() & 0o7777;
        assert_eq!(mode(&dir.join(".bb_sandbox")), 0o700);
        let sandbox = dir.join(format!(".bb_sandbox/{uid}"));
        assert_eq!(mode(&sandbox), 0o2770);
        for name in STANDARD {
            assert_eq!(mode(&sandbox.join(name)), 0o2770, "{name}");
        }
        // What the app removed is not put back.
        fs::remove_dir(sandbox.join("Music")).unwrap();
        prepare_under(&dir, (uid, gid), uid, gid).unwrap();
        assert!(!sandbox.join("Music").exists());
        open_sandbox_kept_by(&dir, uid, uid).unwrap();
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_sandbox_directory_someone_else_made_is_refused() {
        use std::os::unix::fs::PermissionsExt as _;

        let dir = test_dir("dirs-planted");
        let (uid, gid) = unsafe { (libc::getuid(), libc::getgid()) };
        // None yet: told apart from one that is not right.
        let e = open_sandbox_kept_by(&dir, uid, uid).err().unwrap();
        assert_eq!(e.kind(), io::ErrorKind::NotFound);

        // Open to others, as a directory made through the media provider
        // would be.
        fs::create_dir_all(dir.join(format!(".bb_sandbox/{uid}"))).unwrap();
        fs::set_permissions(dir.join(".bb_sandbox"), fs::Permissions::from_mode(0o770)).unwrap();
        let e = open_sandbox_kept_by(&dir, uid, uid).err().unwrap();
        assert_eq!(e.kind(), io::ErrorKind::PermissionDenied);
        assert!(prepare_under(&dir, (uid, gid), uid, gid).is_err());

        // Closed, but another's; and the daemon's, with another's sandbox.
        fs::set_permissions(dir.join(".bb_sandbox"), fs::Permissions::from_mode(0o700)).unwrap();
        assert!(open_sandbox_kept_by(&dir, uid + 1, uid).is_err());
        fs::create_dir(dir.join(".bb_sandbox/1")).unwrap();
        if uid != 1 {
            assert!(open_sandbox_kept_by(&dir, uid, 1).is_err());
        }
        // A link in its place.
        fs::remove_dir(dir.join(format!(".bb_sandbox/{uid}"))).unwrap();
        std::os::unix::fs::symlink("/", dir.join(format!(".bb_sandbox/{uid}"))).unwrap();
        assert!(open_sandbox_kept_by(&dir, uid, uid).is_err());
        fs::remove_dir_all(dir).unwrap();
    }
}
