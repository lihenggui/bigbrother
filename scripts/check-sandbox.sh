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
#   --max-hold-ms N                       the 95th percentile a start may be
#                                         held for (20 ms), for slower devices
#                                         such as an emulator without a GPU
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
max_hold_us=20000
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

# The app ops each item holds (`Item::ops` in bbd/src/sandbox/items.rs).
declare -A ITEM_OPS=(
    [storage]="android:read_external_storage android:write_external_storage android:manage_external_storage android:access_media_location android:read_media_images android:read_media_video android:read_media_audio android:read_media_visual_user_selected"
    [contacts]="android:read_contacts android:write_contacts"
    [messages]="android:read_sms android:receive_sms android:read_call_log"
    [calendar]="android:read_calendar android:write_calendar"
    [clipboard_accounts]="android:read_clipboard android:get_accounts"
    [location]="android:coarse_location android:fine_location android:wifi_scan"
    [camera]="android:camera"
    [microphone]="android:record_audio"
    [body_sensors]="android:body_sensors android:activity_recognition android:read_heart_rate android:read_skin_temperature android:read_oxygen_saturation"
    [device_ids]="android:read_phone_state android:read_phone_numbers"
    [installed_apps]="android:get_usage_stats"
    [nearby_devices]="android:bluetooth_scan android:bluetooth_connect android:nearby_wifi_devices"
)
# The canary is told which ops to report the mode of.
ALL_OPS="$(echo "${ITEM_OPS[*]}" | tr -s ' ' ',')"
# Granted to the canary, so that what it gets is up to the sandbox.
PERMISSIONS="READ_CONTACTS READ_CALENDAR READ_SMS READ_CALL_LOG READ_MEDIA_IMAGES ACCESS_FINE_LOCATION
ACCESS_COARSE_LOCATION CAMERA RECORD_AUDIO BODY_SENSORS ACTIVITY_RECOGNITION READ_PHONE_STATE READ_PHONE_NUMBERS
BLUETOOTH_SCAN BLUETOOTH_CONNECT NEARBY_WIFI_DEVICES"
# What the check puts on the device for the canary to find outside the
# sandbox, and takes away again: a contact, a text message, a call and a
# calendar event, all under these names.
TEST_ACCOUNT=bb.check
TEST_NUMBER=5550199
SEED=/data/local/tmp/bb_check_seed.sh

stress=0
while (($# > 0)); do
    case "$1" in
    --stress) stress="${2:?--stress needs a number of starts}" ;;
    --max-hold-ms)
        [[ "${2:-}" =~ ^[1-9][0-9]{0,5}$ ]] || {
            echo "error: --max-hold-ms needs a positive whole number of milliseconds" >&2
            exit 2
        }
        max_hold_us=$(($2 * 1000))
        ;;
    *)
        echo "error: unknown option $1" >&2
        exit 2
        ;;
    esac
    shift 2
done

as_root() {
    if [[ "$(adb shell id -u | tr -d '\r')" == 0 ]]; then
        adb shell "$*"
    else
        adb shell su -c "'$*'"
    fi
}
# Run a command as the shell user, whose test location provider the shell
# may set: Android 13 and 14 refuse it to root (adb root on an emulator).
as_shell() {
    if [[ "$(adb shell id -u | tr -d '\r')" == 0 ]]; then
        adb shell "su 2000 $*"
    else
        adb shell "$*"
    fi
}

[[ -f "$APK" ]] || {
    echo "error: $APK not found; build it with: (cd android && ./gradlew :canary:assembleDebug)" >&2
    exit 2
}

# A system dialog (an app that crashed elsewhere, say) takes the focus the
# canary waits for: the screen is woken and such dialogs are closed before
# it starts and while it is waited for.
clear_screen() {
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
}
# Whether a window other than the canary's has the focus.
focus_elsewhere() {
    local focus
    focus="$(adb shell dumpsys window windows 2>/dev/null | tr -d '\r' | sed -n 's/.*mCurrentFocus=//p')"
    [[ -n "$focus" && "$focus" != *"$PACKAGE"* ]]
}

failed=0
skipped=0
pass() { echo "  ok      $*"; }
fail() {
    echo "  FAILED  $*"
    failed=$((failed + 1))
}
skip() {
    echo "  skipped $*"
    skipped=$((skipped + 1))
}
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
    # Each start finds a fresh location from the test provider.
    if [[ "$location" == yes ]]; then
        as_shell cmd location providers set-test-provider-location gps --location 52.52,13.405 --accuracy 5
    fi
    clear_screen
    adb shell am start -W -n "$PACKAGE/.MainActivity" --es ops "$ALL_OPS" >/dev/null
    local i log=""
    for ((i = 0; i < 90; i++)); do
        log="$(adb logcat -d -s BigBrotherCanary:I | tr -d '\r')"
        grep -q "BigBrotherCanary: done" <<<"$log" && break
        if ((i % 5 == 4)) && focus_elsewhere; then clear_screen; fi
        sleep 1
    done
    ((i < 90)) || {
        echo "error: the canary did not finish; is the screen on and unlocked?" >&2
        exit 2
    }
    view="$(sed -n 's/.*BigBrotherCanary: sandbox //p' <<<"$log")"
}

# Have the canary ask for a file and pick the marker for it in the system's
# file picker; it keeps the grant (persists it) and reads the file at once.
# Sets `picked`: what it read, or nothing if the marker was not to be found
# in the picker.
pick_marker() {
    local dump bounds i
    adb shell am force-stop "$PACKAGE"
    adb logcat -c
    adb shell am start -W -n "$PACKAGE/.MainActivity" --ez pick true >/dev/null
    picked=""
    for ((i = 0; i < 10; i++)); do
        sleep 1
        dump="$(adb exec-out uiautomator dump /dev/tty 2>/dev/null || true)"
        bounds="$(grep -o "text=\"${MARKER##*/}\"[^>]*bounds=\"[^\"]*\"" <<<"$dump" |
            grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -n 1 | tr '[],' '   ')" || true
        [[ -n "$bounds" ]] && break
    done
    [[ -n "$bounds" ]] || return 0
    set -- $bounds
    adb shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
    for ((i = 0; i < 10; i++)); do
        picked="$(adb logcat -d -s BigBrotherCanary:I | tr -d '\r' | sed -n 's/.*BigBrotherCanary: sandbox picked_file=//p')"
        [[ -n "$picked" ]] && break
        sleep 1
    done
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
# (sed stops at the first; a reader that stops early in a pipeline would
# end the writer with SIGPIPE, which pipefail makes a failure.)
seen() { sed -n "/^$2=/{s///p;q}" <<<"$1"; }
# The mode (0 allowed, 1 ignored, ...) the canary found app op $2 in, in
# report $1; "none" for an op this Android version does not have.
op_mode() { seen "$1" ops | tr ',' '\n' | sed -n "s/^$2=//p"; }
# The ops of the items given after the report that it did not find ignored.
ops_not_held() {
    local report="$1" item op mode out=""
    shift
    for item in "$@"; do
        for op in ${ITEM_OPS[$item]}; do
            mode="$(op_mode "$report" "$op")"
            [[ "$mode" == 1 || "$mode" == none ]] || out+=" ${op#android:}=${mode:-?}"
        done
    done
    echo "$out"
}
# The ops of the items given after two reports whose modes differ between them.
ops_changed() {
    local report="$1" base="$2" item op out=""
    shift 2
    for item in "$@"; do
        for op in ${ITEM_OPS[$item]}; do
            [[ "$(op_mode "$report" "$op")" == "$(op_mode "$base" "$op")" ]] || out+=" ${op#android:}"
        done
    done
    echo "$out"
}

# The first value of a field in the status (awk reads to the end; see seen).
status_field() { grep -o "\"$1\":[a-z0-9]*" <<<"$status" | awk -F: 'NR == 1 { print $2 }'; }

# check_if <yes|no> <why not> <what> <condition...>: a check that needs what
# the device may not allow, such as a provider that keeps a test record;
# skipped, saying why, where it does not.
check_if() {
    local able="$1" why="$2"
    shift 2
    if [[ "$able" == yes ]]; then check "$@"; else skip "$1: $why"; fi
}
NOT_KEPT="the provider did not keep the test record"

# Installed afresh, so that its permissions are those granted below and
# nothing a previous run left.
adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb install "$APK" >/dev/null
for permission in $PERMISSIONS; do
    adb shell pm grant "$PACKAGE" "android.permission.$permission" 2>/dev/null || true
done
# All files access, so that outside the sandbox the marker can be read: the
# check is that the sandbox takes it away, not that the system refuses it.
adb shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow
as_root "echo real > $MARKER && chown media_rw:media_rw $MARKER"
# A location to be had, from a test provider the shell may set.
mock_location="$(adb shell appops get com.android.shell android:mock_location | tr -d '\r' | sed -n 's/.*: \([a-z]*\).*/\1/p')"
adb shell appops set com.android.shell android:mock_location allow
as_shell cmd location providers add-test-provider gps >/dev/null 2>&1 || true
location=no
# The answer is kept as the reason the location checks are skipped.
if no_location="$(as_shell cmd location providers set-test-provider-enabled gps true 2>&1)"; then
    location=yes
fi
no_location="$(tr -d '\r' <<<"$no_location" | sed -n '1{p;q}')"
NO_LOCATION="no test location provider${no_location:+ ($no_location)}"
# The test records, written as root through the providers (the script is
# pushed rather than quoted through su).
adb shell "cat > $SEED" <<EOF
content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s:$TEST_ACCOUNT --bind account_name:s:$TEST_ACCOUNT
raw=\$(content query --uri content://com.android.contacts/raw_contacts --projection _id --where "account_type='$TEST_ACCOUNT'" | grep -o '_id=[0-9]*' | tail -n 1 | cut -d= -f2)
content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:\$raw --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:Canary
content insert --uri content://sms/inbox --bind address:s:$TEST_NUMBER --bind body:s:canary --bind read:i:1
content insert --uri content://call_log/calls --bind number:s:$TEST_NUMBER --bind type:i:1 --bind duration:i:1 --bind date:l:\$(date +%s)000
cal="content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=$TEST_ACCOUNT&account_type=LOCAL"
content insert --uri "\$cal" --bind account_name:s:$TEST_ACCOUNT --bind account_type:s:LOCAL --bind name:s:$TEST_ACCOUNT --bind calendar_displayName:s:$TEST_ACCOUNT --bind calendar_access_level:i:700 --bind ownerAccount:s:$TEST_ACCOUNT --bind visible:i:1 --bind sync_events:i:1
id=\$(content query --uri content://com.android.calendar/calendars --projection _id --where "account_name='$TEST_ACCOUNT'" | grep -o '_id=[0-9]*' | tail -n 1 | cut -d= -f2)
content insert --uri content://com.android.calendar/events --bind calendar_id:i:\$id --bind title:s:canary --bind dtstart:l:\$(date +%s)000 --bind dtend:l:\$(date +%s)000 --bind eventTimezone:s:UTC
echo "kept contacts \$(content query --uri content://com.android.contacts/raw_contacts --projection _id --where "account_type='$TEST_ACCOUNT'" | grep -c '_id=')"
echo "kept sms \$(content query --uri content://sms --projection _id --where "address='$TEST_NUMBER'" | grep -c '_id=')"
echo "kept call_log \$(content query --uri content://call_log/calls --projection _id --where "number='$TEST_NUMBER'" | grep -c '_id=')"
echo "kept calendar \$(content query --uri content://com.android.calendar/events --projection _id --where "calendar_id=\$id" | grep -c '_id=')"
EOF
# Some providers drop an insert without an error (the SMS provider of some
# images, whoever asks): what each kept is read back, keyed by what the
# canary reports the count of.
# A seed that did not run, or did not report on every kind, is an error.
kept="$(as_root "sh $SEED" | tr -d '\r' | sed -n 's/^kept \([a-z_]*\) \([0-9]*\)$/\1 \2/p')" || {
    echo "error: the test records could not be written" >&2
    exit 2
}
declare -A seeded=([contacts]=no [sms]=no [call_log]=no [calendar]=no)
reported=0
while read -r kind count; do
    [[ -n "$kind" && -n "${seeded[$kind]+set}" ]] || continue
    reported=$((reported + 1))
    if ((count > 0)); then seeded[$kind]=yes; fi
done <<<"$kept"
((reported == ${#seeded[@]})) || {
    echo "error: the seed reported on $reported of ${#seeded[@]} kinds of test record" >&2
    exit 2
}

# Leave the device as it was, whatever happens.
cleanup() {
    as_root "$BBD sandbox set $PACKAGE$ALL_GLOBAL" >/dev/null 2>&1 || true
    as_root "$BBD sandbox disable $PACKAGE" >/dev/null 2>&1 || true
    as_root "rm -f $MARKER" || true
    adb shell "cat > $SEED" <<EOF || true
content delete --uri "content://com.android.contacts/raw_contacts?caller_is_syncadapter=true" --where "account_type='$TEST_ACCOUNT'"
content delete --uri content://sms --where "address='$TEST_NUMBER'"
content delete --uri content://call_log/calls --where "number='$TEST_NUMBER'"
content delete --uri "content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=$TEST_ACCOUNT&account_type=LOCAL" --where "account_name='$TEST_ACCOUNT'"
EOF
    as_root "sh $SEED; rm -f $SEED" >/dev/null 2>&1 || true
    as_shell cmd location providers remove-test-provider gps >/dev/null 2>&1 || true
    adb shell appops set com.android.shell android:mock_location "${mock_location:-default}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

as_root "$BBD sandbox disable $PACKAGE" >/dev/null

echo "outside the sandbox:"
run_canary
outside="$view"
for kind in contacts sms call_log calendar; do
    check_if "${seeded[$kind]}" "$NOT_KEPT" "the test record of $kind is seen ($(seen "$outside" "$kind"))" \
        [ "$(seen "$outside" "$kind")" -gt 0 ]
done
check_if "$location" "$NO_LOCATION" "a location is had ($(seen "$outside" location))" grep -q '^accuracy:' <<<"$(seen "$outside" location)"
check "the real storage is visible" [ "$(seen "$outside" marker)" = real ]
check "the chip is named in /proc/cpuinfo" [ "$(seen "$outside" cpu_named)" = true ]
pick_marker
if [[ -n "$picked" ]]; then
    run_canary
    outside="$view"
    check "a file picked through the system can be read again ($(seen "$outside" granted_file))" \
        [ "$(seen "$outside" granted_file)" = real ]
else
    skip "picked files: the marker was not found in the file picker"
fi

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
# The processes of the app under uids of their own, which the gate does not
# hold: the system gives them no storage.
check "an isolated process finds no storage ($(seen "$inside" isolated_storage))" \
    [ "$(seen "$inside" isolated_storage)" = null ]
check "nor the real files ($(seen "$inside" isolated_marker))" \
    grep -q '^refused' <<<"$(seen "$inside" isolated_marker)"
check "nor the Android ID ($(seen "$inside" isolated_android_id))" \
    grep -q '^refused' <<<"$(seen "$inside" isolated_android_id)"
check "the processors are all there ($(seen "$inside" cpu_count))" \
    [ "$(seen "$inside" cpu_count)" = "$(seen "$outside" cpu_count)" ]
check "the memory is reported as it is ($(seen "$inside" mem_total_kb) kB)" \
    [ "$(seen "$inside" mem_total_kb)" = "$(seen "$outside" mem_total_kb)" ]

# Layer 3: personal data.
check_if "${seeded[contacts]}" "$NOT_KEPT" "no contacts ($(seen "$outside" contacts) outside)" [ "$(seen "$inside" contacts)" = 0 ]
check "no clipboard ($(seen "$outside" clipboard) outside)" [ "$(seen "$inside" clipboard)" = 0 ]
check "no images ($(seen "$outside" images) outside)" [ "$(seen "$inside" images)" = 0 ]
check_if "${seeded[sms]}" "$NOT_KEPT" "no messages ($(seen "$outside" sms) outside)" [ "$(seen "$inside" sms)" = 0 ]
check_if "${seeded[call_log]}" "$NOT_KEPT" "no call log ($(seen "$outside" call_log) outside)" [ "$(seen "$inside" call_log)" = 0 ]
check_if "${seeded[calendar]}" "$NOT_KEPT" "no calendar ($(seen "$outside" calendar) outside)" [ "$(seen "$inside" calendar)" = 0 ]
check "no accounts ($(seen "$outside" accounts) outside)" [ "$(seen "$inside" accounts)" = 0 ]
check_if "$location" "$NO_LOCATION" "no location ($(seen "$inside" location))" [ "$(seen "$inside" location)" = none ]
not_held="$(ops_not_held "$inside" "${!ITEM_OPS[@]}")"
check "every item's app ops are ignored${not_held:+ (not:$not_held)}" [ -z "$not_held" ]

# Files picked through the system, which needs the LSPosed hook.
if [[ -z "$picked" ]]; then
    skip "picked files: none was picked"
elif [[ "$(status_field uri_grants)" == true ]]; then
    check "a file picked before is out of reach ($(seen "$inside" granted_file))" \
        grep -q '^refused' <<<"$(seen "$inside" granted_file)"
    pick_marker
    check "a file picked now is open to the app ($picked)" [ "$picked" = real ]
else
    skip "picked files: the hook is not in place"
fi

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
    check "starts were held for under $((max_hold_us / 1000)) ms ($(status_field hold_p95_us) us)" \
        [ "$(status_field hold_p95_us)" -lt "$max_hold_us" ]
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
    check_if "${seeded[contacts]}" "$NOT_KEPT" "the contacts are as outside ($(seen "$only_id" contacts))" \
        [ "$(seen "$only_id" contacts)" = "$(seen "$outside" contacts)" ]
    check "the installed apps are as outside" [ "$(seen "$only_id" apps)" = "$(seen "$outside" apps)" ]
    check_if "$location" "$NO_LOCATION" "the location is as outside ($(seen "$only_id" location))" \
        [ "$(seen "$only_id" location)" = "$(seen "$outside" location)" ]
    not_held="$(ops_not_held "$only_id" device_ids)"
    others=()
    for item in "${!ITEM_OPS[@]}"; do [[ "$item" == device_ids ]] || others+=("$item"); done
    changed="$(ops_changed "$only_id" "$outside" "${others[@]}")"
    check "only device_ids' app ops are ignored${not_held:+ (not held:$not_held)}${changed:+ (changed:$changed)}" \
        [ -z "$not_held$changed" ]
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

# Location made coarse takes the precise permission back (the system then
# gives an approximate location), and foreground-only leaves the camera and
# the microphone to the app while it is in front.
echo "with location coarse, camera and microphone foreground-only:"
# The first grant line, read to the end: a reader that stops early would end
# dumpsys with SIGPIPE, which pipefail makes this script's exit.
precise() {
    adb shell dumpsys package "$PACKAGE" | tr -d '\r' |
        awk '/ACCESS_FINE_LOCATION: granted/ && !found++ { sub(/^ +/, ""); print }'
}
uid_mode() { adb shell appops get --uid "$PACKAGE" "$1" | tr -d '\r' | sed -n 's/^Uid mode: [A-Z_]*: \([a-z]*\).*/\1/p'; }
precise_before="$(precise)"
as_root "$BBD sandbox set $PACKAGE location=coarse camera=foreground microphone=foreground" >/dev/null
run_canary
coarse="$view"
accuracy="$(seen "$coarse" location | sed -n 's/^accuracy://p')"
check_if "$location" "$NO_LOCATION" "the location is approximate ($(seen "$coarse" location))" [ "${accuracy:-0}" -ge 1000 ]
check "the precise permission is taken back ($(precise))" grep -q 'granted=false.*USER_FIXED' <<<"$(precise)"
check "the camera and the microphone are foreground-only" \
    [ "$(uid_mode CAMERA)" = foreground -a "$(uid_mode RECORD_AUDIO)" = foreground ]
check "and in front the app has them" \
    [ "$(op_mode "$coarse" android:camera)" = 0 -a "$(op_mode "$coarse" android:record_audio)" = 0 ]
as_root "$BBD sandbox set $PACKAGE location=on camera=on microphone=on" >/dev/null
check "the precise permission is back as it was" [ "$(precise)" = "$precise_before" ]

echo "disabling the sandbox:"
as_root "$BBD sandbox disable $PACKAGE" >/dev/null
run_canary
after="$view"
check "the real storage is back" [ "$(seen "$after" marker)" = real ]
if [[ -n "$picked" ]]; then
    check "and so is the file picked before" [ "$(seen "$after" granted_file)" = real ]
fi
check "the chip is named again" [ "$(seen "$after" cpu_named)" = true ]
check "the Android ID is the real one" [ "$(seen "$after" android_id)" = "$(seen "$outside" android_id)" ]
check "the installed apps are visible again" [ "$(seen "$after" apps)" = "$(seen "$outside" apps)" ]
check "the clipboard is readable again" [ "$(seen "$after" clipboard)" = "$(seen "$outside" clipboard)" ]
# As the app finds them: `appops get` also lists an op once the app has
# used it, whatever its mode.
changed="$(ops_changed "$after" "$outside" "${!ITEM_OPS[@]}")"
check "the app ops are as they were${changed:+ (changed:$changed)}" [ -z "$changed" ]
check_if "${seeded[sms]}" "$NOT_KEPT" "the messages are back" [ "$(seen "$after" sms)" = "$(seen "$outside" sms)" ]
check_if "$location" "$NO_LOCATION" "the location is back ($(seen "$after" location))" [ "$(seen "$after" location)" = "$(seen "$outside" location)" ]

# A permission granted while in the sandbox is the app's once it is out.
echo "with contacts granted while in the sandbox:"
adb shell pm revoke "$PACKAGE" android.permission.READ_CONTACTS
as_root "$BBD sandbox enable $PACKAGE" >/dev/null
adb shell pm grant "$PACKAGE" android.permission.READ_CONTACTS
as_root "$BBD sandbox disable $PACKAGE" >/dev/null
run_canary
granted="$view"
check "the contacts op is allowed ($(op_mode "$granted" android:read_contacts))" \
    [ "$(op_mode "$granted" android:read_contacts)" = 0 ]
check_if "${seeded[contacts]}" "$NOT_KEPT" "the contacts are back ($(seen "$granted" contacts))" [ "$(seen "$granted" contacts)" = "$(seen "$outside" contacts)" ]

# What a green run did not cover, in sight.
echo "$skipped check(s) skipped"
if ((failed > 0)); then
    echo "$failed check(s) failed"
    exit 1
fi
echo "all checks passed"
