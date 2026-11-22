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

# Checks cold-start survival on a rooted, unlocked device with the module
# running and auto-isolation off. Installs both canaries afresh for user 0.
# Build them with (cd android && ./gradlew :canary:assembleDebug :canary:assembleNoDeclarations).
set -euo pipefail

PACKAGE=li.mercury.bigbrother.canary
NO_DECLARATIONS=$PACKAGE.nodeclarations
APK=android/canary/build/outputs/apk/debug/canary-debug.apk
NO_DECLARATIONS_APK=android/canary/build/outputs/apk/noDeclarations/canary-noDeclarations.apk
BBD=/data/adb/modules/big_brother/bin/bbd
TAG=BigBrotherSurvival
STARTS=10
OPS=(MANAGE_EXTERNAL_STORAGE GET_USAGE_STATS)
diagnostic_uid=""
baseline_modes=() baseline_raw=() expected_modes=() sampled_modes=() sampled_raw=()

fail() {
    local i
    printf '  FAILED  %s\n    expected: %s\n    actual:   %s\n' "$1" "$2" "$3" >&2
    if [[ -n "$diagnostic_uid" ]]; then
        if ((${#baseline_modes[@]} == 0 && ${#sampled_modes[@]} == 0)); then
            ADB_TIMEOUT=5 uid_modes "$diagnostic_uid" sampled_modes sampled_raw || true
        fi
        for i in "${!OPS[@]}"; do
            printf '    UID %s %s\n      expected: %s\n      before:   %s\n      actual:   %s\n' \
                "$diagnostic_uid" "${OPS[i]}" "${expected_modes[i]:-(unavailable)}" \
                "${baseline_modes[i]:-(not sampled)}" "${sampled_modes[i]:-${baseline_modes[i]:-(not sampled)}}" >&2
            if [[ -v baseline_raw[i] ]]; then
                printf '      raw before\n%s\n' "${baseline_raw[i]:-(empty output)}" >&2
            fi
            if [[ -v sampled_raw[i] ]]; then
                printf '      raw latest\n%s\n' "${sampled_raw[i]:-(empty output)}" >&2
            fi
        done
    else
        echo "    raw AppOps output unavailable before a canary UID is selected" >&2
    fi
    exit 1
}

adb() { timeout --kill-after=5 "${ADB_TIMEOUT:-30}" adb "$@"; }
as_root() {
    if $root_shell; then
        adb shell -T "$*"
    else
        adb shell -T su -c "'$*'"
    fi
}

cleanup() {
    if [[ -n ${watcher_input:-} ]]; then exec {watcher_input}>&- || true; fi
    if [[ -n ${sandbox_package:-} ]]; then
        as_root "$BBD sandbox disable $sandbox_package" >/dev/null 2>&1 ||
            fail "cleanup could not release the canary" "exit 0" "exit $?"
    fi
}

uid_of() {
    adb shell -T cmd package list packages --user 0 -U "$1" 2>/dev/null |
        awk -v package="package:$1" '$1 == package && $2 ~ /^uid:[0-9]+$/ { print substr($2, 5) }'
}

# AOSP AppOpsService prints a stored UID mode or the op's built-in default.
# Both tested ops use MODE_DEFAULT on API 33–36, so bare absence has the
# same meaning as the default line.
parse_uid_mode() {
    awk -v op="$1" '
        function known(mode) { return mode ~ /^(allow|ignore|deny|default|foreground)$/ }
        { sub(/^[[:space:]]+/, ""); sub(/[[:space:]]+$/, "") }
        $0 == "" { next }
        $0 == "No operations." { absent = 1; next }
        $1 == "Default" && $2 == "mode:" && NF == 3 && known($3) && default_mode == "" {
            default_mode = $3
            next
        }
        $1 == "Uid" && $2 == "mode:" && $3 == op ":" {
            sub(/;.*/, "", $4)
            if (known($4) && uid_mode == "") {
                uid_mode = $4
                next
            }
        }
        { invalid = 1 }
        END {
            if (invalid || (uid_mode != "" && (absent || default_mode != ""))) exit 1
            if (uid_mode != "") print uid_mode
            else if (default_mode != "") print default_mode
            else if (absent) print "default"
            else exit 1
        }
    '
}

uid_modes() {
    local uid="$1" op dump mode status result=0
    local -n parsed="$2" raw="$3"
    parsed=() raw=()
    # Retain the read that failed, since a repeat could see a reverted mode.
    for op in "${OPS[@]}"; do
        status=0
        dump="$(as_root "cmd appops get --uid $uid $op" 2>&1)" || status=$?
        if ((status != 0)); then
            mode="command exited $status"
            result=1
        elif ! mode="$(parse_uid_mode "$op" <<<"$dump")"; then
            mode="unrecognized output"
            result=1
        fi
        parsed+=("$mode")
        raw+=("$dump")
    done
    return "$result"
}

expect_uid_modes() {
    local phase="$1" i
    uid_modes "$diagnostic_uid" sampled_modes sampled_raw ||
        fail "$phase" "two readable UID modes" "AppOps readback failed"
    for i in "${!OPS[@]}"; do
        [[ "${sampled_modes[i]}" == "${expected_modes[i]}" ]] ||
            fail "$phase (${OPS[i]})" "${expected_modes[i]}" "${sampled_modes[i]}"
    done
}

# PID reuse must not let a replacement pass. The command name can contain
# spaces and parentheses, so count from the state field after the final ")".
process_identity() {
    as_root "pid=\$(pidof $1) && cat /proc/\"\$pid\"/stat" 2>/dev/null |
        awk '
            NR == 1 {
                pid = $1
                sub(/^.*\) /, "")
                if (pid ~ /^[1-9][0-9]*$/ && $1 != "Z" && $20 ~ /^[0-9]+$/) {
                    print pid, $20
                    found = 1
                }
            }
            END { if (!found || NR != 1) exit 1 }
        '
}

mark() {
    adb shell -T "log -t $TAG '$generation $*'" >/dev/null 2>&1 ||
        fail "could not write the $1 log marker" "exit 0" "exit $?"
}

check_log() {
    local fixture="$1" package="$2" uid="$3" generation="$4" starts="$5"
    awk -v label="$fixture" -v package="$package" -v uid="$uid" -v token="$generation" \
        -v starts="$starts" -v tag="$TAG" '
        function fail(why, expected, actual) {
            printf "  FAILED  %s: %s\n    expected: %s\n    actual:   %s\n", label, why, expected, actual > "/dev/stderr"
            failed = 1
        }
        /\// && /: / {
            log_tag = $0
            sub(/^[^\/]*\//, "", log_tag)
            sub(/ *:.*/, "", log_tag)
            sub(/^[^:]*: */, "")
            if (log_tag == tag && $1 == token && $2 == "begin") begun = 1
            if (!begun) next
            if (log_tag == tag && $1 == token) {
                if ($2 == "appop" && $3 == uid) {
                    if (settled || label != "declaring")
                        fail($4 " mode changes", "none", "observer reported a change")
                    else if (++changes[$4] != 1)
                        fail($4 " initial mode changes", 1, changes[$4])
                }
                if ($2 == "settled") settled = 1
                if ($2 == "start") {
                    if (!settled || launch || $3 != finished + 1)
                        fail("invalid launch marker sequence", "settled, then start " (finished + 1),
                            "settled=" (settled + 0) ", active=" (launch + 0) ", start " $3)
                    launch = $3
                    count = 0
                    started_pid = ""
                }
                if ($2 == "end") {
                    if (launch != $3 || count != 1 || started_pid != $4)
                        fail("launch " $3 " did not have one matching process start",
                            "one start for PID " $4, (count + 0) " starts, last PID " (started_pid ? started_pid : "missing"))
                    launch = 0
                    finished++
                }
                next
            }
            # The force-stop has a different reason, so policy kills can
            # be checked even between the individual launch windows.
            if (log_tag == "ActivityManager" && $1 == "Killing" && /changed\.$/) {
                split($2, process, /[:\/]/)
                if (process[2] == package && (process[3] == "u0a" (uid - 10000) ||
                    (process[3] == "" && process[1] == started_pid)))
                    fail("policy kill", "no policy kill", "kill for PID " process[1])
            }
            if (!launch) next
            if (log_tag == "am_proc_start" || log_tag == "am_kill" || log_tag == "am_proc_died") {
                sub(/^\[/, "")
                split($0, fields, ",")
                if (fields[1] != 0) next
                if (log_tag == "am_proc_start" && fields[3] == uid && fields[4] == package) {
                    count++
                    started_pid = fields[2]
                }
                if (log_tag != "am_proc_start" && fields[2] == started_pid && fields[3] == package)
                    fail("launch " launch " process survival", "PID " started_pid " alive", log_tag " for PID " fields[2])
            }
        }
        END {
            if (!settled || launch || finished != starts)
                fail("incomplete launch log", starts " completed launches after settle", (finished + 0) " completed")
            if (label == "declaring" &&
                (changes["manage_external_storage"] != 1 || changes["get_usage_stats"] != 1))
                fail("initial mode changes", "one per op",
                    (changes["manage_external_storage"] + 0) " all-files, " (changes["get_usage_stats"] + 0) " usage")
            exit failed
        }
    '
}

check_fixture() {
    local fixture="$1" package="$2" uid="$3"
    local generation="$$-$RANDOM-$RANDOM" ready result identity actual status launch_state i
    diagnostic_uid="$uid"
    baseline_modes=() baseline_raw=() expected_modes=() sampled_modes=() sampled_raw=()
    echo "$STARTS cold starts with the $fixture canary:"
    adb shell -T am force-stop --user 0 "$package" >/dev/null 2>&1 ||
        fail "could not stop the canary before isolation" "exit 0" "exit $?"

    # Start before applying the policy, since a brief mode change may be
    # gone by the first readback. stdin keeps the observer tied to this run.
    coproc WATCHER {
        ADB_TIMEOUT=360 as_root "CLASSPATH=$observer_apk app_process /system/bin $PACKAGE.SurvivalAppOps $uid $generation" 2>/dev/null
    }
    watcher_input="${WATCHER[1]:-}"
    # shellcheck disable=SC2153 # Bash assigns the PID of the named coprocess.
    local watcher_output="${WATCHER[0]:-}" watcher_pid="${WATCHER_PID:-}"
    [[ -n "$watcher_input" && -n "$watcher_output" && -n "$watcher_pid" ]] ||
        fail "could not launch the AppOps observer" "running observer with open pipes" "observer or pipe missing"
    read -r -t 20 -u "$watcher_output" ready && [[ "$ready" == ready ]] ||
        fail "the AppOps observer did not start" "ready" "no ready handshake"
    mark begin

    uid_modes "$uid" baseline_modes baseline_raw ||
        fail "could not read the initial UID modes" "two readable UID modes" "AppOps readback failed"
    expected_modes=("${baseline_modes[@]}")
    if [[ "$fixture" == declaring ]]; then
        for i in "${!OPS[@]}"; do
            [[ "${baseline_modes[i]}" != ignore ]] ||
                fail "${OPS[i]} is already restricted" "an initial mode other than ignore" "${baseline_modes[i]}"
        done
        expected_modes=(ignore ignore)
    fi

    sandbox_package="$package"
    as_root "$BBD sandbox enable $package" >/dev/null 2>&1 ||
        fail "could not isolate the canary" "exit 0" "exit $?"
    as_root "$BBD sandbox set $package storage=on installed_apps=on" >/dev/null 2>&1 ||
        fail "could not set the sandbox items" "exit 0" "exit $?"
    sleep 1
    expect_uid_modes "UID modes after isolation"
    mark settled

    for ((i = 1; i <= STARTS; i++)); do
        clear_screen
        adb shell -T am force-stop --user 0 "$package" >/dev/null 2>&1 ||
            fail "could not stop the canary before launch $i" "exit 0" "exit $?"
        # The script's force-stop precedes the window for this generation.
        mark start "$i"
        result="$(adb shell -T am start -W --user 0 -n "$package/$PACKAGE.MainActivity" --ez keepalive true 2>/dev/null)" ||
            fail "could not launch the canary ($i)" "exit 0" "exit $?"
        status="$(sed -nE 's/^Status: (ok|timeout)$/\1/p' <<<"$result")"
        launch_state="$(sed -nE 's/^LaunchState: (COLD|WARM|HOT|UNKNOWN)$/\1/p' <<<"$result")"
        [[ "$status" == ok && "$launch_state" == COLD ]] ||
            fail "launch $i first frame" "ok, COLD" "${status:-unknown status}, ${launch_state:-unknown launch state}"
        identity="$(process_identity "$package")" ||
            fail "launch $i first process sample" "one live PID and start time" "no readable live process"
        expect_uid_modes "launch $i UID modes at first frame"
        sleep 1
        expect_uid_modes "launch $i UID modes after helper settle"
        actual="$(process_identity "$package")" && [[ "$actual" == "$identity" ]] ||
            fail "launch $i process after helper settle (PID and start time)" "$identity" "${actual:-no readable live process}"
        sleep 5
        expect_uid_modes "launch $i UID modes after the quiet period"
        actual="$(process_identity "$package")" && [[ "$actual" == "$identity" ]] ||
            fail "launch $i process after the quiet period (PID and start time)" "$identity" "${actual:-no readable live process}"
        mark end "$i" "${identity%% *}"
    done

    printf '\n' >&"$watcher_input" ||
        fail "the AppOps observer disconnected" "open observer input" "write failed"
    read -r -t 10 -u "$watcher_output" result && [[ "$result" == stopped ]] ||
        fail "the AppOps observer did not stay running" "stopped" "no stopped handshake"
    wait "$watcher_pid" || fail "the AppOps observer failed" "exit 0" "exit $?"
    watcher_input=""
    adb logcat -b main -b system -b events -d -v tag -s "$TAG:I" ActivityManager:I \
        am_proc_start:I am_kill:I am_proc_died:I 2>/dev/null |
        check_log "$fixture" "$package" "$uid" "$generation" "$STARTS" ||
        fail "could not verify the launch log" "exit 0" "exit $?"
    as_root "$BBD sandbox disable $package" >/dev/null 2>&1 ||
        fail "could not release the canary" "exit 0" "exit $?"
    sandbox_package=""
    diagnostic_uid=""
    echo "  ok      every process survived and UID modes stayed as expected"
}

main() {
    cd "$(dirname "$0")/.."
    # shellcheck source=scripts/canary-runner.sh
    source scripts/canary-runner.sh
    [[ -f "$APK" && -f "$NO_DECLARATIONS_APK" ]] ||
        fail "build both canary APKs first" "both APKs present" "one or both missing"
    local shell_uid declaring_uid nondeclaring_uid
    shell_uid="$(adb shell -T id -u 2>/dev/null)" ||
        fail "could not connect to the device" "exit 0" "exit $?"
    root_shell=false
    [[ "$shell_uid" == 0 ]] && root_shell=true
    trap cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM

    adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
    adb uninstall "$NO_DECLARATIONS" >/dev/null 2>&1 || true
    adb install --user 0 "$APK" >/dev/null 2>&1 ||
        fail "could not install the declaring canary" "exit 0" "exit $?"
    adb install --user 0 "$NO_DECLARATIONS_APK" >/dev/null 2>&1 ||
        fail "could not install the nondeclaring canary" "exit 0" "exit $?"
    declaring_uid="$(uid_of "$PACKAGE")" ||
        fail "could not read the declaring canary UID" "exit 0" "exit $?"
    nondeclaring_uid="$(uid_of "$NO_DECLARATIONS")" ||
        fail "could not read the nondeclaring canary UID" "exit 0" "exit $?"
    [[ "$declaring_uid" =~ ^[0-9]+$ && "$nondeclaring_uid" =~ ^[0-9]+$ && "$declaring_uid" != "$nondeclaring_uid" ]] ||
        fail "could not resolve the canary UIDs" "two distinct numeric UIDs" \
            "declaring=${declaring_uid:-missing}, nondeclaring=${nondeclaring_uid:-missing}"
    diagnostic_uid="$declaring_uid"
    observer_apk="$(adb shell -T pm path --user 0 "$PACKAGE" 2>/dev/null | sed -n 's/^package:\(.*\/base\.apk\)$/\1/p')" ||
        fail "could not locate the AppOps observer" "exit 0" "exit $?"
    [[ "$observer_apk" =~ ^/data/app/[a-zA-Z0-9_+~./=-]+/base\.apk$ ]] ||
        fail "could not locate the AppOps observer" "one installed base APK" "missing or invalid APK reference"

    check_fixture nondeclaring "$NO_DECLARATIONS" "$nondeclaring_uid"
    check_fixture declaring "$PACKAGE" "$declaring_uid"
    echo "all checks passed"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main; fi
