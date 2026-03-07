"""HID input tests: verify pen/finger events control the Mac cursor."""

import time
import pytest
from conftest import Adb, get_mouse_pos, wait_until


class TestCursorMovement:
    """Test that finger input on tablet moves the Mac cursor (full HID round-trip)."""

    def test_finger_swipe_moves_cursor(self, adb: Adb, app_installed):
        # Reset cursor position
        adb.swipe(800, 600, 400, 400, 300)
        time.sleep(2)

        before = get_mouse_pos()
        adb.swipe(500, 500, 1200, 500, 500)
        time.sleep(2)
        adb.swipe(800, 300, 800, 800, 500)
        time.sleep(2)
        after = get_mouse_pos()

        assert before != after, (
            f"Cursor did not move: {before} → {after}. "
            "Is tablet BT HID-paired with this Mac?"
        )

    def test_single_finger_tap_does_not_move_cursor(self, adb: Adb, app_installed):
        # Move cursor to a known position
        adb.swipe(800, 600, 600, 500, 300)
        time.sleep(1)

        before = get_mouse_pos()
        adb.tap(700, 500)
        time.sleep(1)
        after = get_mouse_pos()

        assert before == after, (
            f"Tap moved cursor {before} → {after} (should be click, not drag)"
        )


class TestLatency:
    """Measure HID input-to-cursor response time."""

    def test_hid_latency_under_2s(self, adb: Adb, app_installed):
        adb.swipe(800, 600, 400, 400, 300)
        time.sleep(2)

        before = get_mouse_pos()
        start = time.time()
        adb.swipe(500, 500, 900, 500, 200)

        # Poll for cursor change
        latency_ms = None
        for _ in range(20):
            now = get_mouse_pos()
            if now != before:
                latency_ms = int((time.time() - start) * 1000)
                break
            time.sleep(0.1)

        assert latency_ms is not None, "Cursor never moved"
        assert latency_ms < 2000, f"Latency {latency_ms}ms exceeds 2s"
        print(f"  HID latency: {latency_ms}ms")
