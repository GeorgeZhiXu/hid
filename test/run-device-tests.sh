#!/bin/bash
# Run instrumented tests on connected device
# Usage: ./test/run-device-tests.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || echo /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home)}"

echo "Checking device..."
adb devices | grep -q "device$" || { echo "No device connected"; exit 1; }

echo "Building and running instrumented tests..."
./gradlew connectedDebugAndroidTest "$@"
