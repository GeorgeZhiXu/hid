"""Stress tests, adaptive quality, ghost stroke, shortcut buttons, radial menu."""

import re
import time
import pytest
from conftest import Adb, ScreenshotServer, take_screenshot, parse_screenshot_log


class TestRapidScreenshots:
    """Stress test: rapid screenshot requests."""

    def test_rapid_screenshots_mostly_succeed(self, adb: Adb, bt_connected):
        adb.clear_logcat()
        for _ in range(5):
            if not adb.tap_button("btn_screenshot"):
                adb.tap(987, 114)
            time.sleep(3)
        time.sleep(10)

        log = adb.logcat("BtScreenshot")
        successes = len(parse_screenshot_log(log))
        failures = log.count("screenshot failed")

        assert successes >= 3, (
            f"Only {successes}/5 screenshots succeeded ({failures} failed)"
        )
        print(f"  {successes}/5 succeeded, {failures} failed")

    def test_app_survives_stress(self, adb: Adb, bt_connected):
        assert adb.is_app_running(), "App crashed during stress test"


class TestAdaptiveQuality:
    """Verify adaptive quality adjusts between screenshots."""

    def test_two_consecutive_screenshots(self, adb: Adb, bt_connected):
        adb.clear_logcat()
        time.sleep(1)

        # First screenshot establishes baseline speed
        if not adb.tap_button("btn_screenshot"):
            adb.tap(987, 114)
        time.sleep(5)

        # Second should use adapted quality
        if not adb.tap_button("btn_screenshot"):
            adb.tap(987, 114)
        time.sleep(5)

        log = adb.logcat("BtScreenshot")
        timings = parse_screenshot_log(log)
        assert len(timings) >= 2, f"Expected 2+ screenshots, got {len(timings)}"
        print(f"  Shot 1: {timings[0]}")
        print(f"  Shot 2: {timings[1]}")


class TestGhostStroke:
    """Verify ghost stroke clears after screenshot."""

    def test_draw_then_screenshot_succeeds(self, adb: Adb, bt_connected):
        # Draw on the pad
        adb.swipe(500, 500, 900, 600, 300)
        time.sleep(1)

        # Screenshot should clear ghost path and succeed
        timing = take_screenshot(adb, timeout=5)
        assert timing is not None, "Screenshot after drawing failed"
        assert adb.is_app_running(), "App crashed after draw + screenshot"


class TestShortcutButtons:
    """Verify shortcut buttons are visible and tappable."""

    def test_shortcut_container_exists(self, adb: Adb, app_installed):
        adb.launch_app()
        time.sleep(2)
        adb.shell("uiautomator dump /sdcard/ui.xml")
        xml = adb.shell("cat /sdcard/ui.xml")

        has_container = "shortcut_container" in xml
        # Shortcut button names depend on preset (Undo, Redo, Brush+, Brush-, etc.)
        has_shortcut = "Undo" in xml or "Redo" in xml or "Brush" in xml or "Save" in xml

        assert has_container or has_shortcut, (
            f"Shortcut buttons not found in UI. Package in dump: "
            f"{xml.split('package=')[1].split('\"')[1] if 'package=' in xml else 'unknown'}"
        )

    def test_shortcut_tap_no_crash(self, adb: Adb, app_installed):
        adb.tap_button("Undo")
        time.sleep(1)
        assert adb.is_app_running(), "App crashed after shortcut tap"


class TestRadialMenu:
    """Verify radial menu opens on long-press without crash."""

    def test_long_press_drag_no_crash(self, adb: Adb, app_installed):
        # Long press ~2s
        adb.swipe(700, 500, 700, 500, duration_ms=2000)
        time.sleep(1)
        # Drag to a segment
        adb.swipe(700, 500, 850, 400, duration_ms=100)
        time.sleep(1)

        assert adb.is_app_running(), "App crashed during radial menu"
