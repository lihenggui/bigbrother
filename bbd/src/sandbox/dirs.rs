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
    collections::BTreeMap,
    ffi::CString,
    fs, io,
    os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd},
    path::{Path, PathBuf},
    sync::Mutex,
};

use crate::{packages::user_of, sync::lock};

/// Group of the files on shared storage (`media_rw`).
pub const MEDIA_RW: u32 = 1023;

// Under `/data/media/<user>` is the directory of the sandboxes of that
// user's apps, one directory in it per app. It is owned by root and closed
// to everyone else, so that an app cannot replace its own sandbox, or reach
// another's.
//
// An app can read the mounts of its own processes, where the path of its
// sandbox shows. So the directory is not named after the project: its name
// is drawn at random when it is made, and it is found again by a file in it
// (`MARK`) that only root can see.

/// The file that marks the directory of the sandboxes as the daemon's.
/// Nobody but root can look into that directory, so the name is not seen.
const MARK: &str = "big_brother";

/// How many names are drawn for a new directory before giving up: each is
/// taken already only by a rare chance.
const NAME_DRAWS: u32 = 8;

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

    /// As [`Self::make`], for a directory the app is to use: its own, with
    /// the group and mode of shared storage.
    pub fn make_shared(&self, name: &str, uid: u32, gid: u32) -> io::Result<(Self, bool)> {
        let (dir, created) = self.make(name)?;
        dir.own(uid, gid, SHARED_MODE)?;
        Ok((dir, created))
    }

    /// Whether [`MARK`] is in this directory: a file of `keeper`'s, not a
    /// link to one.
    fn is_marked(&self, keeper: u32) -> bool {
        let Ok(name) = component(MARK) else {
            return false;
        };
        let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
        let found = unsafe {
            libc::fstatat(
                self.0.as_raw_fd(),
                name.as_ptr(),
                stat.as_mut_ptr(),
                libc::AT_SYMLINK_NOFOLLOW,
            )
        } == 0;
        // SAFETY: filled in by the successful call.
        found && {
            let stat = unsafe { stat.assume_init() };
            stat.st_mode & libc::S_IFMT == libc::S_IFREG && stat.st_uid == keeper
        }
    }

    /// Put [`MARK`] in this directory.
    fn mark(&self) -> io::Result<()> {
        let name = component(MARK)?;
        let flags = libc::O_WRONLY | libc::O_CREAT | libc::O_NOFOLLOW | libc::O_CLOEXEC;
        let fd = unsafe {
            libc::openat(
                self.0.as_raw_fd(),
                name.as_ptr(),
                flags,
                0o600 as libc::c_uint,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: just opened, and owned by nothing else; closed on drop.
        drop(unsafe { OwnedFd::from_raw_fd(fd) });
        Ok(())
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

/// A missing directory can belong to a temporarily unavailable Android user.
/// Only a reachable directory with a different owner or token rules out a match. Operational failures
/// must not silently release a sandbox or discard permission recovery state.
pub fn matches_install(root: &Path, package: &str, uid: u32, expected: &str) -> io::Result<bool> {
    match install_id(root, package, uid, false) {
        Ok(id) => Ok(id == expected),
        Err(e) if matches!(e.raw_os_error(), Some(libc::ENODATA | libc::ESTALE)) => Ok(false),
        Err(e) => Err(e),
    }
}

/// An installation cookie on the package's DE directory. Android controls the
/// parent directory and only a privileged caller can change a trusted xattr.
pub fn install_id(root: &Path, package: &str, uid: u32, create: bool) -> io::Result<String> {
    Ok(pin_install(root, package, uid, create)?.1)
}

pub fn pin_install(
    root: &Path,
    package: &str,
    uid: u32,
    create: bool,
) -> io::Result<(Dir, String)> {
    let create = || {
        if create {
            super::random_bytes().map(Some)
        } else {
            Ok(None)
        }
    };
    pin_install_with(root, package, uid, create)
}

/// Record the same installation cookie on another member of a verified
/// shared UID, so it can own the existing sandbox if the primary package is
/// removed. An existing cookie is never replaced.
pub(super) fn share_install(root: &Path, package: &str, uid: u32, id: &str) -> io::Result<bool> {
    let create = || {
        u128::from_str_radix(id, 16)
            .map(|id| Some(id.to_be_bytes()))
            .map_err(|_| io::Error::from(io::ErrorKind::InvalidData))
    };
    pin_install_with(root, package, uid, create).map(|(_, cookie)| cookie == id)
}

fn pin_install_with(
    root: &Path,
    package: &str,
    uid: u32,
    create: impl FnOnce() -> io::Result<Option<[u8; 16]>>,
) -> io::Result<(Dir, String)> {
    let dir = Dir::open(root)?
        .open_child(&user_of(uid).to_string())?
        .open_child(package)?;
    if dir.owner_and_mode()?.0 != uid {
        return Err(io::Error::from_raw_os_error(libc::ESTALE));
    }
    let fd = dir.as_raw_fd();
    let key = c"trusted.big_brother.install";
    let mut bytes = [0u8; 16];
    let read = |buf: &mut [u8; 16]| unsafe {
        libc::fgetxattr(fd, key.as_ptr(), buf.as_mut_ptr().cast(), buf.len())
    };
    let mut n = read(&mut bytes);
    if n < 0
        && io::Error::last_os_error().raw_os_error() == Some(libc::ENODATA)
        && let Some(new) = create()?
    {
        if unsafe {
            libc::fsetxattr(
                fd,
                key.as_ptr(),
                new.as_ptr().cast(),
                new.len(),
                libc::XATTR_CREATE,
            )
        } < 0
        {
            let e = io::Error::last_os_error();
            if e.raw_os_error() != Some(libc::EEXIST) {
                return Err(e);
            }
        }
        n = read(&mut bytes);
    }
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    if n != 16 {
        return Err(io::ErrorKind::InvalidData.into());
    }
    Ok((dir, format!("{:032x}", u128::from_be_bytes(bytes))))
}

/// Make sure the sandbox of `uid` exists under `media`, the directory behind
/// a user's shared storage (`/data/media/<user>`): what the app will see as
/// the root of shared storage, owned by it and `gid`. The directories apps
/// expect are put in a new one only: an existing sandbox is the app's, and
/// is left as the app has it.
pub fn prepare(media: &Path, uid: u32, gid: u32, generation: &str) -> io::Result<()> {
    prepare_under(media, (0, 0), uid, gid, generation)
}

/// [`prepare`], with the directory of the sandboxes given to `keeper`
/// rather than root, which only root can give it to.
fn prepare_under(
    media: &Path,
    keeper: (u32, u32),
    uid: u32,
    gid: u32,
    generation: &str,
) -> io::Result<()> {
    let top = Dir::open(media)?;
    let (_, sandboxes) = sandboxes(media, &top, keeper.0, Some(keeper))?
        .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
    let (sandbox, created) = sandboxes.make_shared(generation, uid, gid)?;
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
pub fn open_sandbox(media: &Path, uid: u32, generation: &str) -> io::Result<Dir> {
    open_sandbox_kept_by(media, 0, uid, generation)
}

fn open_sandbox_kept_by(media: &Path, keeper: u32, uid: u32, generation: &str) -> io::Result<Dir> {
    let top = Dir::open(media)?;
    let Some((_, sandboxes)) = sandboxes(media, &top, keeper, None)? else {
        return Err(io::Error::from(io::ErrorKind::NotFound));
    };
    let sandbox = sandboxes.open_child(generation)?;
    if sandbox.owner_and_mode()?.0 != uid {
        return Err(not_the_daemons());
    }
    Ok(sandbox)
}

/// The name of the directory of the sandboxes under `media`; `None` if
/// there is none yet, or none can be reached (before the user unlocks the
/// device).
pub fn sandboxes_name(media: &Path) -> io::Result<Option<String>> {
    let top = Dir::open(media)?;
    Ok(sandboxes(media, &top, 0, None)?.map(|(name, _)| name))
}

/// The name of the directory of the sandboxes found under each `media`.
///
/// Held while one is looked for or made, which the gate thread
/// and the main loop both do: two of them at once could each make one. It
/// is never held for longer than a few calls on the directory itself.
static FOUND: Mutex<BTreeMap<PathBuf, String>> = Mutex::new(BTreeMap::new());

/// The directory of the sandboxes in `top`, which is `media`, and its name:
/// the one of `keeper`'s that bears the mark. If there is none, one is made
/// for `make` (its owner and group) or `None` is the answer.
fn sandboxes(
    media: &Path,
    top: &Dir,
    keeper: u32,
    make: Option<(u32, u32)>,
) -> io::Result<Option<(String, Dir)>> {
    let mut found = lock(&FOUND);
    if let Some(name) = found.get(media) {
        match top.open_child(name) {
            Ok(dir) if kept_by(&dir, keeper).is_ok() && dir.is_marked(keeper) => {
                return Ok(Some((name.clone(), dir)));
            }
            // Removed, or no longer as the daemon made it: looked for anew.
            _ => {
                found.remove(media);
            }
        }
    }
    let sandboxes = match find_marked(media, top, keeper)? {
        Some(marked) => Some(marked),
        None => make.map(|keeper| make_sandboxes(top, keeper)).transpose()?,
    };
    if let Some((name, _)) = &sandboxes {
        found.insert(media.to_path_buf(), name.clone());
    }
    Ok(sandboxes)
}

/// The hidden directory in `top` (which is `media`) that is `keeper`'s,
/// closed to others and marked. There is one at most, being made only
/// while [`FOUND`] is held and none is found; should there be more, the
/// first by name, so that it is always the same one.
fn find_marked(media: &Path, top: &Dir, keeper: u32) -> io::Result<Option<(String, Dir)>> {
    let mut names: Vec<String> = fs::read_dir(media)?
        .filter_map(|entry| match entry {
            Ok(entry) => entry.file_name().into_string().ok(),
            Err(e) => {
                eprintln!("bbd: sandbox: cannot read a sandbox directory entry: {e}");
                None
            }
        })
        .filter(|name| name.starts_with('.'))
        .collect();
    names.sort();
    for name in names {
        // Not a directory, or not one to open: not the one looked for.
        let Ok(dir) = top.open_child(&name) else {
            continue;
        };
        if kept_by(&dir, keeper).is_ok() && dir.is_marked(keeper) {
            return Ok(Some((name, dir)));
        }
    }
    Ok(None)
}

/// A new directory of the sandboxes in `top`, `keeper`'s and marked, under
/// a name drawn at random. A name that is taken, by a directory or by a
/// file an app put there, is drawn again.
fn make_sandboxes(top: &Dir, keeper: (u32, u32)) -> io::Result<(String, Dir)> {
    for _ in 0..NAME_DRAWS {
        let name = draw_name()?;
        let sandboxes = match top.make(&name) {
            Ok((sandboxes, true)) => sandboxes,
            Ok((_, false)) => continue,
            Err(e) if is_not_a_directory(&e) => continue,
            Err(e) => return Err(e),
        };
        // Marked first, so that it is found again whatever happens next.
        sandboxes.mark()?;
        sandboxes.own(keeper.0, keeper.1, 0o700)?;
        return Ok((name, sandboxes));
    }
    Err(no_free_name())
}

/// Whether opening a directory failed because nothing, or something else
/// than a directory (a file, a link), is there by that name.
fn is_not_a_directory(e: &io::Error) -> bool {
    e.kind() == io::ErrorKind::NotFound
        || matches!(e.raw_os_error(), Some(libc::ENOTDIR | libc::ELOOP))
}

/// A hidden name, drawn at random: one dot and eight hex digits.
fn draw_name() -> io::Result<String> {
    let bytes = super::random_bytes::<4>()?;
    Ok(format!(".{:08x}", u32::from_le_bytes(bytes)))
}

fn no_free_name() -> io::Error {
    io::Error::new(
        io::ErrorKind::AlreadyExists,
        "every name drawn for the sandbox directory was taken",
    )
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
    use std::{
        fs,
        os::unix::fs::{MetadataExt as _, PermissionsExt as _},
        path::PathBuf,
    };

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

    /// The hidden directories in `dir`, by name.
    fn hidden(dir: &Path) -> Vec<String> {
        let mut names: Vec<String> = fs::read_dir(dir)
            .unwrap()
            .map(|entry| entry.unwrap().file_name().into_string().unwrap())
            .filter(|name| name.starts_with('.'))
            .collect();
        names.sort();
        names
    }

    fn ids() -> (u32, u32) {
        unsafe { (libc::getuid(), libc::getgid()) }
    }

    #[test]
    fn a_new_sandbox_gets_the_directories_apps_expect() {
        let dir = test_dir("dirs-prepare");
        let (uid, gid) = ids();
        prepare_under(&dir, (uid, gid), uid, gid, &uid.to_string()).unwrap();
        let mode = |path: &Path| fs::metadata(path).unwrap().mode() & 0o7777;
        let [sandboxes] = hidden(&dir).try_into().unwrap();
        assert_eq!(mode(&dir.join(&sandboxes)), 0o700);
        let sandbox = dir.join(format!("{sandboxes}/{uid}"));
        assert_eq!(mode(&sandbox), 0o2770);
        for name in STANDARD {
            assert_eq!(mode(&sandbox.join(name)), 0o2770, "{name}");
        }
        // What the app removed is not put back.
        fs::remove_dir(sandbox.join("Music")).unwrap();
        prepare_under(&dir, (uid, gid), uid, gid, &uid.to_string()).unwrap();
        assert!(!sandbox.join("Music").exists());
        open_sandbox_kept_by(&dir, uid, uid, &uid.to_string()).unwrap();
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn the_directory_of_the_sandboxes_is_named_at_random_and_found_again() {
        let dir = test_dir("dirs-name");
        let (uid, gid) = ids();
        prepare_under(&dir, (uid, gid), uid, gid, &uid.to_string()).unwrap();
        let [name] = hidden(&dir).try_into().unwrap();
        assert_eq!(name.len(), 9, "{name}");
        assert!(name[1..].chars().all(|c| c.is_ascii_hexdigit()), "{name}");
        // Found again rather than made anew.
        prepare_under(&dir, (uid, gid), uid, gid, &uid.to_string()).unwrap();
        assert_eq!(hidden(&dir), std::slice::from_ref(&name));
        open_sandbox_kept_by(&dir, uid, uid, &uid.to_string()).unwrap();

        // A hidden directory of the same owner without the mark is not it.
        fs::remove_file(dir.join(format!("{name}/{MARK}"))).unwrap();
        let e = open_sandbox_kept_by(&dir, uid, uid, &uid.to_string())
            .err()
            .unwrap();
        assert_eq!(e.kind(), io::ErrorKind::NotFound);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_sandbox_directory_someone_else_made_is_refused() {
        let dir = test_dir("dirs-planted");
        let (uid, gid) = ids();
        // None yet: told apart from one that is not right.
        let e = open_sandbox_kept_by(&dir, uid, uid, &uid.to_string())
            .err()
            .unwrap();
        assert_eq!(e.kind(), io::ErrorKind::NotFound);

        // Hidden, marked and with a sandbox in it, but open to others, as a
        // directory made through the media provider would be: left alone,
        // and not used.
        let planted = ".planted";
        fs::create_dir_all(dir.join(format!("{planted}/{uid}"))).unwrap();
        fs::write(dir.join(format!("{planted}/{MARK}")), "").unwrap();
        fs::set_permissions(dir.join(planted), fs::Permissions::from_mode(0o770)).unwrap();
        let e = open_sandbox_kept_by(&dir, uid, uid, &uid.to_string())
            .err()
            .unwrap();
        assert_eq!(e.kind(), io::ErrorKind::NotFound);
        prepare_under(&dir, (uid, gid), uid, gid, &uid.to_string()).unwrap();
        let names = hidden(&dir);
        assert_eq!(names.len(), 2);
        let name = names.into_iter().find(|name| name != planted).unwrap();

        // Marked, but open to others.
        fs::set_permissions(dir.join(&name), fs::Permissions::from_mode(0o770)).unwrap();
        assert!(open_sandbox_kept_by(&dir, uid, uid, &uid.to_string()).is_err());
        // Closed, but another's; and the daemon's, with another's sandbox.
        fs::set_permissions(dir.join(&name), fs::Permissions::from_mode(0o700)).unwrap();
        assert!(open_sandbox_kept_by(&dir, uid + 1, uid, &uid.to_string()).is_err());
        fs::create_dir(dir.join(format!("{name}/1"))).unwrap();
        if uid != 1 {
            assert!(open_sandbox_kept_by(&dir, uid, 1, &uid.to_string()).is_err());
        }
        // A link in its place.
        fs::remove_dir_all(dir.join(format!("{name}/{uid}"))).unwrap();
        std::os::unix::fs::symlink("/", dir.join(format!("{name}/{uid}"))).unwrap();
        assert!(open_sandbox_kept_by(&dir, uid, uid, &uid.to_string()).is_err());
        // A link in place of the mark.
        fs::remove_file(dir.join(format!("{name}/{MARK}"))).unwrap();
        std::os::unix::fs::symlink("/etc/hosts", dir.join(format!("{name}/{MARK}"))).unwrap();
        let e = open_sandbox_kept_by(&dir, uid, uid, &uid.to_string())
            .err()
            .unwrap();
        assert_eq!(e.kind(), io::ErrorKind::NotFound);
        fs::remove_dir_all(dir).unwrap();
    }
}
