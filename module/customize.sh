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

# Sourced by the module installer of KernelSU, Magisk or APatch, which share
# one module format. See https://kernelsu.org/guide/module.html
# Also by direct.sh, which installs the module without a root manager.
#
# This module ships inside the Big Brother APK: the same file is both the
# Android app and a flashable module. The installer must therefore not unpack
# the whole archive (it would dump the app's code into the module directory),
# so extraction is done here, for the module's own files only.
# shellcheck disable=SC2034 # Read by the root manager after sourcing this script.
SKIPUNZIP=1

APP_PACKAGE=li.mercury.bigbrother

# Magisk is tested last: KernelSU and APatch also set MAGISK_VER_CODE, for
# modules that only know Magisk.
if [ "$DIRECT_INSTALL" = true ]; then
    ui_print "- Installing with root alone"
elif [ "$APATCH" = true ]; then
    ui_print "- Installing on APatch"
elif [ "$KSU" = true ]; then
    ui_print "- Installing on KernelSU"
elif [ -n "$MAGISK_VER_CODE" ]; then
    ui_print "- Installing on Magisk $MAGISK_VER"
else
    abort "! This module requires KernelSU, Magisk or APatch"
fi
# The archive carries a daemon for each supported architecture, as
# bin/$ARCH/bbd (see scripts/build.sh): arm64 for devices, x64 for the
# Android emulator and Chromebooks.
unzip -l "$ZIPFILE" "bin/$ARCH/bbd" >/dev/null 2>&1 ||
    abort "! Unsupported architecture: $ARCH"
ui_print "- Architecture $ARCH"

# The daemon resolves kernel struct layouts from BTF at startup.
[ -r /sys/kernel/btf/vmlinux ] || abort "! Kernel has no BTF (/sys/kernel/btf/vmlinux); GKI 5.10+ required"

kernel="$(uname -r)"
major="${kernel%%.*}"
rest="${kernel#*.}"
minor="${rest%%.*}"
if [ "$major" -lt 5 ] || { [ "$major" -eq 5 ] && [ "$minor" -lt 10 ]; }; then
    abort "! Kernel $kernel is too old; 5.10 or newer required"
fi
ui_print "- Kernel $kernel"

ui_print "- Extracting module files"
unzip -o "$ZIPFILE" module.prop service.sh supervise.sh stop.sh uninstall.sh \
    action.sh direct.sh -d "$MODPATH" >&2 ||
    abort "! Failed to extract module files"
# Only the daemon for this device, as bin/bbd (-j drops the bin/$ARCH/ part).
mkdir -p "$MODPATH/bin"
unzip -o -j "$ZIPFILE" "bin/$ARCH/bbd" -d "$MODPATH/bin" >&2 ||
    abort "! Failed to extract the daemon for $ARCH"
# unzip does not reliably fail for a name that is missing from the archive.
for file in module.prop service.sh supervise.sh stop.sh uninstall.sh \
    action.sh direct.sh bin/bbd; do
    [ -f "$MODPATH/$file" ] || abort "! $file is missing from the archive"
done
# The daemon and sandbox recovery load the helpers from the root-owned module.
if ! unzip -p "$ZIPFILE" helper.apk > "$MODPATH/helper.apk" || [ ! -s "$MODPATH/helper.apk" ]; then
    abort "! Failed to extract helper.apk"
fi
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/bbd" 0 0 0755
message=$("$MODPATH/bin/bbd" module-check-db 2>&1) || abort "! $message"
# The new version can be disabled before its first boot, with saved
# restrictions still in the database from the installed version.
sh "$MODPATH/uninstall.sh" --register-disable-hook ||
    ui_print "! Could not register sandbox recovery. It will be retried at startup"

# Install the app the module was flashed from, unless that exact APK is
# already installed (which is the case when the app flashes itself).
if unzip -l "$ZIPFILE" AndroidManifest.xml >/dev/null 2>&1; then
    installed="$(pm path "$APP_PACKAGE" 2>/dev/null | head -n 1)"
    installed="${installed#package:}"
    if [ -n "$installed" ] && cmp -s "$ZIPFILE" "$installed"; then
        ui_print "- App already installed"
    else
        ui_print "- Installing the Big Brother app"
        # Binder can pass a pipe to PackageManager even when SELinux
        # prevents it receiving the installer's archive descriptor.
        size=$(wc -c <"$ZIPFILE")
        # shellcheck disable=SC2002 # Keep the pipe to PackageManager.
        if cat "$ZIPFILE" | pm install -r -S "$size" >&2; then
            ui_print "- App installed"
        else
            ui_print "! Could not install the app; rename this .zip to .apk and install it"
        fi
    fi
fi

# Apply without a reboot where the manager supports it (KernelSU; the others
# ignore the request and apply the module at the next boot). Updates don't run
# uninstall.sh, so service.sh stops the previous daemon itself.
# shellcheck disable=SC2034 # Read by KernelSU after sourcing this script.
MODULE_HOT_INSTALL_REQUEST=true
# shellcheck disable=SC2034 # Read by KernelSU after sourcing this script.
MODULE_HOT_RUN_SCRIPT=service.sh

ui_print "- Records are stored in /data/adb/big_brother"
