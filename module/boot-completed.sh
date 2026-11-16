#!/system/bin/sh
# Runs once the system has finished booting: clear the boot guard's marker
# (see service.sh).
rm -f /data/adb/big_brother/boot_pending
