#!/system/bin/sh
MODDIR=${0%/*}
# Magisk's overlay directory timestamp is stable; MIUI otherwise reuses a stale manifest.
find /data/system/package_cache -type f -name 'BluetoothSpeaker-*' -delete
MODE=$(cat "$MODDIR/state/default_mode" 2>/dev/null)
if [ "$MODE" = last ]; then MODE=$(cat "$MODDIR/state/current_mode" 2>/dev/null); fi
case "$MODE" in sink) VALUE=true;; *) MODE=source; VALUE=false;; esac
# Set the current boot's audio role without writing a persistent system property.
resetprop -n persist.vendor.service.bt.a2dp.sink "$VALUE"
printf '%s\n' "$MODE" > "$MODDIR/state/boot_mode"
