# AGENTS.md: Android app

The app follows the module structure of Now in Android, as used by Blocker:
convention plugins in `build-logic/`, versions in `gradle/libs.versions.toml`,
Hilt, Compose with Material 3 Expressive, and Navigation 3.

## Modules

- `app/`: the application, navigation and adaptive layout (`ui/BigBrotherApp.kt`).
- `helper/` (`:helper`): the daemon's resident `FrameworkHelper` runs as root
  under `app_process`. The APK also carries `AppOpsGuard --recover` for
  offline restoration.
- `core/model` holds data types. `core/domain` holds use cases.
  `core/data`: repositories; `root/RootShell.kt` is
  the only place the app runs root commands. `core/datastore`: settings.
  `core/ui`, `core/designsystem`: shared UI. `core/navigation`: navigator and
  scene strategies. `core/common`: dispatchers, DI qualifiers, logging.
- `core/testing`: fakes and `MainDispatcherRule` for unit tests.
  `core/screenshot-testing`: Roborazzi helpers and sample data.
- `feature/<name>/api` (navigation keys) and `feature/<name>/impl` (screens,
  view models) for overview, applist, appdetail, timeline, search, settings
  and jail (the sandbox: isolated apps and their items).
- `hook/`: the LSPosed hook, which runs inside system content providers and,
  for the sandbox, inside system_server.
  `canary/`: a test app that triggers every kind of record.

## Commands

Run from `android/`:

```sh
./gradlew spotlessApply :build-logic:convention:spotlessApply   # ktlint and license headers
./gradlew test                                                   # unit and screenshot tests
./gradlew recordRoborazziDebug                                   # re-record screenshots after a UI change
./gradlew :app:dependencyGuard :app:licenseeAndroidRelease :app:lintRelease
./gradlew :app:checkReleaseBadging                               # run separately from updateReleaseBadging
```

Assembling `:app` needs the daemons in `../out/` (`../scripts/build.sh`).

## Sandbox

- `hook/.../SandboxHook.kt` runs inside system_server (scope `system`). A
  fault there restarts the device: every hooker falls back to the original
  method when its own work throws, and nothing is hooked that cannot be
  fully resolved when system_server starts. The decisions are in
  `SandboxRules.kt`, free of framework classes, and tested on the JVM.
- Only packages the user installed are hidden from a sandboxed app. What
  came with the system stays visible whatever its app id: the same check
  decides whether an app can reach the WebView or the media provider.
- `FrameworkHelper.kt` routes catalog, usage and guard frames. Keep its
  framing in sync with `bbd/src/helper/`. Guard messages use
  `AppOpsGuardProtocol.kt` and `bbd/src/sandbox/appops/`. The guard handler
  reaches Android through `AppOpsSystem`, which the JVM tests replace with
  a model. Offline recovery uses the same handler and its own journal.
- The hook and the daemon share two files in `/data/system`; their formats
  are in `SandboxRules.kt` and `bbd/src/sandbox/identity.rs`.
- The lines of these formats, and of the hook's spool (`EventSpool.kt`,
  `bbd/src/spool.rs`), are listed with their meaning in `../testdata/protocol/`,
  which the tests of both sides read. A change to a format changes its cases.

## Architecture

- UI (composables and ViewModels) uses `core:data` repositories directly or
  through `core:domain` use cases. Keep the Gradle module graph acyclic.
- Keep data sources, `RootShell`, `PackageManager` and DataStore out of UI code.
- Reach the daemon only through `BbdDataSource` (`core/data/.../bbd/`), which
  builds commands and parses JSON. Repositories cache, merge and map.
- Use `core:domain` only for logic shared by several ViewModels, as in
  `GetAppVisibilityUseCase`. Otherwise call the repository.
- Name use cases `<Verb><Noun>UseCase` and expose `operator fun invoke`.
- Keep Android types out of the JVM library `core:model`. Among project
  modules, `core:ui` depends only on `core:model` and `core:designsystem`.
- Each ViewModel exposes one `uiState: StateFlow<<Screen>UiState>` using
  `stateIn(viewModelScope, WhileSubscribed(5_000), …)`.
- Keep launches out of `init`. Put messages and failures in UI state fields
  and keep `Channel` and `SharedFlow` events out of the UI contract.
- Keep `Context` out of ViewModels. Use `@Dispatcher` and `@AppPackageName`
  for injected dispatchers and the app package name.
- Keep ViewModels at screen entry points. Pass state and `on…` lambdas to
  child composables.
- Repositories expose suspend functions or flows. Classify data-layer errors
  as `RecordsException` and map them to `LoadFailure` in ViewModels.
- Keep raw command and installer output out of the UI. Use only
  `core/ui/.../Messages.kt` for failure text. Rethrow `CancellationException`.
- Reuse uid math in `core/model/.../Uids.kt` and time formatting in `core/ui/.../Format.kt`.
- Reuse settings rows such as `SegmentedSwitchRow` in `core:designsystem`.

## Code

- Root commands go through `RootShell.run(command, label)`. Quote every value
  that comes from an app or the user with `shellQuoted()`. The label is what
  gets logged; never put paths or search text in it.
- Keep work off the main thread: loads run on the injected default or IO
  dispatcher, and app icons load through Coil (`AppIcon`).
- Log with Timber. The persisted log (`ReleaseTree`) must not contain paths,
  package names or search text.
- Screens have a public entry point that takes the view model and an internal,
  stateless overload that takes the UI state; tests and previews use the latter.
- Layouts must work from compact phones to tablets and unfolded foldables
  (list-detail panes, navigation bar or rail). Check new screens at more than
  one width.

## User interface text

- Every string goes in `values/strings.xml` and `values-zh/strings.xml`.
- Copy is short and factual, for semi-technical users: no chatty tone, no
  internals such as uids, mode bits or raw paths unless the screen is about
  them. In Chinese, do not address the user with "你的".
- System apps and unattributed isolated processes are hidden by default
  (`GetAppVisibilityUseCase` in `core/domain`). New lists of apps must
  respect that setting.

## Tests

- Unit tests run on the JVM with Android calls returning defaults.
- Use one shared `Test*` fake per repository interface in `core:testing` in
  place of mocks. Put shared fixtures in `core/testing/.../data/`.
- Verify with `CI=true ./gradlew test`. Offline Robolectric has only SDKs 24
  (the library default) and 37. Use `@Config(sdk = [37])` for newer APIs.
- Screenshot tests compare with checked-in images (`roborazzi.test.verify=true`),
  so a UI change fails `./gradlew test` until the images are recorded again.
  Sample data uses made-up apps under `com.example.*` only.

## Dependencies and baselines

- The release APK's libraries (`app/dependencies/`), its badging
  (`app/release-badging.txt`) and the license allow-list
  (`build-logic/.../Licensee.kt`, GPL-3.0-compatible licenses only) are
  checked in CI. Update a baseline only for an intended change, in the same
  pull request.
