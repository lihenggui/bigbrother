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

//! Runs the small programs the app ships for the daemon.
//!
//! Some things can only be learned from inside the Android runtime: the
//! framework's transaction codes, the system's permission usage records.
//! The app's APK carries a class for each (`li.mercury.bigbrother.catalog`);
//! the daemon runs them with `app_process`, as root and outside any app,
//! from the copy of the APK that was installed with the module.

use std::{
    fs, io,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
};

use anyhow::Context as _;

const APP_PROCESS: &str = "/system/bin/app_process";
const CLASS_PACKAGE: &str = "li.mercury.bigbrother.catalog";
/// The module's own copy of the app's APK, next to `bin/`.
const HELPER_APK: &str = "helper.apk";

/// Where the helper classes are loaded from: the copy of the APK the module
/// installer placed in the module's directory.
///
/// Deliberately not the installed app. This code runs as root, and the
/// installed app is whatever APK holds the package name at the moment, which
/// a root daemon must not take on trust; the module directory is writable by
/// root only.
fn helper_apk() -> anyhow::Result<PathBuf> {
    let daemon = std::env::current_exe().context("locating the daemon's own path")?;
    let apk = daemon
        .parent()
        .and_then(Path::parent)
        .map(|module| module.join(HELPER_APK))
        .context("the daemon is not installed in a module directory")?;
    anyhow::ensure!(
        apk.is_file(),
        "{} is missing; reinstall the module",
        apk.display()
    );
    Ok(apk)
}

fn command<'a>(class: &str, args: impl Iterator<Item = &'a str>) -> anyhow::Result<Command> {
    let mut command = Command::new(APP_PROCESS);
    command
        .env("CLASSPATH", helper_apk()?)
        .arg("/system/bin")
        .arg(format!("{CLASS_PACKAGE}.{class}"))
        .args(args);
    Ok(command)
}

fn spawn_for_output(command: Command) -> io::Result<tokio::process::Child> {
    tokio::process::Command::from(command)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        // The catalog is optional and its caller can stop waiting for it.
        .kill_on_drop(true)
        .spawn()
}

/// Run `class` to completion and return what it printed. Dropping the wait
/// kills the helper, including one waiting for the system to come up.
pub async fn run<'a>(class: &str, args: impl Iterator<Item = &'a str>) -> anyhow::Result<String> {
    let output = spawn_for_output(command(class, args)?)
        .with_context(|| format!("starting {APP_PROCESS}"))?
        .wait_with_output()
        .await
        .with_context(|| format!("running {APP_PROCESS}"))?;
    let printed = String::from_utf8_lossy(&output.stdout).into_owned();
    anyhow::ensure!(
        !printed.trim().is_empty(),
        "{class} printed nothing ({}): {}",
        output.status,
        String::from_utf8_lossy(&output.stderr).trim()
    );
    Ok(printed)
}

/// Start `class` and leave it running, with its input, output and errors
/// connected to the daemon. A helper started this way exits when its input
/// closes.
pub fn spawn<'a>(class: &str, args: impl Iterator<Item = &'a str>) -> anyhow::Result<Child> {
    let child = command(class, args)?
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .with_context(|| format!("starting {APP_PROCESS}"))?;
    // The daemon is started exempt from the out-of-memory killer, and so
    // would be the helper, a Java runtime of 40 MB or more. It is started
    // again when it ends, so it may be killed like any other process.
    let _ = fs::write(format!("/proc/{}/oom_score_adj", child.id()), "0");
    Ok(child)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn cancelling_a_helper_wait_ends_the_child() {
        let mut command = Command::new("sleep");
        command.arg("60");
        let child = spawn_for_output(command).unwrap();
        let process = crate::sandbox::gate::Process::open(child.id().unwrap() as i32).unwrap();
        let (started, waiting) = tokio::sync::oneshot::channel();
        let task = tokio::spawn(async move {
            started.send(()).unwrap();
            child.wait_with_output().await
        });
        waiting.await.unwrap();
        assert!(!process.exited(0).unwrap());
        task.abort();
        assert!(task.await.unwrap_err().is_cancelled());
        assert!(process.exited(2000).unwrap());
    }
}
