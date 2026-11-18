# The sandbox

An app put in the sandbox keeps running, with its permissions shown as
granted, but is given an empty or made-up answer where it would read the
device's files, the user's personal data or the device's identity.

The app's Jail tab puts apps in the sandbox and sets its items. The same
can be done from a root shell:

```sh
bbd=/data/adb/modules/big_brother/bin/bbd
$bbd sandbox enable com.example.app      # --user N for another Android user
$bbd sandbox disable com.example.app     # --all for every app
$bbd sandbox list
$bbd sandbox status
$bbd sandbox set com.example.app camera=off   # see Items
$bbd sandbox global                           # the global settings
```

Every subcommand takes `--json`. The commands are sent to the running
daemon (`bbd.ctl`, one JSON line each way); without it they fail.

Enabling and disabling end the app's running processes, so that it starts
again as it should. An app that is not in the sandbox is not affected in
any way.

## What a sandboxed app gets

| | What the app gets | How | Needs |
| --- | --- | --- | --- |
| Shared storage | A directory of its own in place of `/storage/emulated/<user>`, empty at first, which it can write to. Its own `Android/data`, `obb` and `media` directories stay. Other volumes (SD cards) are empty. | Mounts made in the app's mount namespace as it starts | root |
| Kernel information | `/proc/cpuinfo` without what names the chip | The same mounts | root |
| Personal data | Nothing: contacts, calendar, messages, call log, accounts, location, clipboard, camera, microphone, body sensors, media, usage statistics, nearby devices | App ops set to "ignored" for the app's uid; for an approximate location, the precise location permission revoked | root |
| Identity | An ANDROID_ID of its own, fixed per app; no installed app other than itself and what came with the system | Hooks in system_server | LSPosed |

The first three work without LSPosed. `bbd sandbox status` says which parts
are in place on the device.

The sandbox applies to a uid. Packages that share a uid are sandboxed
together, and `enable` names them.

An app's sandbox directory is `/data/media/<user>/<name>/<uid>`. It is
kept when the app leaves the sandbox and when the module is removed, as it
holds what the app saved. The ANDROID_ID is kept too, so an app that is
sandboxed again is the same device to its servers.

An app can read its own mounts, which show the path of its sandbox
directory and the source of each tmpfs. Neither names the project: the
tmpfs mounts have the usual source, `tmpfs`, and `<name>` is a hidden
directory named at random when the first app is sandboxed. The daemon finds
it again by a file in it that only root can see.

## Items

What the sandbox does to an app is set item by item. Each item is off (the
app gets what the system gives it) or on, and location can also be made
coarse:

| Item | On | Modes |
| --- | --- | --- |
| `storage` | Shared storage of its own; an empty media library | off, on |
| `cpu_info` | `/proc/cpuinfo` without what names the chip | off, on |
| `contacts` | No contacts | off, on |
| `messages` | No messages or call log | off, on |
| `calendar` | No calendar | off, on |
| `clipboard_accounts` | An empty clipboard, no accounts | off, on |
| `location` | No location; with coarse, an approximate one only, as with precise location turned off | off, on, coarse |
| `camera` | No camera image | off, on |
| `microphone` | Silence | off, on |
| `body_sensors` | No body sensor or activity data | off, on |
| `device_ids` | An ANDROID_ID of its own; no phone number or phone state | off, on |
| `installed_apps` | No installed app but itself and the system's; no usage statistics | off, on |
| `nearby_devices` | No Bluetooth or Wi-Fi devices nearby | off, on |

The global settings give every item a mode; an app has these, except for
the items it has a mode of its own for. Every item is on unless set
otherwise. An app keeps its own settings when it leaves the sandbox.

```sh
$bbd sandbox global camera=off microphone=off
$bbd sandbox set com.example.app camera=on location=coarse
$bbd sandbox set com.example.app camera=global   # follows the global settings again
```

A change takes effect at once. It ends the app's processes if what is
mounted for it changes (`storage`, `cpu_info`), or an item that was off is
turned on: what the app read before, and may keep, goes with them. Making
location coarse ends them too when the app had precise location: Android
ends an app whenever it revokes a permission the app had. A change of the global
settings is made to every sandboxed app that follows them, or to none. An
app in the sandbox with every item off has nothing done to it: no mount,
no app op, no permission and no answer of the hook.

## How it works

### The gate

zygote gives each app process a mount namespace of its own, mounts the app's
view of storage in it, and then calls `setresuid` to become the app. No code
of the app has run by then, and nothing is mounted for it afterwards: this
is the one moment at which what the app sees can be replaced.

An eBPF program on `setresuid` (`gate_setresuid`) stops a child of zygote
that is about to take a sandboxed uid with `storage` or `cpu_info` on, and
reports it to the daemon. A
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
rather than an error, and the permission still shows as granted. (An op
"errored" would give an error instead, but the system keeps the ops of
runtime permissions at "ignored" whatever is set.) A helper program
(`AppOpsGuard`, run by the daemon with `app_process`) sets the ops of the
items that are on, remembers what they were, and sets them again when the
system changes them, which it does whenever the app's permissions change.
The helper runs only while a sandboxed app has such an item on.

No mode of the app ops makes location approximate: with the precise
location op ignored, the location service still takes the app for one
with precise location and gives it none at all. Location made coarse is
the system's own approximate location instead. The helper revokes the
precise location permission (`ACCESS_FINE_LOCATION`) of every package of
the app that requests it, keeps the approximate one, and marks it as the
user's choice not to be asked again, as Android's settings do; it revokes
it again if the system or the user grants it while location is coarse.
The app's settings show precise location as turned off.

What the ops were, and whether the precise location permission was granted
and with which of the user's choices, is kept in the database and put back
when the app leaves the sandbox or location is no longer coarse. The
permission is written down as perhaps taken before the helper takes it, so
that a daemon that stopped in between still gives it back. Removing the
module takes every app out first. Where the
daemon cannot (the root manager removes the module at the next boot, before
the daemon starts) or does not get every app out, the helper is run by
itself once the system is up, with what the database says each app still
sandboxed had (`bbd sandbox-restore-lines`).

### The hook

`Settings.Secure.ANDROID_ID` and the list of installed apps are answered
inside system_server. The app's LSPosed module hooks the settings
provider's entry points (`ContentProvider.Transport.call` and `query`) and
the package manager's visibility check (`shouldFilterApplication`), and
answers by the calling uid, which Binder reports and an app cannot forge.

The daemon lists the uids with `device_ids` or `installed_apps` on in
`/data/system/big_brother_sandbox`, with an `items` line for a uid that has
only one of them; the hook reads the list again when it changes, so no
restart is needed. The
hook writes which of its hooks are in place to
`/data/system/big_brother_sandbox.status`.

The hook needs the `system` scope in the LSPosed manager: enable that scope
for Big Brother in the manager and restart the device.

A fault in system_server restarts the device, so every hook falls back to
the original method when its own work fails, and the hook does not install
itself when system_server has started more than three times in five
minutes.

## Starting and stopping

- When the daemon starts, at boot or after a restart, it takes up the
  sandboxed apps again and ends those of their processes that should have
  been given mounts and started without them. The supervisor restarts a daemon that died at once.
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
