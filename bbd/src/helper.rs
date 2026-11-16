//! Runs the small programs the app ships for the daemon.
//!
//! Some things can only be learned from inside the Android runtime: the
//! framework's transaction codes, the system's permission usage records.
//! The app's APK carries a class for each (`li.mercury.bigbrother.catalog`);
//! the daemon runs them with `app_process`, as root and outside any app,
//! from the copy of the APK that was installed with the module.

use std::{
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

/// Run `class` (a name within the app's helper package) to completion and
/// return what it printed. Takes about a second: it starts a Java runtime.
/// Fails while the system is still starting.
pub fn run<'a>(class: &str, args: impl Iterator<Item = &'a str>) -> anyhow::Result<String> {
    let output = command(class, args)?
        .output()
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

/// Start `class` and leave it running, with its input and output connected
/// to the daemon. A helper started this way exits when its input closes.
pub fn spawn<'a>(class: &str, args: impl Iterator<Item = &'a str>) -> anyhow::Result<Child> {
    command(class, args)?
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .with_context(|| format!("starting {APP_PROCESS}"))
}
