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

# Stops the supervisor and the daemon, giving the daemon time to flush its
# buffered records. Sourced by service.sh and uninstall.sh.
DATA=/data/adb/big_brother

# is_running <pid> <text expected in the process's command line>
# Pids are reused, so a pid only counts if it still is the process we started.
is_running() {
    [ -n "$1" ] && [ -r /proc/"$1"/cmdline ] && tr '\0' ' ' </proc/"$1"/cmdline | grep -q "$2"
}

# stop_process <pid file> <text expected in the process's command line>
stop_process() {
    pid="$(cat "$1" 2>/dev/null)"
    if is_running "$pid" "$2"; then
        kill "$pid"
        i=0
        while is_running "$pid" "$2" && [ $i -lt 50 ]; do
            sleep 0.1
            i=$((i + 1))
        done
        # Still there after 5 s: it must not survive, or a second instance
        # would end up writing to the same database.
        if is_running "$pid" "$2"; then
            kill -9 "$pid"
            sleep 0.2
        fi
    fi
    rm -f "$1"
}

# The supervisor first: it stops the daemon itself, and must not restart it.
stop_process "$DATA/supervisor.pid" supervise.sh
stop_process "$DATA/bbd.pid" bin/bbd
