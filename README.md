# Big Brother

Records which apps read which of your files on a rooted Android device, and
which device identifiers they collect.

It consists of a KernelSU module that does the recording, an Android app to
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
- Records are kept on the device for 30 days, with the time of each access.

## Requirements

- arm64 device with a GKI 2.0 kernel (5.10 or newer) and
  [KernelSU](https://kernelsu.org).
- Optional: [LSPosed](https://github.com/LSPosed/LSPosed) with Xposed API 101
  or newer.

Developed and tested on Android 16, kernel 6.6.

## Installation

The APK is both the app and the KernelSU module.

1. Install the APK and grant it root in the KernelSU manager.
2. Open the app and tap **Install module**, then reboot.
3. Optional: enable Big Brother in the LSPosed manager, confirm its scope
   (media provider, external storage provider, download provider) and reboot.

Alternatively, flash the APK directly:

```sh
ksud module install big_brother-<version>.apk
```

The module's Action button in the KernelSU manager shows the daemon's status
and the end of its log.

## Building

Prerequisites:

- Rust stable and nightly with `rust-src`; target `aarch64-linux-android`
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

## How it works

```
kernel   kprobe on security_file_open            the file actually opened
         kprobes on file-related syscalls        return values, stat/access/inotify
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
| `module/` | KernelSU module scripts, packaged into the APK |
| `android/` | App and LSPosed hook |
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
