#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p toolchain
cd toolchain
if [ ! -f jdk/bin/javac ]; then
    curl -fsSL --retry 1 --connect-timeout 20 \
        https://download.java.net/java/GA/jdk17/0d483333a00540d886896bac774ff48b/35/GPL/openjdk-17_linux-x64_bin.tar.gz -o jdk.tar.gz
    mkdir -p jdk
    tar -xzf jdk.tar.gz -C jdk --strip-components=1
    rm jdk.tar.gz
fi
if [ ! -f android-11/aapt2 ]; then
    curl -fsSL --retry 1 --connect-timeout 20 \
        https://dl.google.com/android/repository/build-tools_r30.0.3-linux.zip -o build-tools.zip
    unzip -oq build-tools.zip </dev/null
    rm build-tools.zip
fi
if [ ! -f android-30/android.jar ]; then
    curl -fsSL --retry 1 --connect-timeout 20 \
        https://dl.google.com/android/repository/platform-30_r03.zip -o platform.zip
    mkdir -p platform-stage
    unzip -oq platform.zip -d platform-stage </dev/null
    mv platform-stage/android-11 android-30
    rmdir platform-stage
    rm platform.zip
fi
