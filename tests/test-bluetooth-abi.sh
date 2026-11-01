#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/abi-tests
javac --release 8 -d build/abi-tests \
    app/src/local/bluetooth/speaker/BluetoothInvocation.java \
    app/src/local/bluetooth/speaker/AdaptationPlan.java \
    tests/abi/android/content/AttributionSource.java \
    tests/abi/com/android/modules/utils/SynchronousResultReceiver.java \
    tests/abi/local/bluetooth/speaker/BluetoothInvocationTest.java \
    tests/abi/local/bluetooth/speaker/AdaptationPlanTest.java
java -cp build/abi-tests local.bluetooth.speaker.BluetoothInvocationTest
java -cp build/abi-tests local.bluetooth.speaker.AdaptationPlanTest
