#!/bin/bash
# End-to-end test: builds, installs, starts server, triggers screenshot, verifies
# Requires: tablet connected via USB (adb), Bluetooth paired with Mac
set -euo pipefail
cd "$(dirname "$0")/.."

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
PASS=0
FAIL=0

pass() { echo -e "${GREEN}PASS${NC}: $1"; ((PASS++)) || true; }
fail() { echo -e "${RED}FAIL${NC}: $1"; ((FAIL++)) || true; }
info() { echo -e "${YELLOW}INFO${NC}: $1"; }

# ---- Step 1: Build ----
info "Building Android APK..."
if ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}" \
    ./gradlew assembleDebug -q 2>/dev/null; then
    pass "Android APK builds"
else
    fail "Android APK build"
fi

info "Building mouse-pos helper..."
if cd test && swiftc mouse-pos.swift -o mouse-pos 2>/dev/null; then
    pass "mouse-pos builds"
else
    fail "mouse-pos build"
fi
cd ..

info "Building Mac screenshot-server..."
if [ ! -f mac/screenshot-server ] || [ mac/screenshot-server.swift -nt mac/screenshot-server ]; then
    if cd mac && swiftc -framework IOBluetooth -framework Foundation -framework ImageIO \
        screenshot-server.swift -o screenshot-server 2>/dev/null; then
        pass "Mac binary builds"
    else
        fail "Mac binary build"
    fi
    cd ..
else
    pass "Mac binary up to date"
fi


# ---- Step 2: Unit tests ----
info "Running unit tests..."
if ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}" \
    ./gradlew test -q 2>/dev/null; then
    pass "Unit tests"
else
    fail "Unit tests"
fi

# ---- Step 3: Check adb device ----
info "Checking for connected device..."
DEVICE_COUNT=$(adb devices 2>/dev/null | grep -c "device$" || true)
if [ "$DEVICE_COUNT" -ge 1 ]; then
    DEVICE=$(adb devices | grep "device$" | head -1 | cut -f1)
    pass "ADB device connected: $DEVICE"
else
    info "No ADB device connected — skipping device tests"
    echo ""
    echo -e "Results: ${GREEN}${PASS} passed${NC}, ${RED}${FAIL} failed${NC}"
    exit $FAIL
fi

# ---- Step 4: Install APK ----
info "Installing APK..."
if adb install -r app/build/outputs/apk/debug/app-debug.apk 2>/dev/null | grep -q "Success"; then
    pass "APK installed"
else
    fail "APK install"
fi

# ---- Step 5: Launch app ----
info "Launching TabletPen app..."
adb shell am start -n com.hid.tabletpen/.MainActivity 2>/dev/null
sleep 5
# Check if the activity exists (may be paused if tablet screen is on different app)
ACTIVITY=$(adb shell "dumpsys activity activities | grep -i tabletpen | head -1" 2>/dev/null || true)
if echo "$ACTIVITY" | grep -qi "tabletpen"; then
    pass "App launched"
else
    fail "App launch"
fi
# Bring app to foreground
adb shell am start -n com.hid.tabletpen/.MainActivity 2>/dev/null
sleep 2

# ---- Step 6: Start Mac server ----
info "Starting screenshot server..."
SERVER_LOG=$(mktemp)
./mac/screenshot-server > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!
cleanup() {
    kill $SERVER_PID 2>/dev/null || true
    rm -f "$SERVER_LOG"
}
trap cleanup EXIT

# Helper: get current Mac mouse cursor position
get_mouse_pos() {
    ./test/mouse-pos 2>/dev/null || echo "0,0"
}

# Wait for BT connection (up to 60s)
info "Waiting for Bluetooth RFCOMM connection (up to 60s)..."
BT_CONNECTED=false
for i in $(seq 1 60); do
    if grep -q "BT connected" "$SERVER_LOG" 2>/dev/null; then
        BT_CONNECTED=true
        break
    fi
    sleep 1
done

if $BT_CONNECTED; then
    DEVICE_NAME=$(grep "BT connected" "$SERVER_LOG" | head -1 | sed 's/.*BT connected to //' | sed 's/ on ch=.*//')
    pass "BT RFCOMM connected to: $DEVICE_NAME"
else
    fail "BT RFCOMM connection (timeout)"
    echo ""
    echo "Server log:"
    cat "$SERVER_LOG"
    echo ""
    echo -e "Results: ${GREEN}${PASS} passed${NC}, ${RED}${FAIL} failed${NC}"
    exit 1
fi

# ---- Step 7: Trigger screenshot ----
info "Triggering screenshot via adb..."
adb logcat -c 2>/dev/null
# Find Screenshot button bounds from UI dump and tap center
adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
BOUNDS=$(adb shell "cat /sdcard/ui.xml" 2>/dev/null | tr '>' '\n' | grep 'btn_screenshot' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
if [ -n "$BOUNDS" ]; then
    # Parse [left,top][right,bottom] → tap center
    X1=$(echo "$BOUNDS" | grep -o '\[[0-9]*,' | head -1 | tr -d '[,')
    Y1=$(echo "$BOUNDS" | grep -o ',[0-9]*\]' | head -1 | tr -d ',]')
    X2=$(echo "$BOUNDS" | grep -o '\[[0-9]*,' | tail -1 | tr -d '[,')
    Y2=$(echo "$BOUNDS" | grep -o ',[0-9]*\]' | tail -1 | tr -d ',]')
    CX=$(( (X1 + X2) / 2 ))
    CY=$(( (Y1 + Y2) / 2 ))
    info "Tapping Screenshot button at ($CX, $CY)"
    adb shell input tap $CX $CY
else
    info "Button not found via UI dump, tapping approximate location"
    adb shell input tap 987 114
fi
sleep 10  # wait for BT screenshot transfer

# ---- Step 8: Check screenshot result ----
LOGCAT=$(adb logcat -d -s BtScreenshot 2>/dev/null)
if echo "$LOGCAT" | grep -qE "BT .+KB|WiFi .+KB"; then
    TIMING=$(echo "$LOGCAT" | grep -oE "(BT|WiFi) .+total:[0-9]+ms" | tail -1)
    pass "Screenshot received: $TIMING"
elif echo "$LOGCAT" | grep -q "screenshot failed"; then
    fail "Screenshot failed (see logcat)"
else
    info "Screenshot result unclear — check manually"
    fail "Screenshot verification"
fi

# Check Mac server log for capture
if grep -q "capture:" "$SERVER_LOG" 2>/dev/null; then
    CAPTURE=$(grep "capture:" "$SERVER_LOG" | tail -1)
    pass "Mac captured: $CAPTURE"
else
    fail "Mac capture not found in server log"
fi

# ---- Step 9: Test finger input → Mac cursor moves ----
info "Testing finger input → Mac mouse cursor..."

# Record mouse position before
POS_BEFORE=$(get_mouse_pos)
info "Mouse position before: $POS_BEFORE"

# Simulate finger drag on the drawing area via adb
# Swipe across the draw pad (below toolbar, ~y=400 to avoid buttons)
adb shell input swipe 400 400 900 400 500   # horizontal swipe, 500ms
sleep 1
adb shell input swipe 600 300 600 700 500   # vertical swipe, 500ms
sleep 1

# Record mouse position after
POS_AFTER=$(get_mouse_pos)
info "Mouse position after: $POS_AFTER"

if [ "$POS_BEFORE" != "$POS_AFTER" ]; then
    pass "Mac cursor moved: $POS_BEFORE → $POS_AFTER"
else
    fail "Mac cursor did not move (HID input not reaching Mac)"
    info "Check: Is tablet paired as BT HID device? Is pen/mouse mode active?"
fi

# ---- Results ----
echo ""
echo "=============================="
echo -e "Results: ${GREEN}${PASS} passed${NC}, ${RED}${FAIL} failed${NC}"
echo "=============================="
exit $FAIL
