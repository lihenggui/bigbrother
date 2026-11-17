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
mod rules;
mod spool;
mod store;
mod syscalls;
mod trace;

use std::{
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

use clap::{Parser, Subcommand};
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
        /// Leave the LSPosed hook's records for another daemon (diagnostic).
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
    /// Database and daemon counters.
    Stats,
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
    /// Whether the daemon is running. Older versions leave this out, and
    /// their `last_flush` in `meta`, renewed every 5 s, says it instead.
    daemon_running: bool,
}

fn print_json(value: &impl Serialize) -> anyhow::Result<()> {
    println!("{}", serde_json::to_string(value)?);
    Ok(())
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    // Every other command reads the records, which the daemon writes only
    // now and then.
    if !matches!(cli.command, Command::Run { .. }) {
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
        Command::Stats => print_json(&StatsOutput {
            stats: store::Reader::open(&cli.db)?.stats()?,
            daemon_running: control::daemon_running(&cli.db),
        }),
    }
}
