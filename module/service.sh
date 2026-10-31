#!/system/bin/sh
MODDIR=${0%/*}
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
MODE=$(cat "$MODDIR/state/boot_mode" 2>/dev/null)
if [ "$MODE" != source ] && [ "$MODE" != sink ]; then
    MODE=$(cat "$MODDIR/state/default_mode" 2>/dev/null)
    if [ "$MODE" = last ]; then MODE=$(cat "$MODDIR/state/current_mode" 2>/dev/null); fi
fi
case "$MODE" in source|sink) ;; *) MODE=source;; esac
RESULT=$(sh "$MODDIR/mode.sh" "$MODE" boot 2>&1)
STATUS=$?
PROFILES=$(dumpsys bluetooth_manager | grep -E '^Profile:|Bluetooth crashed|state:')
{
    date
    printf 'boot_mode=%s\nexit_status=%s\n%s\n' "$MODE" "$STATUS" "$RESULT"
    printf '%s\n' "$PROFILES"
} > "$MODDIR/status.log"
exit "$STATUS"
