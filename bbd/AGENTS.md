# AGENTS.md: daemon and query CLI

`bbd run` is the recording daemon, started by `module/supervise.sh`. The other
subcommands (`apps`, `files`, `history`, `timeline`, `stats` and so on) are the
app's interface to the records: the app runs them as root and parses their
JSON.

## Output is an interface

- The app parses this output. Change it together with the app, in the same
  commit; no compatibility with earlier versions is kept (see the root
  `AGENTS.md`).
- Every query first asks the running daemon to write what it holds
  (`control::request_flush`, over `bbd.sock`), waiting at most one second.
  `main` does this for every subcommand except `run`; a new subcommand that
  does not read the records should be exempted the same way.
- `bbd.sock` keeps its one-byte flush answer. Commands that change what the
  daemon does (`bbd sandbox ...`) go over `bbd.ctl`, one JSON line each way
  (`control::Request`); they need the running daemon and never write the
  database themselves.

## Sandbox

`src/sandbox/` gives an app the user put in it another view of the device
(docs/sandbox.md). What it does to an app is a list of effects
(`sandbox::Effect`), carried out in order and undone in reverse on failure.

- Each effect follows the app's items (`sandbox/items.rs`): it does nothing
  for an item that is off, and `update` carries out a change of items on an
  app already in the sandbox. An app with every item off has nothing done to
  it, and an app not in the sandbox is never touched.
- A new item names the app ops, runtime permissions, mounts or hook answers
  it covers in `items.rs`; its app ops must also be in
  `AppOpsGuardProtocol.GUARDED_OPS`. A runtime permission it takes is named
  in the entry before it is taken (`appops::naming_taken`), as no covering
  mode of the items can name it.

- Fail closed. A start that cannot be given its sandbox is killed, never let
  through, and the kernel side does the same when the gate thread is stuck
  or gone.
- The gate thread (`sandbox/gate.rs`) is what a starting app waits for. It
  never waits for the main loop, the database or a helper program, and has
  no timer: it blocks until a sandboxed app starts.
- A sandbox directory belongs to the sandboxed app. As root, reach into it
  only through `sandbox/dirs.rs`, one name at a time and never through a
  symbolic link.
- The errors and log lines of the mounts name the step, not the path: the
  paths have package names in them.
- Commands run on the main loop and hold up recording while they do: keep
  what an effect waits for bounded (`appops::ANSWER_WAIT`).
- Helper programs cost a Java runtime each. The app ops helper runs only
  while an app is sandboxed.

## Database

- SQLite at `/data/adb/big_brother/bb.db`, records kept 30 days.
- The schema is `SCHEMA` in `src/store.rs`, at `SCHEMA_VERSION`. A schema
  change edits `SCHEMA` and bumps the version. The daemon sets a database at
  another version aside (`bb.db.old`) and starts a new one; it is not
  upgraded, and the query commands do not read it.

## Power and performance

The daemon runs on every device all the time; its cost is the product's cost.

- No busy loops and no timers shorter than needed. It writes every 30 seconds,
  and only when there is something to write; it holds no wakelock.
- While a sensor session is open, the processes of open sessions are checked
  every 5 seconds (`SENSOR_PROCESS_CHECK`), so that a killed app's session ends
  close to when it did. With no session open that timer does not exist.
- While newly installed apps are put in the sandbox (`sandbox/auto.rs`),
  `/data/system` is watched with inotify for the package list being
  replaced. With that off nothing is watched.
- Rate-limit anything that can repeat (log lines, helper restarts) and back off
  on failure.
- Filter as early as possible: in the kernel if the decision can be made there
  (see `bb-ebpf/AGENTS.md`), otherwise before allocating or storing.

## What gets recorded

- The rules live in `src/rules.rs` (`/proc`, `/sys`, `/dev`), `src/paths.rs`
  (shared storage), `src/commands.rs`, `src/binder.rs`, `src/net.rs` and
  `src/personal.rs`. A new kind of record needs a topic, which the app shows,
  and tests for what is and is not recorded.
- Paths, command lines and package names come from other apps: treat them as
  untrusted data. Never pass them to a shell or interpret them.
