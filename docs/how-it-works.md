# How Big Brother works

```
kernel   kprobe on selinux_file_open             the file actually opened
         kprobes on file-related syscalls        return values, stat/access/inotify
         kprobes on execve, connect, sendto      programs run, local destinations
         kprobes on ptrace, process_vm_*         access to other processes
         kprobe on selinux_netlink_send          network configuration requests
         tracepoint binder_ioctl                 requests to system services
           | ring buffer
daemon   bbd: classify, aggregate, store in SQLite
           ^ spool file
hook     LSPosed, inside the provider processes  who queried or opened what
           |
app      reads the daemon's JSON output as root
```

- Opens are observed at the `file_open` security hook, after path
  resolution. The recorded path is rebuilt from the dentry chain, so `..`,
  symlinks and `/proc/self/root` do not hide a file. Shared storage is
  identified by filesystem type (FUSE), not by path prefix.
- The probes on security hooks attach to SELinux's hook functions, which
  the kernel calls through its table of security modules. The
  `security_*` functions that call them can be inlined into their callers,
  as they are on android13-5.15 kernels, and a probe on an inlined function
  attaches but never fires.
- Kernel struct offsets are read from BTF at startup and passed to the eBPF
  programs, so one binary works across kernel versions.
- Network destinations are classified in the kernel; only loopback, private,
  link-local and multicast addresses leave it. A connection to a port the
  app itself listens on is not recorded. Destinations on the internet leave
  it only of an app in the sandbox whose connections the user has recorded
  (docs/sandbox.md), and are kept apart from the other records.
- The kernel side timestamps each event and keeps it in a buffer. The daemon
  takes the events in and writes them to the database every 30 seconds, or
  sooner if the buffer is filling up. Each query from the app has it write
  first, so the app sees the latest records.
- Only the file probes are required. The others are attached if the kernel
  allows it. A probe can attach and still never run, when the function it
  is on has been inlined into its callers, so after attaching, the daemon
  checks that each one fires: a child process that takes uid 70000, which
  the probes trace and no app has, opens `/proc/version`, checks access to
  it, execs `/`, connects a UDP socket to the loopback address, calls
  `process_vm_readv` on the daemon and asks netlink for the interfaces.
  Its events are counted, not recorded. `bbd stats` reports as active
  (`coverage_*`) what is attached and fired, and the app says which kinds
  of activity are not recorded on the device. `bbd selftest` runs the same
  check on its own and exits non-zero if a feature does not fire.
- Requests to system services are read from the binder transaction the app
  hands to the kernel: the interface name and the method's number, both part
  of binder's userspace format. Which number is which method differs between
  Android versions and manufacturers, so the daemon asks the device at
  startup, by running a small program from the app (`BinderCatalog`) that
  prints them from the framework's own classes.
- Sensors are turned on and off on a connection to the sensor service, a
  C++ interface whose method numbers are fixed. The calls name a sensor by a
  handle that differs between devices; the daemon reads which handle is
  which type from `dumpsys sensorservice` the first time a sensor is turned
  on, and again, at most once a minute, for a handle it does not know. Each
  period a sensor was on is stored (`bbd sensors`), ended by the call that
  turns it off, the connection closing, or the app's process ending. Calls
  that turn a sensor on wake the daemon at once, and while a sensor is on,
  its process is checked every 5 seconds, so a session whose app is killed
  ends at most 5 seconds early. Only the rate asked for is known;
  Android 12 and later deliver at most 200 Hz to apps without the
  `HIGH_SAMPLING_RATE_SENSORS` permission.
- Permission use comes from the system's app-ops records, read by another
  small program from the app (`AppOpsDump`). It is the one source that says
  what an app was actually given; the app marks these as permission records.
- For personal data, only the collection asked for is kept
  (`content://sms/inbox`), never which person or message.
- PackageManager identifies preinstalled provider UIDs. The daemon opens
  spools relative to verified directory descriptors and accepts regular,
  single-link files owned by the provider. The hook drops personal item
  identifiers before queueing a record.
- Telemetry has a 4 MiB buffer budget. Recording pauses at a 256 MiB
  database watermark or below 64 MiB of free space. A full buffer drops new
  samples. Existing history follows its retention period, and sandbox
  recovery writes have a separate allowance.
- The LSPosed hook intercepts `ContentProvider.Transport` in the provider
  processes and takes the caller's uid from Binder. The observed apps are not
  hooked.
- The module has no `system/` overlay, no SELinux rules and no
  `post-fs-data` script. If a boot does not complete while the daemon is
  running, the module disables itself on the next boot; if a hooked provider
  process keeps restarting, the hook stops installing itself.

## The sandbox

Besides recording, the daemon can give an app a view of the device with
nothing of the user's in it. That is described in [sandbox.md](sandbox.md).

## Components

| Path | Contents |
| --- | --- |
| `bb-ebpf/` | eBPF programs ([aya](https://aya-rs.dev)) |
| `bb-common/` | Types shared between the eBPF programs and the daemon |
| `bbd/` | Daemon and query CLI |
| `module/` | Module scripts, packaged into the APK |
| `android/` | App, LSPosed hook, and the canary test app |
| `scripts/` | Build and test scripts |

The daemon runs as `bbd run` under `module/supervise.sh`, which restarts it
with increasing delays if it exits. Its database is
`/data/adb/big_brother/bb.db` and its log `/data/adb/big_brother/bbd.log`.
The app runs the same binary for its queries (`bbd apps`, `bbd files`,
`bbd timeline`, `bbd sensors`, `bbd stats` and others), which print JSON.
`bbd sandbox` sends commands to the running daemon instead.

When the app is updated, it compares the installed module with the one it
carries, by version code and, for the same version, by the daemon's SHA-256,
and replaces an older or different module in place (`module/sync.sh`).
