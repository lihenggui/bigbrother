# The sandbox

An app put in the sandbox keeps running, with its permissions shown as
granted, but is given an empty or made-up answer where it would read the
device's files, the user's personal data or the device's identity. It can
also be kept from the network.

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
$bbd sandbox auto on                          # see Newly installed apps
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
| Network | No connection: what the app sends is refused at once, but for what it sends to the device itself, and the names it looks up are not asked for. Off unless turned on | A rule in the kernel's packet filter, which looks the socket's uid up in a list; a mount over the socket of the system's resolver in the app's processes | root |

The mounts, the app ops and the packet filter work without LSPosed. `bbd sandbox status` says which parts
are in place on the device. `bbd sandbox list` names, for each app, the
items that are on but not in effect and the part each lacks: the mounts,
the app ops (read back after they are set, and whenever the system changes
them), a hook, or the packet filter. An item is in effect only with all its parts: the device
identifiers, for one, need both the ANDROID_ID hook and the phone-state app
ops.

The sandbox applies to a uid. Packages that share a uid are sandboxed
together, and `enable` names them. The sandbox stays with a verified
installation of any package sharing its installation identifier.

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
| `network` | No network: connections fail at once, and no name is looked up | off, on |

The global settings give every item a mode; an app has these, except for
the items it has a mode of its own for. Every item is on unless set
otherwise, except `network`, which is off: an app put in the sandbox keeps
the network until it is taken from it. An app keeps its own settings when
it leaves the sandbox.

```sh
$bbd sandbox global camera=off microphone=off
$bbd sandbox set com.example.app camera=on location=coarse
$bbd sandbox set com.example.app microphone=foreground
$bbd sandbox set com.example.app camera=global   # follows the global settings again
$bbd sandbox set com.example.app network=on      # no network
```

A change takes effect at once. It ends the app's processes if what is
mounted for it changes (`storage`, `cpu_info`), or an item that was off is
turned on, to any mode but off: what the app read before, and may keep,
goes with them. `network` ends none: the connections the app has are
refused from then on, and the resolver is taken from the processes it has.
Making
location coarse ends them too when the app had precise location: Android
ends an app whenever it revokes a permission the app had. A change of the global
settings is made to every sandboxed app that follows them, or to none. An
app in the sandbox with every item off has nothing done to it: no mount,
no app op, no permission and no answer of the hook.

## Newly installed apps

A switch in the global settings, off by default, puts every app installed
after it is turned on in the sandbox, with the global settings, before the
app first starts:

```sh
$bbd sandbox auto on    # or off; without an argument, says which it is
```

`bbd sandbox global --json` reports it as `auto`.

- Turning it on leaves every app installed then as it is, for every Android
  user, then and later. Apps that came with the system, and those a system
  update adds, are never put in.
- An app installed afterwards is put in for each user it is installed for,
  and for a user it is installed for later. Each is put in once: an app taken
  out of the sandbox stays out.
- An update is not a new app. An app uninstalled and installed again is.
  Packages that share an app id count as one app, for as long as any of
  them is installed. A replacement installation gets its own entry even
  if Android gives it the same uid. Re-enabling the same verified
  installation keeps its settings and ANDROID_ID.
- Static shared libraries and SDK libraries are left alone, including the
  static libraries Play installs with Chrome or WebView updates. The app
  ops helper reads the package manager's library flags once per new
  package with a data directory. A lookup that cannot classify the
  package is retried before changing its sandbox.
- An app given the uid of one uninstalled while it was in the sandbox is
  put in once that one is taken out, with an entry of its own.

Android writes its package list (`/data/system/packages.list`) when it
installs, updates or removes an app, and then makes the app's data
directory for each user (`/data/user_de/<user>/<package>`). While the
switch is on, the daemon watches for both (inotify) and puts the new app in
the sandbox. What wakes it is a file renamed or written into
`/data/system` and a directory made in `/data/user_de` or in a user's
directory there; what an app does inside its own data directory does not.

The installer can request a launch immediately. While the gate is in
place, it holds an ordinary zygote start of a UID the daemon has not
dealt with yet; the daemon ends that start, as it ends any start it cannot
give its sandbox, and puts the app in, and the next start is held as a
sandboxed app's. Android can still be waiting for the ended launch to
attach, so a new tap may need to wait for its startup timeout.
`bbd sandbox status` reports the hold as `new_apps_held`.
The kernel uses the UIDs and app IDs from the last scan, and the gate also
checks installation ownership when it applies mounts. The daemon logs
when it stops holding starts because the kernel cannot store what the
daemon knows (more than 1024 uids of apps
installed since the switch was turned on, counted per Android user); new
apps are then still put in the sandbox as the daemon finds them. Apps
installed while no daemon runs are held from when it starts, put in by its
first look, and any of their processes then running are ended.

A look gives library queries up to 4 seconds total, and starts no more
isolations after that budget. An isolation already in progress finishes or
rolls back, and the rest are left for a look 2 seconds later. An app that
cannot be put in, as when the app ops helper
does not answer, is tried again after 10 seconds, then twice as long each
time up to 5 minutes, and its starts are held meanwhile. A timer for the
next try exists only while such an app waits. `bbd sandbox status` reports
how many new apps are not in the sandbox yet (`new_apps_pending`), and the
global settings' status row says so.

What the daemon knows of the installed apps is kept in the database, and
forgotten when the switch is turned off. While it is off, nothing is
watched, and a start of an app that is not sandboxed reads one value more.
While it is on, such a start of an app installed before costs a bit test,
and one of an app installed since a hash lookup more.

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
that are not sandboxed pay for one hash lookup when they start, and the
read of the switch for [newly installed apps](#newly-installed-apps).

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

### The packet filter

The daemon keeps a chain of its own in the `filter` table of the kernel's
packet filter (netfilter), for IPv4 and for IPv6, which the `OUTPUT` chain
jumps to first. Its one rule asks an eBPF program (`network_refused`,
pinned at `/sys/fs/bpf/bb_network` for the rule to name) whether the
socket a packet is sent from belongs to a uid kept from the network, and a
packet that does is refused: TCP is answered with a reset, anything else
with an ICMP error, so that a connection fails at once rather than timing
out. What goes out over the loopback interface is passed: apps talk to
their own processes that way.

The uids are a map the program looks up, so a packet costs one rule and
one lookup however many apps are kept from the network, and taking the
network from an app or giving it back changes the map only. The rules are
written when the first app is listed and removed with the last: with no
app kept from the network, the packets of the device pass no rule of the
sandbox. netd, which owns the other chains of `OUTPUT`, leaves the jump
alone, also when it starts again.

The rules are about the app's own sockets. Other apps and the system can
still fetch something on the app's behalf (the download manager, a browser
it opens).

### The resolver

An app does not look names up itself: it asks netd, over the socket
`/dev/socket/dnsproxyd`, and netd sends the query on a socket of its own,
which the rule for the app's uid does not match. A name of the app's
choosing would leave the device, and with it whatever the app wrote into
the name.

So `/dev/null` is mounted over that socket in the app's mount namespaces:
the app cannot connect, and its lookups fail without a packet being sent.
A starting process is given the mount at the gate, with the other mounts
of the sandbox. An app connects anew for each lookup, so the processes it
already has are given the mount as the network is taken from the app, and
have it removed as the network is given back; none is ended. The gate is
told first either way, so that a process starting meanwhile is as the
others are about to be. A connection
to the resolver that the app opened before and kept open stays usable
until the process ends. netd makes the socket anew when it starts again,
and a mount on the one before goes with it; Android 15 ends every app when
netd is ended, and the processes that start then get the mount at the
gate.

The mount is made only in a namespace whose mounts do not pass to others,
which an app's are: in one that shares them, it would take the resolver
from every process. It shows among the app's own mounts as `/null` of
`/dev`.

On a kernel where starting apps cannot be held, the resolver is left to
the app; `bbd sandbox list` then names the mounts as missing for
`network`.

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
- The packet filter's rules stay when the daemon stops or dies, with the
  uids listed then, and are written again by the one that starts next. They
  do not outlast a restart of the device: until the daemon has started, an
  app has the network. The resolver stays covered in the processes that
  have it so; one that started while there was no daemon is given the
  mount by the daemon that starts next.
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
isolated process of it, a file it was let pick and a connection it tries
to make among it. Picking the
file goes through the system's file picker, driven with `uiautomator`:

```sh
(cd android && ./gradlew :canary:assembleDebug)
./scripts/check-sandbox.sh              # add --stress 200 for cold starts
```
