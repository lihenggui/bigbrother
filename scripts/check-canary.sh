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
# the record each probe should leave. Keep the two in step.
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
    "binder:android.content.IClipboard/getPrimaryClip"
    "setting:secure/android_id"
    "binder:android.location.ILocationManager/"
    "binder:android.net.wifi.IWifiManager/getConnectionInfo"
    "binder:android.accounts.IAccountManager/"
    "binder:android.app.IActivityManager/getRunningAppProcesses"
    "binder:android.gui.SensorServer/createSensorEventConnection"
)
# Recorded only with the LSPosed hook enabled for these providers, and only
# if the system let the query through (it has to be granted the permission).
OPTIONAL=(
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
adb shell am start -W -n "$PACKAGE/.MainActivity" >/dev/null
# The probes take a few seconds (two connection timeouts); the daemon drains
# the kernel's buffer every second and writes every five.
sleep 20
records="$(as_root "$BBD files --uid $uid --hours 1 --limit 2000")"

missing=0
check() {
    local want="$1" required="$2"
    if grep -qF -- "$want" <<<"$records"; then
        echo "  recorded  $want"
    elif [[ "$required" == yes ]]; then
        echo "  MISSING   $want"
        missing=$((missing + 1))
    else
        echo "  absent    $want (optional)"
    fi
}

echo "records for uid $uid:"
for want in "${REQUIRED[@]}"; do check "$want" yes; done
for want in "${OPTIONAL[@]}"; do check "$want" no; done

if ((missing > 0)); then
    echo "$missing required record(s) missing"
    exit 1
fi
echo "all required records present"
