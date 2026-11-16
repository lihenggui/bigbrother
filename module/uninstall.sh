#!/system/bin/sh
# Runs when the module is removed: stop the daemon and delete its records.
MODDIR=${0%/*}
. "$MODDIR/stop.sh"
rm -rf /data/adb/big_brother
