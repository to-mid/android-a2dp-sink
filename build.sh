#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
TOOLS=${TOOLS:-../toolchain}
export JAVA_HOME="$(cd "$TOOLS/jdk" && pwd)"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$(cd "$TOOLS/android-11" && pwd)"
ANDROID="$(cd "$TOOLS/android-30" && pwd)/android.jar"
mkdir -p build dist signing
rm -f module/system.prop
if [ ! -f signing/local-speaker.jks ]; then
    keytool -genkeypair -keystore signing/local-speaker.jks -storepass android \
        -keypass android -alias local -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Local Bluetooth Speaker"
fi
sign_apk() {
    "$BT/zipalign" -f 4 "$1" "$2.aligned"
    "$BT/apksigner" sign --v4-signing-enabled false --ks signing/local-speaker.jks --ks-key-alias local \
        --ks-pass pass:android --key-pass pass:android --out "$2" "$2.aligned"
    rm "$2.aligned"
    "$BT/apksigner" verify "$2"
}
"$BT/aapt2" compile --dir overlay/res -o build/overlay-res.zip
"$BT/aapt2" link -I "$ANDROID" --manifest overlay/AndroidManifest.xml \
    --auto-add-overlay -o build/overlay.apk build/overlay-res.zip
mkdir -p module/system/product/overlay/BluetoothSpeaker
sign_apk build/overlay.apk module/system/product/overlay/BluetoothSpeaker/BluetoothSpeakerOverlay.apk
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
with ZipFile("dist/Mi10S-Bluetooth-Speaker-Magisk.zip", "w", ZIP_DEFLATED) as z:
    for p in sorted(Path("module").rglob("*")):
        if p.is_file() and not p.name.startswith("._") and not p.name.endswith(".idsig"):
            z.write(p, p.relative_to("module"))
PY
if [ -f app/AndroidManifest.xml ]; then
    rm -rf build/app-res build/classes build/dex build/app-assets
    mkdir -p build/app-res build/classes build/dex build/app-assets
    cp dist/Mi10S-Bluetooth-Speaker-Magisk.zip build/app-assets/bluetooth-speaker-module.zip
    "$BT/aapt2" compile --dir app/res -o build/app-res.zip
    "$BT/aapt2" link -I "$ANDROID" --manifest app/AndroidManifest.xml \
        --java build/app-res -A build/app-assets -o build/app.apk build/app-res.zip
    find app/src build/app-res -name '*.java' ! -name '._*' > build/sources.list
    javac -encoding UTF-8 --release 8 -classpath "$ANDROID" \
        -d build/classes @build/sources.list
    find build/classes -name '*.class' > build/classes.list
    "$BT/d8" --lib "$ANDROID" --min-api 30 --output build/dex @build/classes.list
    (cd build/dex && jar uf ../app.apk classes.dex)
    sign_apk build/app.apk dist/Bluetooth-Speaker.apk
fi
sha256sum dist/*
