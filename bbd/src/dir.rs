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

//! Directory descriptor operations safe to use with app-owned directories.

use std::{
    ffi::CString,
    io,
    os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd},
    path::Path,
};

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
    pub fn owner_and_mode(&self) -> io::Result<(u32, libc::mode_t)> {
        let stat = self.stat()?;
        Ok((stat.st_uid, stat.st_mode & 0o7777))
    }

    pub fn stat(&self) -> io::Result<libc::stat> {
        let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
        if unsafe { libc::fstat(self.0.as_raw_fd(), stat.as_mut_ptr()) } != 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: filled in by the successful call.
        Ok(unsafe { stat.assume_init() })
    }

    /// Give the directory to `uid` and `gid`, with `mode`.
    pub fn own(&self, uid: u32, gid: u32, mode: libc::mode_t) -> io::Result<()> {
        let fd = self.0.as_raw_fd();
        if unsafe { libc::fchown(fd, uid, gid) } != 0 || unsafe { libc::fchmod(fd, mode) } != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
}

impl AsRawFd for Dir {
    fn as_raw_fd(&self) -> RawFd {
        self.0.as_raw_fd()
    }
}

/// One name of a path: not empty, not `.` or `..`, and without a slash.
pub(crate) fn component(name: &str) -> io::Result<CString> {
    if name.is_empty() || name == "." || name == ".." || name.contains('/') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "not a single file name",
        ));
    }
    CString::new(name).map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::*;

    fn test_dir(name: &str) -> std::path::PathBuf {
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
}
