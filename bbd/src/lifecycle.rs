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

//! Stop the module processes before their files are replaced or removed.

use std::{fs, io, io::Read as _, os::unix::fs::MetadataExt as _, path::Path};

use anyhow::{Context as _, ensure};

use crate::sandbox::gate::Process;

pub fn stop(module: &Path) -> anyhow::Result<()> {
    // The supervisor normally stops its child and waits for the records to flush.
    for (name, program) in [("supervisor", "supervise.sh"), ("bbd", "bin/bbd")] {
        let file = Path::new("/data/adb/big_brother").join(format!("{name}.pid"));
        let text = match fs::read_to_string(&file) {
            Err(e) if e.kind() == io::ErrorKind::NotFound => continue,
            result => result?,
        };
        let pid: i32 = text.trim().parse().context("invalid module pid file")?;
        ensure!(pid > 1, "invalid module pid");
        match stop_one(pid, &module.join(program), name == "supervisor") {
            Err(e) if matches!(e.raw_os_error(), Some(libc::ENOENT | libc::ESRCH)) => {}
            result => result?,
        }
        // The supervisor may already have removed its child's pid file.
        if let Err(e) = fs::remove_file(file)
            && e.kind() != io::ErrorKind::NotFound
        {
            return Err(e.into());
        }
    }
    Ok(())
}

fn stop_one(pid: i32, expected: &Path, supervisor: bool) -> io::Result<()> {
    let process = Process::open(pid)?;
    let dir = Path::new("/proc").join(pid.to_string());
    if process.exited(0)? || dir.metadata()?.uid() != 0 {
        return Ok(());
    }
    let mut cmdline = Vec::new();
    fs::File::open(dir.join("cmdline"))?
        .take(8192)
        .read_to_end(&mut cmdline)?;
    if matches_command(
        &cmdline,
        expected.as_os_str().as_encoded_bytes(),
        supervisor,
    ) {
        process.signal(libc::SIGTERM)?;
        if !process.exited(5000)? {
            process.signal(libc::SIGKILL)?;
            if !process.exited(1000)? {
                return Err(io::ErrorKind::TimedOut.into());
            }
        }
    }
    Ok(())
}

fn matches_command(cmdline: &[u8], path: &[u8], supervisor: bool) -> bool {
    let mut args = cmdline.split(|b| *b == 0);
    let command = args.next().unwrap_or_default();
    let argument = args.next().unwrap_or_default();
    if !supervisor {
        return command == path && argument == b"run";
    }
    let is_shell = |name: &[u8]| matches!(name, b"sh" | b"ash" | b"mksh");
    let program = command.rsplit(|b| *b == b'/').next().unwrap_or_default();
    if matches!(program, b"busybox" | b"toybox") {
        is_shell(argument) && args.next() == Some(path)
    } else {
        is_shell(program) && argument == path
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stop_recognizes_the_supervisor_from_android_or_the_root_manager_shell() {
        let path = b"/module/supervise.sh";
        assert!(matches_command(
            b"/system/bin/sh\0/module/supervise.sh\0",
            path,
            true
        ));
        assert!(matches_command(
            b"/data/adb/magisk/busybox\0ash\0/module/supervise.sh\0",
            path,
            true
        ));
        assert!(!matches_command(
            b"sh\0-c\0/module/supervise.sh\0",
            path,
            true
        ));
        assert!(!matches_command(b"log\0/module/supervise.sh\0", path, true));
    }
}
