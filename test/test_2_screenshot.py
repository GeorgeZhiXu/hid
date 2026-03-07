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
        time.sleep(15)  # WiFi TCP connection + first frames need time

        # Draw while streaming to generate screen changes
        adb.swipe(500, 500, 900, 500, 300)
        time.sleep(3)
        adb.swipe(600, 400, 600, 700, 300)
        time.sleep(5)

        log = adb.logcat("BtScreenshot")
        frame_count = len(re.findall(r"Stream frame|Stream \[", log))

        # Stop stream
        adb.tap_button("btn_stream")
        time.sleep(2)

        assert frame_count >= 1, f"No stream frames received. Log:\n{log[:500]}"
        print(f"  {frame_count} frames received")

        # Check content variety
        unique_hashes = len(set(re.findall(r"hash=([a-f0-9]+)", log)))
        if unique_hashes >= 2:
            print(f"  {unique_hashes} unique frame hashes (content changed)")


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
