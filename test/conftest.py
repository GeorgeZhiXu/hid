"""
Shared fixtures and utilities for TabletPen E2E tests.

Requirements: tablet USB-connected (adb) + BT-paired with this Mac.
DO NOT touch the Mac mouse/trackpad during tests!

Usage:
    pytest test/ -v                    # run all
    pytest test/test_hid_input.py -v   # run one module
    pytest test/ -k "screenshot" -v    # keyword filter
"""

import os
import re
import signal
import subprocess
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import pytest

PROJECT_ROOT = Path(__file__).parent.parent
ADB = os.environ.get("ADB", "adb")
ANDROID_HOME = os.environ.get(
    "ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools"
)

# Add platform-tools to PATH for adb
os.environ["PATH"] = f"{ANDROID_HOME}/platform-tools:{os.environ['PATH']}"


# ---- ADB helper ----

class Adb:
    """Wrapper around adb shell commands."""

    def run(self, *args: str, timeout: int = 30) -> str:
        """Run an adb command, return stdout."""
        result = subprocess.run(
            [ADB, *args],
            capture_output=True, text=True, timeout=timeout
        )
        return result.stdout.strip()

    def shell(self, cmd: str, timeout: int = 30) -> str:
        return self.run("shell", cmd, timeout=timeout)

    def install(self, apk: str) -> bool:
        out = self.run("install", "-r", apk, timeout=60)
        return "Success" in out

    def launch_app(self):
        self.shell("am start -n com.hid.tabletpen/.MainActivity")
        time.sleep(3)
        self.dismiss_dialogs()

    def dismiss_dialogs(self):
        """Dismiss update dialog, permission dialogs, etc."""
        for _ in range(3):
            time.sleep(1)
            self.shell("uiautomator dump /sdcard/ui.xml")
            xml = self.shell("cat /sdcard/ui.xml")
            if "permissioncontroller" in xml or "Permission" in xml:
                # Tap "Allow" or "While using the app"
                if not self.tap_button("permission_allow_button"):
                    self.tap_button("permission_allow_foreground_only_button")
            elif "alertTitle" in xml or "button2" in xml:
                self.tap_button("button2")  # "Later" on update dialog
            elif "com.hid.tabletpen" in xml:
                break

    def force_stop(self):
        self.shell("am force-stop com.hid.tabletpen")

    def clear_logcat(self):
        self.run("logcat", "-c")
        time.sleep(0.5)

    def logcat(self, tag: str = "", lines: int = 0) -> str:
        args = ["logcat", "-d"]
        if tag:
            args += ["-s", tag]
        return self.run(*args, timeout=10)

    def is_app_running(self) -> bool:
        out = self.shell("dumpsys activity activities | grep -i tabletpen | head -1")
        return "tabletpen" in out.lower()

    def device_connected(self) -> Optional[str]:
        out = self.run("devices")
        for line in out.splitlines():
            if line.endswith("device"):
                return line.split()[0]
        return None

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300):
        self.shell(f"input swipe {x1} {y1} {x2} {y2} {duration_ms}")

    def tap(self, x: int, y: int):
        self.shell(f"input tap {x} {y}")

    def tap_button(self, btn_id: str) -> bool:
        """Tap a button by resource ID or text using uiautomator XML dump."""
        self.shell("uiautomator dump /sdcard/ui.xml")
        xml = self.shell("cat /sdcard/ui.xml")
        # Find bounds for the button
        for segment in xml.replace(">", ">\n").split("\n"):
            if btn_id in segment:
                match = re.search(
                    r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', segment
                )
                if match:
                    x1, y1, x2, y2 = map(int, match.groups())
                    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
                    self.shell(f"input tap {cx} {cy}")
                    return True
        return False

    def key_event(self, key: str):
        self.shell(f"input keyevent {key}")

    def bt_disable(self):
        self.shell("cmd bluetooth_manager disable")

    def bt_enable(self):
        self.shell("cmd bluetooth_manager enable")


# ---- Mouse position helper ----

def get_mouse_pos() -> tuple[int, int]:
    """Get Mac cursor position using the mouse-pos Swift helper."""
    mouse_pos = PROJECT_ROOT / "test" / "mouse-pos"
    try:
        result = subprocess.run(
            [str(mouse_pos)], capture_output=True, text=True, timeout=5
        )
        parts = result.stdout.strip().split(",")
        return int(parts[0]), int(parts[1])
    except Exception:
        return 0, 0


def wait_until(predicate, timeout: float = 30, interval: float = 1.0, msg: str = ""):
    """Poll until predicate returns True, or raise AssertionError."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    raise AssertionError(msg or f"Condition not met within {timeout}s")


# ---- Screenshot server manager ----

class ScreenshotServer:
    """Manages the Mac screenshot-server process."""

    def __init__(self):
        self.process: Optional[subprocess.Popen] = None
        self.log_file = None
        self.log_path: Optional[str] = None

    def start(self):
        binary = PROJECT_ROOT / "mac" / "screenshot-server"
        if not binary.exists():
            pytest.skip("Mac screenshot-server binary not found. Run: cd mac && ./build.sh")
        self.log_file = tempfile.NamedTemporaryFile(mode="w", suffix=".log", delete=False)
        self.log_path = self.log_file.name
        self.process = subprocess.Popen(
            [str(binary)],
            stdout=self.log_file,
            stderr=subprocess.STDOUT,
            cwd=str(PROJECT_ROOT),
        )

    def stop(self):
        if self.process:
            try:
                self.process.send_signal(signal.SIGTERM)
                self.process.wait(timeout=5)
            except Exception:
                self.process.kill()
            self.process = None
        if self.log_file:
            self.log_file.close()
        if self.log_path and os.path.exists(self.log_path):
            os.unlink(self.log_path)
            self.log_path = None

    def read_log(self) -> str:
        if not self.log_path or not os.path.exists(self.log_path):
            return ""
        with open(self.log_path) as f:
            return f.read()

    def wait_for_bt_connection(self, timeout: float = 60) -> bool:
        return wait_until(
            lambda: "BT connected" in self.read_log(),
            timeout=timeout,
            msg=f"BT RFCOMM not connected within {timeout}s. Log:\n{self.read_log()}"
        )

    def bt_connection_count(self) -> int:
        return self.read_log().count("BT connected")


# ---- Fixtures ----

@pytest.fixture(scope="session")
def adb():
    """ADB wrapper, ensures device is connected."""
    a = Adb()
    device = a.device_connected()
    if not device:
        pytest.skip("No ADB device connected")
    return a


@pytest.fixture(scope="session")
def app_installed(adb):
    """Build and install the APK."""
    # Build
    result = subprocess.run(
        ["./gradlew", "assembleDebug", "-q"],
        cwd=str(PROJECT_ROOT),
        capture_output=True, text=True, timeout=120,
        env={**os.environ, "ANDROID_HOME": ANDROID_HOME},
    )
    assert result.returncode == 0, f"Build failed: {result.stderr}"

    # Install
    apk = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    assert adb.install(str(apk)), "APK install failed"

    # Launch
    adb.launch_app()
    time.sleep(2)
    assert adb.is_app_running(), "App did not launch"
    return True


@pytest.fixture(scope="session")
def server():
    """Start and manage the Mac screenshot-server."""
    srv = ScreenshotServer()
    srv.start()
    yield srv
    srv.stop()


@pytest.fixture(scope="session")
def bt_connected(server, adb, app_installed):
    """Ensure BT RFCOMM is connected. Returns the server."""
    server.wait_for_bt_connection(timeout=60)
    return server


def take_screenshot(adb: Adb, timeout: float = 12) -> Optional[str]:
    """Tap Screenshot button and return the logcat timing line, or None."""
    adb.clear_logcat()
    time.sleep(0.5)
    if not adb.tap_button("btn_screenshot"):
        adb.tap(987, 114)  # fallback
    time.sleep(timeout)
    log = adb.logcat("BtScreenshot")
    match = re.search(r"(BT|WiFi) .+total:\d+ms", log)
    return match.group(0) if match else None


def parse_screenshot_log(log: str) -> list[str]:
    """Extract all screenshot timing lines from logcat."""
    return re.findall(r"(BT|WiFi) .+total:\d+ms", log)
