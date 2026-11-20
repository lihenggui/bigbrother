#!/usr/bin/env bash
#
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
    focus="$(adb shell dumpsys window displays 2>/dev/null | tr -d '\r' | sed -n 's/.*mCurrentFocus=//p')"
    [[ -n "$focus" && "$focus" != *"$PACKAGE"* ]]
}

# Capture the failing launch before the next case clears logcat.
save_canary_failure() {
    [[ -n "${LOGS:-}" ]] || return 0
    {
        printf '\n%s\n%s\n' "$canary_error" "$1"
        adb shell dumpsys -t 5 window displays | sed -n '/mCurrentFocus=/p; /mFocusedApp=/p'
        adb shell pidof "$PACKAGE"
        adb logcat -d -s BigBrotherCanary:I ActivityTaskManager:I ActivityManager:I AndroidRuntime:E
    } >>"$LOGS/canary-failures.txt" 2>&1 || true
}

# Callers force-stop the previous run before its completion file is cleared.
# The picker also uses this to discard any result before opening the UI.
start_canary() {
    canary_view="" canary_error=""
    clear_screen
    if ! adb shell run-as "$PACKAGE" rm -f files/probe-result; then
        canary_error="could not clear the previous canary result"
        return 1
    fi
    if ! canary_launch="$(adb shell am start -W -n "$PACKAGE/.MainActivity" "$@" 2>&1)" ||
        grep -qE '^(Error(:| type )|Exception occurred while executing)' <<<"$canary_launch"; then
        canary_error="starting the canary failed"
        printf '%s\n' "$canary_launch" >&2
        save_canary_failure "$canary_launch"
        return 1
    fi
}

# The first line is the outcome. The sandbox and permission checks read
# their observations from the same file so a partial report cannot pass.
wait_for_canary_result() {
    local limit="$1" i result status
    for ((i = 0; i < limit; i++)); do
        if result="$(adb shell -T run-as "$PACKAGE" cat files/probe-result 2>/dev/null)"; then
            status="${result%%$'\n'*}"
            case "$status" in
            done)
                if [[ "$result" == *$'\n'* ]]; then canary_view="${result#*$'\n'}"; fi
                return 0
                ;;
            "probe failed: "*) canary_error="${status#probe failed: }" ;;
            *) canary_error="invalid canary result" ;;
            esac
            break
        fi
        if ((i % 5 == 4)) && focus_elsewhere; then clear_screen; fi
        sleep 1
    done
    canary_error="${canary_error:-the canary did not report completion}"
    save_canary_failure "$canary_launch"
    return 1
}

launch_and_wait_for_canary() {
    local limit="$1"
    shift
    start_canary "$@" && wait_for_canary_result "$limit"
}
