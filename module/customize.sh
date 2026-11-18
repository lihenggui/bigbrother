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
#
# This module ships inside the Big Brother APK: the same file is both the
# Android app and a flashable module. The installer must therefore not unpack
# the whole archive (it would dump the app's code into the module directory),
# so extraction is done here, for the module's own files only.
SKIPUNZIP=1

APP_PACKAGE=li.mercury.bigbrother

# Magisk is tested last: KernelSU and APatch also set MAGISK_VER_CODE, for
# modules that only know Magisk.
if [ "$APATCH" = true ]; then
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
    action.sh -d "$MODPATH" >&2 ||
    abort "! Failed to extract module files"
# Only the daemon for this device, as bin/bbd (-j drops the bin/$ARCH/ part).
mkdir -p "$MODPATH/bin"
unzip -o -j "$ZIPFILE" "bin/$ARCH/bbd" -d "$MODPATH/bin" >&2 ||
    abort "! Failed to extract the daemon for $ARCH"
# unzip does not reliably fail for a name that is missing from the archive.
for file in module.prop service.sh supervise.sh stop.sh uninstall.sh \
    action.sh bin/bbd; do
    [ -f "$MODPATH/$file" ] || abort "! $file is missing from the archive"
done
# The daemon runs two small programs out of the app's APK (see
# bbd/src/helper.rs). It takes them from this copy, which only root can
# replace, rather than from whatever app is installed under the package name.
if unzip -l "$ZIPFILE" classes.dex >/dev/null 2>&1; then
    cp "$ZIPFILE" "$MODPATH/helper.apk" ||
        ui_print "! Could not keep a copy of the app; service calls and permission use will not be recorded"
fi
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/bbd" 0 0 0755

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
MODULE_HOT_INSTALL_REQUEST=true
MODULE_HOT_RUN_SCRIPT=service.sh

ui_print "- Records are stored in /data/adb/big_brother"
