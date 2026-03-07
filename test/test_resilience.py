"""Connection resilience tests: server kill, BT toggle, app kill, sleep/wake."""

import re
import time
import pytest
from conftest import (
    Adb, ScreenshotServer, get_mouse_pos, wait_until, take_screenshot
)


class TestServerRestart:
    """Test graceful handling of server kill and reconnection."""

    def test_screenshot_without_server_no_crash(self, adb: Adb, bt_connected: ScreenshotServer):
        # Kill server
        bt_connected.stop()
        time.sleep(3)

        # Try screenshot — should fail gracefully
        adb.clear_logcat()
        time.sleep(1)
        if not adb.tap_button("btn_screenshot"):
            adb.tap(987, 114)
        time.sleep(5)

        assert adb.is_app_running(), "App crashed when server is down"

    def test_server_restart_reconnects(self, adb: Adb, bt_connected: ScreenshotServer):
        # Restart server
        bt_connected.start()
        bt_connected.wait_for_bt_connection(timeout=60)

        # Screenshot should work (may need retry for stale socket)
        timing = None
        for attempt in range(2):
            timing = take_screenshot(adb, timeout=10)
            if timing:
                break
            time.sleep(3)

        assert timing is not None, "Screenshot failed after server restart"
        print(f"  After restart: {timing}")


class TestBluetoothToggle:
    """Test HID and RFCOMM reconnection after BT disable/enable."""

    def test_bt_off_disconnects_hid(self, adb: Adb, bt_connected):
        adb.bt_disable()
        time.sleep(5)

        before = get_mouse_pos()
        adb.swipe(600, 500, 900, 500, 300)
        time.sleep(2)
        after = get_mouse_pos()

        assert before == after, "Cursor moved with BT off"

    def test_bt_on_reconnects_hid(self, adb: Adb, bt_connected):
        adb.bt_enable()
        time.sleep(15)
        adb.launch_app()
        time.sleep(10)

        # Reset and test cursor movement
        adb.swipe(800, 600, 400, 400, 300)
        time.sleep(2)
        before = get_mouse_pos()
        adb.swipe(500, 500, 1000, 500, 500)
        time.sleep(2)
        after = get_mouse_pos()

        assert before != after, (
            f"HID did not reconnect after BT toggle: {before} → {after}"
        )

    def test_bt_toggle_rfcomm_reconnects(self, adb: Adb, bt_connected: ScreenshotServer):
        prev_count = bt_connected.bt_connection_count()
        wait_until(
            lambda: bt_connected.bt_connection_count() > prev_count,
            timeout=20,
            msg="RFCOMM did not reconnect after BT toggle"
        )


class TestAppKill:
    """Test reconnection after force-stopping and relaunching the app."""

    def test_app_kill_and_relaunch_reconnects(self, adb: Adb, bt_connected: ScreenshotServer):
        adb.launch_app()
        time.sleep(5)

        adb.force_stop()
        time.sleep(3)
        adb.launch_app()
        time.sleep(20)  # HID re-registration takes time on some devices

        # Verify HID reconnects
        adb.swipe(800, 600, 400, 400, 300)
        time.sleep(2)
        before = get_mouse_pos()
        adb.swipe(500, 500, 1000, 500, 500)
        time.sleep(2)
        after = get_mouse_pos()

        assert before != after, (
            f"HID did not reconnect after app kill: {before} → {after}"
        )

    def test_rfcomm_reconnects_after_app_kill(self, adb: Adb, bt_connected: ScreenshotServer):
        prev_count = bt_connected.bt_connection_count()
        wait_until(
            lambda: bt_connected.bt_connection_count() > prev_count,
            timeout=30,
            msg="RFCOMM did not reconnect after app kill"
        )


class TestSleepWake:
    """Test reconnection after tablet sleep/wake cycle."""

    def test_sleep_wake_reconnects_hid(self, adb: Adb, bt_connected):
        adb.key_event("POWER")  # sleep
        time.sleep(8)
        adb.key_event("POWER")  # wake
        time.sleep(3)
        adb.swipe(500, 1500, 500, 500, 300)  # unlock swipe
        time.sleep(5)
        adb.launch_app()
        time.sleep(8)

        adb.swipe(800, 600, 400, 400, 300)
        time.sleep(2)
        before = get_mouse_pos()
        adb.swipe(500, 500, 1000, 500, 500)
        time.sleep(2)
        after = get_mouse_pos()

        assert before != after, (
            f"HID did not reconnect after sleep/wake: {before} → {after}"
        )

    def test_screenshot_works_after_wake(self, adb: Adb, bt_connected):
        timing = None
        for attempt in range(2):
            timing = take_screenshot(adb, timeout=10)
            if timing:
                break
            time.sleep(3)

        assert timing is not None, "Screenshot failed after sleep/wake"
        print(f"  After wake: {timing}")
