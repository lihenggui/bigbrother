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
| Shared storage | A directory of its own in place of `/storage/emulated/<user>`, empty at first, which it can write to. Its own `Android/data`, `obb` and `media` directories stay. Other volumes (SD cards) are empty. A file chosen for it in the system's file picker is open to it for that use only. | Mounts made in the app's mount namespace as it starts; a hook in system_server for the files picked through the system | root; LSPosed for picked files |
| Kernel information | `/proc/cpuinfo` without what names the chip | The same mounts | root |
| Personal data | Nothing: contacts, calendar, messages, call log, accounts, location, clipboard, camera, microphone, body sensors, media, usage statistics, nearby devices | App ops set to "ignored" for the app's uid; for an approximate location, the precise location permission revoked | root |
| Other apps' notifications, the screen | Nothing: its notification listeners and accessibility services lose the access the user gave them, which comes back when it leaves | The access taken as a permission is, and given back | root |
| Identity | An ANDROID_ID of its own, fixed per app; no installed app other than itself and what came with the system | Hooks in system_server | LSPosed |

The mounts and the app ops work without LSPosed. `bbd sandbox status` says which parts
are in place on the device. `bbd sandbox list` names, for each app, the
items that are on but not in effect and the part each lacks: the mounts,
the app ops (read back after they are set, and whenever the system changes
them), or a hook. An item is in effect only with all its parts: the device
identifiers, for one, need both the ANDROID_ID hook and the phone-state app
ops.

The sandbox applies to a uid. Packages that share a uid are sandboxed
together, and `enable` names them.

An app's sandbox directory is `/data/media/<user>/<name>/<uid>-<installation>`. It is
kept when the app leaves the sandbox and when the module is removed, as it
holds what the app saved. The ANDROID_ID is kept too, so an app that is
sandboxed again is the same device to its servers.

An app can read its own mounts, which show the path of its sandbox
directory and the source of each tmpfs. Neither names the project: the
tmpfs mounts have the usual source, `tmpfs`, and `<name>` is a hidden
directory named at random when the first app is sandboxed. The daemon finds
it again by a file in it that only root can see. Root creates a random installation
identifier in a privileged extended attribute on the package's device-encrypted
data directory. Enabling the sandbox requires that the identifier can be read
back. Replacing the installation gives it fresh settings, storage and identity.
If an Android user's data directory is temporarily unavailable on restart, the daemon retains the entry and retries when it can verify the installation again.
Superseded installation records are archived in the root-only database. An older development entry without this identifier cannot authorize a restore.

## Items

What the sandbox does to an app is set item by item. Each item is off (the
app gets what the system gives it) or on. Location can also be made
coarse, and location, the camera and the microphone can be left to the app
while it is in use and kept from it in the background (foreground), as
Android's "only while using the app" does:

| Item | On | Modes |
| --- | --- | --- |
| `storage` | Shared storage of its own; an empty media library; picked files for that use only | off, on |
| `cpu_info` | `/proc/cpuinfo` without what names the chip | off, on |
| `contacts` | No contacts | off, on |
| `messages` | No messages or call log | off, on |
| `calendar` | No calendar | off, on |
| `clipboard_accounts` | An empty clipboard, no accounts | off, on |
| `location` | No location; with coarse, an approximate one only, as with precise location turned off | off, on, coarse, foreground |
| `camera` | No camera image | off, on, foreground |
| `microphone` | Silence | off, on, foreground |
| `body_sensors` | No body sensor or activity data, nor, from Android 16, heart rate, skin temperature or oxygen saturation | off, on |
| `device_ids` | An ANDROID_ID of its own; no phone number or phone state | off, on |
| `installed_apps` | No installed app but itself and the system's; no usage statistics | off, on |
| `nearby_devices` | No Bluetooth or Wi-Fi devices nearby | off, on |
| `notification_content` | No notification access: other apps' notifications cannot be read; its own still show | off, on |
| `screen_content` | Its accessibility services off: what is on screen cannot be read or acted on | off, on |

The global settings give every item a mode; an app has these, except for
the items it has a mode of its own for. Every item is on unless set
otherwise. An app keeps its own settings when it leaves the sandbox.

```sh
$bbd sandbox global camera=off microphone=off
$bbd sandbox set com.example.app camera=on location=coarse
$bbd sandbox set com.example.app microphone=foreground
$bbd sandbox set com.example.app camera=global   # follows the global settings again
```

A change takes effect at once. It ends the app's processes if what is
mounted for it changes (`storage`, `cpu_info`), or an item that was off is
turned on, to any mode but off: what the app read before, and may keep,
goes with them. Making
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

An item left to the app in the foreground has its ops held at
"foreground" instead, which the system keeps for the ops of runtime
permissions as it keeps "ignored": the app gets the data while it is in
the foreground and nothing in the background. For location these are the
coarse and the precise location ops; the Wi-Fi scan op goes with the
coarse location one, under which the system keeps its mode.

No mode of the app ops makes location approximate: with the precise
location op ignored, the location service still takes the app for one
with precise location and gives it none at all. Location made coarse is
the system's own approximate location instead. The helper revokes the
precise location permission (`ACCESS_FINE_LOCATION`) of every package of
the app that requests it, keeps the approximate one, and marks it as the
user's choice not to be asked again, as Android's settings do; it revokes
it again if the system or the user grants it while location is coarse,
and then gives it back granted when location is no longer coarse: the
user's latest choice. The app's settings show precise location as turned
off.

What the ops were, and whether the precise location permission was granted
and with which of the user's choices, is kept in the database and put back
when the app leaves the sandbox or location is no longer coarse. With
the mode of an op of a runtime permission, the helper notes the mode the
system gave the op for whether the permission was granted (allowed, in
the foreground only, or ignored). If the user granted or revoked the
permission while the app was in the sandbox, the op is given the
system's mode for the permission as it is then, not the one saved. The
permission is written down as perhaps taken before the helper takes it, so
that a daemon that stopped in between still gives it back. Removing the
module takes every app out first. Where the
daemon cannot (the root manager removes the module at the next boot, before
the daemon starts) or does not get every app out, the helper is run by
itself once the system is up, with what the database says each app still
sandboxed had (`bbd sandbox-restore-lines`). A root-only recovery directory
under `/data/adb/big_brother_recovery` retains each pending request and the
helper. Each restored item is checked and journaled. The boot worker retries
outstanding requests and removes the batch after acknowledgement. The helper
rechecks the installation before changing an app op or permission.

### Notifications and the screen

The system hands what other apps' notifications say to the notification
listeners the user gave the access to, and what is on screen to the
accessibility services the user turned on, past any app op. With
`notification_content` or `screen_content` on, the helper takes that
access from every such service of the app, as the system's settings do,
and keeps it taken: given again while the app is isolated, it is taken
back within a minute, by the helper's regular check (neither service
reports such a change to it), and that is what the app gets once it is
out. Which
services had the access is saved with the entry (`saved_permissions`,
under the permission the system binds such services with), and the access
goes back to those services and no others: one the user never turned on
is not turned on by the sandbox. An app needs no such access to show
notifications of its own: those are not affected.

The access is taken and given back by the helper, through the
notification service and the `enabled_accessibility_services` setting.
Android also takes an app's accessibility services when the app is
force-stopped.

### The hook

`Settings.Secure.ANDROID_ID` and the list of installed apps are answered
inside system_server. The app's LSPosed module hooks the settings
provider's entry points (`ContentProvider.Transport.call` and `query`) and
the package manager's visibility check (`shouldFilterApplication`), and
answers by the calling uid, which Binder reports and an app cannot forge.

A file the app was let pick through the system (a document, a folder, a
photo) is opened by the provider that holds it, outside the sandbox, once
system_server says the app holds a grant to it (`UriPermission.getStrength`).
An app that keeps such a grant (persists it) keeps it across restarts, and
one it kept from before it entered the sandbox would reach the real file.
For an app with `storage` on, the hook counts only the grants of the
moment: the one made as the user picks a file for it, which lasts while
that use does, and those that last until the device restarts. Such a grant
made just before the app entered the sandbox still counts until then. The
app cannot pass a grant on to another app to be persisted there. Persisted
grants count again once `storage` is off or the app leaves the sandbox; none
is removed. Going by when a grant was persisted would not do: the app can
persist one again at any time.

The daemon lists the uids with `device_ids`, `installed_apps` or `storage`
on in `/data/system/big_brother_sandbox`, with an `items` line naming them
unless a uid has the first two and not `storage`; the hook reads the list
again when it changes, so no restart is needed. The
hook pins each verified installation directory and checks its identity before answering for a UID. It acknowledges the revision it loaded and writes which hooks attached to
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
  been given mounts and started without them. The supervisor restarts a
  daemon that died at once. Until then such a process runs with the real
  storage, and what it read in that time it keeps. Early in boot that is
  little: shared storage cannot be read before the user first unlocks the
  device.
- Isolated processes of a sandboxed app (a service declared
  `isolatedProcess`, a WebView renderer) run under uids of their own and
  are not given the mounts. Android gives them no shared storage, no
  ANDROID_ID and no personal data; `/proc/cpuinfo` in them is the
  device's.
- An app started before the user first unlocks the device, when its sandbox
  directory cannot be reached yet, gets empty, read-only storage in its
  place.
- An entry whose uid Android has since given to another app is taken out.
- On a device where the framework differs from what the hook expects,
  the hook does not attach, and `bbd sandbox status` shows it as inactive.

## Testing

`scripts/check-sandbox.sh` runs the canary app outside the sandbox, inside
it and outside again, and checks what it reports seeing each time, an
isolated process of it and a file it was let pick among it. Picking the
file goes through the system's file picker, driven with `uiautomator`:

```sh
(cd android && ./gradlew :canary:assembleDebug)
./scripts/check-sandbox.sh              # add --stress 200 for cold starts
```
