#!/bin/bash
# End-to-end test for TabletPen: HID input, BT screenshot, WiFi screenshot, streaming
# Requires: tablet connected via USB (adb) + Bluetooth paired with Mac
# IMPORTANT: Do NOT touch the Mac mouse/trackpad during the test!
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

get_mouse_pos() {
    ./test/mouse-pos 2>/dev/null || echo "0,0"
}

# Tap a button by resource ID using uiautomator
tap_button() {
    local btn_id="$1"
    adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
    local bounds
    bounds=$(adb shell "cat /sdcard/ui.xml" 2>/dev/null | tr '>' '\n' | grep "$btn_id" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
    if [ -n "$bounds" ]; then
        local x1 y1 x2 y2 cx cy
        x1=$(echo "$bounds" | grep -o '\[[0-9]*,' | head -1 | tr -d '[,')
        y1=$(echo "$bounds" | grep -o ',[0-9]*\]' | head -1 | tr -d ',]')
        x2=$(echo "$bounds" | grep -o '\[[0-9]*,' | tail -1 | tr -d '[,')
        y2=$(echo "$bounds" | grep -o ',[0-9]*\]' | tail -1 | tr -d ',]')
        cx=$(( (x1 + x2) / 2 ))
        cy=$(( (y1 + y2) / 2 ))
        adb shell input tap $cx $cy
        return 0
    fi
    return 1
}

# Parse --phase argument for selective testing
RUN_PHASES="${1:-}"  # e.g., "9,10,11" or empty for all
should_skip() {
    local phase=$1
    [ -z "$RUN_PHASES" ] && return 1  # no filter = don't skip
    echo ",$RUN_PHASES," | grep -q ",$phase," && return 1  # in list = don't skip
    return 0  # not in list = skip
}

SERVER_PID=""
cleanup() {
    [ -n "$SERVER_PID" ] && kill $SERVER_PID 2>/dev/null || true
    rm -f "$SERVER_LOG" 2>/dev/null || true
}
trap cleanup EXIT
SERVER_LOG=$(mktemp)

echo "============================================"
echo "  TabletPen E2E Test"
echo "  DO NOT touch the Mac mouse during the test"
echo "============================================"
echo ""

# ==== PHASE 1:
if should_skip 1; then info "Skipping phase 1"; else

info "Building Android APK..."
if ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}" \
    ./gradlew assembleDebug -q 2>/dev/null; then
    pass "Android APK builds"
else
    fail "Android APK build"
fi

info "Building mouse-pos helper..."
if [ ! -f test/mouse-pos ] || [ test/mouse-pos.swift -nt test/mouse-pos ]; then
    if cd test && swiftc mouse-pos.swift -o mouse-pos 2>/dev/null; then pass "mouse-pos builds"; else fail "mouse-pos build"; fi
    cd ..
else
    pass "mouse-pos up to date"
fi

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

# ==== PHASE 2:
fi  # end phase 1

if should_skip 2; then info "Skipping phase 2"; else

info "Running unit tests..."
if ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}" \
    ./gradlew test -q 2>/dev/null; then
    pass "Unit tests"
else
    fail "Unit tests"
fi

# ==== PHASE 3:
fi  # end phase 2

if should_skip 3; then info "Skipping phase 3"; else

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

info "Installing APK..."
if adb install -r app/build/outputs/apk/debug/app-debug.apk 2>/dev/null | grep -q "Success"; then
    pass "APK installed"
else
    fail "APK install"
fi

info "Launching TabletPen app..."
adb shell am start -n com.hid.tabletpen/.MainActivity 2>/dev/null
sleep 5
ACTIVITY=$(adb shell "dumpsys activity activities | grep -i tabletpen | head -1" 2>/dev/null || true)
if echo "$ACTIVITY" | grep -qi "tabletpen"; then
    pass "App launched"
else
    fail "App launch"
fi
adb shell am start -n com.hid.tabletpen/.MainActivity 2>/dev/null
sleep 2

# ==== PHASE 4:
fi  # end phase 3

if should_skip 4; then info "Skipping phase 4"; else
# Test finger input moves Mac cursor (tests full HID round-trip)

info "--- HID Input Test ---"
# Reset cursor away from screen edge (previous test may have pushed it to corner)
info "Resetting cursor position..."
adb shell input swipe 800 600 400 400 300
sleep 2

info "Recording mouse position (DO NOT touch mouse!)..."
POS_BEFORE=$(get_mouse_pos)
info "Mouse position before: $POS_BEFORE"

# Simulate finger swipe on the draw pad area
info "Sending finger swipe via adb..."
adb shell input swipe 500 500 1200 500 500   # large horizontal swipe
sleep 2
adb shell input swipe 800 300 800 800 500   # large vertical swipe
sleep 2

POS_AFTER=$(get_mouse_pos)
info "Mouse position after: $POS_AFTER"

if [ "$POS_BEFORE" != "$POS_AFTER" ]; then
    pass "HID finger input: cursor moved $POS_BEFORE → $POS_AFTER"
else
    fail "HID finger input: cursor did not move"
    info "Check: Is tablet BT HID-paired with this Mac?"
fi

# Test: single finger tap → click (cursor should NOT move)
info "Testing single finger tap (click, not drag)..."
adb shell input swipe 800 600 600 500 300  # move cursor to known position
sleep 1
POS_TAP_BEFORE=$(get_mouse_pos)
adb shell input tap 700 500  # quick tap on draw pad
sleep 1
POS_TAP_AFTER=$(get_mouse_pos)
if [ "$POS_TAP_BEFORE" = "$POS_TAP_AFTER" ]; then
    pass "Single finger tap: cursor stayed (click detected, not drag)"
else
    fail "Single finger tap: cursor moved $POS_TAP_BEFORE → $POS_TAP_AFTER (should be click, not drag)"
fi

# ==== PHASE 5:
fi  # end phase 4

if should_skip 5; then info "Skipping phase 5"; else

info "--- BT Screenshot Test ---"
info "Starting screenshot server..."
./mac/screenshot-server > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

# Wait for BT RFCOMM connection
info "Waiting for BT RFCOMM connection (up to 60s)..."
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
    echo "Server log:"
    cat "$SERVER_LOG"
    echo ""
    echo -e "Results: ${GREEN}${PASS} passed${NC}, ${RED}${FAIL} failed${NC}"
    exit 1
fi

# Trigger BT screenshot
info "Triggering screenshot..."
adb logcat -c 2>/dev/null
sleep 1
if tap_button "btn_screenshot"; then
    info "Tapped Screenshot button"
else
    info "Button not found, tapping fallback location"
    adb shell input tap 987 114
fi
sleep 12  # BT transfer can be slow

LOGCAT=$(adb logcat -d -s BtScreenshot 2>/dev/null)
if echo "$LOGCAT" | grep -qE "BT .+KB.*total:" ; then
    TIMING=$(echo "$LOGCAT" | grep -oE "BT .+total:[0-9]+ms" | tail -1)
    pass "BT screenshot: $TIMING"
elif echo "$LOGCAT" | grep -qE "WiFi .+KB.*total:"; then
    TIMING=$(echo "$LOGCAT" | grep -oE "WiFi .+total:[0-9]+ms" | tail -1)
    pass "WiFi screenshot: $TIMING"
else
    fail "Screenshot not received (check logcat)"
fi

if grep -q "capture:" "$SERVER_LOG" 2>/dev/null; then
    CAPTURE=$(grep "capture:" "$SERVER_LOG" | tail -1)
    pass "Mac capture: $CAPTURE"
else
    fail "Mac capture not found in server log"
fi

# ==== PHASE 6:
fi  # end phase 5

if should_skip 6; then info "Skipping phase 6"; else

info "--- WiFi Screenshot Test ---"
# Check if the first screenshot went over WiFi (already proven) or check full logcat
FULL_LOGCAT=$(adb logcat -d -s BtScreenshot 2>/dev/null)
WIFI_CONNECTED=false
if echo "$FULL_LOGCAT" | grep -q "WiFi connected\|WiFi .*KB.*total:"; then
    WIFI_CONNECTED=true
    pass "WiFi transport connected"

    # Take another screenshot — should go over WiFi
    adb logcat -c 2>/dev/null
    sleep 1
    tap_button "btn_screenshot" 2>/dev/null || adb shell input tap 987 114
    sleep 5

    LOGCAT2=$(adb logcat -d -s BtScreenshot 2>/dev/null)
    if echo "$LOGCAT2" | grep -qE "WiFi .+KB.*total:"; then
        TIMING=$(echo "$LOGCAT2" | grep -oE "WiFi .+total:[0-9]+ms" | tail -1)
        pass "WiFi screenshot: $TIMING"
    else
        fail "WiFi screenshot: transfer not over WiFi"
    fi
else
    fail "WiFi not connected (both devices on same network?)"
fi

# ==== PHASE 7:
fi  # end phase 6

if should_skip 7; then info "Skipping phase 7"; else

info "--- WiFi Stream Test ---"
if $WIFI_CONNECTED; then
    adb logcat -c 2>/dev/null
    sleep 1
    if tap_button "btn_stream" 2>/dev/null; then
        info "Tapped Stream button — waiting for frames..."
        sleep 8

        STREAM_LOG=$(adb logcat -d -s BtScreenshot 2>/dev/null)
        if echo "$STREAM_LOG" | grep -q "Stream frame"; then
            FRAME_COUNT=$(echo "$STREAM_LOG" | grep -c "Stream frame" || true)
            pass "WiFi stream: received $FRAME_COUNT frames"
        else
            fail "WiFi stream: no frames received"
        fi

        # Stop stream
        tap_button "btn_stream" 2>/dev/null || true
        sleep 1
    else
        fail "Stream button not found (WiFi connected but button not visible)"
    fi
else
    fail "WiFi not connected — stream test skipped"
fi

# ==== PHASE 8:
fi  # end phase 7

if should_skip 8; then info "Skipping phase 8"; else

info "--- Edge Case: Server killed + screenshot fails gracefully ---"
# Kill the screenshot server
kill $SERVER_PID 2>/dev/null || true
wait $SERVER_PID 2>/dev/null || true
# Parse --phase argument for selective testing
RUN_PHASES="${1:-}"  # e.g., "9,10,11" or empty for all
should_skip() {
    local phase=$1
    [ -z "$RUN_PHASES" ] && return 1  # no filter = don't skip
    echo ",$RUN_PHASES," | grep -q ",$phase," && return 1  # in list = don't skip
    return 0  # not in list = skip
}

SERVER_PID=""
sleep 3

# Try screenshot without server — should fail gracefully
adb logcat -c 2>/dev/null
sleep 1
tap_button "btn_screenshot" 2>/dev/null || adb shell input tap 987 114
sleep 5

EDGE_LOG=$(adb logcat -d -s BtScreenshot 2>/dev/null)
if echo "$EDGE_LOG" | grep -qE "screenshot failed|Broken pipe|Mac not connected|Connecting to Mac"; then
    pass "Screenshot without server: app handled gracefully"
else
    # The button may have shown a toast (no logcat for toast).
    # Verify app didn't crash.
    ACTIVITY2=$(adb shell "dumpsys activity activities | grep -i tabletpen | head -1" 2>/dev/null || true)
    if echo "$ACTIVITY2" | grep -qi "tabletpen"; then
        pass "Screenshot without server: app still running (no crash)"
    else
        fail "Screenshot without server: app may have crashed"
    fi
fi

info "--- Edge Case: Server restart + reconnection ---"
# Restart server — should reconnect
./mac/screenshot-server > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

RECONNECTED=false
for i in $(seq 1 60); do
    if grep -q "BT connected" "$SERVER_LOG" 2>/dev/null; then
        RECONNECTED=true
        break
    fi
    sleep 1
done

if $RECONNECTED; then
    pass "Server restart: BT RFCOMM reconnected"

    # Take a screenshot — first attempt may hit stale socket, retry if needed
    for attempt in 1 2; do
        adb logcat -c 2>/dev/null
        sleep 5
        tap_button "btn_screenshot" 2>/dev/null || adb shell input tap 987 114
        sleep 10

        RECON_LOG=$(adb logcat -d -s BtScreenshot 2>/dev/null)
        if echo "$RECON_LOG" | grep -qE "(BT|WiFi) .+KB.*total:"; then
            TIMING=$(echo "$RECON_LOG" | grep -oE "(BT|WiFi) .+total:[0-9]+ms" | tail -1)
            pass "Screenshot after reconnect: $TIMING"
            break
        elif [ "$attempt" = "2" ]; then
            fail "Screenshot after reconnect: transfer failed on both attempts"
        else
            info "Attempt 1 hit stale socket, retrying..."
        fi
    done
else
    fail "Server restart: BT RFCOMM did not reconnect within 60s"
fi


# ==== PHASE 9:
fi  # end phase 8

if should_skip 9; then info "Skipping phase 9"; else

info "--- Connection: Bluetooth toggle reconnect ---"
info "Disabling tablet Bluetooth..."
adb shell cmd bluetooth_manager disable 2>/dev/null || adb shell svc bluetooth disable 2>/dev/null
sleep 5

# Verify HID disconnected
POS_BT_OFF=$(get_mouse_pos)
adb shell input swipe 600 500 900 500 300 2>/dev/null
sleep 2
POS_BT_OFF2=$(get_mouse_pos)
if [ "$POS_BT_OFF" = "$POS_BT_OFF2" ]; then
    pass "BT off: HID disconnected (cursor didn't move)"
else
    info "Cursor still moved with BT off — may be residual"
fi

info "Re-enabling tablet Bluetooth..."
adb shell cmd bluetooth_manager enable 2>/dev/null || adb shell svc bluetooth enable 2>/dev/null
sleep 15

# Bring app to foreground and give HID time to re-register + auto-connect
adb shell am start -n com.hid.tabletpen/.MainActivity 2>/dev/null
sleep 10

# Verify HID reconnects
info "Checking HID reconnect after BT toggle..."
adb shell input swipe 800 600 400 400 300
sleep 2
POS_BT_ON=$(get_mouse_pos)
adb shell input swipe 500 500 1000 500 500
sleep 2
POS_BT_ON2=$(get_mouse_pos)
if [ "$POS_BT_ON" != "$POS_BT_ON2" ]; then
    pass "BT toggle: HID reconnected — cursor moved $POS_BT_ON → $POS_BT_ON2"
else
    fail "BT toggle: HID did not reconnect after BT re-enable"
fi

# Check RFCOMM reconnect — server needs time to re-discover after BT toggle
info "Waiting for RFCOMM reconnect after BT toggle (up to 20s)..."
BT_TOGGLE_OK=false
for i in $(seq 1 20); do
    # Count connections since the server started (this run)
    RFCOMM_COUNT=$(grep -c "BT connected" "$SERVER_LOG" 2>/dev/null || true)
    if [ "$RFCOMM_COUNT" -ge 2 ]; then
        BT_TOGGLE_OK=true
        break
    fi
    sleep 1
done
if $BT_TOGGLE_OK; then
    pass "BT toggle: RFCOMM reconnected"
else
    fail "BT toggle: RFCOMM did not reconnect within 20s"
fi

# ==== PHASE 10:
fi  # end phase 9

if should_skip 10; then info "Skipping phase 10"; else

# Recovery: ensure app is running and HID has time to settle
adb shell am start -n com.hid.tabletpen/.MainActivity 2>/dev/null
sleep 5

info "--- Connection: App kill + reconnect ---"
info "Force-stopping TabletPen app..."
adb shell am force-stop com.hid.tabletpen 2>/dev/null
sleep 3

info "Relaunching TabletPen..."
adb shell am start -n com.hid.tabletpen/.MainActivity 2>/dev/null
sleep 20  # HID registration + auto-connect takes time after fresh launch on Boox

# Verify HID reconnects
adb shell input swipe 800 600 400 400 300
sleep 2
POS_KILL1=$(get_mouse_pos)
adb shell input swipe 500 500 1000 500 500
sleep 2
POS_KILL2=$(get_mouse_pos)
if [ "$POS_KILL1" != "$POS_KILL2" ]; then
    pass "App kill: HID reconnected — cursor moved $POS_KILL1 → $POS_KILL2"
else
    fail "App kill: HID did not reconnect after app relaunch"
fi

# Verify RFCOMM reconnects (wait up to 30s)
info "Waiting for RFCOMM reconnect after app kill (up to 30s)..."
APP_KILL_RFCOMM=false
PREV_RFCOMM=$(grep -c "BT connected" "$SERVER_LOG" 2>/dev/null || true)
for i in $(seq 1 30); do
    CUR_RFCOMM=$(grep -c "BT connected" "$SERVER_LOG" 2>/dev/null || true)
    if [ "$CUR_RFCOMM" -gt "$PREV_RFCOMM" ]; then
        APP_KILL_RFCOMM=true
        break
    fi
    sleep 1
done
if $APP_KILL_RFCOMM; then
    pass "App kill: RFCOMM reconnected"
else
    fail "App kill: RFCOMM did not reconnect within 30s"
fi

# ==== PHASE 11:
fi  # end phase 10

if should_skip 11; then info "Skipping phase 11"; else

info "--- Connection: Sleep/wake cycle ---"
info "Putting tablet to sleep..."
adb shell input keyevent POWER
sleep 8

info "Waking tablet..."
adb shell input keyevent POWER
sleep 3
# Unlock screen (swipe up) - may not be needed if no lock screen
adb shell input swipe 500 1500 500 500 300
sleep 5
# Bring app to foreground
adb shell am start -n com.hid.tabletpen/.MainActivity 2>/dev/null
sleep 8

# Verify HID reconnects after wake
adb shell input swipe 800 600 400 400 300
sleep 2
POS_WAKE1=$(get_mouse_pos)
adb shell input swipe 500 500 1000 500 500
sleep 2
POS_WAKE2=$(get_mouse_pos)
if [ "$POS_WAKE1" != "$POS_WAKE2" ]; then
    pass "Sleep/wake: HID reconnected — cursor moved $POS_WAKE1 → $POS_WAKE2"
else
    fail "Sleep/wake: HID did not reconnect after wake"
fi

# Verify screenshot works after wake
adb logcat -c 2>/dev/null
sleep 2
for attempt in 1 2; do
    tap_button "btn_screenshot" 2>/dev/null || adb shell input tap 987 114
    sleep 10
    WAKE_LOG=$(adb logcat -d -s BtScreenshot 2>/dev/null)
    if echo "$WAKE_LOG" | grep -qE "(BT|WiFi) .+KB.*total:"; then
        TIMING=$(echo "$WAKE_LOG" | grep -oE "(BT|WiFi) .+total:[0-9]+ms" | tail -1)
        pass "Sleep/wake: screenshot works — $TIMING"
        break
    elif [ "$attempt" = "2" ]; then
        fail "Sleep/wake: screenshot failed after wake"
    else
        adb logcat -c 2>/dev/null
        sleep 3
    fi
done

# ==== PHASE 12:
fi  # end phase 11

if should_skip 12; then info "Skipping phase 12"; else

info "--- Stress: Rapid screenshot requests ---"
adb logcat -c 2>/dev/null
STRESS_FAILURES=0
STRESS_TOTAL=5
for i in $(seq 1 $STRESS_TOTAL); do
    tap_button "btn_screenshot" 2>/dev/null || adb shell input tap 987 114
    sleep 3  # minimal wait between requests
done
sleep 10  # wait for all to complete

STRESS_LOG=$(adb logcat -d -s BtScreenshot 2>/dev/null)
STRESS_OK=$(echo "$STRESS_LOG" | grep -cE "(BT|WiFi) .+KB.*total:" || true)
STRESS_ERR=$(echo "$STRESS_LOG" | grep -c "screenshot failed" || true)

if [ "$STRESS_OK" -ge 3 ]; then
    pass "Stress test: $STRESS_OK/$STRESS_TOTAL screenshots succeeded, $STRESS_ERR failed"
else
    fail "Stress test: only $STRESS_OK/$STRESS_TOTAL screenshots succeeded"
fi

# Verify app didn't crash
ACTIVITY_STRESS=$(adb shell "dumpsys activity activities | grep -i tabletpen | head -1" 2>/dev/null || true)
if echo "$ACTIVITY_STRESS" | grep -qi "tabletpen"; then
    pass "Stress test: app still running"
else
    fail "Stress test: app crashed"
fi

# ==== PHASE 13:
fi  # end phase 12

if should_skip 13; then info "Skipping phase 13"; else

info "--- Latency: HID input response time ---"
# Measure time between sending adb input and detecting cursor movement
# This measures the full pipeline: adb→Android→DrawPadView→BT HID→Mac cursor
adb shell input swipe 800 600 400 400 300  # reset cursor
sleep 2

LATENCY_BEFORE=$(get_mouse_pos)
LATENCY_START=$(python3 -c "import time; print(int(time.time()*1000))")
adb shell input swipe 500 500 900 500 200  # fast swipe, 200ms
# Poll for cursor change
LATENCY_DETECTED=false
for i in $(seq 1 20); do
    LATENCY_NOW=$(get_mouse_pos)
    if [ "$LATENCY_NOW" != "$LATENCY_BEFORE" ]; then
        LATENCY_END=$(python3 -c "import time; print(int(time.time()*1000))")
        LATENCY_MS=$((LATENCY_END - LATENCY_START))
        LATENCY_DETECTED=true
        break
    fi
    sleep 0.1
done

if $LATENCY_DETECTED; then
    if [ "$LATENCY_MS" -lt 2000 ]; then
        pass "HID latency: cursor responded in ${LATENCY_MS}ms"
    else
        fail "HID latency: ${LATENCY_MS}ms (>2s, too slow)"
    fi
else
    fail "HID latency: cursor never moved"
fi

fi  # end last phase

# ==== RESULTS ====
echo ""
echo "=============================="
echo -e "Results: ${GREEN}${PASS} passed${NC}, ${RED}${FAIL} failed${NC}"
echo "=============================="
exit $FAIL
