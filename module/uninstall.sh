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
DATA=/data/adb/big_brother
RECOVERY=/data/adb/big_brother_recovery
WORKER=/data/adb/service.d/big_brother_recover.sh
DISABLE_WORKER=/data/adb/service.d/big_brother_restore_disabled.sh
DISABLED=$RECOVERY/disabled
MODDIR=${0%/*}
if [ "$0" = "$DISABLE_WORKER" ]; then
    MODDIR=/data/adb/modules/big_brother
fi
BBD=$MODDIR/bin/bbd

with_recovery_lock() (
    exec 8>/data/adb/.big_brother_recover.lock
    flock -x 0 <&8 || exit 1
    "$@"
)

module_disabled() {
    [ -f "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]
}

wait_for_boot() {
    # Newer managers expose a property change wait. Older ones may only
    # provide getprop, including installations without a root manager.
    while boot=$(getprop sys.boot_completed) && [ "$boot" != 1 ]; do
        resetprop -w sys.boot_completed "$boot" >/dev/null 2>&1 || sleep 5
    done
    [ "$boot" = 1 ]
}

publish_worker() (
    mkdir -p "$RECOVERY" "${1%/*}" || exit 1
    copy=$(mktemp "$RECOVERY/worker.XXXXXX") || exit 1
    trap 'rm -f "$copy"' 0
    cp "$0" "$copy" && chmod 700 "$copy" && mv -f "$copy" "$1"
)

register_disable_worker() {
    # A module-local script is skipped by both managers when disabled.
    # Keep the general hook independent of module directory replacement.
    if [ ! -x "$DISABLE_WORKER" ] || ! cmp -s "$0" "$DISABLE_WORKER"; then
        publish_worker "$DISABLE_WORKER" && sync
    fi
}

stage_restore() (
    mkdir -p "$RECOVERY" || exit 1
    stage=$(mktemp -d "$RECOVERY/stage.XXXXXX") || exit 1
    trap 'rm -rf "$stage"' 0
    if [ -f "$DATA/bb.db" ]; then
        "$BBD" sandbox-restore-lines > "$stage/commands" || exit 1
        if [ -s "$stage/commands" ]; then
            cp "$MODDIR/helper.apk" "$stage/helper.apk" || exit 1
            while IFS=' ' read -r bound pkg cookie op uid values; do
                case "$uid" in ''|*[!0-9]*) exit 1 ;; esac
                [ "$bound" = bound ] || exit 1
                case "$op" in restore|undo) ;; *) exit 1 ;; esac
                printf '%s %s %s %s %s %s\n' "$bound" "$pkg" "$cookie" "$op" "$uid" "$values" > "$stage/uid.$uid" || exit 1
            done < "$stage/commands"
        fi
        rm "$stage/commands" || exit 1
    fi
    sync
    destination=${1:-$RECOVERY/pending.${stage##*.}}
    [ ! -e "$destination" ] && mv "$stage" "$destination" || exit 1
    sync
)

recover_batch() (
    batch=$1
    for request in "$batch"/uid.*; do
        [ -f "$request" ] || continue
        # Let an enable or uninstall proceed after at most the current
        # helper, even if many profiles are still unavailable.
        if [ "$batch" = "$DISABLED" ] && ! module_disabled; then exit 0; fi
        uid=${request##*.}
        # A helper stuck in a system service must release the recovery lock.
        reply=$(CLASSPATH="$batch/helper.apk" timeout -k 5 30 /system/bin/app_process /system/bin \
            li.mercury.bigbrother.catalog.AppOpsGuard --recover "$batch/done.$uid" < "$request" 2>/dev/null) || continue
        case "$reply" in
        "restored $uid"|"retired $uid") rm -f "$request" ;;
        esac
    done
    set -- "$batch"/uid.*
    [ ! -f "$1" ]
)

recover_uninstall() {
    for batch in "$RECOVERY"/pending.*; do
        [ -d "$batch" ] || continue
        if recover_batch "$batch"; then rm -rf "$batch"; fi
    done
    set -- "$RECOVERY"/pending.*
    [ ! -d "$1" ] || return 1
    rm -f "$WORKER"
    rmdir "$RECOVERY" 2>/dev/null || true
}

recover_disabled() {
    module_disabled || return 0
    rm -f "$DATA/boot_pending"
    # A completed batch marks this disable period too: do not replay saved
    # grants over choices the user makes on later boots while disabled.
    [ -d "$DISABLED" ] || stage_restore "$DISABLED" || return 1
    recover_batch "$DISABLED"
}

start_disabled_worker() {
    (
        exec 7>/data/adb/.big_brother_disabled_worker.lock
        flock -nx 0 <&7 || exit 0
        if [ "$0" != "$DISABLE_WORKER" ]; then
            with_recovery_lock register_disable_worker ||
                echo "could not register sandbox recovery" >>"$DATA/bbd.log"
        fi
        wait_for_boot || exit 1
        delay=5
        while ! with_recovery_lock recover_disabled; do
            if [ "$delay" -eq 5 ]; then
                echo "sandbox recovery still pending" >>"$DATA/bbd.log"
            fi
            sleep "$delay"
            delay=$((delay * 2))
            [ "$delay" -le 300 ] || delay=300
        done
    ) </dev/null >/dev/null 2>&1 &
}

prepare_start() {
    [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || return 1
    # Starting Jail again supersedes pending disable recovery. No offline
    # helper can run concurrently with this, or before the next disable.
    rm -rf "$DISABLED" || return 1
    register_disable_worker
}

case "${1:-}" in
--recover)
    with_recovery_lock recover_uninstall
    exit $?
    ;;
--recover-after-boot)
    wait_for_boot && with_recovery_lock recover_uninstall
    exit $?
    ;;
--restore-disabled)
    start_disabled_worker
    exit 0
    ;;
--register-disable-hook)
    with_recovery_lock register_disable_worker
    exit $?
    ;;
--prepare-start)
    with_recovery_lock prepare_start
    exit $?
    ;;
esac
if [ "$0" = "$DISABLE_WORKER" ]; then
    if module_disabled; then start_disabled_worker; fi
    exit 0
fi
if [ "$0" = "$WORKER" ]; then
    (wait_for_boot && with_recovery_lock recover_uninstall) </dev/null >/dev/null 2>&1 &
    exit 0
fi

released_all() {
    answer=$("$BBD" sandbox disable --all --json 2>/dev/null) || return 1
    case "$answer" in
    *'"failed":[]'*) return 0 ;;
    esac
    return 1
}

stage_uninstall() {
    # Carry over any completed work and journals when uninstalling during
    # or after a disable period.
    if [ -d "$DISABLED" ] || { ! "$online" && [ -f "$DATA/bb.db" ]; }; then
        publish_worker "$WORKER" || return 1
        if [ -d "$DISABLED" ]; then
            slot=$(mktemp -d "$RECOVERY/stage.XXXXXX") || return 1
            rmdir "$slot" || return 1
            pending=$RECOVERY/pending.${slot##*.}
            sync
            [ ! -e "$pending" ] && mv "$DISABLED" "$pending" || return 1
            sync
        else
            stage_restore || return 1
        fi
        sh "$WORKER" 8>&-
    fi
    rm -f "$DISABLE_WORKER"
}

online=false
released_all && online=true
. "$MODDIR/stop.sh"
rm -rf /data/system/big_brother_sandbox /data/system/big_brother_sandbox.status \
    /data/system/big_brother_sandbox.new /data/system/big_brother_hook

with_recovery_lock stage_uninstall || exit 1
rm -rf "$DATA"
# The program the packet filter's rules name. The rules went with the last
# app taken out, and with the restart of the device otherwise.
rm -f /sys/fs/bpf/bb_network
# Sandboxed apps keep their files on shared storage.
