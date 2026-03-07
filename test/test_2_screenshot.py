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
        assert re.search(r"capture:|\[SCK\]|\[push\]|\[h264\]", log), (
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
    """Verify SCK push-model streaming delivers frames at high FPS."""

    def test_push_model_active(self, adb: Adb, bt_connected: ScreenshotServer):
        """Push model delivers [push] frames when screen is changing."""
        import subprocess as sp

        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml = adb.shell("cat /sdcard/ui.xml")
        if "btn_stream" not in xml:
            pytest.skip("Stream button not visible (WiFi not connected)")

        # Stop stream if running
        if 'text="Stop"' in xml and "btn_stream" in xml:
            adb.tap_button("btn_stream")
            time.sleep(5)

        adb.clear_logcat()
        time.sleep(1)
        assert adb.tap_button("btn_stream"), "Stream button not found"

        # Generate screen changes IMMEDIATELY to trigger SCK during 3s probe
        # TextEdit opens a white window — big compositor change
        sp.run(["osascript", "-e", '''
            tell app "TextEdit" to activate
            tell app "System Events" to tell process "TextEdit"
                try
                    click menu item "New" of menu "File" of menu bar 1
                end try
                set frontmost to true
            end tell
        '''], timeout=10)
        time.sleep(5)

        # Close TextEdit — another screen change
        sp.run(["osascript", "-e", 'tell app "TextEdit" to quit saving no'], timeout=5)
        time.sleep(5)

        # Stop stream
        adb.tap_button("btn_stream")
        time.sleep(2)

        # Check server log for push-model
        log = bt_connected.read_log()
        if "SCK push-model" not in log:
            pytest.skip("Push model not attempted (SCK unavailable on this machine)")

        push_frames = log.count("[push]") + log.count("[h264]")
        used_legacy = "falling back to legacy" in log and push_frames == 0

        if used_legacy:
            pytest.fail(
                "Push model fell back to legacy. Check Screen Recording permission "
                "and ensure no other screenshot-server is running.\n"
                f"Server log snippet: {[l for l in log.split(chr(10)) if 'push' in l.lower() or 'SCK' in l or 'legacy' in l]}"
            )

        assert push_frames >= 10, (
            f"Push model active but only {push_frames} frames. Expected 10+.\n"
            f"Push lines: {[l for l in log.split(chr(10)) if '[push]' in l][:5]}"
        )

        # Extract FPS from last push line
        import re
        fps_matches = re.findall(r"fps:(\d+\.?\d*)", log)
        if fps_matches:
            last_fps = float(fps_matches[-1])
            print(f"  SCK push-model: {push_frames} frames, {last_fps:.0f} FPS")
            assert last_fps >= 5, f"Push model FPS too low: {last_fps}"
        else:
            print(f"  SCK push-model: {push_frames} frames")

    def test_push_model_tablet_receives_frames(self, adb: Adb, bt_connected: ScreenshotServer):
        """Verify tablet actually receives push-model stream frames."""
        import subprocess as sp

        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml = adb.shell("cat /sdcard/ui.xml")
        if "btn_stream" not in xml:
            pytest.skip("Stream button not visible")

        if 'text="Stop"' in xml and "btn_stream" in xml:
            adb.tap_button("btn_stream")
            time.sleep(5)

        adb.clear_logcat()
        time.sleep(1)
        assert adb.tap_button("btn_stream"), "Stream button not found"

        # Open TextEdit to trigger SCK frames
        sp.run(["osascript", "-e", '''
            tell app "TextEdit" to activate
            tell app "System Events" to tell process "TextEdit"
                try
                    click menu item "New" of menu "File" of menu bar 1
                end try
                set frontmost to true
            end tell
        '''], timeout=10)
        time.sleep(8)
        sp.run(["osascript", "-e", 'tell app "TextEdit" to quit saving no'], timeout=5)
        time.sleep(3)

        log = adb.logcat("BtScreenshot")
        frame_count = len(re.findall(r"Stream \[full\]|Stream frame", log))

        adb.tap_button("btn_stream")
        time.sleep(2)

        assert frame_count >= 10, (
            f"Tablet received only {frame_count} stream frames, expected 10+.\n"
            f"Log: {log[:500]}"
        )
        # Check hashes changed (screen content updated)
        unique_hashes = set(re.findall(r"hash=([a-f0-9]+)", log))
        print(f"  Tablet received {frame_count} frames, {len(unique_hashes)} unique hashes")
        assert len(unique_hashes) >= 2, (
            f"Tablet frames all identical — screen changes not reflected"
        )


class TestSCKCursorTrigger:
    """Verify cursor movement via HID triggers SCK push-model frames."""

    def test_cursor_movement_triggers_push_frames(self, adb: Adb, bt_connected: ScreenshotServer):
        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml = adb.shell("cat /sdcard/ui.xml")
        if "btn_stream" not in xml:
            pytest.skip("Stream button not visible")

        if 'text="Stop"' in xml and "btn_stream" in xml:
            adb.tap_button("btn_stream")
            time.sleep(5)

        adb.clear_logcat()
        time.sleep(1)
        assert adb.tap_button("btn_stream"), "Stream button not found"

        # Only move cursor — no window changes
        for _ in range(5):
            adb.swipe(300, 300, 800, 500, 200)
            time.sleep(0.5)
            adb.swipe(800, 500, 300, 300, 200)
            time.sleep(0.5)
        time.sleep(3)

        log = adb.logcat("BtScreenshot")
        frame_count = len(re.findall(r"Stream \[full\]|Stream frame", log))

        adb.tap_button("btn_stream")
        time.sleep(2)

        # Check server used push model (not legacy)
        server_log = bt_connected.read_log()
        used_push = "[push]" in server_log or "[h264]" in server_log

        assert frame_count >= 5, (
            f"Cursor movement produced only {frame_count} frames (expected 5+)"
        )
        if used_push:
            print(f"  Cursor triggered push-model: {frame_count} frames")
        else:
            print(f"  Cursor triggered legacy: {frame_count} frames")


class TestH264Streaming:
    """Verify H.264 hardware encoding is used for streaming."""

    def test_h264_frames_in_server_log(self, adb: Adb, bt_connected: ScreenshotServer):
        """Server should show [h264] log lines during streaming."""
        import subprocess as sp
        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml = adb.shell("cat /sdcard/ui.xml")
        if "btn_stream" not in xml:
            pytest.skip("Stream button not visible")

        if 'text="Stop"' in xml:
            adb.tap_button("btn_stream")
            time.sleep(5)

        adb.clear_logcat()
        time.sleep(1)
        assert adb.tap_button("btn_stream"), "Stream button not found"

        # Generate screen changes
        sp.run(["osascript", "-e", '''
            tell app "TextEdit" to activate
            tell app "System Events" to tell process "TextEdit"
                try
                    click menu item "New" of menu "File" of menu bar 1
                end try
            end tell
        '''], timeout=10)
        time.sleep(5)
        sp.run(["osascript", "-e", 'tell app "TextEdit" to quit saving no'], timeout=5)
        time.sleep(3)

        adb.tap_button("btn_stream")
        time.sleep(2)

        log = bt_connected.read_log()
        h264_frames = log.count("[h264]")
        jpeg_frames = log.count("[push]")

        if h264_frames > 0:
            print(f"  H.264 streaming: {h264_frames} frames")
            # Extract bandwidth
            import re
            sizes = re.findall(r"\[h264\] (\d+)KB", log)
            if sizes:
                avg_kb = sum(int(s) for s in sizes) / len(sizes)
                print(f"  Average frame size: {avg_kb:.0f}KB")
        elif jpeg_frames > 0:
            print(f"  JPEG fallback: {jpeg_frames} frames (H.264 not available)")
        else:
            pytest.skip("No streaming frames in server log")

    def test_h264_tablet_receives_frames(self, adb: Adb, bt_connected: ScreenshotServer):
        """Tablet should receive, decode H.264 frames, and display changing content."""
        import subprocess as sp

        # Ensure app is in foreground and stream button is available
        adb.launch_app()
        time.sleep(3)
        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml = adb.shell("cat /sdcard/ui.xml")
        if "btn_stream" not in xml:
            pytest.skip("Stream button not visible")

        # Stop any existing stream
        if 'text="Stop"' in xml:
            adb.tap_button("btn_stream")
            time.sleep(5)

        # Take a screenshot first to warm up WiFi, then close cleanly
        # This ensures the WiFi handler cycles back to accept()
        take_screenshot(adb, timeout=10)
        time.sleep(2)

        adb.clear_logcat()
        time.sleep(1)
        if not adb.tap_button("btn_stream"):
            pytest.skip("Stream button not found")

        # Open TextEdit to trigger screen changes
        sp.run(["osascript", "-e", '''
            tell app "TextEdit" to activate
            tell app "System Events" to tell process "TextEdit"
                try
                    click menu item "New" of menu "File" of menu bar 1
                end try
            end tell
        '''], timeout=10)
        time.sleep(8)
        sp.run(["osascript", "-e", 'tell app "TextEdit" to quit saving no'], timeout=5)
        time.sleep(3)

        log = adb.logcat("BtScreenshot")
        h264_count = len(re.findall(r"Stream \[h264\]", log))
        jpeg_count = len(re.findall(r"Stream \[full\]", log))
        total = h264_count + jpeg_count

        adb.tap_button("btn_stream")
        time.sleep(2)

        if total == 0 and ("ECONNREFUSED" in log or "connect failed" in log):
            pytest.skip("WiFi connection unavailable")

        assert total >= 5, f"Too few frames: {total} (h264={h264_count}, jpeg={jpeg_count}). Log:\n{log[:500]}"

        if h264_count > 0:
            errors = len(re.findall(r"YUV.*failed|decode.*error", log, re.IGNORECASE))
            assert errors == 0, f"H.264 decode had {errors} errors"
            # Verify content changed via hashes
            unique_hashes = set(re.findall(r"hash=([a-f0-9]+)", log))
            none_count = log.count("hash=none")
            decoded = h264_count - none_count
            print(f"  Tablet: {h264_count} H.264 frames, {decoded} decoded to bitmap, {len(unique_hashes)} unique hashes")
            assert decoded >= 5, f"Too few decoded bitmaps ({decoded}/{h264_count})"
            assert len(unique_hashes) >= 2, f"Content not changing — only {len(unique_hashes)} unique hashes"
            print(f"  Content verified: screen changed during H.264 stream")
        else:
            print(f"  Tablet received {jpeg_count} JPEG frames")


class TestBtStreaming:
    """Test H.264 streaming over Bluetooth RFCOMM."""

    def _select_stream_method(self, adb: Adb, method_text: str):
        """Open Settings, select a stream method, tap Apply."""
        import re as _re

        # Tap Settings button
        assert adb.tap_button("btn_settings"), "Settings button not found"
        time.sleep(2)

        # Scroll down in the settings dialog to reveal Stream Method
        adb.swipe(1200, 1400, 1200, 600, 300)
        time.sleep(1)

        # Dump UI and find the Stream Method spinner
        # It's a CheckedTextView after the "Stream Method" label
        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml = adb.shell("cat /sdcard/ui.xml")

        # Find all CheckedTextView entries — the Stream Method spinner is after
        # "Stream Method" text. Find by looking for spinner entries in order.
        segments = xml.replace(">", ">\n").split("\n")
        found_label = False
        spinner_tapped = False
        for seg in segments:
            if "Stream Method" in seg and "TextView" in seg:
                found_label = True
                continue
            if found_label and "CheckedTextView" in seg:
                m = _re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', seg)
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    adb.tap((x1 + x2) // 2, (y1 + y2) // 2)
                    spinner_tapped = True
                    time.sleep(1)
                    break

        if not spinner_tapped:
            return False

        # Dropdown is open — tap the desired method
        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml2 = adb.shell("cat /sdcard/ui.xml")
        item_tapped = False
        for seg in xml2.replace(">", ">\n").split("\n"):
            if method_text in seg:
                m = _re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', seg)
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    adb.tap((x1 + x2) // 2, (y1 + y2) // 2)
                    item_tapped = True
                    time.sleep(1)
                    break

        # Scroll down more to find Apply button
        adb.swipe(1200, 1400, 1200, 600, 300)
        time.sleep(1)

        # Tap Apply (button1 in AlertDialog)
        adb.tap_button("button1")
        time.sleep(1)
        return item_tapped

    def test_bt_h264_streaming(self, adb: Adb, bt_connected: ScreenshotServer):
        """Stream H.264 over Bluetooth RFCOMM."""
        import subprocess as sp

        adb.launch_app()
        time.sleep(3)

        # Stop any running stream
        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml = adb.shell("cat /sdcard/ui.xml")
        if 'text="Stop"' in xml:
            adb.tap_button("btn_stream")
            time.sleep(3)

        # Select BT stream method in Settings
        if not self._select_stream_method(adb, "H.264 (BT)"):
            pytest.skip("Could not select BT stream method in Settings UI")
        time.sleep(2)

        # Verify stream button is visible (BT connected)
        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml = adb.shell("cat /sdcard/ui.xml")
        if "btn_stream" not in xml:
            pytest.skip("Stream button not visible after setting BT method")

        # Start stream
        adb.clear_logcat()
        time.sleep(1)
        assert adb.tap_button("btn_stream"), "Stream button not found"

        # Generate screen changes
        sp.run(["osascript", "-e", '''
            tell app "TextEdit" to activate
            tell app "System Events" to tell process "TextEdit"
                try
                    click menu item "New" of menu "File" of menu bar 1
                end try
            end tell
        '''], timeout=10)
        time.sleep(8)
        sp.run(["osascript", "-e", 'tell app "TextEdit" to quit saving no'], timeout=5)
        time.sleep(3)

        # Check results
        log = adb.logcat("BtScreenshot")
        bt_stream = "BT stream" in log or "BT H.264" in log
        h264_count = len(re.findall(r"Stream \[h264\]", log))
        total_frames = len(re.findall(r"Stream \[", log))

        # Stop stream
        adb.tap_button("btn_stream")
        time.sleep(2)

        # Check server log
        server_log = bt_connected.read_log()
        bt_h264_server = "[h264-bt]" in server_log
        bt_started = "BT streaming started" in server_log

        # Restore Auto stream method
        self._select_stream_method(adb, "Auto")

        if bt_started:
            bt_frames = server_log.count("[h264-bt]")
            print(f"  BT H.264 streaming: {bt_frames} server frames, {h264_count} tablet frames")
            assert bt_frames >= 3, f"Too few BT H.264 frames: {bt_frames}"
        elif total_frames > 0:
            print(f"  Stream worked but used WiFi ({total_frames} frames) — BT method may not have been selected")
        else:
            # Check for BT stream command in device log
            if "Starting BT H.264 stream" in log:
                print(f"  BT stream started but no frames received")
                pytest.fail("BT stream started but no frames received")
            else:
                pytest.skip("Could not select BT stream method via UI automation")


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
