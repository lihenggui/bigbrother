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
# Sets canary_log on success and canary_error on failure.
launch_and_wait_for_canary() {
    local limit="$1" launch i status status_file=files/probe-status
    shift
    canary_log="" canary_error=""
    clear_screen
    if ! adb shell run-as "$PACKAGE" rm -f "$status_file"; then
        canary_error="could not clear the previous canary status"
        return 1
    fi
    if ! launch="$(adb shell am start -W -n "$PACKAGE/.MainActivity" "$@" 2>&1)" ||
        grep -qE '^(Error(:| type )|Exception occurred while executing)' <<<"$launch"; then
        canary_error="starting the canary failed"
        printf '%s\n' "$launch" >&2
    else
        for ((i = 0; i < limit; i++)); do
            if status="$(adb shell run-as "$PACKAGE" cat "$status_file" 2>/dev/null)"; then
                case "$status" in
                done)
                    canary_log="$(adb logcat -d -s BigBrotherCanary:I | tr -d '\r')" && return 0
                    canary_error="could not read the canary observations"
                    ;;
                "probe failed: "*) canary_error="${status#probe failed: }" ;;
                *) canary_error="invalid canary status" ;;
                esac
                break
            fi
            if ((i % 5 == 4)) && focus_elsewhere; then clear_screen; fi
            sleep 1
        done
        canary_error="${canary_error:-the canary did not report completion}"
    fi
    save_canary_failure "$launch"
    return 1
}
