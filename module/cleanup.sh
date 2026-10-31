#!/system/bin/sh
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 10
cmd overlay disable --user 0 local.bluetooth.speaker.overlay >/dev/null 2>&1
cmd package uninstall-system-updates local.bluetooth.speaker.overlay >/dev/null 2>&1
for attempt in 1 2 3 4 5; do
    if ! pm path local.bluetooth.speaker.overlay | grep -q '^package:'; then break; fi
    pm uninstall local.bluetooth.speaker.overlay >/dev/null 2>&1
    sleep 3
done
for component in a2dpsink.A2dpSinkService avrcpcontroller.AvrcpControllerService avrcpcontroller.BluetoothMediaBrowserService; do
    pm default-state "com.android.bluetooth/com.android.bluetooth.$component" >/dev/null 2>&1
done
if [ "$(settings get global bluetooth_on)" = 1 ]; then
    svc bluetooth disable
    sleep 3
    svc bluetooth enable
fi
rm -f /data/adb/service.d/bt_speaker_cleanup.sh
