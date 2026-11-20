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

# Plays the permission matrix (testdata/permissions/matrix.tsv) on a
# device. For each case, a fresh install of the canary app is given the
# permission state the case starts with, put in the sandbox, changed as the
# case says and taken out; then the permission, the uid mode of its app op,
# their flags and what the canary reads are compared with what the case
# expects. The same matrix is played on the JVM by PermissionMatrixTest.
#
#   scripts/check-permissions.sh            every case
#   scripts/check-permissions.sh NAME...    the cases named
#
# Requires adb, a connected and unlocked device with the module installed
# and running, the sandbox's global settings at their default (every item
# on), and the canary built:
#   (cd android && ./gradlew :canary:assembleDebug)
# Each case takes half a minute or so. On Android 12 and 12L the cases with
# settings steps are skipped.
#
# Exits non-zero if a case fails.
set -euo pipefail

cd "$(dirname "$0")/.."

PACKAGE=li.mercury.bigbrother.canary
APK=android/canary/build/outputs/apk/debug/canary-debug.apk
BBD=/data/adb/modules/big_brother/bin/bbd
MATRIX=testdata/permissions/matrix.tsv
# The app ops helper, as its command line names it. The brackets keep the
# pattern from matching the shell that runs pgrep with it.
HELPER='catalog[.]AppOpsGuard'
# How long the helper takes to look after a reported change (SETTLE_MS in
# AppOpsGuard.kt), with room to spare.
SETTLE=3
# Every item of the sandbox (`bbd/src/sandbox/items.rs`), each set to on,
# or back to the global settings.
ITEMS="storage cpu_info contacts messages calendar clipboard_accounts location camera microphone
body_sensors device_ids installed_apps nearby_devices notification_content screen_content"
ALL_ON="" ALL_GLOBAL=""
for item in $ITEMS; do
    ALL_ON+=" $item=on"
    ALL_GLOBAL+=" $item=global"
done
# A contact for the canary to find when it may read contacts.
TEST_ACCOUNT=bb.permissions
SEED=/data/local/tmp/bb_permissions_seed.sh

[[ -f "$APK" ]] || {
    echo "error: $APK not found; build it with: (cd android && ./gradlew :canary:assembleDebug)" >&2
    exit 2
}

if [[ "$(adb shell id -u | tr -d '\r')" == 0 ]]; then
    root_shell=true
else
    root_shell=false
fi
as_root() {
    if $root_shell; then
        adb shell "$*"
    else
        adb shell su -c "'$*'"
    fi
}
# Run a command as the shell user, whose test location provider the shell
# may set: Android 13 and 14 refuse it to root (adb root on an emulator).
as_shell() {
    if $root_shell; then
        adb shell "su 2000 $*"
    else
        adb shell "$*"
    fi
}

source scripts/canary-runner.sh

# What a case's subject is: the permissions pm and settings steps grant,
# those "Don't allow" denies in the settings (location takes background
# location with it), the one that is checked, its app op (as `appops`
# names it) and the ops the user sets to ignore for "ignored".
subject_permissions() {
    case "$1" in
    contacts) echo READ_CONTACTS ;;
    camera) echo CAMERA ;;
    location) echo ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ;;
    *) return 1 ;;
    esac
}
subject_denied_in_settings() {
    case "$1" in
    location) echo ACCESS_BACKGROUND_LOCATION ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ;;
    *) subject_permissions "$1" ;;
    esac
}
subject_permission() {
    case "$1" in
    contacts) echo READ_CONTACTS ;;
    camera) echo CAMERA ;;
    location) echo ACCESS_FINE_LOCATION ;;
    *) return 1 ;;
    esac
}
subject_op() {
    case "$1" in
    contacts) echo READ_CONTACTS ;;
    camera) echo CAMERA ;;
    location) echo FINE_LOCATION ;;
    *) return 1 ;;
    esac
}
subject_ignored_ops() {
    case "$1" in
    contacts) echo READ_CONTACTS ;;
    camera) echo CAMERA ;;
    location) echo COARSE_LOCATION FINE_LOCATION ;;
    *) return 1 ;;
    esac
}
# The permission a step names (settings:grant:fine).
named_permission() {
    case "$1" in
    fine) echo ACCESS_FINE_LOCATION ;;
    coarse) echo ACCESS_COARSE_LOCATION ;;
    background) echo ACCESS_BACKGROUND_LOCATION ;;
    *) return 1 ;;
    esac
}
# The app op of permission $1.
op_of() {
    case "$1" in
    READ_CONTACTS) echo READ_CONTACTS ;;
    CAMERA) echo CAMERA ;;
    ACCESS_FINE_LOCATION) echo FINE_LOCATION ;;
    ACCESS_COARSE_LOCATION) echo COARSE_LOCATION ;;
    *) return 1 ;;
    esac
}

# The package dump, read to the end: a reader that stops early would end
# dumpsys with SIGPIPE.
package_dump() { adb shell dumpsys package "$PACKAGE" | tr -d '\r'; }

dump=""
# The first grant line of permission $1 in `dump`.
line_of() { awk -v name="android.permission.$1: granted=" 'index($0, name) && !found++ { sub(/^ +/, ""); print }' <<<"$dump"; }
is_granted() { [[ "$(line_of "$1")" == *granted=true* ]]; }
# Granted as the settings count it: granted, with its op not ignored for
# it (REVOKED_COMPAT).
granted_to_settings() {
    local line
    line="$(line_of "$1")"
    [[ "$line" == *granted=true* && "$line" != *REVOKED_COMPAT* ]]
}

# The root command that allows ($1 grant) or denies ($1 revoke) permission
# $2 as the settings do (`KotlinUtils` of the permission controller), from
# its state in `dump`: one granted to them is not granted again, and one
# not granted to them is not revoked. Allowing grants, allows the op
# (`allowAppOp`) and clears REVOKED_COMPAT; denying revokes and ignores the
# op (`disallowAppOp`). Either way the permission is left the user's choice
# that the app may ask to change.
settings_command() {
    local verb="$1" permission="$2" full="android.permission.$2" out="" foreground mode
    if [[ "$verb" == grant ]] && ! granted_to_settings "$permission"; then
        is_granted "$permission" || out+="pm grant $PACKAGE $full && "
        if [[ "$permission" == ACCESS_BACKGROUND_LOCATION ]]; then
            for foreground in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
                granted_to_settings "$foreground" && out+="appops set --uid $PACKAGE $(op_of "$foreground") allow && "
            done
        else
            mode=allow
            case "$permission" in
            # Its background permission is not one the app requests.
            CAMERA) mode=foreground ;;
            ACCESS_FINE_LOCATION | ACCESS_COARSE_LOCATION)
                granted_to_settings ACCESS_BACKGROUND_LOCATION || mode=foreground
                ;;
            esac
            out+="appops set --uid $PACKAGE $(op_of "$permission") $mode && "
        fi
        out+="pm clear-permission-flags $PACKAGE $full revoked-compat && "
    elif [[ "$verb" == revoke ]] && granted_to_settings "$permission"; then
        out+="pm revoke $PACKAGE $full && "
        if [[ "$permission" == ACCESS_BACKGROUND_LOCATION ]]; then
            for foreground in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
                granted_to_settings "$foreground" && out+="appops set --uid $PACKAGE $(op_of "$foreground") foreground && "
            done
        else
            out+="appops set --uid $PACKAGE $(op_of "$permission") ignore && "
        fi
    fi
    echo "${out}pm clear-permission-flags $PACKAGE $full user-fixed && pm set-permission-flags $PACKAGE $full user-set"
}

# The root command for step $1 of a case whose subject is $subject; fails
# for a step that is not one, or cannot be joined to others.
step_command() {
    local step="$1" verb argument="" change named item permission permissions out=""
    verb="${step%%:*}"
    [[ "$step" == *:* ]] && argument="${step#*:}"
    case "$verb" in
    pm | settings)
        change="${argument%%:*}"
        [[ "$change" == grant || "$change" == revoke ]] || return 1
        if [[ "$argument" == *:* ]]; then
            named="${argument#*:}"
            permissions="$(named_permission "$named")" || return 1
        elif [[ "$verb:$change" == settings:revoke ]]; then
            permissions="$(subject_denied_in_settings "$subject")"
        else
            permissions="$(subject_permissions "$subject")"
        fi
        for permission in $permissions; do
            if [[ "$verb" == pm ]]; then
                out+="${out:+ && }pm $change $PACKAGE android.permission.$permission"
            else
                out+="${out:+ && }$(settings_command "$change" "$permission")"
            fi
        done
        echo "$out"
        ;;
    set)
        item="$argument"
        [[ "$argument" == *=* ]] || item="$subject=$argument"
        echo "$BBD sandbox set $PACKAGE $item >/dev/null"
        ;;
    restart)
        # Ended; the steps after it in the group run before it is back.
        case "$argument" in
        # By the pid found before the group (`before_pid` of play_case).
        helper) echo "(kill -9 $before_pid || true)" ;;
        daemon) echo "(kill -9 \$(pidof bbd) || true)" ;;
        *) return 1 ;;
        esac
        ;;
    leave)
        case "$argument" in
        disable) echo "$BBD sandbox disable $PACKAGE >/dev/null" ;;
        off) echo "$BBD sandbox set $PACKAGE $subject=off >/dev/null" ;;
        *) return 1 ;;
        esac
        ;;
    *) return 1 ;;
    esac
}

# The pid of the app ops helper, or nothing. pgrep finds none, and says so
# with its status, while the helper is away.
helper_pid() { (as_root "pgrep -f $HELPER" || true) | tr -d '\r' | awk 'NR == 1'; }

# Wait for a helper other than the one with pid $1 (empty for any) to have
# started, and to have been told of the sandboxed apps.
await_helper() {
    local i pid
    for ((i = 0; i < 60; i++)); do
        pid="$(helper_pid)"
        [[ -n "$pid" && "$pid" != "$1" ]] && break
        sleep 1
    done
    [[ -n "$pid" && "$pid" != "$1" ]] || return 1
    sleep "$SETTLE"
}

# Wait for the daemon to answer commands again.
await_daemon() {
    local i
    for ((i = 0; i < 30; i++)); do
        as_root "$BBD sandbox status" >/dev/null 2>&1 && return 0
        sleep 1
    done
    return 1
}

# Uninstall the canary while it is in the sandbox, take it out (the daemon
# finds its uid without the package), install it again and grant the
# subject's permissions.
leave_by_uninstalling() {
    local permission
    adb uninstall "$PACKAGE" >/dev/null || { why="the canary was not uninstalled"; return 1; }
    as_root "$BBD sandbox disable $PACKAGE" >/dev/null || { why="disabling the uninstalled app failed"; return 1; }
    adb install "$APK" >/dev/null || { why="the canary was not installed again"; return 1; }
    for permission in $(subject_permissions "$subject"); do
        adb shell pm grant "$PACKAGE" "android.permission.$permission" || { why="granting $permission failed"; return 1; }
    done
}

# Start the canary and wait for its report. Sets `view`: the lines
# `<key>=<value>` it reported about what it sees.
run_canary() {
    adb shell am force-stop "$PACKAGE"
    adb logcat -c
    # Each start finds a fresh location from the test provider.
    if [[ "$test_location" == yes ]]; then
        as_shell cmd location providers set-test-provider-location gps --location 52.52,13.405 --accuracy 5
    fi
    launch_and_wait_for_canary 90 --es ops "android:camera" || return 1
    view="$(sed -n 's/.*BigBrotherCanary: sandbox //p' <<<"$canary_log")"
}

# The value the canary reported for key $1.
seen() { sed -n "s/^$1=//p" <<<"$view" | awk 'NR == 1'; }

# The uid mode of op $1.
uid_mode() { adb shell appops get --uid "$PACKAGE" "$1" | tr -d '\r' | sed -n 's/^Uid mode: [A-Z_]*: \([a-z]*\).*/\1/p'; }

# What the canary reads of the subject: yes or no; for location precise,
# approximate or none.
reads_of() {
    local location accuracy
    case "$subject" in
    contacts)
        [[ "$(seen contacts)" =~ ^[1-9][0-9]*$ ]] && echo yes || echo no
        ;;
    camera)
        # The mode it finds its op in, in the foreground.
        [[ "$(seen ops)" == "android:camera=0" ]] && echo yes || echo no
        ;;
    location)
        location="$(seen location)"
        accuracy="${location#accuracy:}"
        if [[ "$location" != accuracy:* ]]; then
            echo none
        elif ((accuracy < 1000)); then
            echo precise
        else
            echo approximate
        fi
        ;;
    esac
}

# Play case $name. Sets `why` and fails if it could not be played, and sets
# `seen_state`, and `seen_background` with `expected_background` for
# location, to be compared with what is expected, if it could.
play_case() {
    local permission op group part command output restart before_pid groups=() parts=()
    why=""
    seen_state=""
    seen_background=""
    expected_background=""
    as_root "$BBD sandbox disable $PACKAGE" >/dev/null 2>&1 || true
    adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
    adb install "$APK" >/dev/null || { why="the canary was not installed"; return 1; }
    local granting=""
    case "$before" in
    granted | ignored) granting="$(subject_permissions "$subject")" ;;
    always) granting="$(subject_permissions "$subject") ACCESS_BACKGROUND_LOCATION" ;;
    approximate) granting=ACCESS_COARSE_LOCATION ;;
    denied) ;;
    *)
        why="no state $before"
        return 1
        ;;
    esac
    for permission in $granting; do
        adb shell pm grant "$PACKAGE" "android.permission.$permission" || { why="granting $permission failed"; return 1; }
    done
    if [[ "$before" == ignored ]]; then
        for op in $(subject_ignored_ops "$subject"); do
            adb shell appops set --uid "$PACKAGE" "$op" ignore || { why="setting $op to ignore failed"; return 1; }
        done
    fi
    # The entry an earlier case left keeps its items: every one on again, so
    # that the app enters with them on.
    as_root "$BBD sandbox set $PACKAGE$ALL_ON" >/dev/null 2>&1 || true
    as_root "$BBD sandbox enable $PACKAGE" >/dev/null || { why="enabling failed"; return 1; }
    as_root "$BBD sandbox set $PACKAGE$ALL_ON" >/dev/null || { why="setting every item on failed"; return 1; }
    sleep "$SETTLE"

    # Each group runs before the helper looks; the leave joins the last one
    # when it is to come right after it.
    [[ "$steps" == - ]] || IFS=',' read -r -a groups <<<"$steps"
    if [[ "$leave" == "&"* && ${#groups[@]} -gt 0 ]]; then
        groups[${#groups[@]} - 1]+="&leave:${leave#&}"
    else
        groups+=("leave:${leave#&}")
    fi
    for group in "${groups[@]}"; do
        if [[ "$group" == leave:uninstall ]]; then
            leave_by_uninstalling || return 1
            sleep "$SETTLE"
            continue
        fi
        IFS='&' read -r -a parts <<<"$group"
        # The settings decide by the state the steps find.
        [[ "$group" == *settings:* ]] && dump="$(package_dump)"
        restart=""
        before_pid=""
        if [[ "$group" == *restart:* ]]; then
            restart="${group#*restart:}"
            restart="${restart%%&*}"
            before_pid="$(helper_pid)"
            [[ -n "$before_pid" ]] || { why="no app ops helper is running"; return 1; }
        fi
        command=""
        for part in "${parts[@]}"; do
            part="$(step_command "$part")" || { why="no step that can be joined: $group"; return 1; }
            command+="${command:+ && }$part"
        done
        if ! output="$(as_root "$command" 2>&1)"; then
            why="$group failed: $(tr -d '\r' <<<"$output" | awk 'NF { last = $0 } END { print last }')"
            return 1
        fi
        case "$restart" in
        daemon)
            sleep 1
            await_daemon || { why="the daemon did not start again"; return 1; }
            await_helper "$before_pid" || { why="the app ops helper did not start with the daemon"; return 1; }
            ;;
        helper)
            # The daemon starts it again after ten seconds.
            await_helper "$before_pid" || { why="the app ops helper was not started again"; return 1; }
            ;;
        esac
        sleep "$SETTLE"
    done

    run_canary || { why="$canary_error"; return 1; }
    dump="$(package_dump)"
    local line mode granted=denied
    line="$(line_of "$(subject_permission "$subject")")"
    [[ "$line" == *granted=true* ]] && granted=granted
    mode="$(uid_mode "$(subject_op "$subject")")"
    # No uid mode: the op's default, which for these is allowed.
    mode="${mode:-allow}"
    local revoked_compat=false user_fixed=false
    [[ "$line" == *REVOKED_COMPAT* ]] && revoked_compat=true
    [[ "$line" == *USER_FIXED* ]] && user_fixed=true
    seen_state="$granted $mode $(reads_of) revoked_compat=$revoked_compat user_fixed=$user_fixed"
    if [[ "$subject" == location ]]; then
        # Marked for any mode but allowed, while it is granted.
        line="$(line_of ACCESS_BACKGROUND_LOCATION)"
        seen_background="background_revoked_compat=false"
        [[ "$line" == *REVOKED_COMPAT* ]] && seen_background="background_revoked_compat=true"
        expected_background="background_revoked_compat=false"
        [[ "$line" == *granted=true* && "$mode" != allow ]] && expected_background="background_revoked_compat=true"
    fi
    return 0
}

mapfile -t CASES < <(awk -F '\t' '!/^#/ && NF == 9' "$MATRIX")
if (($# > 0)); then
    for wanted in "$@"; do
        found=false
        for line in "${CASES[@]}"; do
            [[ "${line%%$'\t'*}" == "$wanted" ]] && found=true
        done
        $found || {
            echo "error: no case $wanted in $MATRIX" >&2
            exit 2
        }
    done
fi

# Leave the device as it was, but for the canary, which stays installed
# without its permissions: in place before anything on it changes.
mock_location="$(adb shell appops get com.android.shell android:mock_location | tr -d '\r' | sed -n 's/.*: \([a-z]*\).*/\1/p')"
cleanup() {
    as_root "$BBD sandbox set $PACKAGE$ALL_GLOBAL" >/dev/null 2>&1 || true
    as_root "$BBD sandbox disable $PACKAGE" >/dev/null 2>&1 || true
    adb shell "cat > $SEED" <<EOF || true
content delete --uri "content://com.android.contacts/raw_contacts?caller_is_syncadapter=true" --where "account_type='$TEST_ACCOUNT'"
EOF
    as_root "sh $SEED; rm -f $SEED" >/dev/null 2>&1 || true
    as_shell cmd location providers remove-test-provider gps >/dev/null 2>&1 || true
    adb shell appops set com.android.shell android:mock_location "${mock_location:-default}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# A contact to read, and a location to be had from a test provider the
# shell may set, as scripts/check-sandbox.sh has them.
adb shell "cat > $SEED" <<EOF
content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s:$TEST_ACCOUNT --bind account_name:s:$TEST_ACCOUNT
raw=\$(content query --uri content://com.android.contacts/raw_contacts --projection _id --where "account_type='$TEST_ACCOUNT'" | grep -o '_id=[0-9]*' | tail -n 1 | cut -d= -f2)
content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:\$raw --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:Canary
echo "kept contacts \$(content query --uri content://com.android.contacts/raw_contacts --projection _id --where "account_type='$TEST_ACCOUNT'" | grep -c '_id=')"
EOF
# A provider may drop the insert without an error: what it kept is read
# back, and the contacts cases are skipped without it.
kept="$(as_root "sh $SEED" | tr -d '\r' | sed -n 's/^kept contacts \([0-9]*\)$/\1/p')" || {
    echo "error: the test contact could not be written" >&2
    exit 2
}
[[ -n "$kept" ]] || {
    echo "error: the seed did not report on the test contact" >&2
    exit 2
}
contacts=no
((kept > 0)) && contacts=yes
adb shell appops set com.android.shell android:mock_location allow
as_shell cmd location providers add-test-provider gps >/dev/null 2>&1 || true
test_location=no
# The answer is kept as the reason the location cases are skipped.
if no_location="$(as_shell cmd location providers set-test-provider-enabled gps true 2>&1)"; then
    test_location=yes
fi
no_location="$(tr -d '\r' <<<"$no_location" | sed -n '1{p;q}')"

# The settings' steps change flags with pm set-permission-flags and
# clear-permission-flags, which Android 13 (API 33) added.
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"

failed=0 played=0 skipped=0
skip() {
    echo "  skipped $*"
    skipped=$((skipped + 1))
}
for line in "${CASES[@]}"; do
    IFS=$'\t' read -r name subject before steps leave want_permission want_mode want_reads where <<<"$line"
    if (($# > 0)); then
        chosen=false
        for wanted in "$@"; do [[ "$wanted" == "$name" ]] && chosen=true; done
        $chosen || continue
    fi
    if [[ "$where" != both ]]; then
        skip "$name: played on the JVM only"
        continue
    fi
    if [[ "$steps" == *settings:* ]] && ((sdk < 33)); then
        skip "$name: settings steps need Android 13"
        continue
    fi
    if [[ "$subject" == contacts && "$contacts" == no ]]; then
        skip "$name: the provider did not keep the test contact"
        continue
    fi
    if [[ "$subject" == location && "$test_location" == no ]]; then
        skip "$name: no test location provider${no_location:+ ($no_location)}"
        continue
    fi
    played=$((played + 1))
    # Marked REVOKED_COMPAT only while granted with its op ignored, or,
    # revoked, if the user had ignored its op before: Android leaves that.
    if [[ "$want_permission" == granted ]]; then
        revoked_compat="$([[ "$want_mode" == ignore ]] && echo true || echo false)"
    else
        revoked_compat="$([[ "$before" == ignored && "$leave" != uninstall ]] && echo true || echo false)"
    fi
    expected="$want_permission $want_mode $want_reads revoked_compat=$revoked_compat user_fixed=false"
    if ! play_case; then
        echo "  FAILED  $name: $why"
        failed=$((failed + 1))
    elif [[ "$seen_state $seen_background" != "$expected $expected_background" ]]; then
        echo "  FAILED  $name: expected $expected $expected_background, found $seen_state $seen_background"
        failed=$((failed + 1))
    else
        echo "  ok      $name"
    fi
done

# What a green run did not cover, in sight.
echo "$skipped case(s) skipped"
if ((failed > 0)); then
    echo "$failed of $played case(s) failed"
    exit 1
fi
echo "all $played case(s) passed"
