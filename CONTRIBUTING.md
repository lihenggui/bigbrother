# Contributing

Bug reports and pull requests are welcome. For a bug, include the output of
**Settings → Export logs** in the app; the issue template lists the other
details that help.

## Building

Prerequisites:

- Rust stable, and nightly with `rust-src`; targets `aarch64-linux-android`
  and `x86_64-linux-android`
- [bpf-linker](https://github.com/aya-rs/bpf-linker)
- Android SDK (platform 37) and NDK
- JDK 17 or newer

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
(`x86_64-unknown-linux-musl`, `musl-gcc`) unless given one (`--bbd`). The
`Kernels` workflow runs it for every change to the daemon or the eBPF
programs:

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
from `out/` with the module's own installer and starts the daemon, without
a root manager:

```sh
./scripts/install-on-emulator.sh
```

The Device workflow (`.github/workflows/device.yml`) does this on emulators
of API 33 to 36, nightly and for pull requests that touch the eBPF
programs, the daemon, the Cargo manifests, the module, the scripts, the
app, the hook, the canary, the Gradle build or the build setup, and plays
the permission matrix there too. Without LSPosed there, the sandbox check
skips what needs the hook.

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

Build the daemons with `./scripts/build.sh`, then connect an Android 13
(API 33) or newer emulator or device. The generator launches the app,
skips the first-launch guide if shown, visits the four main destinations,
and scrolls the Apps list when records are available. It also scrolls
Settings on a fresh install without root or the module.

```sh
cd android
./gradlew :app:generateReleaseBaselineProfile
```

Review and commit the generated `baseline-prof.txt` and `startup-prof.txt`
under `app/src/release/generated/baselineProfiles/`. The release build
packages the baseline profile and uses the startup profile for DEX
layout. Profile generation is a separate task from CI and release builds.

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

CI (`.github/workflows/ci.yml`) runs on every push and pull request. Besides
building and testing, it fails when one of these changes without the
baseline being updated in the same pull request:

| Check | Baseline | Update with |
| --- | --- | --- |
| Libraries in the release APK | `android/app/dependencies/releaseRuntimeClasspath.txt` | `./gradlew :app:dependencyGuardBaseline` |
| Permissions and other manifest facts | `android/app/release-badging.txt` | `./gradlew :app:updateReleaseBadging` |
| Licenses of dependencies | allow-list in `build-logic/.../Licensee.kt` | edit the list |

Run the source checks locally.

```sh
cargo fmt --all --check
cargo clippy --all-targets -- -D warnings
cargo test
(cd android && ./gradlew lintStructure -PlintStructureOnly)
(cd android && ./gradlew :app:lintRelease)
```

Clippy checks Rust functions against an 80-line threshold. Nightly Clippy
checks both eBPF syscall-state backends with the x86_64 configuration.

```sh
rustup component add --toolchain nightly clippy
for features in "" task-storage; do
  RUSTFLAGS='--cfg bpf_target_arch="x86_64"' cargo +nightly clippy \
    -Z build-std=core --target bpfel-unknown-none -p bb-ebpf --features "$features" \
    -- -A clippy::all -D clippy::too_many_lines
done
```

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
CI checks that every Rust and shell file has a license header.

## Releasing

Upgrade support starts with v0.3.0. When changing a schema
that a supported release wrote, test the upgrade from that release and
verify that records and Jail state survive, including the saved grants.
Keep the installation and upgrade steps in both READMEs current.

Set `version` and increase `versionCode` in `module/module.prop`. Update
the app's badging baseline if it changes. After the release commit passes
CI, push a tag that matches `version`.
`release.yml` builds the signed `.zip` and publishes it, with the R8
mapping, as a GitHub release. It needs the repository secrets
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD`.
