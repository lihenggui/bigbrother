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

//! big_brother daemon and query tool.
//!
//! `bbd run` records what apps read and probe and stores it; the other
//! subcommands read the database and print JSON for the app.

mod appops;
mod binder;
mod btf;
mod commands;
mod control;
mod helper;
mod lifecycle;
mod mounts;
mod net;
mod netctl;
mod packages;
mod paths;
mod personal;
#[cfg(test)]
mod protocol_cases;
mod rules;
mod sandbox;
mod selftest;
mod sensors;
mod spool;
mod store;
mod syscalls;
#[cfg(test)]
mod test_sync;
mod trace;

use std::{
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

use clap::{Parser, Subcommand};
use sandbox::items::{self, Item, Mode};
use serde::Serialize;

#[derive(Parser)]
#[command(
    version,
    about = "Records which apps open user files and device/system information"
)]
struct Cli {
    /// Database file.
    #[arg(long, global = true, env = "BB_DB", default_value = store::DEFAULT_DB)]
    db: PathBuf,

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Trace and record until interrupted. Requires root.
    Run {
        /// Delete records older than this many days.
        #[arg(long, default_value_t = 30)]
        retention_days: u32,
        /// Also print every recorded event.
        #[arg(short, long)]
        verbose: bool,
        /// Measure the CPU time spent in the eBPF programs; printed on exit.
        #[arg(long)]
        profile: bool,
        /// Print events that reach userspace only to be discarded (diagnostic).
        #[arg(long, hide = true)]
        explain_discards: bool,
        /// Leave the LSPosed hook's records, and the sandbox, for another
        /// daemon (diagnostic).
        #[arg(long, hide = true)]
        no_provider_events: bool,
        #[arg(long, default_value = packages::PACKAGES_LIST, hide = true)]
        packages_list: String,
    },
    /// Per-app summary, most recently active first.
    Apps {
        /// Only the last N hours (default: everything).
        #[arg(long)]
        hours: Option<u32>,
    },
    /// Accessed paths, most recent first.
    Files {
        /// Only these uids (repeat for several).
        #[arg(long)]
        uid: Vec<u32>,
        /// Only these categories (repeat for several).
        #[arg(long, value_enum)]
        cat: Vec<store::Category>,
        /// Only paths starting with this prefix.
        #[arg(long)]
        prefix: Option<String>,
        /// Only paths containing this text, ignoring ASCII case.
        #[arg(long)]
        contains: Option<String>,
        /// Only the last N hours (default: everything).
        #[arg(long)]
        hours: Option<u32>,
        #[arg(long, default_value_t = 200)]
        limit: u32,
        #[arg(long, default_value_t = 0)]
        offset: u32,
    },
    /// Each time one app touched one path, most recent first.
    History {
        #[arg(long)]
        uid: u32,
        /// The path exactly as recorded (repeat for several).
        #[arg(long, required = true)]
        path: Vec<String>,
        /// Only the last N hours (default: everything).
        #[arg(long)]
        hours: Option<u32>,
    },
    /// Each app's activity per hour and kind, most recent first, a page at a time.
    Timeline {
        /// Only the last N hours (default: everything).
        #[arg(long)]
        hours: Option<u32>,
        /// Only hours that start before this unix time (default: now); the
        /// `next_before` of the previous page.
        #[arg(long)]
        before: Option<i64>,
        /// Entries after which the page ends (at the end of a day of records).
        #[arg(long, default_value_t = 300)]
        limit: usize,
    },
    /// The periods one app had sensors on, most recent first.
    Sensors {
        #[arg(long)]
        uid: u32,
        /// Only periods that overlap the last N hours (default: everything).
        #[arg(long)]
        hours: Option<u32>,
    },
    /// Database and daemon counters.
    Stats,
    /// Attach the probes and check that each fires; exits non-zero if one
    /// does not. Requires root.
    Selftest,
    /// Put apps in the sandbox and take them out again. Needs the daemon.
    Sandbox {
        /// Print the daemon's answer as JSON.
        #[arg(long, global = true)]
        json: bool,
        #[command(subcommand)]
        command: SandboxCommand,
    },
    /// The network of apps in the sandbox: where the ones whose connections
    /// are recorded connected to, and the destinations apps are kept from.
    Net {
        /// Print the daemon's answer as JSON.
        #[arg(long, global = true)]
        json: bool,
        #[command(subcommand)]
        command: NetCommand,
    },
    /// The app ops helper's lines that give every sandboxed app its app ops
    /// back, read from the database; for uninstall.sh, which may run without
    /// the daemon.
    #[command(hide = true)]
    SandboxRestoreLines,
    /// Check the database before replacing the installed module.
    #[command(hide = true)]
    ModuleCheckDb,
    /// Stop the module processes before an update or removal.
    #[command(hide = true)]
    ModuleStop { module: PathBuf },
    /// Publish a fully prepared module directory with one filesystem operation.
    #[command(hide = true)]
    ModuleExchange { staged: PathBuf, installed: PathBuf },
}

#[derive(Subcommand)]
enum NetCommand {
    /// Where an app connected to since a time, as JSON: by site and by
    /// address, with what it is kept from.
    Activity {
        package: String,
        #[command(flatten)]
        chart: netctl::ChartOptions,
        /// Android user the app is installed for.
        #[arg(long, default_value_t = 0)]
        user: u32,
    },
    /// One destination of an app with the chart of its connections:
    /// `domain:<site>` or `ip:<address>`.
    Destination {
        package: String,
        target: String,
        #[command(flatten)]
        chart: netctl::ChartOptions,
        #[arg(long, default_value_t = 0)]
        user: u32,
    },
    /// Keep an app from a destination: `domain:example.com` (and the names
    /// under it), `host:t.example.com`, `ip:203.0.113.7` or
    /// `ip:203.0.113.7:8080`. Needs the daemon.
    Block {
        package: String,
        target: String,
        /// The destination was typed, not chosen among the records.
        #[arg(long)]
        manual: bool,
        #[arg(long, default_value_t = 0)]
        user: u32,
    },
    /// Stop keeping an app from a destination. Needs the daemon.
    Unblock {
        package: String,
        target: String,
        #[arg(long, default_value_t = 0)]
        user: u32,
    },
    /// Delete what is recorded of an app's network. Needs the daemon.
    Clear {
        package: String,
        #[arg(long, default_value_t = 0)]
        user: u32,
    },
}

#[derive(Subcommand)]
enum SandboxCommand {
    /// Sandbox an app, with every package that shares its uid.
    Enable {
        package: String,
        /// Android user the app is installed for.
        #[arg(long, default_value_t = 0)]
        user: u32,
    },
    /// Take an app out of the sandbox; it keeps its sandbox identity.
    Disable {
        #[arg(required_unless_present = "all")]
        package: Option<String>,
        /// Android user the app is installed for.
        #[arg(long, default_value_t = 0)]
        user: u32,
        /// Take every app out.
        #[arg(long, conflicts_with = "package")]
        all: bool,
    },
    /// Apps in the sandbox, enabled or not.
    List,
    /// Whether each part of the sandbox works on this device.
    Status,
    /// Set what the sandbox does to an app, item by item, as ITEM=MODE:
    /// MODE is off, on, (for location) coarse, (for location, camera and
    /// microphone) foreground, or global to follow the global settings again.
    Set {
        package: String,
        #[arg(required = true, value_parser = parse_setting)]
        settings: Vec<(Item, Option<Mode>)>,
        /// Android user the app is installed for.
        #[arg(long, default_value_t = 0)]
        user: u32,
    },
    /// The global settings; with ITEM=MODE arguments, change them.
    Global {
        #[arg(value_parser = parse_global_setting)]
        settings: Vec<(Item, Mode)>,
    },
    /// Whether apps installed from now on are sandboxed, with the global
    /// settings, before they first start; with on or off, change it.
    /// Turning it on leaves every app installed now as it is.
    Auto {
        #[arg(value_enum)]
        state: Option<Switch>,
    },
}

#[derive(Clone, Copy, PartialEq, Eq, clap::ValueEnum)]
enum Switch {
    On,
    Off,
}

/// `ITEM=MODE`, where `global` stands for no mode of the app's own.
fn parse_setting(text: &str) -> Result<(Item, Option<Mode>), String> {
    let (item, mode) = text
        .split_once('=')
        .ok_or_else(|| format!("{text}: not ITEM=MODE"))?;
    let item = Item::from_name(item).ok_or_else(|| format!("{item}: no such item"))?;
    if mode == "global" {
        return Ok((item, None));
    }
    let mode = items::parse_mode(mode).ok_or_else(|| format!("{mode}: no such mode"))?;
    Ok((item, Some(mode)))
}

fn parse_global_setting(text: &str) -> Result<(Item, Mode), String> {
    match parse_setting(text)? {
        (item, Some(mode)) => Ok((item, mode)),
        (_, None) => Err(format!("{text}: the global settings need a mode")),
    }
}

impl From<SandboxCommand> for control::Request {
    fn from(command: SandboxCommand) -> Self {
        match command {
            SandboxCommand::Enable { package, user } => Self::SandboxEnable { package, user },
            SandboxCommand::Disable {
                package: Some(package),
                user,
                ..
            } => Self::SandboxDisable { package, user },
            // Without a package only with `--all`.
            SandboxCommand::Disable { package: None, .. } => Self::SandboxDisableAll,
            SandboxCommand::List => Self::SandboxList,
            SandboxCommand::Status => Self::SandboxStatus,
            SandboxCommand::Set {
                package,
                settings,
                user,
            } => Self::SandboxSet {
                package,
                user,
                settings: settings.into_iter().collect(),
            },
            SandboxCommand::Global { settings } if settings.is_empty() => Self::SandboxGlobal,
            SandboxCommand::Global { settings } => Self::SandboxSetGlobal {
                settings: settings.into_iter().collect(),
            },
            SandboxCommand::Auto { state: None } => Self::SandboxAuto,
            SandboxCommand::Auto { state: Some(state) } => Self::SandboxSetAuto {
                on: state == Switch::On,
            },
        }
    }
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() as i64)
}

fn since(hours: Option<u32>) -> i64 {
    hours.map_or(0, |h| now() - i64::from(h) * 3600)
}

#[derive(Serialize)]
struct StatsOutput {
    #[serde(flatten)]
    stats: store::Stats,
    /// Whether the daemon is running.
    daemon_running: bool,
}

fn print_json(value: &impl Serialize) -> anyhow::Result<()> {
    println!("{}", serde_json::to_string(value)?);
    Ok(())
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    // Every other command reads the records, which the daemon writes only
    // now and then. The sandbox commands do not read them.
    if !matches!(
        cli.command,
        Command::Run { .. }
            | Command::Sandbox { .. }
            | Command::ModuleCheckDb
            | Command::ModuleStop { .. }
            | Command::ModuleExchange { .. }
            | Command::SandboxRestoreLines
            | Command::Selftest
    ) {
        control::request_flush(&cli.db);
    }
    match cli.command {
        Command::Run {
            retention_days,
            verbose,
            profile,
            explain_discards,
            no_provider_events,
            packages_list,
        } => tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?
            .block_on(trace::run(trace::Options {
                db: &cli.db,
                packages_list: &packages_list,
                retention_days,
                verbose,
                profile,
                explain_discards,
                ingest_provider_events: !no_provider_events,
                sandbox: !no_provider_events,
            })),
        Command::Apps { hours } => print_json(&store::Reader::open(&cli.db)?.apps(since(hours))?),
        Command::Files {
            uid,
            cat,
            prefix,
            contains,
            hours,
            limit,
            offset,
        } => print_json(&store::Reader::open(&cli.db)?.files(
            &uid,
            &cat,
            prefix.as_deref(),
            contains.as_deref(),
            since(hours),
            limit,
            offset,
        )?),
        Command::Timeline {
            hours,
            before,
            limit,
        } => print_json(&store::Reader::open(&cli.db)?.timeline(
            since(hours),
            // Through the current hour.
            before.unwrap_or_else(|| now() + 1),
            limit,
        )?),
        Command::History { uid, path, hours } => {
            print_json(&store::Reader::open(&cli.db)?.history_of(uid, &path, since(hours))?)
        }
        Command::Sensors { uid, hours } => {
            print_json(&store::Reader::open(&cli.db)?.sensor_sessions(uid, since(hours))?)
        }
        Command::Stats => print_json(&StatsOutput {
            stats: store::Reader::open(&cli.db)?.stats()?,
            daemon_running: control::daemon_running(&cli.db),
        }),
        Command::Selftest => {
            if !selftest::command()? {
                std::process::exit(1);
            }
            Ok(())
        }
        Command::ModuleCheckDb => store::check_module_database(&cli.db),
        Command::Sandbox { json, command } => sandbox::command(&cli.db, command.into(), json),
        Command::Net { json, command } => match command {
            NetCommand::Activity {
                package,
                chart,
                user,
            } => netctl::activity(&cli.db, &package, user, chart),
            NetCommand::Destination {
                package,
                target,
                chart,
                user,
            } => netctl::destination(&cli.db, &package, user, &target, chart),
            NetCommand::Block {
                package,
                target,
                manual,
                user,
            } => sandbox::command(
                &cli.db,
                control::Request::NetBlock {
                    package,
                    user,
                    target,
                    manual,
                },
                json,
            ),
            NetCommand::Unblock {
                package,
                target,
                user,
            } => sandbox::command(
                &cli.db,
                control::Request::NetUnblock {
                    package,
                    user,
                    target,
                },
                json,
            ),
            NetCommand::Clear { package, user } => {
                sandbox::command(&cli.db, control::Request::NetClear { package, user }, json)
            }
        },
        Command::ModuleStop { module } => lifecycle::stop(&module),
        Command::ModuleExchange { staged, installed } => {
            let staged = std::ffi::CString::new(staged.as_os_str().as_encoded_bytes())?;
            let installed = std::ffi::CString::new(installed.as_os_str().as_encoded_bytes())?;
            if unsafe {
                libc::syscall(
                    libc::SYS_renameat2,
                    libc::AT_FDCWD,
                    staged.as_ptr(),
                    libc::AT_FDCWD,
                    installed.as_ptr(),
                    2, /* RENAME_EXCHANGE */
                )
            } != 0
            {
                return Err(std::io::Error::last_os_error().into());
            }
            Ok(())
        }
        Command::SandboxRestoreLines => {
            for line in sandbox::restore_lines(&cli.db)? {
                println!("{line}");
            }
            Ok(())
        }
    }
}
