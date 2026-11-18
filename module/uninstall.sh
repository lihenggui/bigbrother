#!/system/bin/sh
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

# Runs when the module is removed: stop the daemon and delete its records.
MODDIR=${0%/*}
BBD=$MODDIR/bin/bbd

# Sandboxed apps are given their app ops, and the precise location taken
# from those with coarse location, back first; left as they are, the apps
# would be refused their data for good. The daemon does it where it is
# still running and gets every app out. Otherwise (most root managers run
# this script at the next boot, before anything of the module has started)
# the lines that give each app still sandboxed its ops and permissions back
# are read from the database, and the app ops helper is run with them by
# itself once the system is up, from a copy of its own.
released_all() {
    answer=$("$BBD" sandbox disable --all --json 2>/dev/null) || return 1
    case "$answer" in
    *'"failed":[]'*) return 0 ;;
    esac
    return 1
}
lines=
released_all || lines=$("$BBD" sandbox-restore-lines 2>/dev/null)
# Stopped before the helper runs: a daemon still holding apps must not hold
# them again behind its back.
. "$MODDIR/stop.sh"
if [ -n "$lines" ]; then
    helper=/data/adb/big_brother_release.apk
    if cp "$MODDIR/helper.apk" "$helper"; then
        (
            while [ "$(getprop sys.boot_completed)" != 1 ]; do
                sleep 5
            done
            echo "$lines" | CLASSPATH=$helper /system/bin/app_process /system/bin \
                li.mercury.bigbrother.catalog.AppOpsGuard >/dev/null 2>&1
            rm -f "$helper"
        ) &
    fi
fi
rm -rf /data/adb/big_brother
# What the sandbox shares with the hook in system_server. Without the list
# the hook, which stays for as long as the app does, sandboxes nothing.
rm -rf /data/system/big_brother_sandbox /data/system/big_brother_sandbox.status \
    /data/system/big_brother_sandbox.new /data/system/big_brother_hook
# The sandbox directories (in a hidden directory of /data/media/<user> with a
# name drawn at random) are kept: they hold what sandboxed apps saved.
