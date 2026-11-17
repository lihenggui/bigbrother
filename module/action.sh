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

# Action button in the root manager: show daemon status.
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
