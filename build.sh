#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
TOOLS=${TOOLS:-../toolchain}
export JAVA_HOME="$(cd "$TOOLS/jdk" && pwd)"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$(cd "$TOOLS/android-11" && pwd)"
ANDROID="$(cd "$TOOLS/android-30" && pwd)/android.jar"
mkdir -p build dist signing
if [ ! -f signing/local-speaker.jks ]; then
    keytool -genkeypair -keystore signing/local-speaker.jks -storepass android \
        -keypass android -alias local -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Local Bluetooth Speaker"
fi
for generated in build/app-res build/classes build/dex build/app-assets; do
    if [ -d "$generated" ]; then rm -r "$generated"; fi
done
mkdir -p build/app-res build/classes build/dex build/app-assets
"$BT/aapt2" compile --dir overlay/res -o build/overlay-res.zip
"$BT/aapt2" link -I "$ANDROID" --manifest overlay/standalone-manifest.xml \
    --auto-add-overlay -o build/overlay.apk build/overlay-res.zip
"$BT/zipalign" -f 4 build/overlay.apk build/overlay-aligned.apk
"$BT/apksigner" sign --v4-signing-enabled false --ks signing/local-speaker.jks --ks-key-alias local \
    --ks-pass pass:android --key-pass pass:android \
    --out build/app-assets/legacy-bluetooth-resources.apk build/overlay-aligned.apk
"$BT/apksigner" verify build/app-assets/legacy-bluetooth-resources.apk
"$BT/aapt2" compile --dir app/res -o build/app-res.zip
"$BT/aapt2" link -I "$ANDROID" --manifest app/AndroidManifest.xml \
    --java build/app-res -A build/app-assets -o build/app.apk build/app-res.zip
find app/src build/app-res -name '*.java' ! -name '._*' > build/sources.list
javac -encoding UTF-8 --release 8 -classpath "$ANDROID" -d build/classes @build/sources.list
find build/classes -name '*.class' > build/classes.list
"$BT/d8" --lib "$ANDROID" --min-api 30 --output build/dex @build/classes.list
(cd build/dex && jar uf ../app.apk classes.dex)
"$BT/zipalign" -f 4 build/app.apk build/app-aligned.apk
"$BT/apksigner" sign --v4-signing-enabled false --ks signing/local-speaker.jks --ks-key-alias local \
    --ks-pass pass:android --key-pass pass:android --out dist/Android-A2DP-Sink.apk build/app-aligned.apk
"$BT/apksigner" verify dist/Android-A2DP-Sink.apk
sha256sum dist/Android-A2DP-Sink.apk
