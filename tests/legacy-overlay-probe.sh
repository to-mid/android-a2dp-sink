#!/system/bin/sh
set -eu
[ "$(id -u)" = 0 ]
OVERLAY=$(pm path local.bluetooth.speaker.overlay | sed -n 's/^package://p')
TARGET=$(pm path com.android.bluetooth | sed -n 's/^package://p')
[ -f "$OVERLAY" ]
[ -f "$TARGET" ]
IDMAP="/data/resource-cache/$(printf '%s' "${OVERLAY#/}" | tr / @)@idmap"
idmap2 create --target-apk-path "$TARGET" --overlay-apk-path "$OVERLAY" \
    --idmap-path "$IDMAP" --ignore-overlayable
chown 1000:1000 "$IDMAP"
chmod 0644 "$IDMAP"
restorecon "$IDMAP"
trap 'cmd overlay disable --user 0 local.bluetooth.speaker.overlay' EXIT
cmd overlay enable --user 0 local.bluetooth.speaker.overlay
cmd overlay list --user 0 com.android.bluetooth
for resource in profile_supported_a2dp_sink profile_supported_avrcp_controller a2dp_sink_automatically_request_audio_focus; do
    VALUE=$(cmd overlay lookup com.android.bluetooth "com.android.bluetooth:bool/$resource")
    printf '%s=%s\n' "$resource" "$VALUE"
    [ "$VALUE" = true ]
done
