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

# $1 is private staging space on the module filesystem. The APK belongs
# to PackageManager. Finish every copy before stopping the live generation.
set -eu
STAGED=$1
APK=$2
DAEMON=$3
# The module in the root manager, or else the one the app installed. A
# manager keeps a directory without the daemon for a module it has yet to apply.
MODULE=/data/adb/modules/big_brother
MODDIR=$MODULE
[ -e "$MODDIR/bin/bbd" ] || MODDIR=/data/adb/big_brother_direct
UPDATE=/data/adb/modules_update/big_brother
NEXT=$STAGED/module
exec 9>/data/adb/.big_brother_update.lock
flock -x 0 <&9
[ -d "$MODDIR" ] && [ ! -e "$MODDIR/remove" ] && [ ! -e "$MODDIR/disable" ] || exit 1
cp -a "$MODDIR" "$NEXT"
for file in module.prop service.sh supervise.sh stop.sh uninstall.sh action.sh direct.sh; do
    rm -f "$NEXT/$file"
    unzip -p "$APK" "$file" > "$NEXT/$file" && [ -s "$NEXT/$file" ] || exit 1
    chmod 644 "$NEXT/$file"
done
rm -f "$NEXT/bin/bbd" "$NEXT/helper.apk"
unzip -p "$APK" "$DAEMON" > "$NEXT/bin/bbd" && [ -s "$NEXT/bin/bbd" ] || exit 1
chmod 755 "$NEXT/bin/bbd"
"$NEXT/bin/bbd" module-check-db
cp "$APK" "$NEXT/helper.apk"
chmod 644 "$NEXT/helper.apk"
cmp -s "$APK" "$NEXT/helper.apk"
# Keep the previous payload even if power is lost just after publication.
# This copy lets the owner recover the previous module files.
DATA=/data/adb/big_brother
BACKUP=$DATA/previous-module
mkdir -p "$DATA"
chmod 700 "$DATA"
cp -a "$MODDIR" "$STAGED/previous"
if [ -d "$BACKUP" ]; then
    "$NEXT/bin/bbd" module-exchange "$STAGED/previous" "$BACKUP"
else
    mv "$STAGED/previous" "$BACKUP"
fi
sync
[ ! -e "$MODDIR/remove" ] && [ ! -e "$MODDIR/disable" ] || exit 1
"$NEXT/bin/bbd" module-stop "$MODDIR"
if ! "$NEXT/bin/bbd" module-exchange "$NEXT" "$MODDIR"; then
    sh "$MODDIR/service.sh" 9>&- </dev/null >/dev/null 2>&1
    exit 1
fi
sync
for flag in disable remove; do
    if [ -e "$NEXT/$flag" ]; then touch "$MODDIR/$flag"; fi
done
[ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 1
# An update the root manager staged that is not newer than these files
# would only bring them back at the next boot.
if [ "$MODDIR" = "$MODULE" ] && [ -d "$UPDATE" ]; then
    staged="$(sed -n 's/^versionCode=//p' "$UPDATE/module.prop" 2>/dev/null)"
    current="$(sed -n 's/^versionCode=//p' "$MODDIR/module.prop")"
    if [ "${staged:-0}" -le "${current:-0}" ]; then
        rm -rf "$UPDATE" "$MODDIR/update"
    fi
fi

# Stops the running daemon and starts the new one, as at boot.
sh "$MODDIR/service.sh" 9>&- </dev/null >/dev/null 2>&1
# Report startup failures after publishing a complete generation.
i=0
while [ "$i" -lt 10 ]; do
    pid=$(cat /data/adb/big_brother/bbd.pid 2>/dev/null) || pid=
    if [ -n "$pid" ] && [ "/proc/$pid/exe" -ef "$MODDIR/bin/bbd" ] &&
        "$MODDIR/bin/bbd" stats 2>/dev/null | grep -q '"daemon_running":true'; then
        exit 0
    fi
    i=$((i + 1))
    sleep 1
done
echo "Module updated; daemon startup was not confirmed"
exit 1
