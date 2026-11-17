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
app, and writes `out/big_brother-<version>.zip` together with the R8
mapping. The file is both the app's APK and the flashable module. It is
signed with the debug key unless `android/keystore.properties` exists, with
the keys `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

## Testing

```sh
cargo test
(cd android && ./gradlew test)
```

On a device with the module running, `scripts/check-canary.sh` installs a
test app (`android/canary`) that does one of everything Big Brother is meant
to notice, and checks that each left a record:

```sh
(cd android && ./gradlew :canary:assembleDebug)
./scripts/check-canary.sh
```

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

It also runs Android lint on the release app and checks that every Rust and
shell file has a license header.

## Releasing

Push a tag that matches `version` in `module/module.prop`, such as `v0.2.0`.
`release.yml` builds the signed `.zip` and publishes it, with the R8
mapping, as a GitHub release. It needs the repository secrets
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD`.
