//! big_brother daemon and query tool.
//!
//! `bbd run` traces app access to shared storage and stores it; the other
//! subcommands read the database and print JSON for the WebUI.

mod btf;
mod mounts;
mod packages;
mod paths;
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
        /// Only this uid.
        #[arg(long)]
        uid: Option<u32>,
        /// Only this category.
        #[arg(long, value_enum)]
        cat: Option<store::Category>,
        /// Only paths starting with this prefix.
        #[arg(long)]
        prefix: Option<String>,
        /// Only the last N hours (default: everything).
        #[arg(long)]
        hours: Option<u32>,
        #[arg(long, default_value_t = 200)]
        limit: u32,
        #[arg(long, default_value_t = 0)]
        offset: u32,
    },
    /// When one app touched one path, hour by hour, most recent first.
    History {
        #[arg(long)]
        uid: u32,
        /// The path exactly as recorded.
        #[arg(long)]
        path: String,
        /// Only the last N hours (default: everything).
        #[arg(long)]
        hours: Option<u32>,
    },
    /// Database and daemon counters.
    Stats,
}

fn since(hours: Option<u32>) -> i64 {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() as i64);
    hours.map_or(0, |h| now - i64::from(h) * 3600)
}

fn print_json(value: &impl Serialize) -> anyhow::Result<()> {
    println!("{}", serde_json::to_string(value)?);
    Ok(())
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
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
            hours,
            limit,
            offset,
        } => print_json(&store::Reader::open(&cli.db)?.files(
            uid,
            cat,
            prefix.as_deref(),
            since(hours),
            limit,
            offset,
        )?),
        Command::History { uid, path, hours } => {
            print_json(&store::Reader::open(&cli.db)?.history(uid, &path, since(hours))?)
        }
        Command::Stats => print_json(&store::Reader::open(&cli.db)?.stats()?),
    }
}
