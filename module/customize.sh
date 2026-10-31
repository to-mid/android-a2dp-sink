#!/system/bin/sh
[ "$API" = 30 ] || abort "This module is for Android 11 (API 30)."
[ "$(getprop ro.product.device)" = thyme ] || abort "This module is for Xiaomi thyme."
ui_print "- Bluetooth audio mode switcher"
ui_print "- Author: 橘橙的记忆"
ui_print "- First install requires a reboot"
mkdir -p "$MODPATH/state"
if [ -d /data/adb/modules/bt_speaker_thyme/state ]; then
    cp -af /data/adb/modules/bt_speaker_thyme/state/. "$MODPATH/state/"
else
    for prop in persist.vendor.service.bt.a2dp.sink persist.vendor.bt.a2dp.sink_conn; do
        getprop "$prop" > "$MODPATH/state/$prop"
    done
fi
[ -f "$MODPATH/state/default_mode" ] || printf 'source\n' > "$MODPATH/state/default_mode"
if [ ! -f "$MODPATH/state/current_mode" ]; then
    if [ "$(getprop persist.vendor.service.bt.a2dp.sink)" = true ]; then
        printf 'sink\n' > "$MODPATH/state/current_mode"
    else
        printf 'source\n' > "$MODPATH/state/current_mode"
    fi
fi
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/mode.sh" 0 0 0755
set_perm "$MODPATH/cleanup.sh" 0 0 0755
