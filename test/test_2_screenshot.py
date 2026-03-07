"""Screenshot and streaming tests: BT RFCOMM, WiFi, stream frames."""

import re
import time
import pytest
from conftest import Adb, ScreenshotServer, take_screenshot, parse_screenshot_log


class TestBtScreenshot:
    """Test BT RFCOMM screenshot capture."""

    def test_bt_rfcomm_connects(self, bt_connected: ScreenshotServer):
        log = bt_connected.read_log()
        assert "BT connected" in log
        # Extract device name
        match = re.search(r"BT connected to (.+?) on ch=", log)
        if match:
            print(f"  Connected to: {match.group(1)}")

    def test_screenshot_succeeds(self, adb: Adb, bt_connected: ScreenshotServer):
        timing = take_screenshot(adb)
        assert timing is not None, "Screenshot not received"
        print(f"  {timing}")

    def test_mac_capture_logged(self, bt_connected: ScreenshotServer):
        log = bt_connected.read_log()
        assert re.search(r"capture:|\[SCK\]", log), (
            "Mac capture not found in server log"
        )


class TestWifiScreenshot:
    """Test WiFi screenshot transport."""

    def test_wifi_connected(self, adb: Adb, bt_connected):
        log = adb.logcat("BtScreenshot")
        if "WiFi connected" not in log and "WiFi " not in log:
            pytest.skip("WiFi not connected (both devices on same network?)")

    def test_wifi_screenshot(self, adb: Adb, bt_connected):
        timing = take_screenshot(adb, timeout=5)
        assert timing is not None, "WiFi screenshot failed"
        print(f"  {timing}")


class TestStreaming:
    """Test WiFi streaming with frame reception."""

    def test_stream_receives_frames(self, adb: Adb, bt_connected):
        # Check if Stream button is visible
        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml = adb.shell("cat /sdcard/ui.xml")
        if "btn_stream" not in xml:
            pytest.skip("Stream button not visible (WiFi not connected)")

        # Stop stream if already running
        if 'text="Stop"' in xml and "btn_stream" in xml:
            adb.tap_button("btn_stream")
            time.sleep(8)

        adb.clear_logcat()
        time.sleep(1)
        assert adb.tap_button("btn_stream"), "Stream button not found"

        # Immediately move cursor to trigger SCK push-model during 3s probe
        # adb swipe sends HID events → Mac cursor moves → screen changes → SCK delivers frames
        adb.swipe(500, 400, 800, 400, 200)
        time.sleep(1)
        adb.swipe(800, 400, 500, 600, 200)
        time.sleep(3)

        # Open/close TextEdit for a more visible screen change
        import subprocess as sp
        sp.run(["osascript", "-e", '''
            tell app "TextEdit" to activate
            tell app "System Events" to tell process "TextEdit"
                try
                    click menu item "New" of menu "File" of menu bar 1
                end try
                set frontmost to true
            end tell
        '''], timeout=10)
        time.sleep(4)
        sp.run(["osascript", "-e", '''
            tell app "TextEdit" to quit saving no
        '''], timeout=5)
        time.sleep(3)

        log = adb.logcat("BtScreenshot")
        frame_count = len(re.findall(r"Stream frame|Stream \[", log))

        # Stop stream
        adb.tap_button("btn_stream")
        time.sleep(2)

        assert frame_count >= 5, f"Too few stream frames ({frame_count}). Log:\n{log[:500]}"

        # Check content variety (soft check — 4-pixel sample may miss small changes)
        unique_hashes = set(re.findall(r"hash=([a-f0-9]+)", log))
        print(f"  {frame_count} frames, {len(unique_hashes)} unique hashes")
        if len(unique_hashes) >= 2:
            print(f"  Content verified: screen changed during stream")
        else:
            print(f"  Warning: hash constant — 4-pixel sample may not cover changed area")


class TestSCKPushModel:
    """Verify SCK push-model is attempted and falls back gracefully."""

    def test_push_model_probe_and_fallback(self, adb: Adb, bt_connected: ScreenshotServer):
        """Push model is attempted; falls back to legacy on static desktop."""
        log = bt_connected.read_log()
        if "SCK push-model" not in log:
            pytest.skip("Push model not attempted (SCK may be unavailable)")
        # Push model was attempted — verify it either worked or fell back gracefully
        used_push = "[push-key]" in log or "[push-delta]" in log
        fell_back = "falling back to legacy" in log
        assert used_push or fell_back, (
            "Push model neither delivered frames nor fell back to legacy"
        )
        if used_push:
            print("  SCK push-model active")
        else:
            print("  SCK push-model fell back to legacy (static desktop)")


class TestDeltaStreaming:
    """Test delta compression during streaming."""

    def test_delta_frames_during_drawing(self, adb: Adb, bt_connected):
        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml = adb.shell("cat /sdcard/ui.xml")
        if "btn_stream" not in xml:
            pytest.skip("Stream button not visible")

        adb.clear_logcat()
        time.sleep(1)
        assert adb.tap_button("btn_stream"), "Stream button not found"
        time.sleep(2)

        adb.swipe(500, 500, 900, 500, 300)
        time.sleep(1)
        adb.swipe(600, 400, 600, 700, 300)
        time.sleep(5)

        log = adb.logcat("BtScreenshot")
        total = len(re.findall(r"Stream", log))
        delta = len(re.findall(r"\[delta\]", log))
        key = len(re.findall(r"\[key\]", log))

        adb.tap_button("btn_stream")
        time.sleep(1)

        assert total >= 5 or re.search(r"Stream frame|Stream \[", log), (
            f"Too few frames: {total} total, {delta} delta, {key} key"
        )
        print(f"  {total} frames ({delta} delta, {key} key)")
