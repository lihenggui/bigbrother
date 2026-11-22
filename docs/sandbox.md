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

The network of an app in the sandbox has commands of its own (see
Destinations):

```sh
$bbd sandbox set com.example.app network_record=on       # record where it connects
$bbd net activity com.example.app --since 1763393520     # where it connected, as JSON
$bbd net block com.example.app domain:adnet.example      # a name and those under it
$bbd net block com.example.app host:t.example.com        # one name
$bbd net block com.example.app ip:203.0.113.7            # an address; ip:203.0.113.7:8080 for one port
$bbd net unblock com.example.app ip:203.0.113.7
$bbd net clear com.example.app                           # delete what is recorded; the rules stay
```

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
| Identity | An ANDROID_ID of its own, fixed per app; an advertising ID of zeros; no installed app other than itself and what came with the system | Hooks in system_server | LSPosed |
| Network | No connection: what the app sends is refused at once, but for what it sends to the device itself, and the names it looks up are not asked for. Off unless turned on | A rule in the kernel's packet filter, which looks the socket's uid up in a list; a mount over the socket of the system's resolver in the app's processes | root |

The mounts, the app ops and the packet filter work without LSPosed. `bbd sandbox status` says which parts
are in place on the device. `bbd sandbox list` names, for each app, the
items that are on but not in effect and the part each lacks: the mounts,
the app ops (read back after they are set, and whenever the system changes
them), a hook, or the packet filter. While the helper checks an app after
restarting, `appops_checking` marks its Android restrictions as pending
readback. When readback fails or expires, Jail reports that the Android
restriction could not be verified. An item is in effect only with all its parts: the device
identifiers, for one, need the ANDROID_ID and advertising ID hooks and the
phone-state app ops.

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
Superseded installation records are archived in the root-only database.

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
| `device_ids` | An ANDROID_ID of its own; an advertising ID of zeros; no phone number or phone state | off, on |
| `installed_apps` | No installed app but itself and the system's; no usage statistics | off, on |
| `nearby_devices` | No Bluetooth or Wi-Fi devices nearby | off, on |
| `notification_content` | No notification access: other apps' notifications cannot be read; its own still show | off, on |
| `screen_content` | Its accessibility services off: what is on screen cannot be read or acted on | off, on |
| `network` | No network: connections fail at once, and no name is looked up | off, on |
| `network_record` | Nothing is kept from the app: where it connects is recorded | off, on |

The global settings give every item a mode; an app has these, except for
the items it has a mode of its own for. Every item is on unless set
otherwise, except `network` and `network_record`, which are off: an app put
in the sandbox keeps the network until it is taken from it, and its
connections are recorded only once that is turned on for it. An app keeps its own settings when
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
Nor do `network_record` and the destinations an app is kept from.
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
  package is retried within the same limit as an isolation attempt.
- An app given the uid of one uninstalled while it was in the sandbox is
  put in once that one is taken out, with an entry of its own.

Android writes its package list (`/data/system/packages.list`) when it
installs, updates or removes an app, and then makes the app's data
directory for each user (`/data/user_de/<user>/<package>`). While the
switch is on, the daemon watches for both (inotify) and puts the new app in
the sandbox. What wakes it is a file renamed or written into
`/data/system` and a directory made in `/data/user_de` or in a user's
directory there; what an app does inside its own data directory does not.

The installer can request a launch immediately. With a restriction enabled
in the global settings and the gate available, the gate holds an ordinary
zygote start of a UID the daemon has not dealt with yet. The daemon ends
that start if it is still waiting for isolation. Android can still be
waiting for the ended launch to attach, so a new tap may need to wait for
its startup timeout. With all restrictions off, including when only
connection recording is on, auto-isolation does not hold starts.

`bbd sandbox status` reports whether the hold is active as `new_apps_held`.
The kernel uses the UIDs and app IDs from the last scan, and the gate also
checks installation ownership when it applies mounts. If the kernel cannot
store what the daemon knows (more than 1024 uids of apps installed since
the switch was turned on, counted per Android user), the daemon releases
new starts and continues finding apps through the watch. Apps installed
while no daemon runs are processed by its first look.

A look gives library queries up to 4 seconds total and starts no more
isolations after that budget. An isolation in progress finishes or rolls
back, and apps that could not be attempted are looked at again after
2 seconds. A temporary failure gets one retry after 10 seconds. The daemon
also releases starts after 30 seconds of deferral, or as soon as it finds
an error that needs a change outside the daemon, such as an unsupported
restriction or a missing profile directory. A released app can start
without isolation. The daemon can try again after a package or profile
change, a helper recovery, or a change to the global settings. The user
can also enable Jail for the app directly.

`bbd sandbox status` reports how many new apps are waiting for a first
attempt or a timed retry (`new_apps_pending`), and how many could not be
isolated and were released (`new_apps_failed`). Jail's global settings
show both counts.

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

The kernel journals the PID and its start time before stopping it. An
independent reader ends a stop after three seconds, or when the mount
worker exits. A start that still requires mounts is ended. A start whose
policy needs no mounts is resumed. The next daemon also checks the
journal for starts left stopped by a crash.

If the gate stops working, future starts continue and Jail reports the
mounts as unavailable. While the gate is working, a UID whose required
mount fails cannot start with that isolation enabled. Jail shows the
cause and how to let the app start by turning off the affected items or
removing it from Jail. Mounts are tried on the next launch after the
mount settings change, an installed package version changes, the
daemon starts, or storage becomes accessible at unlock. A recorded
failure keeps the daemon from restarting the running app on its own.
A recording cover by itself never holds the app. The deadline timer is
armed only while a start is held.

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

The helper skips modes that already match and keeps reapplying ordinary
app ops whenever Android resets them. All-files mode changes, runtime
permission revocations, and disabling notification listeners or
accessibility services can end processes or unbind services. For each of
these restrictions, the helper retries at most three times per UID in any
ten-minute period. If the conflict continues, Jail shows the restriction as
unavailable. The helper tracks the budget in memory. It resets when the UID's
targets change, the app leaves Jail, or its installation is replaced.
MediaProvider authorizes all-files access in its own process, outside the
app's storage mounts. Changing that app op can make Android end processes
with the same app ID in every user. Before a write, the helper checks for
running processes with that app ID in other users. If a copy is running or
the check fails, the helper leaves the mode alone and reports the restriction
as unavailable. A restore that needs a change keeps the saved mode for retry.
A copy that starts after the check can still be caught by Android's kill.
Jail also reports all-files access as unavailable if the helper cannot
read which packages request it.

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
user's choice not to be asked again, as Android's settings do. If the
system or user grants it while location is coarse, the helper records the
choice and attempts to revoke it again within the retry budget. A user
revocation clears the fixed mark. When location is no longer coarse, the
helper restores the user's latest choice. Android's settings show precise
location as turned off while the restriction is effective.

What the ops were, and whether the precise location permission was granted
and with which of the user's choices, is kept in the database and put back
when the app leaves the sandbox or location is no longer coarse. When an
app enters the sandbox, the daemon writes the original values before the
helper applies the restrictions. With the mode of an op of a runtime
permission, the helper notes the mode the system gave the op for whether
the permission was granted (allowed, in the foreground only, or ignored).
If the user granted or revoked the
permission while the app was in the sandbox, the op is given the
system's mode for the permission as it is then, not the one saved. A
permission revoked meanwhile loses the `REVOKED_COMPAT` mark Android gave
it while its op was held, unless the user had it so before. The
permission is written down as perhaps taken before the helper takes it, so
that a daemon that stopped in between still gives it back. Removing the
module takes every app out first. Where the
daemon cannot (the root manager removes the module at the next boot, before
the daemon starts) or does not get every app out, the helper is run by
itself once the system is up, with what the database says each app still
sandboxed had (`bbd sandbox-restore-lines`). A root-only recovery directory
under `/data/adb/big_brother_recovery` retains each pending request and the
helper. Each restored item is checked and journaled. The helper rechecks the
installation before changing an app op or permission. After stopping the
daemon, uninstall also removes the module's IPv4 and IPv6 packet-filter
chains and detaches its resolver covers in running app namespaces.

Disabling the module also gives back the AppOps modes, runtime permissions,
notification listener access, and accessibility access saved by Jail. The
boot guard schedules recovery for the current boot and exits before starting
the daemon. If the module is turned off in Magisk or KernelSU, recovery runs
on the next normal boot. Both managers skip a disabled module's own
`post-fs-data.sh` and `service.sh`, so the module registers a root-only
recovery hook in `/data/adb/service.d`. It exits immediately when the module
is enabled, and uninstall removes it. Manager safe modes can skip general
scripts too. In that case recovery runs on the next normal boot with the
module still disabled.
[Magisk boot scripts](https://topjohnwu.github.io/Magisk/guides.html#boot-scripts)
and [KernelSU boot scripts](https://kernelsu.org/guide/module.html#boot-scripts)
describe the two kinds of hooks.

The recovery worker waits for Android to finish booting. It retries pending
requests with increasing delays and leaves completed requests journaled for
the duration of the disable, including across reboots. Jail settings, records,
identities and sandbox files stay on disk. When the module starts again,
the daemon uses those settings to reapply Jail. Starting Jail retires any
pending disable recovery before it begins enforcing again.

### Notifications and the screen

The system hands what other apps' notifications say to the notification
listeners the user gave the access to, and what is on screen to the
accessibility services the user turned on, past any app op. With
`notification_content` or `screen_content` on, the helper takes that
access from every such service of the app, as the system's settings do,
and keeps it taken. The helper checks for regrants once a minute and
takes them back within the retry budget. Which
services had the access is saved with the entry (`saved_permissions`,
under the permission the system binds such services with), and the access
goes back to those services and no others: one the user never turned on
is not turned on by the sandbox. An app needs no such access to show
notifications of its own: those are not affected.

The access is taken and given back by the helper, through the
notification service and the `enabled_accessibility_services` setting.
The helper rereads the accessibility list around a write and merges changes
it observes. Android replaces the entire setting, so a change made between
the last read and the write can still be lost.
Android also takes an app's accessibility services when the app is
force-stopped.

Before changing an app's Android restrictions, the daemon saves the
installation's current AppOps modes, grants and service access in its
database. It commits the policy and clears this recovery record together.
If the command is interrupted, a helper restores and reads back the
saved values before the record can be cleared. The daemon tries a restore
up to three times, then only checks the values. A new explicit Jail change
for that app can retry the restore.

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

### Destinations

An app in the sandbox can be kept from single destinations while it keeps
the network (`bbd net block`): an address, one port of an address, a name,
or a name with every name under it. The rules are the app's whether it is
in the sandbox or not, and are carried out while it is.

An address is refused by the same program of the packet filter: for an app
that has such rules, and for no other, it reads the destination of the
packet and looks it up among them. A name is refused where it is looked up
(see The resolver), and the addresses the app was already given for it are
refused with it, so that a connection it has, or an address it kept, does
not go on. The addresses already given to that installation are saved with
the rule and restored after a daemon restart. The Android resolver reply
carries no DNS TTL, so those address blocks expire one day after the
answer was seen. Two things follow from a name being more than one address and an
address more than one name: a name that shares an address with one the app
is kept from is not reached either, and an app that finds the address of a
name by itself, without the system's resolver, is not kept from it; the
connection is then recorded under the address, which it can be kept from.

Where an app connects is recorded once `network_record` is on for it, and
of no other app: the probes that report connections drop destinations on
the internet in the kernel unless the app is listed. A row is an hour in
which the app connected to a port of an address, under the name it was
given the address for when the app uses the relay, with how many connections
were tried and how many the sandbox refused. Without a relay cover, the
connections are recorded by address and port. An app has rows for some
hundreds of destinations in an hour; what it connects to past them is
counted together. When a TCP
connection ends, the kernel's own counts
of the bytes sent and received over it are read once and added to the row;
nothing is counted packet by packet, and other protocols have connection
counts only. The rows are kept 30 days, apart from the other records.
The daily charts follow the device's local dates. Each recorded UTC hour
stays together in the day in which the hour began.

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

A sandboxed app whose connections are recorded or that is kept from a
name can use the daemon's socket in place of netd's. The daemon passes
each allowed request on to
netd on a connection made with the app's uid as the effective one, so netd
chooses the network of the app and not of the daemon, and passes the
answer back; it remembers which name the app was given each address for,
and does not ask for a name the app is kept from. One request is taken on
a connection. The socket carries the SELinux labels of netd's, which lets
an app connect to it without a rule added to the policy, is open to the
group netd's is open to, and serves only the apps given it, a few lookups
of each at once: any process can find the socket, and an app kept from the
network is to have no resolver at all. Where
the daemon's domain may not label a socket so, `bbd sandbox status`
reports name rules as unavailable. Jail shows saved rules as unenforced when
the app has no working resolver cover. With the gate in place, a saved
name rule keeps DNS blocked while its relay is unavailable. The next
daemon replaces the cover when it can serve lookups.

For an app that uses the relay only to record connections, the cover is
optional. When recording is the only reason for telling the gate about a
start, the process keeps running as the cover is placed. An early lookup
can go directly to Android. A failed recording cover leaves lookups to
Android. If the relay stops serving, its covers are removed from those
running apps. If the daemon exits abruptly, the supervisor reads the
saved policies and detaches optional covers before it waits to restart
the daemon or gives up. The next daemon keeps a cover only if it leads
to its own live listener, replaces a stale cover if the relay is
serving, or removes the optional cover if the relay is unavailable.
`bbd sandbox list` reports missing domain names under `network_record`
when the app has no working relay.

On a kernel where starting apps cannot be held, the resolver is left to
the app; `bbd sandbox list` then names the mounts as missing for
`network`.

### The hook

`Settings.Secure.ANDROID_ID` and the list of installed apps are answered
inside system_server. The app's LSPosed module hooks the settings
provider's entry points (`ContentProvider.Transport.call` and `query`) and
the package manager's visibility check (`shouldFilterApplication`), and
answers by the calling uid, which Binder reports and an app cannot forge.

The advertising ID is not Android's but Google Play services', which gives
it to an app that targets Android 13 or later only if the app holds the
`com.google.android.gms.permission.AD_ID` permission, and an ID of zeros
otherwise, as to every app once the user deleted the ID. Play services
asks the permission manager in system_server, and for an app with
`device_ids` on, the hook answers that it does not hold it
(`PermissionManagerService.checkPermission` and `checkUidPermission`).
Processes keep the answers to such checks; as the system does when a
permission changes, the hook has them forget the answers whenever the list
changes. Play services gives an app that targets an earlier Android its
ID without asking: to such an app, the hook makes Play services'
advertising ID service as if it were not there.

The other services that give advertising and device identifiers are not
there for an app with `device_ids` on either: binding one fails
(`ActiveServices.bindServiceLocked` returns as for a service not found,
and `bindService` returns false), as on a device without it, rather than
throwing in the app. These are Android's own advertising ID
(AdServices, `AdIdManager`), which looks only at whether the app asks for
its permission, and the vendors' identifiers (OAID) that are bound: those
of Huawei, OPPO, Samsung, Lenovo, ASUS, Coolpad, 360, Freeme and the MSA
service. The OAID providers of vivo, Meizu, nubia and Xiaomi are not
found either (`ContentProviderHelper.getContentProvider`). Xiaomi's OAID,
VAID and AAID are read in the app's own process by a class of the system
(`com.android.id.impl.IdProviderImpl`), which queries Security Center's
provider (`com.miui.idprovider`); finding none, it gives no identifier.
The vendors' services and providers are named as the identifier SDKs
reach them.

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
hook checks and pins installation directories on a worker, then publishes
the validated list as one snapshot. Framework calls keep using the last verified snapshot while the worker
rechecks ownership. An event during a reload schedules another pass.
If the watch stops, the hook keeps serving that snapshot and reports degraded
health while it retries registration with backoff. The hook acknowledges the
loaded revision and reports the health of each part in
`/data/system/big_brother_sandbox.status`.

Jail reports when the hook has not loaded. Enable Big Brother's `system`
scope in LSPosed and restart the device in that case. A loaded hook reports
unsupported framework signatures and runtime failures separately.

A fault in system_server restarts the device, so every hook falls back to
the original method when its own work fails, and the hook does not install
itself when system_server has started more than three times in five
minutes.

## Starting and stopping

- When the daemon starts, at boot or after a restart, it takes up the
  sandboxed apps again. If the gate is ready for an app's requested
  mounts, the daemon restarts processes that started without them. The supervisor restarts a
  daemon that died at once. Until then such a process runs with the real
  storage, and what it read in that time it keeps. Early in boot that is
  little: shared storage cannot be read before the user first unlocks the
  device.
- The packet filter's rules stay when the daemon stops or dies, with the
  uids listed then, and are written again by the one that starts next. They
  do not outlast a restart of the device: until the daemon has started, an
  app has the network. Required resolver covers stay in place when the
  daemon stops. The next daemon reconciles the covers of running apps.
- Isolated processes of a sandboxed app (a service declared
  `isolatedProcess`, a WebView renderer) run under uids of their own and
  are not given the mounts. Android gives them no shared storage, no
  ANDROID_ID and no personal data; `/proc/cpuinfo` in them is the
  device's.
- An app started before the user first unlocks the device, when its sandbox
  directory cannot be reached yet, gets empty, read-only storage in its
  place. When the kernel makes credential-encrypted storage accessible,
  the daemon verifies the sandbox directory and restarts only processes
  still using the stand-in. Kernels without the unlock probe can replace
  it on the next normal app start or daemon restart.
- An entry whose uid Android has since given to another app is taken out.
- On a device where a hook target has an unsupported signature,
  `bbd sandbox status --json` reports that part as incompatible. A part that
  fails at runtime passes calls through and reports degraded until restart.

## Testing

`scripts/check-module-recovery.sh` exercises the boot guard, the disabled
module hook and re-enabling on a Linux host with fake Android commands.

`scripts/check-sandbox.sh` runs the canary app outside the sandbox, inside
it and outside again, and checks what it reports seeing each time, an
isolated process of it, a file it was let pick and the connections it
tries to make among it. Picking the
file goes through the system's file picker, driven with `uiautomator`:

```sh
(cd android && ./gradlew :canary:assembleDebug)
./scripts/check-sandbox.sh              # add --stress 200 for cold starts
```

`scripts/check-survival.sh` installs both canaries for Android user 0 on a
rooted, unlocked device with auto-isolation off. It turns on Files and media
and Installed apps, then checks ten cold starts of each app. Each start draws a
first frame and must keep the same process through the helper's one-second
settle and a five-second quiet period. ActivityManager events catch deaths
and replacements, and AppOps callbacks catch changes between UID-mode
readbacks. When isolation is applied, the declaring canary must move
`MANAGE_EXTERNAL_STORAGE` and `GET_USAGE_STATS` to ignored once each. The
canary without the two declarations must keep its original modes. Both
are released after the check. Device CI runs this after the mount and
hold-latency checks on API 33–36.

```sh
(cd android && ./gradlew :canary:assembleDebug :canary:assembleNoDeclarations)
./scripts/check-survival.sh
```

What an app's permissions and their app ops end as is set out case by case
in `testdata/permissions/matrix.tsv`: the permission's state as the app
enters the sandbox, what the user or the sandbox's items change while it is
in, and how it leaves. `scripts/check-permissions.sh` plays every case on a
device with a fresh install of the canary, and `PermissionMatrixTest` plays
them on the JVM, with the helper's own code on a model of Android:

```sh
./scripts/check-permissions.sh          # or the names of some cases
```
