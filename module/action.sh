#!/system/bin/sh
# Action button in the KernelSU manager: show daemon status.
MODDIR=${0%/*}
DATA=/data/adb/big_brother

pid="$(cat "$DATA/bbd.pid" 2>/dev/null)"
if [ -n "$pid" ] && [ "$(cat /proc/"$pid"/comm 2>/dev/null)" = bbd ]; then
    echo "Daemon: running (pid $pid)"
else
    echo "Daemon: NOT running"
fi
echo
echo "Counters:"
"$MODDIR/bin/bbd" stats 2>&1
echo
echo "Last log lines:"
tail -n 15 "$DATA/bbd.log" 2>&1
