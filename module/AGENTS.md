# AGENTS.md: module scripts

These scripts run as root on the device. They are packaged into the app's
APK, which is also a module that KernelSU, Magisk and APatch can flash, and
`module.prop` holds the version of both. The module is in one of two places:
`/data/adb/modules/big_brother` when flashed in a root manager, or
`/data/adb/big_brother_direct` when the app installed it with root alone.
The flashed one is in use once it has its daemon (`bin/bbd`): a manager
keeps a directory without it for a module it applies at the next boot.

## Shell

- Write POSIX `sh`: the scripts run under Android's `/system/bin/sh` or the
  root manager's BusyBox `ash`, not bash. No arrays, `[[ ]]` or process
  substitution. CI only checks syntax (`bash -n`), so test on a device.
- Tools come from toybox or BusyBox, whose options differ. Check that a
  command works before relying on it (see how `supervise.sh` checks `nice` and
  `ionice`).

## Lifecycle

- `customize.sh` is sourced by the root manager's installer: use `ui_print`
  and `abort`, and the installer's `$ZIPFILE`, `$MODPATH` and `$ARCH`. It also
  installs the app from the same file.
- `direct.sh` installs the module without a root manager: it gives
  `customize.sh` what an installer would, moves the result into place and
  starts it. It leaves a script in `/data/adb/service.d`, which the three
  managers run at late_start, to start the daemon at boot (`direct.sh boot`);
  where nothing runs it, the app starts the daemon. `direct.sh remove`
  uninstalls, from the app or at boot once the app is gone from
  `/data/system/packages.list`. A module flashed afterwards takes over in its
  `service.sh`. `scripts/install-on-emulator.sh` installs with it too.
- `service.sh` runs at boot (late_start) and must return quickly; the daemon
  runs under `supervise.sh` in the background, which restarts it with
  increasing delays.
- `sync.sh` is run by the app to update an installed module in place. Stage and verify
  the whole module before stopping the daemon and exchanging the directories. Both
  install paths check the existing database with the staged daemon.
- `uninstall.sh` takes every app out of the sandbox. Root managers run it
  either at once, with the daemon running, or at the next boot, before
  anything of the module has started, and `direct.sh` does the same: it has
  to work both ways. It then
  removes the data in `/data/adb/big_brother` and what the
  sandbox shares with the hook in `/data/system`. The sandbox directories
  under `/data/media` are the apps' files and are kept.
- `supervise.sh` restarts a daemon that died at once the first time: apps in
  the sandbox start without it while there is none.
- The module has no `system/` overlay, no SELinux rules and no `post-fs-data`
  script. Keep it that way: a module that can break booting must not.

## Version

`version` must match the release tag, and `versionCode` increases
with every release. The app compares the installed module with its own by
`versionCode`, then by SHA-256 digests of the installed module files and the helper APK.
