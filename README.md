# Big Brother

[![CI](https://github.com/lihenggui/bigbrother/actions/workflows/ci.yml/badge.svg)](https://github.com/lihenggui/bigbrother/actions/workflows/ci.yml)

Records which apps read which of your files on a rooted Android device, and
which device identifiers they collect.

It consists of a root module that does the recording, an Android app to
browse the result, and an optional LSPosed hook that covers access made
through the media library.

## Features

- Per-app list of the files on shared storage an app opened, with read counts
  and timestamps, browsable like a file manager.
- Attempts that the system denied, existence checks (`stat`, `access`) and
  inotify watches, shown separately from successful reads.
- MediaStore queries and files opened through MediaStore, the Storage Access
  Framework and the download provider, attributed to the calling app
  (requires LSPosed).
- Reads of privacy-relevant `/proc`, `/sys` and `/dev` entries: network
  hardware address, ARP table, serial numbers, other processes, root and
  emulator checks, and so on.
- Requests to system services for the installed apps, the clipboard, the
  location, Wi-Fi networks, phone identifiers, accounts, running apps,
  usage history and sensors, and reads of the Android ID.
- Use of the camera, the microphone, the location and other sensitive
  permissions, and refusals, as the system itself recorded them.
- Queries for contacts, the call log, text messages and the calendar,
  attributed to the calling app (requires LSPosed).
- Requests for the device's network interfaces, hardware addresses and
  neighbours over netlink, and whether the system allowed them.
- Attempts to trace another app's process or read its memory.
- System programs an app runs to learn about the device: `getprop`,
  `pm list packages`, `ip neigh`, attempts to run `su`, and so on.
- Connections to ports on the device itself and to other devices on the
  local network, and multicast device discovery.
- Records are kept on the device for 30 days, with the time of each access.

## Requirements

- arm64 or x86_64 device with a GKI 2.0 kernel (5.10 or newer, with BTF).
  x86_64 covers the Android emulator.
- Root with module support: [KernelSU](https://kernelsu.org),
  [Magisk](https://github.com/topjohnwu/Magisk) or
  [APatch](https://apatch.dev).
- Optional: [LSPosed](https://github.com/LSPosed/LSPosed) with Xposed API 101
  or newer.

Developed and tested on Android 16, kernel 6.6, with KernelSU.

## Installation

The APK is both the app and the flashable module; the `.zip` is the same
file under another name. Use either route:

- Flash `big_brother-<version>.zip` in your root manager. This installs the
  app as well.
- Or install the APK, grant it root in your root manager, open the app and
  tap **Install module**.

Then:

1. Reboot (not needed on KernelSU, which applies the module at once).
2. Optional: enable Big Brother in the LSPosed manager, confirm its scope
   (the media, external storage and download providers; the contacts,
   telephony and calendar providers) and reboot.

From a root shell:

```sh
ksud module install big_brother-<version>.apk       # KernelSU
magisk --install-module big_brother-<version>.apk   # Magisk
apd module install big_brother-<version>.apk        # APatch
```

The module's Action button in the root manager shows the daemon's status
and the end of its log (Magisk 28 or newer).

## Building

Prerequisites:

- Rust stable and nightly with `rust-src`; targets `aarch64-linux-android`
  and `x86_64-linux-android`
- [bpf-linker](https://github.com/aya-rs/bpf-linker)
- Android NDK and SDK (platform 37)
- JDK 17 or newer

```sh
./scripts/build.sh
```

The APK is written to `out/`. It is signed with the debug key unless
`android/keystore.properties` exists, with the keys `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`.

Tests:

```sh
cargo test -p bbd
(cd android && ./gradlew test)
```

On a device with the module running, `scripts/check-canary.sh` installs a
test app (`android/canary`) that does one of everything Big Brother is meant
to notice, and checks that each left a record:

```sh
(cd android && ./gradlew :canary:assembleDebug)
./scripts/check-canary.sh
```

Every push and pull request is built and tested by GitHub Actions
(`.github/workflows/ci.yml`). Pushing a tag that matches the version in
`module/module.prop`, such as `v0.1.6`, builds the signed APK and publishes
it as a release (`release.yml`); this needs the repository secrets
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD`.

## How it works

```
kernel   kprobe on security_file_open            the file actually opened
         kprobes on file-related syscalls        return values, stat/access/inotify
         kprobes on execve, connect, sendto      programs run, local destinations
         kprobes on ptrace, process_vm_*         access to other processes
         kprobe on security_netlink_send         network configuration requests
         tracepoint binder_ioctl                 requests to system services
           | ring buffer
daemon   bbd: classify, aggregate, store in SQLite
           ^ spool file
hook     LSPosed, inside the provider processes  who queried or opened what
           |
app      reads the daemon's JSON output as root
```

- Opens are observed at `security_file_open`, after path resolution. The
  recorded path is rebuilt from the dentry chain, so `..`, symlinks and
  `/proc/self/root` do not hide a file. Shared storage is identified by
  filesystem type (FUSE), not by path prefix.
- Kernel struct offsets are read from BTF at startup and passed to the eBPF
  programs, so one binary works across kernel versions.
- Network destinations are classified in the kernel; only loopback, private,
  link-local and multicast addresses leave it. A connection to a port the
  app itself listens on is not recorded.
- Only the file probes are required. The others are attached if the kernel
  allows it; `bbd stats` reports which are active (`coverage_*`), and the
  app says which kinds of activity are not recorded on the device.
- Requests to system services are read from the binder transaction the app
  hands to the kernel: the interface name and the method's number, both part
  of binder's userspace format. Which number is which method differs between
  Android versions and manufacturers, so the daemon asks the device at
  startup, by running a small program from the app (`BinderCatalog`) that
  prints them from the framework's own classes.
- Permission use comes from the system's app-ops records, read by another
  small program from the app (`AppOpsDump`). It is the one source that says
  what an app was actually given, and the app shows it in a tab of its own.
- For personal data, only the collection asked for is kept
  (`content://sms/inbox`), never which person or message.
- The LSPosed hook intercepts `ContentProvider.Transport` in the provider
  processes and takes the caller's uid from Binder. The observed apps are not
  hooked.
- The module has no `system/` overlay, no SELinux rules and no
  `post-fs-data` script. If a boot does not complete while the daemon is
  running, the module disables itself on the next boot; if a hooked provider
  process keeps restarting, the hook stops installing itself.

## Limitations

- Without the LSPosed hook, access through content providers is not
  recorded. With it, only successful provider calls are, and a query is
  recorded as the collection and the number of rows returned.
- `stat`/`access`/inotify records and opens that fail during path lookup are
  matched on the path string the app passed, and can be evaded by an app that
  spells the path unusually. Successful opens cannot.
- A request to a system service is recorded when it is made. What the
  service answered, or whether it refused, is not seen. Until the device's
  method numbers are known, shortly after boot, such requests are not
  recorded.
- Permission use is read from the system twice a minute. The system keeps
  the latest use and the latest refusal per app and permission, so several
  uses in between count as one.
- Connections are recorded when attempted; whether they succeeded is not
  known. On mobile data, the carrier's private addresses count as the local
  network.
- A command line is recorded up to six arguments of 63 bytes each.
- File operations an app delegates to a privileged process (root, Shizuku)
  are not attributed to it.
- Files already open when the daemon starts are not seen.
- A record means a file was opened, not how much of it was read.

## Repository layout

| Path | Contents |
| --- | --- |
| `bb-ebpf/` | eBPF programs ([aya](https://aya-rs.dev)) |
| `bb-common/` | Types shared between the eBPF programs and the daemon |
| `bbd/` | Daemon and query CLI |
| `module/` | Module scripts, packaged into the APK |
| `android/` | App, LSPosed hook, and the canary test app |
| `scripts/` | Build script |

The app follows the module structure of
[Now in Android](https://github.com/android/nowinandroid), as used by
[Blocker](https://github.com/lihenggui/blocker).

## License

Copyright (C) 2026 Mercury Li

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License version 3 as published by the Free
Software Foundation. See [LICENSE](LICENSE).

The eBPF programs (`bb-ebpf/`) and the types they share with the daemon
(`bb-common/`) are loaded into the kernel and are licensed under the GNU
General Public License version 2 or, at your option, any later version. See
[LICENSE-GPL-2.0](LICENSE-GPL-2.0).

The Cargo build scripts are based on
[aya-template](https://github.com/aya-rs/aya-template).
