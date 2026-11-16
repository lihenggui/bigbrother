# Sourced by the KernelSU installer. See https://kernelsu.org/guide/module.html
#
# This module ships inside the Big Brother APK: the same file is both the
# Android app and a flashable module. The installer must therefore not unpack
# the whole archive (it would dump the app's code into the module directory),
# so extraction is done here, for the module's own files only.
SKIPUNZIP=1

APP_PACKAGE=li.mercury.bigbrother

[ "$KSU" = true ] || abort "! This module requires KernelSU"
[ "$ARCH" = arm64 ] || abort "! Unsupported architecture: $ARCH (arm64 only)"

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
unzip -o "$ZIPFILE" module.prop service.sh boot-completed.sh supervise.sh stop.sh uninstall.sh \
    action.sh 'bin/*' -d "$MODPATH" >&2 ||
    abort "! Failed to extract module files"
# unzip does not reliably fail for a name that is missing from the archive.
for file in module.prop service.sh boot-completed.sh supervise.sh stop.sh uninstall.sh \
    action.sh bin/bbd; do
    [ -f "$MODPATH/$file" ] || abort "! $file is missing from the archive"
done
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
        # The package manager cannot read the installer's temporary files.
        apk=/data/local/tmp/big_brother.apk
        if cp "$ZIPFILE" "$apk" && chmod 644 "$apk" && pm install -r "$apk" >&2; then
            ui_print "- App installed"
        else
            ui_print "! Could not install the app; install the APK manually"
        fi
        rm -f "$apk"
    fi
fi

# Apply without a reboot where the manager supports it. Updates don't run
# uninstall.sh, so service.sh stops the previous daemon itself.
MODULE_HOT_INSTALL_REQUEST=true
MODULE_HOT_RUN_SCRIPT=service.sh

ui_print "- Records are stored in /data/adb/big_brother"
