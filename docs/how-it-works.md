# How Big Brother works

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
- The kernel side timestamps each event and keeps it in a buffer. The daemon
  takes the events in and writes them to the database every 30 seconds, or
  sooner if the buffer is filling up. Each query from the app has it write
  first, so the app sees the latest records.
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
  what an app was actually given; the app marks these as permission records.
- For personal data, only the collection asked for is kept
  (`content://sms/inbox`), never which person or message.
- The LSPosed hook intercepts `ContentProvider.Transport` in the provider
  processes and takes the caller's uid from Binder. The observed apps are not
  hooked.
- The module has no `system/` overlay, no SELinux rules and no
  `post-fs-data` script. If a boot does not complete while the daemon is
  running, the module disables itself on the next boot; if a hooked provider
  process keeps restarting, the hook stops installing itself.

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
`bbd timeline`, `bbd stats` and others), which print JSON.

When the app is updated, it compares the installed module with the one it
carries, by version code and, for the same version, by the daemon's SHA-256,
and replaces an older or different module in place (`module/sync.sh`).
