# AGENTS.md: module scripts

These scripts run as root on the device, under KernelSU, Magisk and APatch.
They are packaged into the app's APK, which is also the flashable module, and
`module.prop` holds the version of both.

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
- `service.sh` runs at boot (late_start) and must return quickly; the daemon
  runs under `supervise.sh` in the background, which restarts it with
  increasing delays.
- `sync.sh` is run by the app to update an installed module in place. Stage and verify
  the whole module before stopping the daemon and exchanging the directories.
- `uninstall.sh` takes every app out of the sandbox. Root managers run it
  either at once, with the daemon running, or at the next boot, before
  anything of the module has started: it has to work both ways. It then
  removes the data in `/data/adb/big_brother` and what the
  sandbox shares with the hook in `/data/system`. The sandbox directories
  under `/data/media` are the apps' files and are kept.
- `supervise.sh` restarts a daemon that died at once the first time: apps in
  the sandbox start without it while there is none.
- The module has no `system/` overlay, no SELinux rules and no `post-fs-data`
  script. Keep it that way: a module that can break booting must not.

## Version

`version` must match the release tag (`v0.2.0`), and `versionCode` increases
with every release. The app compares the installed module with its own by
`versionCode`, then by SHA-256 digests of the installed module files and the helper APK.
