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
- `android/`: the app, the LSPosed hook and the canary test app. See `android/AGENTS.md`.
- `scripts/`: `build.sh` (full build), `check-canary.sh` and `check-sandbox.sh`
  (on-device checks of the records and of the sandbox).

The sandbox (`bbd sandbox`, `bbd/src/sandbox/`, `SandboxHook` in the hook,
`AppOpsGuard` in the app) is described in docs/sandbox.md.

## Commands

```sh
cargo fmt --all --check
cargo clippy --all-targets -- -D warnings
cargo test
for s in module/*.sh scripts/*.sh; do bash -n "$s"; done
./scripts/build.sh            # eBPF, daemons for arm64 and x86_64, and out/big_brother-<version>.zip
(cd android && ./gradlew spotlessCheck test)
```

The Android build packages the daemons from `out/`: run `./scripts/build.sh`
once before `:app` tasks that assemble the APK.

## Contracts between components

- The app and the module are updated separately, so the app must work with an
  older daemon and the daemon with an older app. Add JSON fields to `bbd`
  output; never rename, remove or change the meaning of one. The app maps a
  daemon that rejects a command or option to "module update needed"
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

## Privacy rules

- The app has no network access. Do not add the INTERNET permission or any
  library that needs it; the badging check (`android/app/release-badging.txt`)
  fails on any new permission.
- Never log or export what the app records: paths, package names, search text.
  Log what ran, how long it took and how it ended.
- Personal data (contacts, messages, call log, calendar) is recorded as the
  collection queried, never the item.

## Pull requests

Branch from `main`. CI (`.github/workflows/ci.yml`) must pass: Rust format,
clippy and tests, script syntax, license headers, the full build, the app's
unit and screenshot tests, Dependency Guard, badging, Licensee and lint. When a
change is intended to alter a baseline, update it in the same pull request
(see CONTRIBUTING.md).
