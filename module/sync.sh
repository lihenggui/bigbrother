# Run by the app, as root, to bring the installed module up to date with
# the app without a reboot. The app stages the module files it carries in
# $1 (the daemon for this device as bbd) and passes its APK as $2: the same
# files customize.sh installs. Each file is replaced in one rename, so a
# running script or daemon keeps the file it opened.
STAGED=$1
APK=$2
MODDIR=/data/adb/modules/big_brother
UPDATE=/data/adb/modules_update/big_brother

[ -d "$MODDIR" ] || {
    echo "Module not installed"
    exit 1
}

# replace <source> <target> <mode>
replace() {
    cp "$1" "$2.new" && chmod "$3" "$2.new" && mv -f "$2.new" "$2" || {
        rm -f "$2.new"
        echo "Could not replace $2"
        exit 1
    }
}

for file in module.prop service.sh supervise.sh stop.sh uninstall.sh action.sh; do
    replace "$STAGED/$file" "$MODDIR/$file" 644
done
replace "$STAGED/bbd" "$MODDIR/bin/bbd" 755
replace "$APK" "$MODDIR/helper.apk" 644

# An update the root manager staged that is not newer than these files
# would only bring them back at the next boot.
if [ -d "$UPDATE" ]; then
    staged="$(sed -n 's/^versionCode=//p' "$UPDATE/module.prop" 2>/dev/null)"
    current="$(sed -n 's/^versionCode=//p' "$MODDIR/module.prop")"
    if [ "${staged:-0}" -le "${current:-0}" ]; then
        rm -rf "$UPDATE" "$MODDIR/update"
    fi
fi

# Stops the running daemon and starts the new one, as at boot.
sh "$MODDIR/service.sh" </dev/null >/dev/null 2>&1
