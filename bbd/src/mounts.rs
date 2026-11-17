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

//! Maps a filesystem's device number to where it is mounted.
//!
//! The kernel side reports a file as "device X, path relative to that
//! filesystem's root". To turn that into an absolute path we need the mount
//! point, which `/proc/self/mountinfo` provides:
//!   `<id> <parent> <major>:<minor> <root> <mount point> <options> ...`

use std::{
    collections::HashMap,
    fs,
    time::{Duration, Instant},
};

const MOUNTINFO: &str = "/proc/self/mountinfo";
const RELOAD_INTERVAL: Duration = Duration::from_secs(10);

/// Kernel-internal `dev_t` encoding (`MKDEV`): 12 bits major, 20 bits minor.
fn kernel_dev(major: u32, minor: u32) -> u32 {
    (major << 20) | minor
}

pub struct Mounts {
    by_dev: HashMap<u32, String>,
    loaded_at: Option<Instant>,
}

impl Mounts {
    pub fn new() -> Self {
        let mut this = Self {
            by_dev: HashMap::new(),
            loaded_at: None,
        };
        this.reload();
        this
    }

    fn reload(&mut self) {
        self.loaded_at = Some(Instant::now());
        match fs::read_to_string(MOUNTINFO) {
            Ok(text) => self.by_dev = parse(&text),
            Err(e) => eprintln!("bbd: cannot read {MOUNTINFO}: {e}"),
        }
    }

    /// Mount point of the filesystem with kernel device number `dev`.
    /// Volumes can appear after startup (SD card inserted, user unlocked), so
    /// an unknown device triggers a rate-limited re-read.
    pub fn mount_point(&mut self, dev: u32) -> Option<&str> {
        if !self.by_dev.contains_key(&dev)
            && self
                .loaded_at
                .is_none_or(|t| t.elapsed() >= RELOAD_INTERVAL)
        {
            self.reload();
        }
        self.by_dev.get(&dev).map(String::as_str)
    }
}

fn parse(text: &str) -> HashMap<u32, String> {
    let mut map = HashMap::new();
    for line in text.lines() {
        let mut fields = line.split(' ');
        let (Some(dev), Some(root), Some(mount_point)) =
            (fields.nth(2), fields.next(), fields.next())
        else {
            continue;
        };
        // Only mounts of the filesystem's root: for a bind mount of a
        // subdirectory, root-relative paths don't start at the mount point.
        if root != "/" {
            continue;
        }
        let Some((major, minor)) = dev.split_once(':') else {
            continue;
        };
        let (Ok(major), Ok(minor)) = (major.parse(), minor.parse()) else {
            continue;
        };
        // A filesystem mounted in several places: keep the first, any works
        // because paths are canonicalized afterwards.
        map.entry(kernel_dev(major, minor))
            .or_insert_with(|| mount_point.to_string());
    }
    map
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_mountinfo() {
        let map = parse(
            "25 1 253:5 / / ro,relatime shared:1 - ext4 /dev/block/dm-5 ro,seclabel\n\
             4221 4220 0:142 / /mnt/user/0/emulated rw,nosuid master:55 - fuse /dev/fuse rw,user_id=0\n\
             4300 4220 0:142 / /storage/emulated rw,nosuid master:55 - fuse /dev/fuse rw,user_id=0\n\
             4400 4220 254:48 /0/Android/data /mnt/user/0/emulated/0/Android/data rw - f2fs /dev/block/dm-48 rw\n\
             garbage\n",
        );
        assert_eq!(map[&kernel_dev(0, 142)], "/mnt/user/0/emulated");
        assert_eq!(map[&kernel_dev(253, 5)], "/");
        // Subdirectory bind mount is skipped.
        assert!(!map.contains_key(&kernel_dev(254, 48)));
        assert_eq!(map.len(), 2);
    }
}
