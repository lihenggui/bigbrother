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

# Installs the module and the app on an emulator that has no root manager,
# then starts the daemon, so that check-canary.sh and check-sandbox.sh can
# run there.
#
#   scripts/install-on-emulator.sh [out/big_brother-<version>.apk.zip]
#
# Needs an image on which `adb root` works (google_apis, not
# google_apis_playstore). The module's own module/direct.sh does the
# installing, as it does when the app installs the module with root alone.
# Nothing is changed in the system image, and the module is gone again with
# the emulator's data.
set -euo pipefail

cd "$(dirname "$0")/.."

BBD=/data/adb/big_brother_direct/bin/bbd
version="$(sed -n 's/^version=//p' module/module.prop)"
zip="${1:-out/big_brother-$version.apk.zip}"
[[ -f "$zip" ]] || {
    echo "error: $zip not found; build it with ./scripts/build.sh" >&2
    exit 2
}

# adbd restarts to become root, and a request made while it does so is
# refused: asked again a few times.
for ((i = 0; i < 5; i++)); do
    adb wait-for-device
    [[ "$(adb shell id -u | tr -d '\r')" == 0 ]] && break
    adb root >/dev/null 2>&1 || true
    sleep 2
done
adb wait-for-device
[[ "$(adb shell id -u | tr -d '\r')" == 0 ]] || {
    echo "error: adb cannot run as root here; use a google_apis emulator image" >&2
    exit 2
}
# adbd restarts as root; the package manager is needed before installing.
for ((i = 0; i < 120; i++)); do
    [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] && break
    sleep 1
done
((i < 120)) || {
    echo "error: the emulator did not finish booting" >&2
    exit 2
}

stage=$(adb shell 'umask 077; mkdir -p /data/adb && mktemp -d /data/adb/.bb-emulator.XXXXXX' | tr -d '\r')
[[ $stage =~ ^/data/adb/\.bb-emulator\.[[:alnum:]]+$ ]] || exit 2
trap 'adb shell "rm -rf $stage" >/dev/null' EXIT
DEVICE_ZIP=$stage/module.zip
DEVICE_SCRIPT=$stage/direct.sh
adb push "$zip" "$DEVICE_ZIP" >/dev/null
adb shell "unzip -p $DEVICE_ZIP direct.sh >$DEVICE_SCRIPT && sh $DEVICE_SCRIPT install $DEVICE_ZIP" || {
    echo "error: the module installer failed" >&2
    exit 1
}

# The daemon counts as running once its probes are attached.
for ((i = 0; i < 60; i++)); do
    stats="$(adb shell "$BBD stats" 2>/dev/null | tr -d '\r' || true)"
    grep -q '"daemon_running":true' <<<"$stats" && break
    sleep 1
done
((i < 60)) || {
    echo "error: the daemon is not running; its log:" >&2
    adb shell "tail -n 50 /data/adb/big_brother/bbd.log" >&2 || true
    exit 1
}
echo "daemon running: $stats"
