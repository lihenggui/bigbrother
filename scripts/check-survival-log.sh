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

# Replay the scoped AppOps readbacks and logcat records on the host.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/check-survival.sh
source scripts/check-survival.sh

package=li.mercury.bigbrother.canary.nodeclarations
uid=10123
token=replay
begin="I/$TAG: $token begin"
settled="I/$TAG: $token settled"
start="I/$TAG: $token start 1"
end="I/$TAG: $token end 1 321"
proc_start="I/am_proc_start: [0,321,$uid,$package,top-activity,fixture]"
kill="I/ActivityManager: Killing 321:$package/u0a123 (adj 0): MANAGE_EXTERNAL_STORAGE changed."
change="I/$TAG: $token appop $uid manage_external_storage"
usage="I/$TAG: $token appop $uid get_usage_stats"

accept() {
    local what="$1" fixture="$2"
    shift 2
    printf '%s\n' "$@" | check_log "$fixture" "$package" "$uid" "$token" 1
    echo "  ok      $what"
}

reject() {
    local what="$1" fixture="$2"
    shift 2
    if printf '%s\n' "$@" | check_log "$fixture" "$package" "$uid" "$token" 1 2>/dev/null; then
        fail "$what" "rejected log" "accepted log"
    fi
    echo "  ok      rejected $what"
}

# AppOpsService.onShellCommand prints the same forms on API 33–36.
mode_is() {
    local op="$1" expected="$2" dump="$3" actual
    actual="$(parse_uid_mode "$op" <<<"$dump")" ||
        fail "$op parser" "$expected" "parse error"
    [[ "$actual" == "$expected" ]] || fail "$op parser" "$expected" "$actual"
}

no_operations=$'No operations.\nDefault mode: default'
mode_is MANAGE_EXTERNAL_STORAGE default "$no_operations"
mode_is GET_USAGE_STATS default "$no_operations"
mode_is GET_USAGE_STATS default 'No operations.'
mode_is GET_USAGE_STATS default $'No operations.\r\nDefault mode: default\r'
mode_is GET_USAGE_STATS default 'Uid mode: GET_USAGE_STATS: default'
mode_is GET_USAGE_STATS ignore $'No operations.\nDefault mode: ignore'
mode_is MANAGE_EXTERNAL_STORAGE ignore 'Uid mode: MANAGE_EXTERNAL_STORAGE: ignore'
mode_is GET_USAGE_STATS allow 'Uid mode: GET_USAGE_STATS: allow'
mode_is GET_USAGE_STATS deny 'Uid mode: GET_USAGE_STATS: deny; rejectTime=+1s ago'
mode_is GET_USAGE_STATS foreground 'Uid mode: GET_USAGE_STATS: foreground; time=+1s ago (running)'
mode_is GET_USAGE_STATS allow 'Uid mode: GET_USAGE_STATS: allow; time=+2s ago; duration=+1s'
mode_is GET_USAGE_STATS ignore $'Uid mode: GET_USAGE_STATS: ignore\r'
for invalid in '' 'Error: Unknown operation' 'Uid mode: GET_USAGE_STATS: unknown' 'GET_USAGE_STATS: allow'; do
    if parse_uid_mode GET_USAGE_STATS <<<"$invalid" >/dev/null; then
        fail "invalid AppOps readback" "parse error" "accepted output"
    fi
done
echo "  ok      AppOps shell forms and validation"

# A system-set UID mode can survive isolation. A different representation
# of the same default and changing access timestamps carry no mode change.
as_root() {
    case "$1" in
        "cmd appops get --uid $uid MANAGE_EXTERNAL_STORAGE")
            if [[ "$sample" == before ]]; then printf '%s\n' "$no_operations"
            else echo 'Uid mode: MANAGE_EXTERNAL_STORAGE: default'
            fi
            ;;
        "cmd appops get --uid $uid GET_USAGE_STATS")
            case "$sample" in
                before) echo 'Uid mode: GET_USAGE_STATS: allow; time=+1s ago' ;;
                after) echo 'Uid mode: GET_USAGE_STATS: allow; time=+2s ago' ;;
                changed) echo 'Uid mode: GET_USAGE_STATS: ignore' ;;
                malformed) echo 'Uid mode: GET_USAGE_STATS: unknown' ;;
                failed) echo 'Error: AppOps service unavailable' >&2; return 20 ;;
            esac
            ;;
        *) return 1 ;;
    esac
}

diagnostic_uid="$uid"
sample=before
uid_modes "$uid" baseline_modes baseline_raw
expected_modes=("${baseline_modes[@]}")
sample=after
expect_uid_modes "equivalent modes after isolation"
echo "  ok      unchanged system-set mode and equivalent default"

expect_failed_sample() {
    local sample="$1" actual="$2" dump="$3" report
    if report="$(expect_uid_modes "UID modes after isolation" 2>&1)"; then
        fail "$sample readback" "failed check" "passed check"
    fi
    if [[ "$report" != *"expected: allow"* || "$report" != *"actual:   $actual"* ||
        "$report" != *"raw before"$'\n'"Uid mode: GET_USAGE_STATS: allow; time=+1s ago"* ||
        "$report" != *"raw latest"$'\n'"$dump"* ]]; then
        fail "$sample readback diagnostics" "expected and actual mode with both raw samples" "missing evidence"
    fi
    echo "  ok      $sample readback with failure diagnostics"
}

expect_failed_sample changed ignore 'Uid mode: GET_USAGE_STATS: ignore'
expect_failed_sample malformed 'unrecognized output' 'Uid mode: GET_USAGE_STATS: unknown'
expect_failed_sample failed 'command exited 20' 'Error: AppOps service unavailable'
diagnostic_uid=""

accept "scoped logs" nondeclaring "$kill" "$change" "$begin" "$settled" \
    "I/am_kill: [0,320,$package,0,stop due to from pid 9]" "$start" "$proc_start" \
    "I/am_proc_died: [0,320,$package,0,2]" "I/$TAG: $token appop 10124 get_usage_stats" \
    "I/ActivityManager: Killing 999:$package/u1a123 (adj 0): MANAGE_EXTERNAL_STORAGE changed." "$end"
accept "one initial transition per op" declaring "$begin" "$change" "$usage" "$settled" "$start" "$proc_start" "$end"
reject "a transient all-files change" nondeclaring "$begin" "$change" "$settled" "$start" "$proc_start" "$end"
reject "a transient usage change" nondeclaring "$begin" "$settled" "$start" "$proc_start" "$usage" "$end"
reject "a repeated policy transition" declaring "$begin" "$change" "$usage" "$settled" "$start" "$proc_start" "$change" "$end"
reject "a late first policy transition" declaring "$begin" "$usage" "$settled" "$start" "$proc_start" "$change" "$end"
reject "an unobserved expected transition" declaring "$begin" "$change" "$settled" "$start" "$proc_start" "$end"
reject "an AppOps kill" nondeclaring "$begin" "$settled" "$start" "$proc_start" "$kill" "$end"
reject "a policy kill between launch windows" nondeclaring "$begin" "$settled" "$start" "$proc_start" "$end" "$kill"
reject "an unexpected death" nondeclaring "$begin" "$settled" "$start" "$proc_start" "I/am_proc_died: [0,321,$package,0,2]" "$end"
reject "a process replacement" nondeclaring "$begin" "$settled" "$start" "$proc_start" "${proc_start/321/322}" "${end/321/322}"
reject "a missing start marker" nondeclaring "$begin" "$settled" "$proc_start" "$end"
