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

# End-to-end check on a device: installs the canary app, lets it do one of
# everything Big Brother is meant to notice, and compares the daemon's
# records with what was done.
#
# Requires adb, a connected device with the module installed and running,
# and the canary built: (cd android && ./gradlew :canary:assembleDebug)
#
# Exits non-zero if a required record is missing.
set -euo pipefail

cd "$(dirname "$0")/.."

PACKAGE=li.mercury.bigbrother.canary
APK=android/canary/build/outputs/apk/debug/canary-debug.apk
BBD=/data/adb/modules/big_brother/bin/bbd

# What the canary does (android/canary/.../MainActivity.kt), as a piece of
# the record each probe should leave; a trailing quote matches the end of
# the record. Keep the two in step.
REQUIRED=(
    "/proc/cpuinfo"
    # Recorded under the device's real sysfs path if the read is allowed.
    "net/wlan0/address"
    "/storage/emulated/0/Download"
    "exec:getprop ro.serialno"
    "exec:su"
    "net://127.0.0.1:5555"
    "net://192.168.255.254:9"
    "net://239.255.255.250:1900"
    "netlink:route/"
    "binder:android.content.pm.IPackageManager/getInstalledPackages"
    "binder:android.content.pm.IPackageManager/getPackageInfo#com.android.vending"
    "binder:android.content.IClipboard/addPrimaryClipChangedListener"
    "binder:android.content.IClipboard/setPrimaryClip\""
    "binder:android.content.IClipboard/getPrimaryClip"
    "setting:secure/android_id"
    "binder:android.location.ILocationManager/"
    "binder:android.net.wifi.IWifiManager/getConnectionInfo"
    "binder:android.accounts.IAccountManager/"
    "binder:android.app.IActivityManager/getRunningAppProcesses"
    "binder:android.gui.SensorServer/createSensorEventConnection"
    "binder:android.gui.SensorEventConnection/enableDisable@android.sensor.accelerometer\""
    "binder:android.gui.SensorEventConnection/enableDisable@android.sensor.gyroscope:fast\""
)
# The sensors the canary turns on, whether at a high rate, and how long it
# keeps them on (SENSORS_ON_MS): each should leave a session that long.
SENSOR_SESSIONS=(
    "android.sensor.accelerometer false"
    "android.sensor.gyroscope true"
)
SENSORS_ON_S=10
# Recorded only with the LSPosed hook enabled for these providers, and only
# if the system let the query through (it has to be granted the permission).
OPTIONAL=(
    # The system's own records, read every 30 s.
    "appop:android:read_clipboard"
    "appop:android:write_clipboard"
    "content://com.android.contacts/"
    "content://com.android.calendar/"
    "content://sms"
)

# Run a command as root on the device: directly where adb itself is root
# (an emulator after `adb root`), through su otherwise.
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

adb install -r "$APK" >/dev/null
for permission in READ_CONTACTS READ_CALENDAR READ_SMS; do
    adb shell pm grant "$PACKAGE" "android.permission.$permission" 2>/dev/null || true
done
uid="$(adb shell cmd package list packages -U "$PACKAGE" | tr -d '\r' | sed -n 's/.*uid:\([0-9]*\).*/\1/p')"
[[ -n "$uid" ]] || {
    echo "error: could not find the canary's uid" >&2
    exit 2
}

echo "coverage reported by the daemon:"
as_root "$BBD stats" | tr ',' '\n' | grep -o '"coverage_[a-z]*":[01]' | sed 's/^/  /' || true

adb shell am force-stop "$PACKAGE"
adb logcat -c
adb shell am start -W -n "$PACKAGE/.MainActivity" >/dev/null
# The probes take about 15 s: two connection timeouts and the sensors. They
# start once the canary's window has focus, which needs the screen on.
for ((i = 0; i < 60; i++)); do
    grep -q "BigBrotherCanary: done" <<<"$(adb logcat -d -s BigBrotherCanary:I)" && break
    sleep 1
done
((i < 60)) || {
    echo "error: the canary did not finish; is the screen on and unlocked?" >&2
    exit 2
}
# The queries have the daemon write what it holds before they read.
records="$(as_root "$BBD files --uid $uid --hours 1 --limit 2000")"
sessions="$(as_root "$BBD sensors --uid $uid --hours 1" || true)"

missing=0
check() {
    local want="$1" required="$2"
    if grep -qF -- "$want" <<<"$records"; then
        echo "  recorded  ${want%\"}"
    elif [[ "$required" == yes ]]; then
        echo "  MISSING   ${want%\"}"
        missing=$((missing + 1))
    else
        echo "  absent    ${want%\"} (optional)"
    fi
}

# The latest session of a sensor: ended, at the rate expected, and about as
# long as the canary kept it on (times are to the second).
check_session() {
    local sensor="$1" high="$2" session start end
    # One session per line; the most recent comes first.
    session="$(grep -F "\"sensor\":\"$sensor\"" <<<"${sessions//'},{'/$'}\n{'}" | head -n 1 || true)"
    start="$(sed -n 's/.*"start_ts":\([0-9]*\).*/\1/p' <<<"$session")"
    end="$(sed -n 's/.*"end_ts":\([0-9]*\).*/\1/p' <<<"$session")"
    if [[ -z "$session" ]]; then
        echo "  MISSING   session of $sensor"
    elif [[ -z "$end" ]]; then
        echo "  OPEN      session of $sensor: not seen turned off"
    elif ! grep -qF "\"high_rate\":$high" <<<"$session"; then
        echo "  WRONG     session of $sensor: high rate should be $high"
    elif ((end - start < SENSORS_ON_S - 1 || end - start > SENSORS_ON_S + 2)); then
        echo "  WRONG     session of $sensor: on for $((end - start)) s, not $SENSORS_ON_S"
    else
        echo "  recorded  session of $sensor, $((end - start)) s, high rate $high"
        return
    fi
    missing=$((missing + 1))
}

echo "records for uid $uid:"
for want in "${REQUIRED[@]}"; do check "$want" yes; done
for want in "${OPTIONAL[@]}"; do check "$want" no; done
echo "sensor sessions:"
for expected in "${SENSOR_SESSIONS[@]}"; do
    read -r sensor high <<<"$expected"
    check_session "$sensor" "$high"
done

if ((missing > 0)); then
    echo "$missing required record(s) missing"
    exit 1
fi
echo "all required records present"
