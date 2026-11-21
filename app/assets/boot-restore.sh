#!/system/bin/sh
# Magisk / KernelSU service.d. Never run a persistent Bluetooth watchdog.
STATE=/data/user_de/0/local.bluetooth.speaker/files/root-control
for attempt in $(seq 1 90); do
    [ "$(getprop sys.boot_completed)" = 1 ] && break
    sleep 2
done
[ "$(getprop sys.boot_completed)" = 1 ] || exit 0
[ -f "$STATE/settings.json" ] || exit 0
for attempt in 1 2 3; do
    APK=$(pm path local.bluetooth.speaker 2>/dev/null | sed -n 's/^package://p' | head -n 1)
    [ -f "$APK" ] || exit 0
    # Pipes and /dev/null avoid passing Magisk-private descriptors to app_process.
    RESULT=$(CLASSPATH="$APK" /system/bin/app_process /system/bin \
        local.bluetooth.speaker.RootCommand apply-boot </dev/null 2>&1)
    STATUS=$?
    printf '%s\nattempt=%s exit=%s\n%s\n' "$(date)" "$attempt" "$STATUS" "$RESULT" > "$STATE/boot.log"
    [ "$STATUS" = 0 ] && exit 0
    sleep 15
done
exit 1
