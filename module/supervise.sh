#!/system/bin/sh
# Keeps the daemon running: restarts it if it dies, and gives up if it keeps
# dying right after starting (a crash loop would only drain the battery).
MODDIR=${0%/*}
DATA=/data/adb/big_brother
LOG=$DATA/bbd.log

# A run shorter than this many seconds counts as a failed start.
MIN_UPTIME=30
MAX_FAILED_STARTS=5

log() {
    echo "--- $(date '+%F %T') $*" >>"$LOG"
}

# Seconds since boot. Unlike the wall clock, this does not jump when the
# time is set during boot.
uptime_seconds() {
    cut -d. -f1 /proc/uptime
}

pid=
# Asked to stop: take the daemon down too, and wait so that it can flush.
trap 'kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null; rm -f "$DATA/bbd.pid"; exit 0' TERM INT

failed=0
while :; do
    started=$(uptime_seconds)
    "$MODDIR/bin/bbd" run >>"$LOG" 2>&1 &
    pid=$!
    echo "$pid" >"$DATA/bbd.pid"
    wait "$pid"
    code=$?
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
    log "daemon exited with status $code after ${ran}s; restarting"
    sleep 5
done

rm -f "$DATA/supervisor.pid" "$DATA/bbd.pid"
