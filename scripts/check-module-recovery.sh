#!/usr/bin/env bash
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

# Exercise the root scripts with fake Android commands on a Linux host.
set -euo pipefail
cd "$(dirname "$0")/.."
source_module=${BB_TEST_MODULE:-module}
scratch=$(mktemp -d "$PWD/.module-recovery.XXXXXX")
trap 'rm -rf "$scratch"' EXIT

await() {
    for ((i = 0; i < 400; i++)); do
        if "$@"; then return; fi
        sleep 0.025
    done
    echo "Timed out waiting for $check" >&2
    return 1
}

fixture() {
    export state=$scratch/$1
    mod=$state/data/adb/modules/big_brother
    data=$state/data/adb/big_brother
    hook=$state/data/adb/service.d/big_brother_restore_disabled.sh
    batch=$state/data/adb/big_brother_recovery/disabled
    mkdir -p "$mod/bin" "$data" "$state/bin" "$state/system/bin"
    for file in service.sh supervise.sh stop.sh uninstall.sh; do
        sed -e "s|/data/|$state/data/|g" \
            -e "s|/system/bin/app_process|$state/system/bin/app_process|g" \
            -e "s|/sys/fs/bpf/|$state/sys/fs/bpf/|g" \
            "$source_module/$file" >"$mod/$file"
    done
    export PATH="$state/bin:$PATH"
    printf '0\n' >"$state/boot"
    printf 'fixture database\n' >"$data/bb.db"
    touch "$mod/helper.apk"
    cat >"$state/bin/getprop" <<'SH'
#!/bin/sh
touch "$state/read-boot"
cat "$state/boot"
SH
    cat >"$state/bin/resetprop" <<'SH'
#!/bin/sh
touch "$state/waiting"
while [ "$(cat "$state/boot")" != 1 ]; do sleep 0.025; done
SH
    cat >"$state/bin/sync" <<'SH'
#!/bin/sh
exit 0
SH
    cat >"$mod/bin/bbd" <<'SH'
#!/bin/sh
case "$1" in
module-stop) touch "$state/stopped" ;;
sandbox-remove-network)
    [ -f "$state/stopped" ] && [ -f "$state/data/adb/big_brother/bb.db" ] || exit 2
    touch "$state/network-removed"
    ;;
sandbox-restore-lines)
    echo 'bound com.example.owner aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa restore 10100 android:camera=4'
    echo 'bound com.example.profile bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb restore 1010101 android:camera=4'
    ;;
run) touch "$state/running" ;;
*) exit 1 ;;
esac
SH
    cat >"$state/system/bin/app_process" <<'SH'
#!/bin/sh
[ "$(cat "$state/boot")" = 1 ] || exit 2
read -r bound package token op uid values
[ "$bound" = bound ] && [ "$op" = restore ] &&
    [ "${#token}" -eq 32 ] && [ "$3" = --recover ] && [ -f "$CLASSPATH" ] || exit 2
if [ ! -f "$4" ]; then
    printf '%s\n' "$values" >"$4"
    echo "$uid" >>"$state/writes"
    if [ "$uid" = 1010101 ] && [ -e "$state/defer-profile" ]; then
        touch "$state/deferred"
        exit 1
    fi
fi
echo "restored $uid"
SH
    chmod +x "$mod/bin/bbd" "$state/bin/"* "$state/system/bin/app_process"
}

wait_for_worker_exit() {
    exec {worker_fd}>"$state/data/adb/.big_brother_disabled_worker.lock"
    flock -x -w 10 "$worker_fd"
    exec {worker_fd}>&-
}

restored() {
    [[ -f "$batch/done.10100" && -f "$batch/done.1010101" ]] &&
        [[ ! -e "$batch/uid.10100" && ! -e "$batch/uid.1010101" ]]
}

boot_guard_restores_after_boot() (
    fixture guard
    touch "$data/boot_pending"
    sh "$mod/service.sh"
    [[ -e "$mod/disable" ]]
    check="boot guard recovery to wait for Android"
    await test -f "$state/waiting"
    [[ ! -e "$state/writes" && ! -e "$state/running" ]]
    printf '1\n' >"$state/boot"
    check="boot guard recovery to restore both installations"
    await restored
    wait_for_worker_exit
    [[ ! -e "$data/boot_pending" && ! -e "$state/running" ]]
    [[ $(cat "$data/bb.db") = 'fixture database' ]]
)

manager_toggle_retries_and_reenables() (
    fixture toggle
    printf '1\n' >"$state/boot"
    sh "$mod/service.sh"
    check="enabled module to register its hook and start"
    await test -f "$state/running"
    await grep -q "daemon exited normally" "$data/bbd.log"
    [[ -x "$hook" ]]
    rm "$state/running"
    touch "$mod/disable" "$state/defer-profile"
    sh "$hook"
    check="first recovery pass to defer the second installation"
    await test -f "$state/deferred"
    [[ -f "$batch/uid.1010101" && -f "$batch/done.1010101" ]]
    check="retry to finish from its journal"
    await restored
    wait_for_worker_exit
    [[ $(wc -l <"$state/writes") -eq 2 && ! -e "$state/running" ]]

    rm "$state/read-boot"
    sh "$hook"
    check="disabled boot hook to run again"
    await test -f "$state/read-boot"
    wait_for_worker_exit
    [[ $(wc -l <"$state/writes") -eq 2 ]]

    rm "$mod/disable" "$state/defer-profile" "$data/bbd.log"
    sh "$mod/service.sh"
    check="re-enabled module to start from its database"
    await test -f "$state/running"
    await grep -q "daemon exited normally" "$data/bbd.log"
    [[ ! -e "$batch" && $(cat "$data/bb.db") = 'fixture database' ]]

    touch "$mod/disable"
    sh "$hook"
    check="next disable to restore again"
    await restored
    wait_for_worker_exit
    [[ $(wc -l <"$state/writes") -eq 4 ]]
    sh "$mod/uninstall.sh"
    [[ -f "$state/network-removed" && ! -e "$hook" && ! -e "$data" ]]
    check="uninstall recovery worker to finish"
    await test ! -e "${hook%/*}/big_brother_recover.sh"
)

case "${1:-all}" in
guard) boot_guard_restores_after_boot ;;
toggle) manager_toggle_retries_and_reenables ;;
all)
    boot_guard_restores_after_boot
    manager_toggle_retries_and_reenables
    ;;
*) exit 2 ;;
esac
echo "Module disable recovery checks passed"
