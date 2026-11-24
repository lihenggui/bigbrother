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

# The module where it is not flashed in a root manager: the app installs it
# with root alone, into a directory of its own, and the manager's module
# list does not show it.
#
#   direct.sh install <archive>   from a copy taken out of the archive
#   direct.sh boot                at boot, from /data/adb/service.d
#   direct.sh remove              by the app, or at boot once the app is gone
#   direct.sh register            after an update, in case the boot script is gone
#
# KernelSU, Magisk and APatch run what is in /data/adb/service.d at
# late_start, as they run a module's service.sh. Where nothing does, the
# daemon starts when the app is opened.
DIRECT=/data/adb/big_brother_direct
MODULE=/data/adb/modules/big_brother
LAUNCHER=/data/adb/service.d/big_brother.sh
APP_PACKAGE=li.mercury.bigbrother

# What a root manager's installer gives customize.sh.
ui_print() { echo "$1"; }
abort() {
    echo "$1"
    exit 1
}
set_perm() {
    chown "$2:$3" "$1" && chmod "$4" "$1"
}
set_perm_recursive() {
    find "$1" -type d | while read -r dir; do set_perm "$dir" "$2" "$3" "$4"; done
    find "$1" -type f | while read -r file; do set_perm "$file" "$2" "$3" "$5"; done
}

write_launcher() {
    mkdir -p "${LAUNCHER%/*}" || return 1
    # Written beside the module first: a manager runs whatever it finds in
    # service.d, a half-written file included.
    cat >"$DIRECT/launcher" <<EOF || return 1
#!/system/bin/sh
# Starts Big Brother at boot where it is not a module. Written by direct.sh.
[ -f $DIRECT/direct.sh ] && exec sh $DIRECT/direct.sh boot
rm -f "\$0"
EOF
    chmod 700 "$DIRECT/launcher" && mv -f "$DIRECT/launcher" "$LAUNCHER"
}

start() {
    # In a session of its own where there is a command for it: started from
    # a terminal, the daemon outlives it.
    session=
    setsid true 2>/dev/null && session=setsid
    $session sh "$DIRECT/service.sh" </dev/null >/dev/null 2>&1
}

install() {
    ZIPFILE=$1
    [ -f "$ZIPFILE" ] || abort "! No archive to install from"
    # While a module is in the root manager, that one is in use. One flashed
    # and not yet applied has no daemon there until the next boot.
    [ ! -e "$MODULE/bin/bbd" ] || abort "! The module is already in the root manager"
    case "$(getprop ro.product.cpu.abi)" in
    arm64-v8a) ARCH=arm64 ;;
    x86_64) ARCH=x64 ;;
    *) ARCH=$(getprop ro.product.cpu.abi) ;;
    esac
    stage=$(umask 077 && mktemp -d /data/adb/.bb-direct.XXXXXX) || abort "! No space to install in"
    trap 'rm -rf "$stage"' 0
    MODPATH=$stage/module
    mkdir "$MODPATH" || abort "! No space to install in"
    unzip -p "$ZIPFILE" customize.sh >"$stage/customize.sh" && [ -s "$stage/customize.sh" ] ||
        abort "! customize.sh is missing from the archive"
    # The module's own installer does the installing, as in a root manager.
    DIRECT_INSTALL=true
    . "$stage/customize.sh"
    # Complete and checked: only now is an earlier one stopped and replaced.
    if [ -d "$DIRECT" ]; then
        "$MODPATH/bin/bbd" module-stop "$DIRECT" && rm -rf "$DIRECT" ||
            abort "! Could not replace the installed module"
    fi
    mv "$MODPATH" "$DIRECT" || abort "! Could not install the module"
    write_launcher || ui_print "! Could not register for boot; the daemon starts when the app is opened"
    start
}

# The package manager's list of installed packages, replaced as a whole when
# it changes. One that cannot be read, or lacks what every device has, says
# nothing about the app.
app_removed() {
    list=/data/system/packages.list
    grep -q '^com\.android\.shell ' "$list" 2>/dev/null || return 1
    ! grep -q "^$APP_PACKAGE " "$list"
}

remove() {
    sh "$DIRECT/uninstall.sh" || return 1
    rm -rf "$DIRECT" "$LAUNCHER"
}

boot() {
    # A module flashed since takes over: its service.sh removes this one.
    [ ! -e "$MODULE/bin/bbd" ] || exit 0
    # Nothing is left to manage this installation or to show its records.
    if app_removed; then
        remove
        exit
    fi
    exec sh "$DIRECT/service.sh"
}

case "${1:-}" in
install) install "$2" ;;
boot) boot ;;
remove) remove ;;
register) write_launcher ;;
*) exit 2 ;;
esac
