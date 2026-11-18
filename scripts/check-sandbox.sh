#!/usr/bin/env bash
# Copyright (C) 2026 Mercury Li
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 3 as
# published by the Free Software Foundation.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.

# End-to-end check of the sandbox on a device: runs the canary app outside
# the sandbox, inside it and outside again, and compares what it reports
# seeing each time with what the sandbox promises.
#
#   scripts/check-sandbox.sh              the check
#   scripts/check-sandbox.sh --stress N   also N cold starts in the sandbox,
#                                         none of which may fail or stay held
#
# Requires adb, a connected and unlocked device with the module installed
# and running, and the canary built:
#   (cd android && ./gradlew :canary:assembleDebug)
#
# Exits non-zero if a check fails.
set -euo pipefail

cd "$(dirname "$0")/.."

PACKAGE=li.mercury.bigbrother.canary
APK=android/canary/build/outputs/apk/debug/canary-debug.apk
BBD=/data/adb/modules/big_brother/bin/bbd
# Put in the real shared storage; a sandboxed app must not find it.
# MARKER in the canary's MainActivity.kt.
MARKER=/data/media/0/Download/bb_sandbox_marker.txt
LIST=/data/system/big_brother_sandbox
# What a new sandbox has in it, and the app's own directories.
SANDBOX_ENTRIES="Android DCIM Documents Download Movies Music Pictures"
# The 95th percentile a start may be held for, in microseconds.
MAX_HOLD_US=20000
# Every item of the sandbox (`bbd/src/sandbox/items.rs`), each set to on,
# back to the global settings, or off but for device_ids.
ITEMS="storage cpu_info contacts messages calendar clipboard_accounts location camera microphone
body_sensors device_ids installed_apps nearby_devices"
ALL_ON="" ALL_GLOBAL="" ONLY_IDS=""
for item in $ITEMS; do
    ALL_ON+=" $item=on"
    ALL_GLOBAL+=" $item=global"
    [[ "$item" == device_ids ]] && ONLY_IDS+=" $item=on" || ONLY_IDS+=" $item=off"
done

stress=0
if [[ "${1:-}" == --stress ]]; then
    stress="${2:?--stress needs a number of starts}"
fi

as_root() {
    if [[ "$(adb shell id -u | tr -d '\r')" == 0 ]]; then
        adb shell "$*"
    else
        adb shell su -c "'$*'"
    fi
}

[[ -f "$APK" ]] || {
    echo "error: $APK not found; build it with: (cd android && ./gradlew :canary:assembleDebug)" >&2
    exit 2
}

failed=0
pass() { echo "  ok      $*"; }
fail() {
    echo "  FAILED  $*"
    failed=$((failed + 1))
}
skip() { echo "  skipped $*"; }
# check <what> <condition...>: the condition is a command.
check() {
    local what="$1"
    shift
    if "$@"; then pass "$what"; else fail "$what"; fi
}

# Start the canary and wait for its report. Sets `view`: the lines
# `<key>=<value>` it reported about what it sees.
run_canary() {
    adb shell am force-stop "$PACKAGE"
    adb logcat -c
    adb shell am start -W -n "$PACKAGE/.MainActivity" >/dev/null
    local i log=""
    for ((i = 0; i < 90; i++)); do
        log="$(adb logcat -d -s BigBrotherCanary:I | tr -d '\r')"
        grep -q "BigBrotherCanary: done" <<<"$log" && break
        sleep 1
    done
    ((i < 90)) || {
        echo "error: the canary did not finish; is the screen on and unlocked?" >&2
        exit 2
    }
    view="$(sed -n 's/.*BigBrotherCanary: sandbox //p' <<<"$log")"
}

# Whether every process of the canary has its sandbox directory mounted as
# shared storage (the entry of /proc/<pid>/mountinfo for it). One that ended
# since it was listed, as the last start's does, is not looked at.
has_sandbox() {
    local pid mounts looked=0
    for pid in $(adb shell pidof "$PACKAGE" | tr -d '\r'); do
        mounts="$(as_root "cat /proc/$pid/mountinfo 2>/dev/null")"
        [[ -n "$mounts" ]] || continue
        looked=$((looked + 1))
        grep -qE " /[^ ]+/$uid /storage/emulated/0 " <<<"$mounts" || return 1
    done
    ((looked > 0))
}

# Whether the mounts of the canary's processes leave the project unnamed: an
# app can read its own mounts. (The canary's package name has "bigbrother"
# in it, and its own directories are mounted under that name.)
names_nothing() {
    local pid mounts looked=0
    for pid in $(adb shell pidof "$PACKAGE" | tr -d '\r'); do
        mounts="$(as_root "cat /proc/$pid/mountinfo 2>/dev/null")"
        [[ -n "$mounts" ]] || continue
        looked=$((looked + 1))
        grep -qE 'bb_sandbox|big_brother' <<<"$mounts" && return 1
    done
    ((looked > 0))
}

# The value the canary reported for a key, in a report.
seen() { sed -n "s/^$2=//p" <<<"$1" | head -n 1; }

status_field() { grep -o "\"$1\":[a-z0-9]*" <<<"$status" | head -n 1 | cut -d: -f2; }

adb install -r "$APK" >/dev/null
for permission in READ_CONTACTS READ_CALENDAR READ_SMS READ_MEDIA_IMAGES; do
    adb shell pm grant "$PACKAGE" "android.permission.$permission" 2>/dev/null || true
done
# All files access, so that outside the sandbox the marker can be read: the
# check is that the sandbox takes it away, not that the system refuses it.
adb shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow
as_root "echo real > $MARKER && chown media_rw:media_rw $MARKER"

# Leave the device as it was, whatever happens.
cleanup() {
    as_root "$BBD sandbox set $PACKAGE$ALL_GLOBAL" >/dev/null 2>&1 || true
    as_root "$BBD sandbox disable $PACKAGE" >/dev/null 2>&1 || true
    as_root "rm -f $MARKER" || true
}
trap cleanup EXIT

as_root "$BBD sandbox disable $PACKAGE" >/dev/null
# The modes of the app's ops, without when each was last used.
ops() { adb shell appops get --uid "$PACKAGE" | tr -d '\r' | sed 's/;.*//' | sort; }
ops_before="$(ops)"

echo "outside the sandbox:"
run_canary
outside="$view"
check "the real storage is visible" [ "$(seen "$outside" marker)" = real ]
check "the chip is named in /proc/cpuinfo" [ "$(seen "$outside" cpu_named)" = true ]

echo "enabling the sandbox:"
enabled="$(as_root "$BBD sandbox enable $PACKAGE --json")"
uid="$(grep -o '"uid":[0-9]*' <<<"$enabled" | cut -d: -f2)"
check "the daemon took the app in (uid ${uid:-?})" [ -n "$uid" ]
# Every item on, whatever the global settings of this device say.
as_root "$BBD sandbox set $PACKAGE$ALL_ON" >/dev/null
status="$(as_root "$BBD sandbox status --json")"
echo "  status  $status"
check "the gate is in place" [ "$(status_field gate)" = true ]
check "app ops are kept" [ "$(status_field appops)" = true ]

echo "in the sandbox:"
run_canary
inside="$view"
check "the process has the sandbox mounted over shared storage" has_sandbox
check "no mount names the project" names_nothing

# Layers 1 and 2: storage and kernel information.
unexpected=""
for entry in $(seen "$inside" storage | tr ',' ' '); do
    [[ " $SANDBOX_ENTRIES " == *" $entry "* ]] || unexpected+=" $entry"
done
check "shared storage holds only what a new sandbox has${unexpected:+ (also:$unexpected)}" [ -z "$unexpected" ]
check "the real storage is out of reach ($(seen "$inside" marker))" \
    grep -q '^refused' <<<"$(seen "$inside" marker)"
check "nor is it reachable another way ($(seen "$inside" other_ways))" [ -z "$(seen "$inside" other_ways)" ]
check "the app can write to shared storage" [ "$(seen "$inside" write)" = written ]
check "the app can write to its own external directory" [ "$(seen "$inside" own_dir)" = written ]
# Apps may not list /storage itself on most devices; where they may, other
# volumes (an SD card) are not to be among what is listed.
volumes="$(seen "$inside" volumes)"
check "no other storage volume is listed ($volumes)" \
    [ "$volumes" = null -o "$volumes" = "emulated,self" ]
check "the chip is not named in /proc/cpuinfo" [ "$(seen "$inside" cpu_named)" = false ]
check "the processors are all there ($(seen "$inside" cpu_count))" \
    [ "$(seen "$inside" cpu_count)" = "$(seen "$outside" cpu_count)" ]
check "the memory is reported as it is ($(seen "$inside" mem_total_kb) kB)" \
    [ "$(seen "$inside" mem_total_kb)" = "$(seen "$outside" mem_total_kb)" ]

# Layer 3: personal data.
check "no contacts ($(seen "$outside" contacts) outside)" [ "$(seen "$inside" contacts)" = 0 ]
check "no clipboard ($(seen "$outside" clipboard) outside)" [ "$(seen "$inside" clipboard)" = 0 ]
check "no images ($(seen "$outside" images) outside)" [ "$(seen "$inside" images)" = 0 ]

# Layer 4: identity, which needs the LSPosed hook.
if [[ "$(status_field android_id)" == true ]]; then
    want="$(as_root "cat $LIST" | tr -d '\r' | sed -n "s/^$uid //p")"
    check "the Android ID is the sandbox's ($want)" [ "$(seen "$inside" android_id)" = "$want" ]
    check "and so it is when queried" [ "$(seen "$inside" android_id_query)" = "$want" ]
    check "it is not the real one" [ "$want" != "$(seen "$outside" android_id)" ]
else
    skip "the Android ID: the hook is not in place"
fi
if [[ "$(status_field app_list)" == true ]]; then
    check "no other installed app is visible ($(seen "$inside" apps))" \
        [ "$(seen "$inside" apps)" = "$PACKAGE" ]
    check "the system's packages are all visible ($(seen "$inside" system_apps))" \
        [ "$(seen "$inside" system_apps)" = "$(seen "$outside" system_apps)" ]
else
    skip "the app list: the hook is not in place"
fi

if ((stress > 0)); then
    echo "$stress cold starts in the sandbox:"
    unsandboxed=0
    for ((i = 0; i < stress; i++)); do
        adb shell am force-stop "$PACKAGE"
        adb shell am start -W -n "$PACKAGE/.MainActivity" >/dev/null
        has_sandbox || unsandboxed=$((unsandboxed + 1))
    done
    status="$(as_root "$BBD sandbox status --json")"
    echo "  status  $status"
    check "every start got its sandbox" [ "$unsandboxed" = 0 ]
    check "no start failed" [ "$(status_field failed)" = 0 ]
    check "starts were held for under $((MAX_HOLD_US / 1000)) ms ($(status_field hold_p95_us) us)" \
        [ "$(status_field hold_p95_us)" -lt "$MAX_HOLD_US" ]
    # State T in /proc/<pid>/stat: stopped and never let go.
    # grep finds none, and says so with its status, when all is well.
    held="$( (as_root "grep -l \"^[0-9]* (.*) T \" /proc/[0-9]*/stat 2>/dev/null" || true) | wc -l)"
    check "no process was left held" [ "$held" = 0 ]
fi

# One item on and the rest off: only the Android ID changes, and nothing is
# mounted, so restarting the daemon leaves the app running.
if [[ "$(status_field android_id)" == true ]]; then
    echo "with only device_ids on:"
    as_root "$BBD sandbox set $PACKAGE$ONLY_IDS" >/dev/null
    run_canary
    only_id="$view"
    check "the real storage is visible" [ "$(seen "$only_id" marker)" = real ]
    check "the chip is named" [ "$(seen "$only_id" cpu_named)" = true ]
    check "the contacts are as outside ($(seen "$only_id" contacts))" \
        [ "$(seen "$only_id" contacts)" = "$(seen "$outside" contacts)" ]
    check "the installed apps are as outside" [ "$(seen "$only_id" apps)" = "$(seen "$outside" apps)" ]
    check "the Android ID is the sandbox's" [ "$(seen "$only_id" android_id)" = "$want" ]
    pids="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
    as_root "kill -9 \$(pidof bbd)"
    for _ in $(seq 1 30); do
        as_root "$BBD sandbox status" >/dev/null 2>&1 && break
        sleep 1
    done
    sleep 2
    check "the app keeps running when the daemon restarts" [ "$(adb shell pidof "$PACKAGE" | tr -d '\r')" = "$pids" ]
    as_root "$BBD sandbox set $PACKAGE$ALL_ON" >/dev/null
else
    skip "only device_ids on: the hook is not in place"
fi

echo "disabling the sandbox:"
as_root "$BBD sandbox disable $PACKAGE" >/dev/null
run_canary
after="$view"
check "the real storage is back" [ "$(seen "$after" marker)" = real ]
check "the chip is named again" [ "$(seen "$after" cpu_named)" = true ]
check "the Android ID is the real one" [ "$(seen "$after" android_id)" = "$(seen "$outside" android_id)" ]
check "the installed apps are visible again" [ "$(seen "$after" apps)" = "$(seen "$outside" apps)" ]
check "the clipboard is readable again" [ "$(seen "$after" clipboard)" = "$(seen "$outside" clipboard)" ]
check "the app ops are as they were" [ "$ops_before" = "$(ops)" ]

if ((failed > 0)); then
    echo "$failed check(s) failed"
    exit 1
fi
echo "all checks passed"
