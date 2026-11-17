# AGENTS.md: daemon and query CLI

`bbd run` is the recording daemon, started by `module/supervise.sh`. The other
subcommands (`apps`, `files`, `history`, `timeline`, `stats` and so on) are the
app's interface to the records: the app runs them as root and parses their
JSON.

## Output is an interface

- Newer and older apps and daemons talk to each other (see the root
  `AGENTS.md`). Add fields and subcommands; do not rename, remove or repurpose
  existing ones, and keep options backward compatible.
- Every query first asks the running daemon to write what it holds
  (`control::request_flush`, over `bbd.sock`), waiting at most one second.
  `main` does this for every subcommand except `run`; a new subcommand that
  does not read the records should be exempted the same way.

## Database

- SQLite at `/data/adb/big_brother/bb.db`, records kept 30 days.
- The schema is versioned (`SCHEMA_VERSION` in `src/store.rs`). A schema change
  bumps it, adds a step to `migrate`, and adds a test that upgrades a database
  of the previous version.

## Power and performance

The daemon runs on every device all the time; its cost is the product's cost.

- No busy loops and no timers shorter than needed. It writes every 30 seconds,
  and only when there is something to write; it holds no wakelock.
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
