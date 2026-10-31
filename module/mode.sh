#!/system/bin/sh
MODDIR=${0%/*}
MODE=$1
case "$MODE" in source|sink) ;; *) echo "Invalid audio mode"; exit 1;; esac
mkdir -p "$MODDIR/state"
LOCK="$MODDIR/state/mode-lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    OWNER=$(cat "$LOCK/pid" 2>/dev/null)
    if [ -n "$OWNER" ] && kill -0 "$OWNER" 2>/dev/null; then
        echo "A mode change is already in progress"
        exit 1
    fi
    rm -f "$LOCK/pid"
    rmdir "$LOCK" 2>/dev/null
    mkdir "$LOCK" 2>/dev/null || exit 1
fi
printf '%s\n' "$$" > "$LOCK/pid"
trap 'rm -f "$LOCK/pid"; rmdir "$LOCK" 2>/dev/null' EXIT
trap 'exit 1' HUP INT TERM
OVERLAY=local.bluetooth.speaker.overlay
APK="$MODDIR/system/product/overlay/BluetoothSpeaker/BluetoothSpeakerOverlay.apk"
OLD_MODE=$(cat "$MODDIR/state/current_mode" 2>/dev/null)
case "$OLD_MODE" in source|sink) ;; *)
    if [ "$(getprop persist.vendor.service.bt.a2dp.sink)" = true ]; then OLD_MODE=sink; else OLD_MODE=source; fi;;
esac
WAS_ON=$(settings get global bluetooth_on 2>/dev/null)

ensure_overlay() {
    ACTIVE=$(pm path "$OVERLAY" 2>/dev/null | sed 's/^package://')
    if [ "${ACTIVE#/data/app/}" != "$ACTIVE" ] && [ -f "$ACTIVE" ] && cmp -s "$APK" "$ACTIVE"; then return 0; fi
    for attempt in 1 2 3 4 5 6; do
        # Only pipes may cross into Android; Magisk-private log FDs cannot.
        if RESULT=$(pm install -r "$APK" 2>&1); then
            printf '%s\n' "$RESULT" >> "$MODDIR/install.log"
            return 0
        fi
        printf '%s\n' "$RESULT" >> "$MODDIR/install.log"
        sleep 2
    done
    echo "Cannot install the audio resource overlay"
    return 1
}

configure_mode() {
    if [ "$1" = sink ]; then
        ensure_overlay || return 1
        resetprop -n persist.vendor.service.bt.a2dp.sink true || return 1
        resetprop -n persist.vendor.bt.a2dp.sink_conn 1 || return 1
        RESULT=$(cmd overlay enable --user 0 "$OVERLAY" 2>&1) || { echo "$RESULT"; return 1; }
        CONTROL_APK=$(pm path local.bluetooth.speaker 2>/dev/null | sed 's/^package://')
        COMPONENTS_READY=0
        if [ -f "$CONTROL_APK" ]; then
            if RESULT=$(CLASSPATH="$CONTROL_APK" /system/bin/app_process /system/bin \
                local.bluetooth.speaker.RootCommand prepare-components 2>&1); then
                COMPONENTS_READY=1
            fi
        fi
        if [ "$COMPONENTS_READY" != 1 ]; then
            for component in a2dpsink.A2dpSinkService avrcpcontroller.AvrcpControllerService avrcpcontroller.BluetoothMediaBrowserService; do
                RESULT=$(pm enable "com.android.bluetooth/com.android.bluetooth.$component" 2>&1) || { echo "$RESULT"; return 1; }
            done
        fi
    else
        if pm path "$OVERLAY" 2>/dev/null | grep -q '^package:'; then
            RESULT=$(cmd overlay disable --user 0 "$OVERLAY" 2>&1) || { echo "$RESULT"; return 1; }
        fi
        resetprop -n persist.vendor.service.bt.a2dp.sink false || return 1
    fi
}

profile_ready() {
    if [ "$1" = sink ]; then EXPECTED=A2dpSinkService; OTHER=A2dpService; else EXPECTED=A2dpService; OTHER=A2dpSinkService; fi
    PROFILES=$(dumpsys bluetooth_manager)
    printf '%s\n' "$PROFILES" | grep -q "^Profile: $EXPECTED$" &&
        ! printf '%s\n' "$PROFILES" | grep -q "^Profile: $OTHER$"
}

stop_bluetooth() {
    [ "$WAS_ON" = 1 ] || return 0
    RESULT=$(svc bluetooth disable 2>&1) || return 1
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
        if dumpsys bluetooth_manager | grep -Eq '^  state: (OFF|BLE_ON)$'; then return 0; fi
        sleep 0.4
    done
    echo "Bluetooth did not turn off"
    return 1
}

start_bluetooth() {
    [ "$WAS_ON" = 1 ] || return 0
    RESULT=$(svc bluetooth enable 2>&1) || return 1
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
        profile_ready "$1" && return 0
        sleep 0.5
    done
    echo "The requested Bluetooth audio profile did not start"
    return 1
}

# Reapplying a healthy boot mode does not interrupt existing Bluetooth connections.
CURRENT=$(getprop persist.vendor.service.bt.a2dp.sink)
OVERLAY_ON=$(cmd overlay list --user 0 com.android.bluetooth 2>/dev/null | grep -F "[x] $OVERLAY")
if [ "$WAS_ON" = 1 ] && profile_ready "$MODE"; then
    if { [ "$MODE" = sink ] && [ "$CURRENT" = true ] && [ -n "$OVERLAY_ON" ]; } ||
       { [ "$MODE" = source ] && [ "$CURRENT" != true ] && [ -z "$OVERLAY_ON" ]; }; then
        printf '%s\n' "$MODE" > "$MODDIR/state/current_mode"
        echo "mode=$MODE"
        exit 0
    fi
fi
stop_bluetooth || { [ "$WAS_ON" != 1 ] || RESULT=$(svc bluetooth enable 2>&1); exit 1; }
if configure_mode "$MODE" && start_bluetooth "$MODE"; then
    printf '%s\n' "$MODE" > "$MODDIR/state/current_mode.tmp"
    mv "$MODDIR/state/current_mode.tmp" "$MODDIR/state/current_mode"
    echo "mode=$MODE"
    exit 0
fi
echo "Mode change failed; restoring $OLD_MODE"
RESULT=$(svc bluetooth disable 2>&1)
sleep 1
configure_mode "$OLD_MODE"
start_bluetooth "$OLD_MODE"
exit 1
