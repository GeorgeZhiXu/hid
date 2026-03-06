#!/bin/bash
# Run instrumented tests on connected device
# Usage: ./test/run-device-tests.sh
set -euo pipefail
cd "$(dirname "$0")/.."

ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"

echo "Checking device..."
$ADB devices | grep -q "device$" || { echo "No device connected"; exit 1; }

echo "Building test APKs..."
ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}" \
    ./gradlew assembleDebug assembleDebugAndroidTest -q 2>/dev/null

echo "Installing APKs..."
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "Running instrumented tests..."
$ADB shell am instrument -w com.hid.tabletpen.test/androidx.test.runner.AndroidJUnitRunner
