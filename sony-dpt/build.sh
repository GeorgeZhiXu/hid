#!/bin/bash
# Cross-compile tabletpen for Sony DPT-RP1 (ARM 32-bit Android)
#
# Option 1: Android NDK (recommended)
#   Install: sdkmanager "ndk;27.0.12077973"
#   Then run this script.
#
# Option 2: ARM cross-compiler
#   brew install arm-linux-gnueabihf-binutils
#   arm-linux-gnueabihf-gcc -static -o tabletpen tabletpen.c -lm
#
# Option 3: Docker
#   docker run --rm -v $PWD:/src -w /src arm32v7/gcc gcc -static -o tabletpen tabletpen.c -lm

cd "$(dirname "$0")"

# Try Android NDK first
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK_DIR=$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -1)

if [ -n "$NDK_DIR" ]; then
    CC="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi21-clang"
    if [ -x "$CC" ]; then
        echo "Using NDK: $NDK_DIR"
        $CC -static -O2 -o tabletpen tabletpen.c -lm
        echo "Built: tabletpen ($(file tabletpen | grep -o 'ARM.*'))"
        exit 0
    fi
fi

# Try system ARM cross-compiler
if command -v arm-linux-gnueabihf-gcc &>/dev/null; then
    echo "Using system ARM cross-compiler"
    arm-linux-gnueabihf-gcc -static -O2 -o tabletpen tabletpen.c -lm
    echo "Built: tabletpen"
    exit 0
fi

echo "ERROR: No ARM cross-compiler found."
echo
echo "Install Android NDK:"
echo "  sdkmanager 'ndk;27.0.12077973'"
echo
echo "Or install ARM cross-compiler:"
echo "  brew install arm-linux-gnueabihf-binutils"
echo
echo "Or use Docker:"
echo "  docker run --rm -v \$PWD:/src -w /src arm32v7/gcc gcc -static -o tabletpen tabletpen.c -lm"
exit 1
