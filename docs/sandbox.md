# The sandbox

An app put in the sandbox keeps running, with its permissions shown as
granted, but is given an empty or made-up answer where it would read the
device's files, the user's personal data or the device's identity.

The sandbox is driven from a root shell:

```sh
bbd=/data/adb/modules/big_brother/bin/bbd
$bbd sandbox enable com.example.app      # --user N for another Android user
$bbd sandbox disable com.example.app     # --all for every app
$bbd sandbox list
$bbd sandbox status
```

Every subcommand takes `--json`. The commands are sent to the running
daemon (`bbd.ctl`, one JSON line each way); without it they fail.

Enabling and disabling end the app's running processes, so that it starts
again as it should.

## What a sandboxed app gets

| | What the app gets | How | Needs |
| --- | --- | --- | --- |
| Shared storage | A directory of its own in place of `/storage/emulated/<user>`, empty at first, which it can write to. Its own `Android/data`, `obb` and `media` directories stay. Other volumes (SD cards) are empty. | Mounts made in the app's mount namespace as it starts | root |
| Kernel information | `/proc/cpuinfo` without what names the chip | The same mounts | root |
| Personal data | Nothing: contacts, calendar, messages, call log, accounts, location, clipboard, camera, microphone, body sensors, media, usage statistics, nearby devices | App ops set to "ignored" for the app's uid | root |
| Identity | An ANDROID_ID of its own, fixed per app; no installed app other than itself and what came with the system | Hooks in system_server | LSPosed |

The first three work without LSPosed. `bbd sandbox status` says which parts
are in place on the device.

The sandbox applies to a uid. Packages that share a uid are sandboxed
together, and `enable` names them.

An app's sandbox directory is `/data/media/<user>/.bb_sandbox/<uid>`. It
is kept when the app leaves the sandbox and when the module is removed, as
it holds what the app saved. The ANDROID_ID is kept too, so an app that is
sandboxed again is the same device to its servers.

## How it works

### The gate

zygote gives each app process a mount namespace of its own, mounts the app's
view of storage in it, and then calls `setresuid` to become the app. No code
of the app has run by then, and nothing is mounted for it afterwards: this
is the one moment at which what the app sees can be replaced.

An eBPF program on `setresuid` (`gate_setresuid`) stops a child of zygote
that is about to take a sandboxed uid, and reports it to the daemon. A
thread of the daemon that does nothing else enters the child's mount
namespace, makes the sandbox's mounts and lets the process continue. Apps
that are not sandboxed pay for one hash lookup when they start.

A start is held for a few milliseconds.

A start that cannot be given its sandbox is killed, never let through; the
system starts the app again when it is next wanted. If the daemon's thread
is stuck for more than three seconds, or has ended, the kernel side kills
such starts by itself. Other apps are not affected in either case.

The thread has no timer: with no sandboxed app starting, it does not run.

### App ops

System services ask the system's app ops whether a uid may have the data
behind a permission. With an op "ignored" the caller gets an empty answer
rather than an error. A helper program (`AppOpsGuard`, run by the daemon
with `app_process`) sets the ops, remembers what they were, and sets them
again when the system changes them, which it does whenever the app's
permissions change. The helper runs only while an app is sandboxed.

What the ops were is kept in the database and put back when the app leaves
the sandbox. Removing the module takes every app out first; where the root
manager removes it at the next boot, when there is no daemon to do that, the
ops of the apps still sandboxed are cleared instead, which leaves them as
the system sets them by default.

### The hook

`Settings.Secure.ANDROID_ID` and the list of installed apps are answered
inside system_server. The app's LSPosed module hooks the settings
provider's entry points (`ContentProvider.Transport.call` and `query`) and
the package manager's visibility check (`shouldFilterApplication`), and
answers by the calling uid, which Binder reports and an app cannot forge.

The daemon lists the sandboxed uids in `/data/system/big_brother_sandbox`;
the hook reads the list again when it changes, so no restart is needed. The
hook writes which of its hooks are in place to
`/data/system/big_brother_sandbox.status`.

The hook needs the `system` scope in the LSPosed manager. After updating
from a version without the sandbox, enable that scope for Big Brother in
the manager and restart the device.

A fault in system_server restarts the device, so every hook falls back to
the original method when its own work fails, and the hook does not install
itself when system_server has started more than three times in five
minutes.

## Starting and stopping

- When the daemon starts, at boot or after a restart, it takes up the
  sandboxed apps again and ends any of their processes that started without
  it. The supervisor restarts a daemon that died at once.
- An app started before the user first unlocks the device, when its sandbox
  directory cannot be reached yet, gets empty, read-only storage in its
  place.
- An entry whose uid Android has since given to another app is taken out.
- On a device where the framework differs from what the hook expects,
  the hook does not attach, and `bbd sandbox status` shows it as inactive.

## Testing

`scripts/check-sandbox.sh` runs the canary app outside the sandbox, inside
it and outside again, and checks what it reports seeing each time:

```sh
(cd android && ./gradlew :canary:assembleDebug)
./scripts/check-sandbox.sh              # add --stress 200 for cold starts
```
