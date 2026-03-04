#!/usr/bin/env python3
"""
TabletPen for Sony DPT-RP1 / DPT-S1

Turns a rooted Sony Digital Paper into a Bluetooth HID digitizer tablet.
Bypasses the missing BluetoothHidDevice API (requires Android 9+) by
using raw Linux evdev input and L2CAP Bluetooth sockets directly.

Features:
  - Digitizer mode (absolute pen) and Mouse mode (barrel double-press toggle)
  - Pressure tuning (floor + curve)
  - Aspect ratio mapping (DPT 3:4 → laptop 16:10/16:9/3:2)
  - Auto-reconnect to last paired laptop
  - Settings persistence (~/.tabletpen.conf)

Requirements:
  - Sony DPT-RP1 or DPT-S1 with root access (via dpt-tools)
  - Python 3 deployed to device
  - Bluetooth enabled

Device info:
  - DPT-RP1: 1404x1872, 13.3" E Ink, Wacom digitizer, Android 5.1.1
  - DPT-S1:  1200x1600, 13.3" E Ink, Wacom digitizer, Android 4.0.3

Root access:
  Install dpt-tools: https://github.com/janten/dpt-rp1-py
  Enable SSH: python3 -m dptrp1 enable-wifi-ssh
  SSH in: ssh root@<device-ip>

Usage:
  scp tabletpen.py root@<dpt-ip>:/data/local/tmp/
  ssh root@<dpt-ip>
  python3 /data/local/tmp/tabletpen.py

  Options:
    --mouse          Start in mouse mode
    --ratio 16:10    Target aspect ratio
    --floor 80       Pressure floor (0-100)
    --curve 0.5      Pressure curve exponent
    --sensitivity 2  Mouse sensitivity multiplier
"""

import struct
import os
import sys
import glob
import time
import socket
import signal
import json
import argparse
import math
import fcntl
import array

# ---- evdev constants ----
EV_SYN = 0x00
EV_KEY = 0x01
EV_ABS = 0x03
SYN_REPORT = 0x00

ABS_X = 0x00
ABS_Y = 0x01
ABS_PRESSURE = 0x18
ABS_TILT_X = 0x1a
ABS_TILT_Y = 0x1b

BTN_TOUCH = 0x14a
BTN_STYLUS = 0x14b
BTN_STYLUS2 = 0x14c
BTN_TOOL_PEN = 0x140
BTN_TOOL_RUBBER = 0x141

EV_FORMAT = 'llHHi'
EV_SIZE = struct.calcsize(EV_FORMAT)

# ---- HID ----
X_MAX = 32767
Y_MAX = 32767
P_MAX = 4095

HID_DESCRIPTOR = bytes([
    # Digitizer Pen (Report ID 1)
    0x05, 0x0D, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x01,
    0x09, 0x20, 0xA1, 0x00,
    0x09, 0x42, 0x09, 0x44, 0x09, 0x32,
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02,
    0x75, 0x05, 0x95, 0x01, 0x81, 0x03,
    0x05, 0x01, 0x09, 0x30, 0x15, 0x00, 0x26, 0xFF, 0x7F,
    0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
    0x09, 0x31, 0x81, 0x02,
    0x05, 0x0D, 0x09, 0x30, 0x26, 0xFF, 0x0F, 0x81, 0x02,
    0xC0, 0xC0,
    # Mouse (Report ID 2)
    0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02,
    0x09, 0x01, 0xA1, 0x00,
    0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02,
    0x75, 0x05, 0x95, 0x01, 0x81, 0x03,
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
    0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x02, 0x81, 0x06,
    0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
    0xC0, 0xC0,
])

SDP_RECORD = f"""<?xml version="1.0" encoding="UTF-8" ?>
<record>
  <attribute id="0x0001"><sequence><uuid value="0x1124" /></sequence></attribute>
  <attribute id="0x0004"><sequence>
    <sequence><uuid value="0x0100" /><uint16 value="0x0011" /></sequence>
    <sequence><uuid value="0x0011" /></sequence>
  </sequence></attribute>
  <attribute id="0x0005"><sequence><uuid value="0x1002" /></sequence></attribute>
  <attribute id="0x0006"><sequence>
    <uint16 value="0x656e" /><uint16 value="0x006a" /><uint16 value="0x0100" />
  </sequence></attribute>
  <attribute id="0x0009"><sequence><sequence>
    <uuid value="0x1124" /><uint16 value="0x0100" />
  </sequence></sequence></attribute>
  <attribute id="0x000d"><sequence><sequence>
    <sequence><uuid value="0x0100" /><uint16 value="0x0013" /></sequence>
    <sequence><uuid value="0x0011" /></sequence>
  </sequence></sequence></attribute>
  <attribute id="0x0100"><text value="TabletPen" /></attribute>
  <attribute id="0x0101"><text value="Sony DPT HID Digitizer" /></attribute>
  <attribute id="0x0102"><text value="TabletPen" /></attribute>
  <attribute id="0x0200"><uint16 value="0x0100" /></attribute>
  <attribute id="0x0201"><uint16 value="0x0005" /></attribute>
  <attribute id="0x0202"><uint16 value="0x00c5" /></attribute>
  <attribute id="0x0203"><boolean value="true" /></attribute>
  <attribute id="0x0204"><boolean value="true" /></attribute>
  <attribute id="0x0205"><boolean value="false" /></attribute>
  <attribute id="0x0206"><sequence><sequence>
    <uint8 value="0x22" />
    <text encoding="hex" value="{HID_DESCRIPTOR.hex()}" />
  </sequence></sequence></attribute>
  <attribute id="0x0207"><sequence><sequence>
    <uint16 value="0x0409" /><uint16 value="0x0100" />
  </sequence></sequence></attribute>
  <attribute id="0x020b"><uint16 value="0x0100" /></attribute>
  <attribute id="0x020c"><uint16 value="0x0c80" /></attribute>
  <attribute id="0x020d"><boolean value="false" /></attribute>
  <attribute id="0x020e"><boolean value="true" /></attribute>
</record>"""

# ---- Settings ----

CONFIG_PATH = "/data/local/tmp/.tabletpen.conf"

DEFAULT_SETTINGS = {
    "mode": "digitizer",
    "aspect_ratio": "16:10",
    "pressure_floor": 0.8,
    "pressure_curve": 0.5,
    "mouse_sensitivity": 2.0,
    "last_device": None,
}


def load_settings():
    settings = dict(DEFAULT_SETTINGS)
    try:
        with open(CONFIG_PATH, 'r') as f:
            settings.update(json.load(f))
    except (FileNotFoundError, json.JSONDecodeError):
        pass
    return settings


def save_settings(settings):
    try:
        with open(CONFIG_PATH, 'w') as f:
            json.dump(settings, f, indent=2)
    except OSError:
        pass


def parse_ratio(s):
    parts = s.split(':')
    return (int(parts[0]), int(parts[1])) if len(parts) == 2 else (16, 10)


# ---- Device discovery ----

def find_digitizer():
    """Find the Wacom digitizer. DPT-RP1 typically uses /dev/input/event0 or event1."""
    # Try known DPT-RP1 device names first
    known_names = ['wacom', 'pen', 'digitizer', 'ntrig', 'sony_digitizer']

    for devpath in sorted(glob.glob('/dev/input/event*')):
        try:
            with open(devpath, 'rb') as f:
                buf = array.array('B', [0] * 256)
                fcntl.ioctl(f, 0x80FF0106, buf)
                name = buf.tobytes().split(b'\x00')[0].decode('utf-8', errors='ignore').lower()
                if any(k in name for k in known_names):
                    print(f"Found digitizer: {devpath} ({name})")
                    return devpath
        except (PermissionError, OSError):
            continue

    # Fallback: check which devices have ABS_X + ABS_Y + ABS_PRESSURE
    for devpath in sorted(glob.glob('/dev/input/event*')):
        try:
            with open(devpath, 'rb') as f:
                # Check if device supports ABS_PRESSURE (likely a pen)
                buf = struct.pack('iiiii', 0, 0, 0, 0, 0)
                try:
                    fcntl.ioctl(f, 0x80184040 + ABS_PRESSURE, buf)
                    _, _, p_max, _, _ = struct.unpack('iiiii', buf)
                    if p_max > 100:  # has meaningful pressure range
                        buf2 = array.array('B', [0] * 256)
                        fcntl.ioctl(f, 0x80FF0106, buf2)
                        name = buf2.tobytes().split(b'\x00')[0].decode('utf-8', errors='ignore')
                        print(f"Found pressure device: {devpath} ({name}) p_max={p_max}")
                        return devpath
                except OSError:
                    continue
        except (PermissionError, OSError):
            continue

    return None


def get_axis_range(devpath, axis):
    try:
        with open(devpath, 'rb') as f:
            buf = struct.pack('iiiii', 0, 0, 0, 0, 0)
            result = fcntl.ioctl(f, 0x80184040 + axis, buf)
            _, minimum, maximum, _, _ = struct.unpack('iiiii', result)
            return minimum, maximum
    except OSError:
        return None


# ---- HID reports ----

def build_digitizer_report(tip, barrel, in_range, x, y, pressure):
    buttons = int(tip) | (int(barrel) << 1) | (int(in_range) << 2)
    return struct.pack('<BHHH', buttons,
        max(0, min(X_MAX, x)), max(0, min(Y_MAX, y)), max(0, min(P_MAX, pressure)))


def build_mouse_report(left, right, middle, dx, dy, scroll=0):
    buttons = int(left) | (int(right) << 1) | (int(middle) << 2)
    return struct.pack('<Bbbb', buttons,
        max(-127, min(127, dx)), max(-127, min(127, dy)), max(-127, min(127, scroll)))


# ---- Bluetooth ----

def setup_bluetooth():
    """Set up Bluetooth on the DPT-RP1."""
    print("Setting up Bluetooth...")

    # On rooted Android, Bluetooth should already be managed by the system
    # But we may need to enable it and make discoverable
    os.system("svc bluetooth enable 2>/dev/null")
    time.sleep(2)

    # Use Android's Bluetooth settings or hciconfig
    os.system("hciconfig hci0 up 2>/dev/null")
    os.system("hciconfig hci0 piscan 2>/dev/null")  # discoverable + connectable
    os.system("hciconfig hci0 name 'TabletPen DPT' 2>/dev/null")

    # Register SDP record
    sdp_path = "/tmp/tabletpen_sdp.xml"
    with open(sdp_path, 'w') as f:
        f.write(SDP_RECORD)
    os.system("sdptool add --handle=0x10001 --channel=1 SP 2>/dev/null")

    print("Bluetooth discoverable as 'TabletPen DPT'")


def open_hid_sockets():
    PSM_CTRL = 0x11
    PSM_INTR = 0x13

    ctrl = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    intr = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    ctrl.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    intr.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ctrl.bind(("", PSM_CTRL))
    intr.bind(("", PSM_INTR))
    ctrl.listen(1)
    intr.listen(1)

    print(f"HID listening on L2CAP PSM {PSM_CTRL}/{PSM_INTR}")
    return ctrl, intr


def try_auto_connect(settings, ctrl_sock, intr_sock):
    addr = settings.get("last_device")
    if not addr:
        return None, None

    print(f"Auto-connecting to {addr}...")
    try:
        # Try to trigger reconnection
        os.system(f"hcitool cc {addr} 2>/dev/null")
        time.sleep(3)
        ctrl_sock.settimeout(5.0)
        intr_sock.settimeout(5.0)
        ctrl_client, _ = ctrl_sock.accept()
        intr_client, _ = intr_sock.accept()
        ctrl_sock.settimeout(None)
        intr_sock.settimeout(None)
        print(f"Auto-connected!")
        return ctrl_client, intr_client
    except (socket.timeout, OSError):
        ctrl_sock.settimeout(None)
        intr_sock.settimeout(None)
        print("Auto-connect failed, waiting for manual pairing...")
        return None, None


# ---- Main pen loop ----

def run_pen_loop(settings, evdev_fd, intr_client, ranges):
    x_min, x_max = ranges['x']
    y_min, y_max = ranges['y']
    p_min, p_max = ranges['pressure']

    floor = settings['pressure_floor']
    curve = settings['pressure_curve']
    sensitivity = settings['mouse_sensitivity']

    # Aspect ratio mapping
    ratio_w, ratio_h = parse_ratio(settings['aspect_ratio'])
    target_ratio = ratio_w / ratio_h
    rm_x_range = x_max - x_min
    rm_y_range = y_max - y_min
    rm_ratio = rm_x_range / rm_y_range if rm_y_range > 0 else 1.0

    if rm_ratio > target_ratio:
        active_y_range = rm_y_range
        active_x_range = int(rm_y_range * target_ratio)
        x_offset = (rm_x_range - active_x_range) // 2
        y_offset = 0
    else:
        active_x_range = rm_x_range
        active_y_range = int(rm_x_range / target_ratio)
        x_offset = 0
        y_offset = (rm_y_range - active_y_range) // 2

    print(f"Ratio: {ratio_w}:{ratio_h}, active area: {active_x_range}x{active_y_range}")

    # State
    tip_down = False
    barrel = False
    in_range = False
    cur_x = 0
    cur_y = 0
    cur_pressure = 0
    last_x = -1
    last_y = -1
    mouse_mode = (settings['mode'] == 'mouse')
    barrel_last_time = 0

    def apply_pressure(raw_p):
        normalized = (raw_p - p_min) / (p_max - p_min) if p_max > p_min else 0
        curved = math.pow(max(0, normalized), curve)
        return int((floor + curved * (1.0 - floor)) * P_MAX) if tip_down else 0

    def send_digitizer():
        ax = cur_x - x_min - x_offset
        ay = cur_y - y_min - y_offset
        nx = int(max(0, min(1, ax / active_x_range)) * X_MAX) if active_x_range > 0 else 0
        ny = int(max(0, min(1, ay / active_y_range)) * Y_MAX) if active_y_range > 0 else 0
        report = build_digitizer_report(tip_down, barrel, in_range, nx, ny, apply_pressure(cur_pressure))
        intr_client.send(bytes([0xA1, 0x01]) + report)

    def send_mouse():
        nonlocal last_x, last_y
        if last_x < 0:
            last_x, last_y = cur_x, cur_y
            return
        dx = int((cur_x - last_x) * sensitivity)
        dy = int((cur_y - last_y) * sensitivity)
        last_x, last_y = cur_x, cur_y
        if dx == 0 and dy == 0:
            return
        while dx != 0 or dy != 0:
            sx, sy = max(-127, min(127, dx)), max(-127, min(127, dy))
            report = build_mouse_report(tip_down, barrel, False, sx, sy)
            intr_client.send(bytes([0xA1, 0x02]) + report)
            dx -= sx
            dy -= sy

    while True:
        data = os.read(evdev_fd, EV_SIZE)
        if len(data) < EV_SIZE:
            continue

        _, _, ev_type, ev_code, ev_value = struct.unpack(EV_FORMAT, data)

        if ev_type == EV_ABS:
            if ev_code == ABS_X: cur_x = ev_value
            elif ev_code == ABS_Y: cur_y = ev_value
            elif ev_code == ABS_PRESSURE: cur_pressure = ev_value
        elif ev_type == EV_KEY:
            if ev_code == BTN_TOUCH:
                tip_down = (ev_value == 1)
            elif ev_code in (BTN_STYLUS, BTN_STYLUS2):
                new_barrel = (ev_value == 1)
                if new_barrel and not barrel:
                    now = time.time() * 1000
                    if now - barrel_last_time < 500:
                        mouse_mode = not mouse_mode
                        print(f"Mode: {'MOUSE' if mouse_mode else 'DIGITIZER'}")
                        last_x, last_y = -1, -1
                    barrel_last_time = now
                barrel = new_barrel
            elif ev_code == BTN_TOOL_PEN:
                in_range = (ev_value == 1)
                if not in_range:
                    last_x, last_y = -1, -1
        elif ev_type == EV_SYN and ev_code == SYN_REPORT:
            if mouse_mode:
                send_mouse()
            else:
                send_digitizer()


# ---- Main ----

def main():
    parser = argparse.ArgumentParser(description="TabletPen for Sony DPT-RP1")
    parser.add_argument('--mouse', action='store_true', help='Start in mouse mode')
    parser.add_argument('--ratio', type=str, help='Target aspect ratio (16:10, 16:9, 3:2)')
    parser.add_argument('--floor', type=int, help='Pressure floor 0-100 (default: 80)')
    parser.add_argument('--curve', type=float, help='Pressure curve exponent (default: 0.5)')
    parser.add_argument('--sensitivity', type=float, help='Mouse sensitivity (default: 2.0)')
    parser.add_argument('--device', type=str, help='Input device path (auto-detect if omitted)')
    args = parser.parse_args()

    settings = load_settings()
    if args.mouse: settings['mode'] = 'mouse'
    if args.ratio: settings['aspect_ratio'] = args.ratio
    if args.floor is not None: settings['pressure_floor'] = args.floor / 100.0
    if args.curve is not None: settings['pressure_curve'] = args.curve
    if args.sensitivity is not None: settings['mouse_sensitivity'] = args.sensitivity

    print("=== TabletPen for Sony DPT-RP1 ===")
    print(f"Mode: {settings['mode']} | Ratio: {settings['aspect_ratio']} | "
          f"Floor: {settings['pressure_floor']:.0%} | Curve: {settings['pressure_curve']}")
    print(f"Double-press barrel button to toggle digitizer/mouse")
    print()

    # Find digitizer
    devpath = args.device or find_digitizer()
    if not devpath:
        print("ERROR: Could not find digitizer")
        print("Try specifying manually: --device /dev/input/event0")
        print()
        os.system("cat /proc/bus/input/devices 2>/dev/null")
        sys.exit(1)

    ranges = {}
    # DPT-RP1 known defaults: X=0-11747, Y=0-15891, P=0-2047
    for axis, name, default in [(ABS_X, 'x', (0, 11747)), (ABS_Y, 'y', (0, 15891)),
                                 (ABS_PRESSURE, 'pressure', (0, 2047))]:
        r = get_axis_range(devpath, axis)
        ranges[name] = r if r else default
        print(f"  {name}: {ranges[name][0]}-{ranges[name][1]}")

    setup_bluetooth()

    try:
        ctrl_sock, intr_sock = open_hid_sockets()
    except PermissionError:
        print("ERROR: Need root")
        sys.exit(1)
    except OSError as e:
        print(f"ERROR: {e}")
        print("If 'Address already in use': kill other BT HID services first")
        print("  killall -9 com.sony.dp.hid 2>/dev/null")
        sys.exit(1)

    running = True
    def cleanup(sig=None, frame=None):
        nonlocal running
        running = False
        sys.exit(0)

    signal.signal(signal.SIGINT, cleanup)
    signal.signal(signal.SIGTERM, cleanup)

    while running:
        ctrl_client, intr_client = try_auto_connect(settings, ctrl_sock, intr_sock)

        if not ctrl_client:
            print("Waiting for laptop to connect...")
            print("On laptop: Bluetooth → pair with 'TabletPen DPT'")
            ctrl_client, ctrl_addr = ctrl_sock.accept()
            intr_client, _ = intr_sock.accept()
            addr = ctrl_addr[0] if ctrl_addr else None
            if addr:
                settings['last_device'] = addr
                save_settings(settings)
                print(f"Saved {addr} for auto-reconnect")

        print("Connected! Reading pen input...")
        evdev_fd = os.open(devpath, os.O_RDONLY)

        try:
            run_pen_loop(settings, evdev_fd, intr_client, ranges)
        except (BrokenPipeError, OSError) as e:
            print(f"Connection lost: {e}")
        finally:
            os.close(evdev_fd)
            try: ctrl_client.close()
            except: pass
            try: intr_client.close()
            except: pass

        if running:
            print("Reconnecting in 3s...")
            time.sleep(3)


if __name__ == '__main__':
    main()
