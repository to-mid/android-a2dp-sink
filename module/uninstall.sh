#!/system/bin/sh
MODDIR=${0%/*}
# Magisk can uninstall before Android's package service is available.
mkdir -p /data/adb/service.d
cp "$MODDIR/cleanup.sh" /data/adb/service.d/bt_speaker_cleanup.sh
chmod 0755 /data/adb/service.d/bt_speaker_cleanup.sh
for prop in persist.vendor.service.bt.a2dp.sink persist.vendor.bt.a2dp.sink_conn; do
    value=$(cat "$MODDIR/state/$prop" 2>/dev/null)
    if [ -n "$value" ]; then
        resetprop -p "$prop" "$value"
    else
        resetprop -p --delete "$prop"
        resetprop --delete "$prop"
    fi
done
