#!/system/bin/sh
# Starts the daemon under its supervisor. Runs at late_start, and again after
# a hot install, in which case a previous instance is stopped first.
MODDIR=${0%/*}
DATA=/data/adb/big_brother
LOG=$DATA/bbd.log

mkdir -p "$DATA"
. "$MODDIR/stop.sh"

# Boot guard. A marker is left while the system boots and removed by
# boot-completed.sh. Finding it here means the previous boot started the
# daemon and never finished, so the module disables itself rather than risk
# the same again. Not applied when started after boot (a hot install).
BOOT_MARKER=$DATA/boot_pending
if [ "$(getprop sys.boot_completed)" != 1 ]; then
    if [ -f "$BOOT_MARKER" ]; then
        echo "--- $(date '+%F %T') previous boot did not complete; module disabled" >>"$LOG"
        rm -f "$BOOT_MARKER"
        touch "$MODDIR/disable"
        exit 0
    fi
    touch "$BOOT_MARKER"
    # The boot may have completed between the check and the touch, in which
    # case boot-completed.sh has already run and nothing would remove the marker.
    [ "$(getprop sys.boot_completed)" = 1 ] && rm -f "$BOOT_MARKER"
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
