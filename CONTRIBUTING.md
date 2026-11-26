# Contributing

Bug reports and pull requests are welcome. For a bug, include the output of
**Settings → Export logs** in the app; the issue template lists the other
details that help.

## Building

Prerequisites:

- Rust stable with `llvm-tools` (or `llvm-objcopy` available in PATH), and
  nightly with `rust-src`; targets `aarch64-linux-android`
  and `x86_64-linux-android`
- [bpf-linker](https://github.com/aya-rs/bpf-linker)
- Android SDK (platform 37) and NDK
- JDK 21

```sh
./scripts/build.sh
```

This builds the eBPF programs, the daemon for each architecture and the
app, and writes `out/big_brother-<version>.apk.zip` together with the R8
mapping. The file is both the app's APK and the flashable module. It is
signed with the debug key unless `android/keystore.properties` exists, with
the keys `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

## Testing

```sh
cargo test
(cd android && ./gradlew test)
```

`scripts/check-kernels.sh` boots the kernel of each Android release from
13 on (those of the x86_64 emulator images) in QEMU, with an initramfs
that runs `bbd selftest`, and fails if a probe of the x86_64 build does
not load, attach or fire on one of them; the arm64 build is checked on
devices (`check-canary.sh`). It needs `qemu-system-x86_64` and a static
busybox, and builds a static daemon for musl
(`x86_64-unknown-linux-musl`, `musl-gcc`) unless given one (`--bbd`).

```sh
./scripts/check-kernels.sh                  # --api 33, or --kernel PATH
```

On a device with the module running, `scripts/check-canary.sh` installs a
test app (`android/canary`) that does one of everything Big Brother is meant
to notice, and checks that each left a record:

```sh
(cd android && ./gradlew :canary:assembleDebug)
./scripts/check-canary.sh
```

`scripts/check-sandbox.sh` runs the same app outside the sandbox, inside it
and outside again, and checks what it sees each time
([docs/sandbox.md](docs/sandbox.md)). It needs the LSPosed hook, with the
`system` scope, for the identity checks, and skips them without it:

```sh
./scripts/check-sandbox.sh              # --stress 200 adds cold starts
```

`scripts/check-permissions.sh` plays the permission matrix,
`testdata/permissions/matrix.tsv`: for each case, a fresh install of the
canary enters the sandbox with a permission in one state, has it or the
sandbox's items changed while it is in, and leaves; the permission, its app
op and what the canary reads must end as the case says. The unit tests play
the same matrix on a model of Android (`PermissionMatrixTest`). A change to
how the sandbox holds or gives back permissions comes with the cases it
changes, in the matrix:

```sh
./scripts/check-permissions.sh          # or the names of some cases
```

For the arm64 release check, install the candidate module on an unlocked
arm64 test device and select it with `ANDROID_SERIAL`. Run the canary and
the restart cases to check that probes fire and that taking the canary out
of the sandbox restores the saved mode and permission after a daemon restart.

```sh
(cd android && ./gradlew :canary:assembleDebug)
./scripts/check-canary.sh
./scripts/check-permissions.sh camera_ignored_by_the_user_through_a_daemon_restart location_coarse_through_a_daemon_restart
```

These checks run on an emulator as well. On a `google_apis` image, which allows
`adb root`, `scripts/install-on-emulator.sh` installs the module and the app
from `out/` with `module/direct.sh`, as the app does with root alone, and
starts the daemon:

```sh
./scripts/install-on-emulator.sh
```

The Device workflow (`.github/workflows/device.yml`) runs these checks and
the permission matrix on emulators of API 33 to 36, with the matrix shared
between two more emulators of each (`--shard 1/2` and `--shard 2/2`). Without LSPosed there,
the sandbox check skips what needs the hook.

The unit tests include screenshot tests
([Roborazzi](https://github.com/takahirom/roborazzi)), which render the
screens with sample records and compare them with the checked-in images:
the README's screenshots in `fastlane/metadata/android/<locale>/images/`,
which also serve as store listing images, and the rest in each module's
`src/test/screenshots/`. After a change to the user interface, record them
again:

```sh
(cd android && ./gradlew recordRoborazziDebug)
```

## Baseline and startup profiles

Build the daemons with `./scripts/build.sh`. Gradle provisions a Pixel 6
emulator with the API 36 x86_64 AOSP system image. On Linux, the emulator
needs access to `/dev/kvm`.

```sh
cd android
./gradlew :app:generateReleaseBaselineProfile \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

The generator skips the first-launch guide if shown, visits the four main
destinations, scrolls the Apps list when records are available, and scrolls
Settings on a fresh install. Review `baseline-prof.txt` and `startup-prof.txt`
under `app/src/release/generated/baselineProfiles/`. The release build packages
the baseline profile and uses the startup profile for DEX layout.

The [Baseline Profiles workflow](.github/workflows/baseline-profiles.yml)
runs weekly and can be started manually on `main`. When the profiles differ
from `main`, it opens or updates a pull request from `ci/baseline-profiles`.
The workflow starts CI and Device on that branch after each push it makes.
Enable **Allow GitHub Actions to create and approve pull requests** in the
repository's Actions settings so the workflow can open the pull request.

To compare cold startup with and without baseline profile compilation,
run the startup benchmark on a physical device from `android/`. The
plugin supplies a debug-signed benchmark variant of the release build.

```sh
./gradlew :benchmarks:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=li.mercury.bigbrother.benchmarks.StartupBenchmark
```

## Conventions

- Code, comments, commit messages and documentation are in English. User
  interface text is in English with a Simplified Chinese translation.
- The app follows the module structure of
  [Now in Android](https://github.com/android/nowinandroid), as used by
  [Blocker](https://github.com/lihenggui/blocker).
- Kotlin is formatted with ktlint through Spotless, which also adds the
  license header to new files. Rust is formatted with `rustfmt` and linted
  with `clippy -D warnings`. Every source file carries a license header.

  ```sh
  (cd android && ./gradlew spotlessApply :build-logic:convention:spotlessApply)
  cargo fmt --all
  ```

- Kotlin tests are named `givenState_whenAction_thenOutcome`. Rust tests
  are named after the behaviour they check, in snake_case.

## Checks

CI, Device and Kernels report `CI result`, `Device result` and
`Kernels result` on pull requests and merge groups. Require these three
checks and up-to-date branches when configuring the branch ruleset. A result
passes when all jobs succeeded or were skipped. A failed or cancelled job
fails the result.

Each workflow uses the changed files to decide which work to run on pull
requests and merge groups. All three exclude Markdown changes.

| Workflow | Runs for these changes |
| --- | --- |
| CI | Files outside `docs/`. |
| Device | The Rust components and Cargo files, the module, scripts, `testdata/permissions/`, `android/app/`, `android/helper/`, `android/hook/`, `android/canary/`, Gradle build logic and project configuration, `.github/actions/setup/` or `device.yml`. Changes limited to `android/app/` run only the four `checks` emulators and skip the eight `permissions-1`/`permissions-2` shards. |
| Kernels | The Rust components and Cargo files, `scripts/check-kernels.sh` or `kernels.yml`. |

CI runs the full build on pushes to `main`. Device runs nightly. Kernels
also runs on pushes that match its path filter, with temporary queue branches
handled by the merge group run. All three can be started manually. Run the
workflow regression tests locally with Python, PyYAML and `jq`.

```sh
python3 .github/tests/test_ci_gates.py
```

CI also checks that changes to the following include a baseline update in
the same pull request.

| Check | Baseline | Update with |
| --- | --- | --- |
| Libraries in the release APK | `android/app/dependencies/releaseRuntimeClasspath.txt` | `./gradlew :app:dependencyGuardBaseline` |
| Permissions and other manifest facts | `android/app/release-badging.txt` | `./gradlew :app:updateReleaseBadging` |
| Licenses of dependencies | allow-list in `build-logic/.../Licensee.kt` | edit the list |

Run the same host checks as CI before opening or updating a pull request.

```sh
./scripts/check.sh
```

While working, run `./scripts/check.sh --fast` for the workflow regression
tests, formatting, Clippy, Rust tests, shell syntax, license headers, Spotless
and Kotlin structure lint. The host script also runs ShellCheck and checks
Rust dependency licenses when `shellcheck` and `cargo-deny` are installed.
CI runs both tools and checks the full Rust dependency graph against RustSec.
The pre-push hook is opt-in. Enable it for this clone to run the fast checks
before each push.

```sh
git config core.hooksPath .githooks
```

Use JDK 21 to match CI. Install `rustfmt` and `clippy` for stable Rust and
`clippy` for nightly. Clippy checks Rust functions against an 80-line
threshold. The full check also uses nightly Clippy for both eBPF syscall-state
backends on x86_64 and aarch64.

`cargo test` checks daemon module cycles at the top level and between
siblings by following production source paths. To run that check alone,
use `cargo test -p bbd --test module_cycles`.

The Kotlin structure run checks functions over 80 lines, files with
composable functions over 500 lines and other Kotlin files over
800 lines. It excludes tests and generated code from those limits and
checks test names against `givenState_whenAction_thenOutcome`. It uses
the debug variant for Android app and library modules, which includes
their test sources. JVM modules using the lint convention run the same
structure checks.

## Releasing

Upgrade support starts with v0.3.0. When changing a schema
that a supported release wrote, test the upgrade from that release and
verify that records and Jail state survive, including the saved grants.
Keep the installation and upgrade steps in both READMEs current.

Set `version` and increase `versionCode` in `module/module.prop`. Update
the app's badging baseline if it changes. After the release commit passes
CI, push a tag that matches `version`.
`release.yml` builds and attests the signed `.zip`, then publishes it with
the R8 mapping and a SHA-256 checksum file for both assets. It needs the
repository secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.
