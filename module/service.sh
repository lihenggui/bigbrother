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

# Starts the daemon under its supervisor. Runs at late_start, and again after
# a hot install, in which case a previous instance is stopped first.
MODDIR=${0%/*}
DATA=/data/adb/big_brother
LOG=$DATA/bbd.log

# Root managers and adb shells can supply different inherited umasks.
umask 077
mkdir -p "$DATA" && chmod 700 "$DATA" || exit 1
. "$MODDIR/stop.sh"

# Boot guard. A marker is left while the system boots and removed once it has
# finished. Finding it here means the previous boot started the daemon and
# never finished, so the module disables itself rather than risk the same
# again. Not applied when started after boot (a hot install).
BOOT_MARKER=$DATA/boot_pending
[ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
if [ "$(getprop sys.boot_completed)" != 1 ]; then
    if [ -f "$BOOT_MARKER" ]; then
        echo "--- $(date '+%F %T') previous boot did not complete; module disabled" >>"$LOG"
        touch "$MODDIR/disable" || exit 1
        sh "$MODDIR/uninstall.sh" --restore-disabled
        exit 0
    fi
    touch "$BOOT_MARKER"
    # Waited for here rather than in a boot-completed.sh, which Magisk does
    # not run. A boot that hangs takes this process down with it, and the
    # marker stays.
    (
        while [ "$(getprop sys.boot_completed)" != 1 ]; do
            sleep 2
        done
        rm -f "$BOOT_MARKER"
    ) &
fi

# Finish saved restores from an earlier removal before enabling fresh guards.
# Run after boot, when app_process can reach the package and app-op services.
set -- /data/adb/big_brother_recovery/pending.*
if [ -d "$1" ]; then
    (if sh "$MODDIR/uninstall.sh" --recover-after-boot; then
         sh "$MODDIR/service.sh"
     else
         echo "sandbox recovery still pending" >>"$LOG"
     fi) </dev/null >/dev/null 2>&1 &
    exit 0
fi

# Start a new log once the old one passes 1 MiB. Checked at each start only.
if [ -f "$LOG" ] && [ "$(stat -c %s "$LOG")" -gt 1048576 ]; then
    mv -f "$LOG" "$LOG.old"
fi

echo "--- $(date '+%F %T') starting" >>"$LOG"
sh "$MODDIR/supervise.sh" &
# Written here rather than by the supervisor, so that a stop request arriving
# right away already finds it.
echo $! >"$DATA/supervisor.pid"
