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

//! Wire sandbox effects and serve commands off the recording loop.

use std::{
    collections::BTreeSet,
    path::Path,
    sync::{Arc, mpsc},
};

use anyhow::Context as _;
use tokio::{
    net::{UnixListener, UnixStream},
    sync::Notify,
    time::{MissedTickBehavior, interval},
};

use super::{FLUSH_INTERVAL, Options, next_request, sleep_until};
use crate::{
    control,
    log::{Limited, Policy, RECURRING_INTERVAL},
    packages::Packages,
    probes::{Probes, attach_feature, attach_traffic},
    sandbox::{
        self, Sandbox,
        appops::AppOps,
        auto,
        destinations::Destinations,
        gate::Storage,
        names::{self, Names, RefusedLookups, Serving},
        network::Network,
    },
    store::Writer,
    syscalls,
    tracer::Tracer,
};

/// Most command connections waiting to send their request at once. Only
/// root can connect; this only keeps a stuck client from piling them up.
const MAX_PENDING_COMMANDS: usize = 8;
/// Uids of new apps whose start the gate ended, waiting for the main loop.
/// One look at the package list deals with all of them; a uid that finds no
/// room is reported again by its next start.
const NEW_STARTS_QUEUE: usize = 16;
/// Where every user's apps keep the data they can reach before the user
/// unlocks the device, a directory per user and package: there for every
/// installed app from the start.
const USER_DATA: &str = "/data/user_de";

pub(super) fn start(
    opts: &Options<'_>,
    probes: &mut Probes,
    tracer: &mut Tracer,
) -> (Option<SandboxThread>, mpsc::Receiver<u32>) {
    // The sandbox (see `sandbox`). Like the optional probes, it costs only
    // itself where it cannot work: the daemon records regardless.
    let (new_starts_sender, new_starts) = tokio::sync::mpsc::channel(NEW_STARTS_QUEUE);
    // Woken when the app ops helper finds a permission changed by the user,
    // which is kept in its entry at once (`Sandbox::keep_found`, below).
    let found = Arc::new(Notify::new());
    let helper_restarted = Arc::new(Notify::new());
    let names_stopped = Arc::new(Notify::new());
    let sandbox = if opts.sandbox {
        start_sandbox(SandboxStart {
            probes,
            tracer,
            db: opts.db,
            new_starts: new_starts_sender,
            found: &found,
            helper_restarted: &helper_restarted,
            names_stopped: names_stopped.clone(),
        })
    } else {
        None
    };
    // Commands from `bbd sandbox` (see `control`) are listened for on the
    // sandbox's thread, before the flush socket, which is what tells a
    // client that the daemon is up.
    let (net_cleared_sender, net_cleared) = mpsc::channel();
    let sandbox = sandbox.and_then(|sandbox| {
        SandboxThread::start(SandboxParts {
            sandbox,
            db: opts.db.to_owned(),
            packages_list: opts.packages_list.to_owned(),
            new_starts,
            found,
            helper_restarted,
            names_stopped,
            net_cleared: net_cleared_sender,
        })
        .inspect_err(|e| eprintln!("bbd: warning: the sandbox is off: {e:#}"))
        .ok()
    });
    (sandbox, net_cleared)
}

struct SandboxStart<'a> {
    probes: &'a mut Probes,
    tracer: &'a mut Tracer,
    db: &'a Path,
    new_starts: tokio::sync::mpsc::Sender<u32>,
    found: &'a Arc<Notify>,
    helper_restarted: &'a Arc<Notify>,
    names_stopped: Arc<Notify>,
}

/// Load the sandbox, attach its gate and put the apps that were sandboxed
/// back in. `None`, with a warning, if the sandbox's entries cannot be
/// read; a gate that cannot be attached leaves a sandbox that refuses to
/// take apps in. The gate sends `new_starts` the uid of each new app whose
/// start it ended (see `sandbox::auto`), and the app ops helper wakes
/// `found` when it finds a permission changed by the user.
fn start_sandbox(context: SandboxStart<'_>) -> Option<LiveSandbox> {
    let SandboxStart {
        probes,
        tracer,
        db,
        new_starts,
        found,
        helper_restarted,
        names_stopped,
    } = context;
    let user_data = Path::new(USER_DATA);
    let mut sandbox = match Sandbox::load(&tracer.writer, Vec::new(), Some(user_data)) {
        Ok(sandbox) => sandbox,
        Err(e) => {
            eprintln!("bbd: warning: the sandbox is off: {e:#}");
            return None;
        }
    };
    let relay = Serving::default();
    let storage = attach_gate(probes, tracer, db, sandbox.book(), relay.clone());
    let appops = AppOps::start();
    // Whether an app is kept from single destinations: what the one effect
    // finds out and the other needs the packet filter's rules for.
    let ruled = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let destinations = Destinations::start(&mut probes.ebpf, sandbox.book(), ruled.clone());
    let network = Network::start(&mut probes.ebpf, ruled);
    let names = start_name_relay(
        &sandbox,
        tracer,
        storage.is_ok(),
        relay.clone(),
        names_stopped,
    );
    let recording_covers = names.is_some();
    let found = found.clone();
    appops.on_found(move || found.notify_one());
    let helper_restarted = helper_restarted.clone();
    appops.on_restart(move || helper_restarted.notify_one());
    let (storage, new_apps) = build_storage_effect(
        &mut probes.ebpf,
        &sandbox,
        storage,
        &appops,
        network.is_ok(),
        new_starts,
    );
    // Libraries are told from apps by the package manager, through the
    // helper, which is started for that only when a new app is found.
    sandbox.tell_libraries_with(Box::new(appops.librarian()));
    sandbox.add_effect(Box::new(appops));
    sandbox.add_effect(Box::new(sandbox::identity::Identity::new(
        &sandbox.snapshot(),
        &sandbox.global(),
    )));
    build_net_effects(probes, tracer, &mut sandbox, destinations, network, names);
    // Adding the UID to the storage gate lets a held start proceed. Its
    // other policies must already be in place at that point.
    sandbox.add_effect(storage);
    sandbox.add_effect(Box::new(sandbox::sweep::Restart));
    sandbox.resume(&tracer.writer, &mut tracer.packages);
    sandbox.note_recording(&tracer.writer);
    sandbox.resume_auto(new_apps, &tracer.writer, &mut tracer.packages);
    Some(LiveSandbox {
        sandbox,
        relay,
        recording_covers,
    })
}

fn attach_gate(
    probes: &mut Probes,
    tracer: &mut Tracer,
    db: &Path,
    book: sandbox::destinations::Book,
    relay: Serving,
) -> anyhow::Result<Storage> {
    // Attached while no uid is listed for it, so it holds nothing yet. The
    // uids are listed next, and what started in between is ended last
    // (`sweep`): no start falls between the two.
    let storage = attach_feature(
        &mut probes.ebpf,
        &syscalls::GATE,
        &probes.symbols,
        probes.selinux,
        &probes.offsets,
    )
    .and_then(|_| Storage::start(&mut probes.ebpf, &db.with_file_name("scratch"), book, relay));
    tracer.write_coverage(
        &format!("coverage_{}", syscalls::GATE.name),
        i64::from(storage.is_ok()),
    );
    storage
}

fn start_name_relay(
    sandbox: &Sandbox,
    tracer: &mut Tracer,
    gate: bool,
    relay: Serving,
    stopped: Arc<Notify>,
) -> Option<Arc<Names>> {
    // The names apps look up, heard by standing between them and netd.
    let names = Arc::new(Names::default());
    let (lookups_refused, refused_lookups) = RefusedLookups::channel();
    let (serves, refuses) = sandbox.name_policy();
    let started = gate
        && names::start(
            names.clone(),
            serves,
            refuses,
            lookups_refused,
            relay.clone(),
            stopped,
        )
        .inspect_err(|e| eprintln!("bbd: warning: the names apps look up are not known: {e:#}"))
        .is_ok();
    tracer.write_coverage("coverage_net_names", i64::from(relay.alive()));
    let names = started.then_some(names);
    tracer.refused_lookups = started.then_some(refused_lookups);
    tracer.net = Some(sandbox::destinations::View {
        snapshot: sandbox.snapshot(),
        global: sandbox.global(),
        book: sandbox.book(),
        names: names.clone(),
    });
    names
}

fn build_storage_effect(
    ebpf: &mut aya::Ebpf,
    sandbox: &Sandbox,
    storage: anyhow::Result<Storage>,
    appops: &AppOps,
    network_available: bool,
    new_starts: tokio::sync::mpsc::Sender<u32>,
) -> (Box<dyn sandbox::Effect>, Option<auto::Kernel>) {
    match storage {
        Ok(mut storage) => {
            storage.on_admitted(appops.verifier());
            // The resolver goes with the network, and stays without it.
            if network_available {
                storage.with_network();
            }
            // Not waited for: the gate thread waits for nothing.
            storage.on_unknown(move |uid| {
                let _ = new_starts.try_send(uid);
            });
            let new_apps = auto::Kernel::take(ebpf, &storage)
                .inspect_err(|e| eprintln!("bbd: warning: starts of new apps are not held: {e:#}"))
                .ok();
            (Box::new(storage), new_apps)
        }
        Err(e) => {
            eprintln!("bbd: warning: the sandbox is not available: {e:#}");
            (Box::new(sandbox::gate::Unavailable(sandbox.book())), None)
        }
    }
}

fn build_net_effects(
    probes: &mut Probes,
    tracer: &mut Tracer,
    sandbox: &mut Sandbox,
    destinations: anyhow::Result<Destinations>,
    network: anyhow::Result<Network>,
    names: Option<Arc<Names>>,
) {
    // What went over a connection is told by another program, which reads
    // the same list of apps.
    let traffic = destinations
        .as_ref()
        .map_err(|e| anyhow::anyhow!("{e:#}"))
        .and_then(|_| attach_traffic(&mut probes.ebpf, &probes.offsets));
    if let Err(e) = &traffic {
        eprintln!("bbd: warning: the bytes of connections are not counted: {e:#}");
    }
    tracer.write_coverage("coverage_net_traffic", i64::from(traffic.is_ok()));
    match destinations {
        Ok(mut destinations) => {
            destinations.with_traffic(traffic.is_ok());
            if let Some(names) = names {
                destinations.with_names(names);
            }
            sandbox.add_effect(Box::new(destinations));
        }
        Err(e) => eprintln!("bbd: warning: connections are not recorded: {e:#}"),
    }
    let network: Box<dyn sandbox::Effect> = match network {
        Ok(network) => Box::new(network),
        Err(e) => {
            eprintln!("bbd: warning: apps cannot be kept from the network: {e:#}");
            Box::new(sandbox::network::Unavailable::new())
        }
    };
    sandbox.add_effect(network);
}

struct LiveSandbox {
    sandbox: Sandbox,
    relay: Serving,
    recording_covers: bool,
}

impl LiveSandbox {
    fn stop_relay(&mut self) {
        self.relay.stop();
        if std::mem::take(&mut self.recording_covers) {
            self.sandbox.uncover_recording_dns();
        }
    }
}

impl Drop for LiveSandbox {
    fn drop(&mut self) {
        self.stop_relay();
    }
}

/// What the sandbox's thread is started with.
struct SandboxParts {
    sandbox: LiveSandbox,
    db: std::path::PathBuf,
    packages_list: String,
    /// Starts the gate ended for a new app or a stale entry.
    new_starts: tokio::sync::mpsc::Receiver<u32>,
    /// Woken when the app ops helper finds a permission changed by the
    /// user, which is kept in its entry at once (`Sandbox::keep_found`).
    found: Arc<Notify>,
    helper_restarted: Arc<Notify>,
    names_stopped: Arc<Notify>,
    /// The apps whose network records the sandbox cleared, for the event
    /// loop to drop what it holds of them.
    net_cleared: mpsc::Sender<u32>,
}

/// The sandbox, run on a thread of its own. Carrying out a command waits
/// for the app ops helper's answer, which takes up to seconds; on the event
/// loop that would leave the kernel's events unread for as long, and lose
/// them once its buffer is full.
pub(super) struct SandboxThread {
    stop: tokio::sync::oneshot::Sender<()>,
    thread: std::thread::JoinHandle<()>,
}

impl SandboxThread {
    /// Start the thread, and return once it listens for commands.
    fn start(parts: SandboxParts) -> anyhow::Result<Self> {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .context("starting the sandbox's runtime")?;
        let (stop, stopped) = tokio::sync::oneshot::channel();
        let (listening, listened) = mpsc::channel();
        let thread = std::thread::Builder::new()
            .name("sandbox".into())
            .spawn(move || runtime.block_on(run_sandbox(parts, stopped, listening)))
            .context("starting the sandbox's thread")?;
        // Sent once the command socket is bound or could not be.
        if listened.recv().is_err() {
            eprintln!("bbd: sandbox: thread ended before reporting command listener status");
        }
        Ok(Self { stop, thread })
    }

    /// Stop the sandbox, which keeps what it has learned first.
    pub(super) fn stop(self) {
        let _ = self.stop.send(());
        if self.thread.join().is_err() {
            eprintln!("bbd: warning: the sandbox's thread panicked");
        }
    }
}

async fn run_sandbox(
    parts: SandboxParts,
    mut stopped: tokio::sync::oneshot::Receiver<()>,
    listening: mpsc::Sender<()>,
) {
    let SandboxParts {
        sandbox: mut live,
        db,
        packages_list,
        mut new_starts,
        found,
        helper_restarted,
        names_stopped,
        net_cleared,
    } = parts;
    let commands = listen_commands(&db);
    let _ = listening.send(());
    // A connection of its own: the event loop's writes and the sandbox's
    // wait for each other only while one commits.
    let Some(writer) = open_sandbox_writer(&db, net_cleared) else {
        return;
    };
    let mut packages = Packages::new(&packages_list);
    let mut command_reads = tokio::task::JoinSet::new();
    let mut command_read_errors = Limited::new(RECURRING_INTERVAL, Policy::ResetOnChange);
    // The package list, watched while newly installed apps are sandboxed.
    let mut package_watch = auto::watch::Watch::default();
    let mut tick = interval(FLUSH_INTERVAL);
    tick.set_missed_tick_behavior(MissedTickBehavior::Delay);
    loop {
        if package_watch.arm(
            live.sandbox.auto_on(),
            Path::new(auto::watch::PACKAGE_DIR),
            Path::new(USER_DATA),
        ) {
            // What was installed before the watch began.
            live.sandbox.isolate_new(&writer, &mut packages);
        }
        // A new app left out of the sandbox is tried again then; no timer
        // while none is.
        let next_look = live.sandbox.auto_next_look();
        tokio::select! {
            _ = &mut stopped => break,
            _ = tick.tick() => live.sandbox.retry_net_refresh(),
            _ = found.notified() => {}
            _ = helper_restarted.notified() => live.sandbox.auto_helper_restarted(&writer, &mut packages),
            _ = names_stopped.notified() => stop_relay(&mut live, &writer),
            // A command is read without holding up the others, and carried
            // out once it has arrived.
            stream = next_request(commands.as_ref()) => {
                if let Some(stream) = stream
                    && command_reads.len() < MAX_PENDING_COMMANDS
                {
                    command_reads.spawn(control::read_request(stream));
                }
                continue;
            }
            Some(read) = command_reads.join_next(), if !command_reads.is_empty() => {
                respond_to_command(read, &mut live.sandbox, &writer, &mut packages, &mut command_read_errors);
            }
            // An app was installed, updated or removed.
            changed = package_watch.changed(), if package_watch.armed() => match changed {
                Ok(()) => live.sandbox.auto_packages_changed(&writer, &mut packages),
                Err(e) => package_watch.give_up(&e),
            },
            _ = sleep_until(next_look), if next_look.is_some() => {
                live.sandbox.isolate_new(&writer, &mut packages);
            }
            // The gate ended a start for a new app or a stale entry.
            Some(uid) = new_starts.recv() => {
                let mut starts = BTreeSet::from([uid]);
                while let Ok(uid) = new_starts.try_recv() {
                    starts.insert(uid);
                }
                live.sandbox.auto_started(&writer, &mut packages, starts);
            }
        }
        live.sandbox.release_reassigned(&writer, &mut packages);
        live.sandbox.keep_found(&writer);
    }
    stop_relay(&mut live, &writer);
    control::unlisten_commands(&db);
    live.sandbox.keep_found(&writer);
}

fn stop_relay(live: &mut LiveSandbox, writer: &Writer) {
    live.stop_relay();
    if let Err(e) = writer.set_meta("coverage_net_names", 0) {
        eprintln!("bbd: warning: could not update resolver coverage: {e:#}");
    }
}

fn open_sandbox_writer(db: &Path, net_cleared: mpsc::Sender<u32>) -> Option<Writer> {
    match Writer::open(db) {
        Ok(mut writer) => {
            writer.tell_net_cleared(net_cleared);
            Some(writer)
        }
        Err(e) => {
            eprintln!("bbd: warning: the sandbox cannot write: {e:#}");
            control::unlisten_commands(db);
            None
        }
    }
}

fn listen_commands(db: &Path) -> Option<UnixListener> {
    match control::listen_commands(db) {
        Ok(listener) => Some(listener),
        Err(e) => {
            eprintln!("bbd: warning: not taking sandbox commands: {e:#}");
            None
        }
    }
}

fn respond_to_command(
    read: Result<Option<(UnixStream, String)>, tokio::task::JoinError>,
    sandbox: &mut Sandbox,
    writer: &Writer,
    packages: &mut Packages,
    command_read_errors: &mut Limited,
) {
    match read {
        Ok(Some((stream, line))) => {
            let answer = sandbox.answer(&line, writer, packages);
            control::send_answer(stream, answer);
        }
        Ok(None) => {}
        Err(e) => {
            // Panic payloads can include command data.
            let outcome = if e.is_panic() {
                "panicked"
            } else {
                "was cancelled"
            };
            command_read_errors.log(format_args!("bbd: sandbox: command read task {outcome}"));
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicUsize, Ordering};

    use super::*;
    use crate::sandbox::{Effect, Entry, items::Settings};

    struct RecordingGate(Arc<AtomicUsize>);

    impl Effect for RecordingGate {
        fn name(&self) -> &'static str {
            "recording gate"
        }

        fn enable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
            Ok(())
        }

        fn disable(&mut self, _: &mut Entry, _: &[String], _: &Settings) -> anyhow::Result<()> {
            Ok(())
        }

        fn update(
            &mut self,
            _: &mut Entry,
            _: &[String],
            _: &Settings,
            _: &Settings,
        ) -> anyhow::Result<()> {
            Ok(())
        }

        fn relay_stopped(&mut self) {
            self.0.fetch_add(1, Ordering::Relaxed);
        }
    }

    #[test]
    fn relay_loss_and_shutdown_start_the_recording_cover_cleanup_once() {
        let writer = Writer::open(Path::new(":memory:")).unwrap();
        let cleanups = Arc::new(AtomicUsize::new(0));
        let live = || LiveSandbox {
            sandbox: Sandbox::load(
                &writer,
                vec![Box::new(RecordingGate(cleanups.clone()))],
                None,
            )
            .unwrap(),
            relay: Serving::default(),
            recording_covers: true,
        };

        let mut after_loss = live();
        stop_relay(&mut after_loss, &writer);
        assert_eq!(cleanups.load(Ordering::Relaxed), 1);
        drop(after_loss);
        assert_eq!(cleanups.load(Ordering::Relaxed), 1);

        drop(live());
        assert_eq!(cleanups.load(Ordering::Relaxed), 2);
    }
}
