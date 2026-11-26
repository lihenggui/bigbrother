# Changelog

## [Unreleased]

### Added

- Set up and remove recording from the app with root access alone. Setup
  starts recording without a reboot.
- Security policy, code of conduct, feature request form and pull request
  checklist.
- Store listing text in English and Simplified Chinese.

### Changed

- For app-managed installations without boot integration, start recording
  when the app opens.
- Retry the Android permission service sooner after boot so Jail can
  check Files and media status.

### Fixed

- Wait for recording to start after setup before loading the Overview.
- Restore the boot script when updating a module installed from the app.

### Removed

- Drop Boot ID records and stop counting the system's SELinux context
  reads at process startup as app activity.

## [v0.4.2] - 2026-11-23

### Fixed

- Support the service binding overload on OxygenOS 16 so Jail can apply
  its advertising ID controls.
- Retry access to the Android permission and package services after boot
  so Files and media status can recover without restarting the helper.

## [v0.4.1] - 2026-11-22

### Changed

- Bump the app and module version and point the clean-install instructions
  for builds older than v0.3.0 at this release.

## [v0.4.0] - 2026-11-22

### Added

- Package baseline and startup profiles for the app.
- Add `./scripts/check.sh` to run host checks.

### Changed

- Redesign the first-launch guide.
- Apply the app theme's motion to list and content changes.
- Move root helpers into `android/helper/` and consolidate how the app
  reads the daemon.

### Fixed

- Recover held app starts when the Jail worker stops and refuse starts
  that cannot get required mounts.
- Check the active firewall rules before reporting Jail network coverage
  and clean up network restrictions on uninstall.
- Restore DNS access if the relay used for recording stops.
- Restore saved grants when the module is disabled.
- Wait for file history before opening the file details sheet so its
  height stays steady.

## [v0.3.0] - 2026-11-20

### Added

- Jail with global settings, per-app overrides and automatic isolation
  of newly installed apps.
- Jail network blocking, connection recording and rules for individual
  destinations.
- Clipboard read, write and watch events, and sensor sessions with their
  requested sampling rates.
- A first-launch guide and predictive back support.
- A probe self-test and a permission matrix checked on the JVM and on
  Android devices.

### Changed

- Start upgrade support at v0.3.0. Take apps out of Jail if applicable and
  remove the old module and app before installing from an older build.
- Run Jail commands on a separate thread so the daemon can keep reading
  events while a command waits for Android.

### Fixed

- Attach file-open probes to SELinux hook functions so they fire on
  Android kernels that inline the security wrappers.
- Keep global search in the list pane on layouts that show an app's
  details alongside the list.

## [v0.2.0] - 2026-11-17

### Added

- Record app access to shared files, device identifiers and system
  services, with an app for browsing and searching the records.
- Optional LSPosed hooks for recording content provider access, including
  queries for contacts, call logs, messages and calendars.
- A Material 3 Expressive interface with app details and an hourly
  timeline.
- Builds for arm64 and x86_64, with KernelSU, Magisk and APatch support.
- Log export from Settings and bilingual user documentation.

### Changed

- Ship one ZIP that can be flashed as a module or renamed and installed
  as an APK.
- Update the installed module from the app without rebooting.

[Unreleased]: https://github.com/lihenggui/bigbrother/compare/v0.4.2...main
[v0.4.2]: https://github.com/lihenggui/bigbrother/compare/v0.4.1...v0.4.2
[v0.4.1]: https://github.com/lihenggui/bigbrother/compare/v0.4.0...v0.4.1
[v0.4.0]: https://github.com/lihenggui/bigbrother/compare/v0.3.0...v0.4.0
[v0.3.0]: https://github.com/lihenggui/bigbrother/compare/v0.2.0...v0.3.0
[v0.2.0]: https://github.com/lihenggui/bigbrother/releases/tag/v0.2.0
