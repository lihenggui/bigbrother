# AGENTS.md

Big Brother records which apps on a rooted Android device read files, collect
device identifiers or ask the system for private data. A root module (eBPF
programs and a Rust daemon) records; an Android app reads the records as root.
User documentation is in README.md, internals in docs/how-it-works.md, and
building, checks and releasing in CONTRIBUTING.md.

## Layout

- `bb-ebpf/`: eBPF programs (aya, `no_std`). See `bb-ebpf/AGENTS.md`.
- `bb-common/`: types shared by the eBPF programs and the daemon.
- `bbd/`: the daemon (`bbd run`) and the query CLI the app calls. See `bbd/AGENTS.md`.
- `module/`: root module scripts, packaged into the app. See `module/AGENTS.md`.
- `android/`: the app, the root helper programs (`:helper`), the LSPosed hook and the canary test app. See `android/AGENTS.md`.
- `scripts/`: `build.sh` (full build), `check-kernels.sh` (`bbd selftest` on
  Android kernels in QEMU), `check-canary.sh`, `check-sandbox.sh` and
  `check-permissions.sh` (on-device checks of the records, of the sandbox
  and of the permissions an app leaves it with), and
  `install-on-emulator.sh` (the module on an emulator without a root manager).
- `testdata/permissions/matrix.tsv`: the permission states an app ends in
  after the sandbox, played by `check-permissions.sh` and on the JVM by
  `PermissionMatrixTest`.

The sandbox (`bbd sandbox`, `bbd/src/sandbox/`, `SandboxHook` in the hook,
`AppOpsGuard` in `:helper`) is described in docs/sandbox.md.

## Commands

```sh
./scripts/check.sh           # before opening or updating a pull request
./scripts/check.sh --fast    # while working
```

The Android build packages the daemons from `out/`: run `./scripts/build.sh`
once before `:app` tasks that assemble the APK.

## Contracts between components

- Upgrade support starts with v0.3.0. Write compatibility code only for
  upgrades from supported releases, and only when an upgrade needs it.
  A schema change that affects a supported release must preserve its records,
  sandbox settings, saved grants, identities and sandbox files. Development
  builds can change their schema directly.
- `bbd` output changes as needed, with the app in the same commit: the app
  and the module ship together. The app updates the module when the
  installed one is older or built differently, and maps a daemon that
  rejects a command or option to "module update needed"
  (`RootAccessRepository`).
- Structs in `bb-common` are read by both sides byte for byte: `#[repr(C)]`,
  4-byte fields, no padding. Change both sides in the same commit.
- `module/module.prop` is the single source of the version (`version`,
  `versionCode`) for the app and the module. A release tag must equal `version`.

## Conventions

- Code, comments, commit messages and documentation in English. Comments
  explain why, not what.
- Commit subjects are plain imperative sentences without prefixes
  ("Write nothing while nothing is recorded"); the body says why. One topic per
  commit and per pull request. Do not add AI attribution, co-author trailers or
  session links to commits or pull requests.
- Every source file carries a license header: GPL-3.0 everywhere, except
  `bb-ebpf/` and `bb-common/`, which are GPL-2.0-or-later. Android files get it
  from Spotless; add it by hand to Rust and shell files. CI rejects files
  without one.
- Tests: Kotlin tests are named `givenState_whenAction_thenOutcome`; Rust tests
  are snake_case statements of the behaviour (`rejects_malformed_lines`), with
  no `test_` prefix.
- Dependencies stay on their latest stable versions. Dependabot proposes
  updates for Gradle, Cargo and GitHub Actions.

## Code structure

- Split non-test source files past about 800 lines (500 for Compose) and
  functions past about 80. Split by responsibility and name pieces for their work.
- Event loops and command handlers dispatch to named handlers that do the work.
- CI checks function length (see CONTRIBUTING.md) with Clippy `too_many_lines`
  for Rust and the `:lint` module for Kotlin.
- Use one implementation and one name per concept. Check `bbd/AGENTS.md` and
  `android/AGENTS.md` for shared helpers before writing one.
- Delete unused code. Keep refactors free of compatibility shims and re-exports.
- Keep module dependencies acyclic in the daemon and the app.
- Handle, return or log every error, or explain in a one-line comment why it
  can be ignored. Rate-limit logs for errors that can repeat.
- Refactors preserve behaviour, output, schema and screenshots. Put a change
  to any of these in its own commit and explain why in the commit body.

## Privacy rules

- The app has no network access. Do not add the INTERNET permission or any
  library that needs it; the badging check (`android/app/release-badging.txt`)
  fails on any new permission.
- Never log or export what the app records: paths, package names, search text.
  Log what ran, how long it took and how it ended.
- Personal data (contacts, messages, call log, calendar) is recorded as the
  collection queried, never the item.

## Sandbox enforcement

- Refuse or end an app only when that is what an item exists for, such as
  Network on, or a Files and media mount that cannot be made. When anything
  else is missing, slow, crashed or unsupported, let the app run and report
  the part as not in effect.
- Read a mode, permission or setting before writing it, and write only when
  it differs. When the system or another manager keeps changing it back,
  stop and report it as not in effect instead of setting it again.
- Changes that end an app's processes or unbind its services (revoking a
  runtime permission, changing all-files access, taking a special access)
  get a small retry budget per app. Ordinary app ops are set again whenever
  the system resets them.
- Leave alone an op whose permission no package of the app requests: at its
  default it already denies the app.
- Report what is in force now, read back from the system. When it cannot be
  read, report it as unknown, not as the last result.
- Save the original state durably before changing it, and give it back when
  the app leaves Jail, the module is disabled or uninstalled, or a change
  fails half way.
- Model each Android behaviour a change relies on in the JVM model
  (`FakeAndroid`) or the Rust tests first. Assert how often state changed,
  not only how it ended.

## Pull requests

Branch from `main`. When a change is intended to alter a baseline, update it
in the same pull request (see CONTRIBUTING.md).
