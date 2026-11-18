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
mod mounts;
mod net;
mod packages;
mod paths;
mod personal;
#[cfg(test)]
mod protocol_cases;
mod rules;
mod sandbox;
mod sensors;
mod spool;
mod store;
mod syscalls;
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
    /// Put apps in the sandbox and take them out again. Needs the daemon.
    Sandbox {
        /// Print the daemon's answer as JSON.
        #[arg(long, global = true)]
        json: bool,
        #[command(subcommand)]
        command: SandboxCommand,
    },
    /// The app ops helper's lines that give every sandboxed app its app ops
    /// back, read from the database; for uninstall.sh, which may run without
    /// the daemon.
    #[command(hide = true)]
    SandboxRestoreLines,
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
        Command::Run { .. } | Command::Sandbox { .. } | Command::SandboxRestoreLines
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
        Command::Sandbox { json, command } => sandbox::command(&cli.db, command.into(), json),
        Command::SandboxRestoreLines => {
            for line in sandbox::restore_lines(&cli.db)? {
                println!("{line}");
            }
            Ok(())
        }
    }
}
