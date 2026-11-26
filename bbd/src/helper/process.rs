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

//! Launch and reap the privileged framework helper.

use std::{
    fs, io,
    io::Write as _,
    os::{fd::AsRawFd as _, unix::process::CommandExt as _},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::Arc,
};

use anyhow::Context as _;

const APP_PROCESS: &str = "/system/bin/app_process";
const MAX_LINE: usize = 4095;

/// Load code only from the root-owned module, since the installed app can change.
fn helper_apk() -> anyhow::Result<PathBuf> {
    let daemon = std::env::current_exe().context("locating the daemon")?;
    let apk = daemon
        .parent()
        .and_then(Path::parent)
        .map(|module| module.join("helper.apk"))
        .context("the daemon is not installed in a module directory")?;
    anyhow::ensure!(apk.is_file(), "helper.apk is missing; reinstall the module");
    Ok(apk)
}

pub(super) fn spawn() -> anyhow::Result<Child> {
    let mut command = Command::new(APP_PROCESS);
    command.env("CLASSPATH", helper_apk()?).args([
        "-Xms4m",
        "-Xmx32m",
        "/system/bin",
        "li.mercury.bigbrother.catalog.FrameworkHelper",
    ]);
    command.process_group(0);
    let parent = std::process::id();
    // A former daemon's child must never finish a queued mutation after restart.
    unsafe {
        command.pre_exec(move || {
            if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) == -1 {
                return Err(io::Error::last_os_error());
            }
            if libc::getppid() as u32 != parent {
                return Err(io::Error::from_raw_os_error(libc::ESRCH));
            }
            Ok(())
        });
    }
    let child = command
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .context("starting app_process")?;
    // The resident helper can be replaced if Android needs its memory.
    if let Err(e) = fs::write(format!("/proc/{}/oom_score_adj", child.id()), "0") {
        eprintln!("bbd: framework helper OOM score: {:?}", e.kind());
    }
    Ok(child)
}

pub(super) fn kill_helper(child: &mut Child) {
    // The helper's group includes any blocked settings command it launched.
    unsafe { libc::kill(-(child.id() as i32), libc::SIGKILL) };
    // The group can already be gone when stdout closes.
    let _ = child.wait();
}

#[derive(Clone)]
pub(crate) struct Pipe(Arc<ChildStdin>);

impl Pipe {
    pub(crate) fn new(input: ChildStdin) -> io::Result<Self> {
        if unsafe { libc::fcntl(input.as_raw_fd(), libc::F_SETFL, libc::O_NONBLOCK) } == -1 {
            return Err(io::Error::last_os_error());
        }
        Ok(Self(Arc::new(input)))
    }

    /// A pipe write up to PIPE_BUF either writes a full frame or fails immediately.
    pub(crate) fn send(&self, line: &str) -> anyhow::Result<()> {
        anyhow::ensure!(
            line.len() <= MAX_LINE && !line.contains(['\r', '\n']),
            "invalid framework helper frame"
        );
        (&*self.0)
            .write_all(format!("{line}\n").as_bytes())
            .context("writing a framework helper command")
    }
}
