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

# Recovery inputs, including saved grants, are accessible only to root.
umask 077
RECOVERY=/data/adb/big_brother_recovery
WORKER=/data/adb/service.d/big_brother_recover.sh

recover() (
    [ -d "$RECOVERY" ] || exit 0
    exec 8>/data/adb/.big_brother_recover.lock
    flock -x 0 <&8 || exit 1
    for batch in "$RECOVERY"/pending.*; do
        [ -d "$batch" ] || continue
        for request in "$batch"/uid.*; do
            [ -f "$request" ] || continue
            uid=${request##*.}
            reply=$(CLASSPATH="$batch/helper.apk" timeout 30 /system/bin/app_process /system/bin \
                li.mercury.bigbrother.catalog.AppOpsGuard --recover "$batch/done.$uid" < "$request" 2>/dev/null) || continue
            case "$reply" in
            "restored $uid"|"retired $uid") rm -f "$request" ;;
            esac
        done
        set -- "$batch"/uid.*
        [ -f "$1" ] || rm -rf "$batch"
    done
    set -- "$RECOVERY"/pending.*
    [ ! -d "$1" ] || exit 1
    rm -f "$WORKER"
    rmdir "$RECOVERY" 2>/dev/null || true
)

# The copy stays available through reboot when system services or individual
# grants were unavailable during uninstall. A new module waits for this work.
if [ "${1:-}" = --recover ]; then
    recover
    exit $?
fi
if [ "$0" = "$WORKER" ]; then
    (while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done
     recover) </dev/null >/dev/null 2>&1 &
    exit 0
fi

MODDIR=${0%/*}
BBD=$MODDIR/bin/bbd
released_all() {
    answer=$("$BBD" sandbox disable --all --json 2>/dev/null) || return 1
    case "$answer" in
    *'"failed":[]'*) return 0 ;;
    esac
    return 1
}
online=false
released_all && online=true
. "$MODDIR/stop.sh"
rm -rf /data/system/big_brother_sandbox /data/system/big_brother_sandbox.status \
    /data/system/big_brother_sandbox.new /data/system/big_brother_hook

if ! "$online" && [ -f /data/adb/big_brother/bb.db ]; then
    exec 8>/data/adb/.big_brother_recover.lock
    flock -x 0 <&8 || exit 1
    mkdir -p "$RECOVERY" /data/adb/service.d || exit 1
    stage=$(mktemp -d "$RECOVERY/stage.XXXXXX") || exit 1
    trap 'rm -rf "$stage"' EXIT
    "$BBD" sandbox-restore-lines > "$stage/commands" || exit 1
    if [ -s "$stage/commands" ]; then
        cp "$MODDIR/helper.apk" "$stage/helper.apk" || exit 1
        while IFS=' ' read -r bound pkg cookie op uid values; do
            case "$uid" in ''|*[!0-9]*) exit 1 ;; esac
            [ "$bound" = bound ] && [ "$op" = restore ] || exit 1
            printf '%s %s %s %s %s %s\n' "$bound" "$pkg" "$cookie" "$op" "$uid" "$values" > "$stage/uid.$uid" || exit 1
        done < "$stage/commands"
        rm "$stage/commands" || exit 1
        cp "$MODDIR/uninstall.sh" "$stage/worker" && chmod 700 "$stage/worker" &&
            mv -f "$stage/worker" "$WORKER" || exit 1
        sync
        mv "$stage" "$RECOVERY/pending.${stage##*.}" || exit 1
        sync
        sh "$WORKER" 8>&-
    fi
fi
rm -rf /data/adb/big_brother
# The program the packet filter's rules name. The rules went with the last
# app taken out, and with the restart of the device otherwise.
rm -f /sys/fs/bpf/bb_network
# Sandboxed apps keep their files on shared storage.
