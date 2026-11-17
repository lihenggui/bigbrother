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

# Keeps the daemon running: restarts it if it dies, and gives up if it keeps
# dying right after starting (a crash loop would only drain the battery).
MODDIR=${0%/*}
DATA=/data/adb/big_brother
LOG=$DATA/bbd.log

# A run shorter than this many seconds counts as a failed start.
MIN_UPTIME=30
MAX_FAILED_STARTS=5
# Seconds before a daemon that died is started again. The first time, at
# once: while there is no daemon, apps in the sandbox start without it. Then
# FIRST_DELAY, twice as long after each further restart, up to MAX_DELAY;
# and at once again once a run has lasted HEALTHY_UPTIME.
FIRST_DELAY=5
MAX_DELAY=600
HEALTHY_UPTIME=600

log() {
    echo "--- $(date '+%F %T') $*" >>"$LOG"
}

# Seconds since boot. Unlike the wall clock, this does not jump when the
# time is set during boot.
uptime_seconds() {
    cut -d. -f1 /proc/uptime
}

# The daemon, and the programs it starts, yield the CPU and the disk to the
# apps, where the system has the commands for it.
priority=
nice -n 10 true 2>/dev/null && priority="nice -n 10"
ionice -c 2 -n 7 true 2>/dev/null && priority="$priority ionice -c 2 -n 7"

pid=
sleeping=
# Asked to stop: take the daemon down too, and wait so that it can flush.
stop() {
    if [ -n "$pid" ]; then
        kill "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
    fi
    [ -n "$sleeping" ] && kill "$sleeping" 2>/dev/null
    rm -f "$DATA/bbd.pid"
    exit 0
}
trap stop TERM INT

failed=0
delay=0
while :; do
    started=$(uptime_seconds)
    # Left by a daemon that crashed: the new one creates its own once its
    # probes are attached, which is what tells the app it is running.
    rm -f "$DATA/bbd.sock" "$DATA/bbd.ctl"
    # Unquoted: a command with its arguments, or nothing.
    $priority "$MODDIR/bin/bbd" run >>"$LOG" 2>&1 &
    pid=$!
    echo "$pid" >"$DATA/bbd.pid"
    wait "$pid"
    code=$?
    # Gone: its pid may be handed to another process.
    pid=
    ran=$(($(uptime_seconds) - started))

    # The daemon exits with status 0 only when it is asked to stop.
    if [ "$code" -eq 0 ]; then
        log "daemon exited normally after ${ran}s; not restarting"
        break
    fi

    if [ "$ran" -lt "$MIN_UPTIME" ]; then
        failed=$((failed + 1))
    else
        failed=0
    fi
    if [ "$failed" -ge "$MAX_FAILED_STARTS" ]; then
        log "daemon exited with status $code after ${ran}s; giving up after $failed failed starts"
        break
    fi
    [ "$ran" -ge "$HEALTHY_UPTIME" ] && delay=0
    log "daemon exited with status $code after ${ran}s; restarting in ${delay}s"
    if [ "$delay" -gt 0 ]; then
        # In the background, so that a request to stop is acted on at once.
        sleep "$delay" &
        sleeping=$!
        wait "$sleeping"
        sleeping=
        delay=$((delay * 2))
        [ "$delay" -gt "$MAX_DELAY" ] && delay=$MAX_DELAY
    else
        delay=$FIRST_DELAY
    fi
done

rm -f "$DATA/supervisor.pid" "$DATA/bbd.pid"
