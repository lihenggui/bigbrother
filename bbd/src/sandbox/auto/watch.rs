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

//! Watch for changes to the package list and users' app directories.

use std::{
    collections::BTreeSet,
    ffi::CString,
    io,
    os::{
        fd::{AsRawFd, FromRawFd, OwnedFd},
        unix::ffi::OsStrExt as _,
    },
    path::{Path, PathBuf},
};

use tokio::io::{Interest, unix::AsyncFd};

/// The directory of Android's package list, which is replaced by a new file
/// on every change.
pub const PACKAGE_DIR: &str = "/data/system";

/// The package list's name in [`PACKAGE_DIR`].
const PACKAGE_LIST: &[u8] = b"packages.list";

/// Bytes read from the watch at a time: room for a dozen events.
const EVENTS_SIZE: usize = 4096;

/// What is watched in the package list's directory: renamed into it, which
/// is how Android writes the list, and written in place, which it may do.
const LIST_MASK: u32 = libc::IN_MOVED_TO | libc::IN_CLOSE_WRITE;
/// What is watched in the directory of all users' data: a user added.
const USERS_MASK: u32 = libc::IN_CREATE | libc::IN_MOVED_TO | libc::IN_ONLYDIR;
/// What is watched in a user's data directory: an app's directory made.
/// Only that it is there counts, so a change of what is in it or of its
/// attributes, which an app can make, does not wake the daemon.
const USER_MASK: u32 = libc::IN_CREATE | libc::IN_MOVED_TO | libc::IN_ONLYDIR;

/// Watches for apps being installed, while newly installed apps are put in
/// the sandbox.
///
/// Android writes its package list to a new file and renames that over the
/// old one, so the list's directory is watched, and only the list counts
/// there; the other files in it change now and then, and each change costs
/// a read of the event. The list is written before the app's data
/// directories are made, which is how an app is known to be installed for a
/// user, and need not be written again after. So each user's data directory
/// is watched for new entries too, and the directory of all users for new
/// users. Those change only when apps are installed and users added.
#[derive(Default)]
pub struct Watch {
    inotify: Option<Inotify>,
    /// Watching failed, which was logged: it is not tried again until this
    /// is turned off and on, or the daemon starts again.
    failed: bool,
}

#[derive(Default)]
pub struct Change {
    pub packages: bool,
    profiles: bool,
}

struct Inotify {
    fd: AsyncFd<OwnedFd>,
    /// The watch of the package list's directory.
    list: i32,
    /// The watch of the directory of all users' data, and that directory.
    users: Option<(i32, PathBuf)>,
    /// The watches of each user's data directory.
    user_dirs: BTreeSet<i32>,
    /// Failures already reported while this watch is armed.
    failed_user_dirs: BTreeSet<Vec<u8>>,
}

impl Watch {
    /// Watch the package list while `on`. Only automatic isolation also
    /// needs to watch the users' data directories. True when the
    /// watch has just begun: what changed before it did was not seen, and is
    /// to be looked at.
    pub fn arm(&mut self, on: bool, list_dir: &Path, users_dir: Option<&Path>) -> bool {
        if !on {
            self.inotify = None;
            self.failed = false;
            return false;
        }
        if self
            .inotify
            .as_ref()
            .is_some_and(|watch| watch.users.is_some() == users_dir.is_some())
            || self.failed
        {
            return false;
        }
        match Inotify::new(list_dir, users_dir) {
            Ok(inotify) => {
                self.inotify = Some(inotify);
                true
            }
            Err(e) => {
                self.give_up(&e);
                false
            }
        }
    }

    pub fn armed(&self) -> bool {
        self.inotify.is_some()
    }

    /// Wait until an app may have been installed. Never, while not armed.
    /// Every event there is is read, so that one change is reported once.
    pub async fn changed(&mut self) -> io::Result<Change> {
        let Some(inotify) = &mut self.inotify else {
            return std::future::pending().await;
        };
        let mut buffer = [0; EVENTS_SIZE];
        loop {
            let mut events = Vec::new();
            {
                let mut ready = inotify.fd.readable().await?;
                loop {
                    match ready.try_io(|fd| read(fd.get_ref(), &mut buffer)) {
                        Ok(Ok(n)) => events.extend(parse_events(&buffer[..n])),
                        Ok(Err(e)) if e.kind() == io::ErrorKind::Interrupted => {}
                        Ok(Err(e)) => return Err(e),
                        // Nothing more to read.
                        Err(_) => break,
                    }
                }
            }
            let changed = inotify.note(&events);
            if changed.packages || changed.profiles {
                return Ok(changed);
            }
        }
    }

    /// Stop watching, after `e`.
    pub fn give_up(&mut self, e: &io::Error) {
        eprintln!(
            "bbd: sandbox: cannot watch for installed apps ({e}); new apps are found as they start, or when the daemon next starts"
        );
        self.inotify = None;
        self.failed = true;
    }
}

impl Inotify {
    fn new(list_dir: &Path, users_dir: Option<&Path>) -> io::Result<Self> {
        let fd = unsafe { libc::inotify_init1(libc::IN_NONBLOCK | libc::IN_CLOEXEC) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: just opened, and owned by nothing else.
        let fd = unsafe { OwnedFd::from_raw_fd(fd) };
        let list = add_watch(&fd, list_dir, LIST_MASK)?;
        let users = users_dir
            .map(|dir| add_watch(&fd, dir, USERS_MASK).map(|wd| (wd, dir.to_owned())))
            .transpose()?;
        // SAFETY: the OwnedFd owns its fd, which stays open and unchanged
        // for as long as the AsyncFd owns the OwnedFd.
        let fd = unsafe { AsyncFd::register_with_interest(fd, Interest::READABLE) }
            .map_err(|e| e.into_parts().1)?;
        let mut inotify = Self {
            fd,
            list,
            users,
            user_dirs: BTreeSet::new(),
            failed_user_dirs: BTreeSet::new(),
        };
        inotify.watch_users();
        Ok(inotify)
    }

    /// Watch the data directory of every user there is. One that cannot be
    /// watched is still covered by the package list and the gate.
    fn watch_users(&mut self) {
        let Some((_, path)) = &self.users else { return };
        let users: Vec<Vec<u8>> = std::fs::read_dir(path)
            .into_iter()
            .flatten()
            .flatten()
            .map(|entry| entry.file_name().as_bytes().to_vec())
            .collect();
        for user in users {
            self.watch_user(&user);
        }
    }

    fn watch_user(&mut self, name: &[u8]) {
        let is_user = !name.is_empty() && name.iter().all(u8::is_ascii_digit);
        if !is_user {
            return;
        }
        let Some((_, path)) = &self.users else { return };
        let dir = path.join(std::ffi::OsStr::from_bytes(name));
        match add_watch(self.fd.get_ref(), &dir, USER_MASK) {
            Ok(wd) => {
                self.user_dirs.insert(wd);
            }
            Err(e) if !self.failed_user_dirs.contains(name) => {
                self.failed_user_dirs.insert(name.to_vec());
                let user = String::from_utf8_lossy(name);
                eprintln!("bbd: sandbox: cannot watch for installed apps for user {user}: {e}");
            }
            Err(_) => {}
        }
    }

    fn note(&mut self, events: &[Event]) -> Change {
        let mut changed = Change::default();
        for event in events {
            if event.mask & libc::IN_Q_OVERFLOW != 0 {
                // Any of what was lost may have been an install, or a new
                // user.
                self.watch_users();
                changed.packages = true;
                changed.profiles |= self.users.is_some();
            } else if event.mask & libc::IN_IGNORED != 0 {
                // A user removed: the kernel dropped the watch.
                self.user_dirs.remove(&event.wd);
            } else if event.wd == self.list {
                changed.packages |= event.name == PACKAGE_LIST;
            } else if self.users.as_ref().is_some_and(|(wd, _)| event.wd == *wd) {
                self.watch_user(&event.name);
                changed.profiles = true;
            } else if self.user_dirs.contains(&event.wd) {
                // The directory itself changing says nothing.
                changed.profiles |= !event.name.is_empty();
            }
        }
        changed
    }
}

fn add_watch(fd: &OwnedFd, dir: &Path, mask: u32) -> io::Result<i32> {
    let dir = CString::new(dir.as_os_str().as_bytes())?;
    let wd = unsafe { libc::inotify_add_watch(fd.as_raw_fd(), dir.as_ptr(), mask) };
    if wd < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(wd)
}

fn read(fd: &OwnedFd, into: &mut [u8]) -> io::Result<usize> {
    let n = unsafe { libc::read(fd.as_raw_fd(), into.as_mut_ptr().cast(), into.len()) };
    usize::try_from(n).map_err(|_| io::Error::last_os_error())
}

/// An event read from the watch.
#[derive(Debug, PartialEq, Eq)]
struct Event {
    wd: i32,
    mask: u32,
    /// The entry of the watched directory it is about; empty for the
    /// directory itself.
    name: Vec<u8>,
}

/// The events in `bytes`, as read from an inotify descriptor.
fn parse_events(bytes: &[u8]) -> Vec<Event> {
    let header = size_of::<libc::inotify_event>();
    let mut events = Vec::new();
    let mut rest = bytes;
    while rest.len() >= header {
        // SAFETY: a header's bytes, read unaligned; any bytes are one.
        let event = unsafe { rest.as_ptr().cast::<libc::inotify_event>().read_unaligned() };
        let end = rest.len().min(header + event.len as usize);
        // The name is padded with NULs.
        let name = rest[header..end].split(|&b| b == 0).next().unwrap_or(&[]);
        events.push(Event {
            wd: event.wd,
            mask: event.mask,
            name: name.to_vec(),
        });
        rest = &rest[end..];
    }
    events
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::super::tests::{Fixture, NEW, sandboxed};
    use super::*;

    /// An event as the kernel writes it.
    fn event(wd: i32, mask: u32, name: &str) -> Vec<u8> {
        // Names are padded to a multiple of the header's alignment.
        let len = if name.is_empty() {
            0
        } else {
            (name.len() + 1).next_multiple_of(4)
        };
        let header = libc::inotify_event {
            wd,
            mask,
            cookie: 0,
            len: len as u32,
        };
        let mut bytes = unsafe {
            std::slice::from_raw_parts(
                (&raw const header).cast::<u8>(),
                size_of::<libc::inotify_event>(),
            )
        }
        .to_vec();
        bytes.extend(name.bytes());
        bytes.resize(bytes.len() + len - name.len(), 0);
        bytes
    }

    #[test]
    fn events_are_read_with_their_names() {
        let bytes = [
            event(1, libc::IN_MOVED_TO, "packages.list"),
            event(3, libc::IN_ATTRIB, ""),
            event(3, libc::IN_CREATE | libc::IN_ISDIR, "com.example.new"),
        ]
        .concat();
        assert_eq!(
            parse_events(&bytes),
            [
                Event {
                    wd: 1,
                    mask: libc::IN_MOVED_TO,
                    name: b"packages.list".to_vec()
                },
                Event {
                    wd: 3,
                    mask: libc::IN_ATTRIB,
                    name: vec![]
                },
                Event {
                    wd: 3,
                    mask: libc::IN_CREATE | libc::IN_ISDIR,
                    name: b"com.example.new".to_vec()
                },
            ]
        );
        assert!(parse_events(&[]).is_empty());
        assert!(parse_events(&[0; 5]).is_empty());
    }

    /// A package list's directory and a users' data directory with user 0,
    /// as Android has them.
    fn layout(f: &Fixture) -> (PathBuf, PathBuf) {
        let system = f.dir.join("system");
        let users = f.dir.join("user_de");
        std::fs::create_dir_all(&system).unwrap();
        std::fs::create_dir_all(users.join("0")).unwrap();
        (system, users)
    }

    /// Whether the watch reports a change within a short while.
    async fn reports(watch: &mut Watch) -> bool {
        match tokio::time::timeout(Duration::from_millis(200), watch.changed()).await {
            Ok(changed) => {
                changed.unwrap();
                true
            }
            Err(_) => false,
        }
    }

    #[tokio::test]
    async fn a_failed_user_watch_is_reported_once_and_can_still_be_added() {
        let f = Fixture::new("auto-watch-failure");
        let (system, users) = layout(&f);
        let mut watch = Inotify::new(&system, Some(&users)).unwrap();
        let watched = watch.user_dirs.len();

        watch.watch_user(b"10");
        watch.watch_user(b"10");
        assert_eq!(watch.failed_user_dirs, BTreeSet::from([b"10".to_vec()]));
        assert_eq!(watch.user_dirs.len(), watched);

        std::fs::create_dir(users.join("10")).unwrap();
        watch.watch_user(b"10");
        assert_eq!(watch.user_dirs.len(), watched + 1);
    }

    #[tokio::test]
    async fn a_package_only_watch_ignores_profile_changes() {
        let f = Fixture::new("gate-package-watch");
        let (system, users) = layout(&f);
        let mut watch = Watch::default();
        assert!(watch.arm(true, &system, None));
        std::fs::create_dir(users.join("0/com.example.new")).unwrap();
        assert!(!reports(&mut watch).await);

        std::fs::write(system.join("packages.list"), "x").unwrap();
        let change = tokio::time::timeout(Duration::from_secs(2), watch.changed())
            .await
            .unwrap()
            .unwrap();
        assert!(change.packages);
        assert!(!change.profiles);
    }

    #[tokio::test]
    async fn installs_are_seen_in_the_list_and_the_data_directories() {
        let f = Fixture::new("auto-watch");
        let (system, users) = layout(&f);
        let mut watch = Watch::default();
        assert!(!watch.arm(false, &system, Some(&users)));
        assert!(watch.arm(true, &system, Some(&users)));
        assert!(!watch.arm(true, &system, Some(&users)));
        assert!(watch.armed());

        // As Android writes the list: a new file renamed over the old one.
        std::fs::write(system.join("appops.xml"), "x").unwrap();
        assert!(!reports(&mut watch).await);
        std::fs::write(system.join("packages.list.tmp"), "x").unwrap();
        std::fs::rename(
            system.join("packages.list.tmp"),
            system.join("packages.list"),
        )
        .unwrap();
        assert!(reports(&mut watch).await);
        // Reported once.
        assert!(!reports(&mut watch).await);

        // An app's data directory made.
        let app = users.join("0/com.example.new");
        std::fs::create_dir(&app).unwrap();
        assert!(reports(&mut watch).await);
        // What the app does with it is its own business: its attributes,
        // which it can change, and what it keeps there.
        std::fs::set_permissions(&app, std::os::unix::fs::PermissionsExt::from_mode(0o700))
            .unwrap();
        assert!(!reports(&mut watch).await);
        std::fs::write(app.join("prefs.xml"), "x").unwrap();
        assert!(!reports(&mut watch).await);

        // A user added, and an app installed for it.
        std::fs::create_dir(users.join("10")).unwrap();
        assert!(reports(&mut watch).await);
        std::fs::create_dir(users.join("10/com.example.new")).unwrap();
        assert!(reports(&mut watch).await);
        // And removed again.
        std::fs::remove_dir_all(users.join("10")).unwrap();
        assert!(!reports(&mut watch).await);

        assert!(!watch.arm(false, &system, Some(&users)));
        assert!(!watch.armed());
        // What cannot be watched is given up on until turned off and on.
        assert!(!watch.arm(true, &f.dir.join("absent"), Some(&users)));
        assert!(!watch.arm(true, &system, Some(&users)));
        watch.arm(false, &system, Some(&users));
        assert!(watch.arm(true, &system, Some(&users)));
    }

    #[test]
    fn an_idle_loop_is_woken_by_each_change() {
        let f = Fixture::new("auto-wake");
        let (system, users) = layout(&f);
        // As the daemon runs its main loop.
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let looked_at = runtime.block_on(async {
            let mut watch = Watch::default();
            assert!(watch.arm(true, &system, Some(&users)));
            // Two writes of the list, the second while the first is being
            // looked at.
            let writer = {
                let system = system.clone();
                std::thread::spawn(move || {
                    for (pause, list) in [(100, "first"), (10, "second")] {
                        std::thread::sleep(Duration::from_millis(pause));
                        std::fs::write(system.join("packages.list.tmp"), list).unwrap();
                        std::fs::rename(
                            system.join("packages.list.tmp"),
                            system.join("packages.list"),
                        )
                        .unwrap();
                    }
                })
            };
            let (_commands, mut command) = tokio::sync::mpsc::channel::<()>(1);
            let mut flush = tokio::time::interval(Duration::from_secs(30));
            flush.tick().await;
            // Well before the flush, so that only the watch can wake the
            // loop; how soon it does depends on how busy the machine is.
            let until = tokio::time::Instant::now() + Duration::from_secs(10);
            let mut looked_at = Vec::new();
            while looked_at.last().map(String::as_str) != Some("second") {
                tokio::select! {
                    _ = flush.tick() => {}
                    _ = command.recv() => {}
                    changed = watch.changed(), if watch.armed() => {
                        changed.unwrap();
                        // A look at the list takes a while.
                        looked_at.push(std::fs::read_to_string(system.join("packages.list")).unwrap());
                        std::thread::sleep(Duration::from_millis(30));
                    }
                    () = tokio::time::sleep_until(until) => break,
                }
            }
            writer.join().unwrap();
            looked_at
        });
        // The loop caught up with the last write: when it came while the
        // first was looked at, it woke the loop again.
        assert_eq!(
            looked_at.last().map(String::as_str),
            Some("second"),
            "{looked_at:?}"
        );
    }

    #[tokio::test]
    async fn an_app_whose_data_directory_comes_after_the_list_is_put_in() {
        let mut f = Fixture::new("auto-late-data");
        let mut sandbox = f.sandbox();
        sandbox.set_auto(&f.writer, &mut f.packages, true).unwrap();
        let mut watch = Watch::default();
        assert!(watch.arm(true, &f.dir, Some(&f.user_data())));
        let new_two = "com.example.two 10231 0 /d default:targetSdkVersion=35 none 0 1 1 @null";

        // The list first: the app is installed for no user yet.
        f.list(&[
            "com.android.chrome 10150 0 /d default:partition=product none 0 1 1 @system",
            "com.example.old 10200 0 /d default none 0 1 1 com.android.vending",
            NEW,
            new_two,
        ]);
        assert!(reports(&mut watch).await);
        sandbox.isolate_new(&f.writer, &mut f.packages);
        assert!(sandboxed(&sandbox).is_empty());

        // Then its data directory, and the list is not written again.
        f.install(0, "com.example.new");
        assert!(reports(&mut watch).await);
        sandbox.isolate_new(&f.writer, &mut f.packages);
        assert_eq!(sandboxed(&sandbox), [10230]);

        // A user added later, and an app installed for it.
        std::fs::create_dir(f.user_data().join("10")).unwrap();
        assert!(reports(&mut watch).await);
        sandbox.isolate_new(&f.writer, &mut f.packages);
        f.install(10, "com.example.two");
        assert!(reports(&mut watch).await);
        sandbox.isolate_new(&f.writer, &mut f.packages);
        assert_eq!(sandboxed(&sandbox), [10230, 1_010_231]);
    }
}
