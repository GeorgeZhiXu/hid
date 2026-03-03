#!/usr/bin/env python3
"""
TabletPen for reMarkable Paper Pro

Turns the reMarkable into a Bluetooth HID digitizer tablet.
Reads Wacom pen input via evdev, sends HID reports over Bluetooth.

Features:
  - Digitizer mode (absolute pen) and Mouse mode (relative, barrel toggle)
  - Pressure tuning (floor + curve, matching Android app)
  - Aspect ratio mapping (rM 4:3 → laptop 16:10/16:9/3:2)
  - Auto-reconnect to last paired laptop
  - Settings persistence (~/.tabletpen.conf)

Requirements:
  - reMarkable Paper Pro with Developer Mode enabled
  - Bluetooth module loaded: modprobe btnxpuart
  - BlueZ running: systemctl start bluetooth
  - Python 3

Usage:
  python3 tabletpen.py [--mouse] [--ratio 16:10] [--floor 80] [--curve 0.5]
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
BTN_TOOL_PEN = 0x140

EV_FORMAT = 'llHHi'
EV_SIZE = struct.calcsize(EV_FORMAT)

# ---- HID constants ----
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

# SDP record for HID device
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
  <attribute id="0x0101"><text value="reMarkable HID Digitizer" /></attribute>
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

CONFIG_PATH = os.path.expanduser("~/.tabletpen.conf")

DEFAULT_SETTINGS = {
    "mode": "digitizer",        # "digitizer" or "mouse"
    "aspect_ratio": "16:10",    # target laptop ratio
    "pressure_floor": 0.8,      # 0.0-1.0, min pressure when tip down
    "pressure_curve": 0.5,      # exponent (lower = more sensitive)
    "mouse_sensitivity": 2.0,   # multiplier for mouse deltas
    "last_device": None,        # BT address of last connected laptop
}


def load_settings():
    settings = dict(DEFAULT_SETTINGS)
    try:
        with open(CONFIG_PATH, 'r') as f:
            saved = json.load(f)
            settings.update(saved)
    except (FileNotFoundError, json.JSONDecodeError):
        pass
    return settings


def save_settings(settings):
    try:
        with open(CONFIG_PATH, 'w') as f:
            json.dump(settings, f, indent=2)
    except OSError:
        pass


def parse_ratio(ratio_str):
    """Parse '16:10' into (16, 10) tuple."""
    parts = ratio_str.split(':')
    if len(parts) == 2:
        return int(parts[0]), int(parts[1])
    return 16, 10


# ---- Device discovery ----

def find_wacom_device():
    for devpath in sorted(glob.glob('/dev/input/event*')):
        try:
            with open(devpath, 'rb') as f:
                buf = array.array('B', [0] * 256)
                fcntl.ioctl(f, 0x80FF0106, buf)
                name = buf.tobytes().split(b'\x00')[0].decode('utf-8', errors='ignore').lower()
                if 'wacom' in name or 'pen' in name or 'digitizer' in name:
                    print(f"Found digitizer: {devpath} ({name})")
                    return devpath
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
    buttons = (int(tip)) | (int(barrel) << 1) | (int(in_range) << 2)
    return struct.pack('<BHHH',
        buttons,
        max(0, min(X_MAX, x)),
        max(0, min(Y_MAX, y)),
        max(0, min(P_MAX, pressure))
    )


def build_mouse_report(left, right, middle, dx, dy, scroll=0):
    buttons = int(left) | (int(right) << 1) | (int(middle) << 2)
    return struct.pack('<Bbbb',
        buttons,
        max(-127, min(127, dx)),
        max(-127, min(127, dy)),
        max(-127, min(127, scroll))
    )


# ---- Bluetooth ----

def setup_bluetooth():
    print("Setting up Bluetooth...")
    os.system("modprobe btnxpuart 2>/dev/null")
    os.system("systemctl start bluetooth 2>/dev/null")
    time.sleep(2)
    os.system("bluetoothctl power on")
    time.sleep(1)
    os.system("bluetoothctl discoverable on")
    os.system("bluetoothctl pairable on")
    os.system("bluetoothctl agent NoInputNoOutput")
    os.system("bluetoothctl default-agent")

    sdp_path = "/tmp/tabletpen_sdp.xml"
    with open(sdp_path, 'w') as f:
        f.write(SDP_RECORD)
    os.system("sdptool add --handle=0x10001 --channel=1 SP 2>/dev/null")

    print("Bluetooth discoverable.")


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
    """Try connecting to the last known device."""
    addr = settings.get("last_device")
    if not addr:
        return None, None

    print(f"Auto-connecting to {addr}...")
    try:
        os.system(f"bluetoothctl connect {addr} 2>/dev/null")
        time.sleep(3)
        # Wait briefly for incoming connection
        ctrl_sock.settimeout(5.0)
        intr_sock.settimeout(5.0)
        ctrl_client, _ = ctrl_sock.accept()
        intr_client, _ = intr_sock.accept()
        ctrl_sock.settimeout(None)
        intr_sock.settimeout(None)
        print(f"Auto-connected to {addr}")
        return ctrl_client, intr_client
    except (socket.timeout, OSError):
        ctrl_sock.settimeout(None)
        intr_sock.settimeout(None)
        print("Auto-connect failed, waiting for manual pairing...")
        return None, None


# ---- Main loop ----

def run_pen_loop(settings, evdev_fd, intr_client, ranges):
    x_min, x_max = ranges['x']
    y_min, y_max = ranges['y']
    p_min, p_max = ranges['pressure']

    mode = settings['mode']
    floor = settings['pressure_floor']
    curve = settings['pressure_curve']
    sensitivity = settings['mouse_sensitivity']

    # Aspect ratio mapping
    ratio_w, ratio_h = parse_ratio(settings['aspect_ratio'])
    target_ratio = ratio_w / ratio_h
    # rM screen ratio (portrait: width < height)
    rm_x_range = x_max - x_min
    rm_y_range = y_max - y_min
    rm_ratio = rm_x_range / rm_y_range if rm_y_range > 0 else 1.0

    # Compute active area within rM coordinates to match target ratio
    if rm_ratio > target_ratio:
        # rM is wider — constrain X
        active_y_range = rm_y_range
        active_x_range = int(rm_y_range * target_ratio)
        x_offset = (rm_x_range - active_x_range) // 2
        y_offset = 0
    else:
        # rM is taller — constrain Y
        active_x_range = rm_x_range
        active_y_range = int(rm_x_range / target_ratio)
        x_offset = 0
        y_offset = (rm_y_range - active_y_range) // 2

    print(f"Aspect ratio: {ratio_w}:{ratio_h} (target {target_ratio:.2f}, rM {rm_ratio:.2f})")
    print(f"Active area: X offset={x_offset} range={active_x_range}, Y offset={y_offset} range={active_y_range}")

    # State
    tip_down = False
    barrel = False
    in_range = False
    cur_x = 0
    cur_y = 0
    cur_pressure = 0

    # Mouse mode state
    last_x = -1
    last_y = -1
    mouse_mode_active = (mode == "mouse")

    def apply_pressure(raw_p):
        """Apply floor + curve, matching Android app logic."""
        normalized = (raw_p - p_min) / (p_max - p_min) if p_max > p_min else 0
        curved = math.pow(max(0, normalized), curve)
        if tip_down:
            return int((floor + curved * (1.0 - floor)) * P_MAX)
        return 0

    def send_digitizer():
        # Map to active area
        ax = cur_x - x_min - x_offset
        ay = cur_y - y_min - y_offset

        nx = int(max(0, min(1, ax / active_x_range)) * X_MAX) if active_x_range > 0 else 0
        ny = int(max(0, min(1, ay / active_y_range)) * Y_MAX) if active_y_range > 0 else 0
        np = apply_pressure(cur_pressure)

        report = build_digitizer_report(tip_down, barrel, in_range, nx, ny, np)
        intr_client.send(bytes([0xA1, 0x01]) + report)

    def send_mouse():
        nonlocal last_x, last_y
        if last_x < 0 or last_y < 0:
            last_x = cur_x
            last_y = cur_y
            return

        dx = int((cur_x - last_x) * sensitivity)
        dy = int((cur_y - last_y) * sensitivity)
        last_x = cur_x
        last_y = cur_y

        if dx == 0 and dy == 0:
            return

        # Send in chunks for large deltas
        while dx != 0 or dy != 0:
            sx = max(-127, min(127, dx))
            sy = max(-127, min(127, dy))
            report = build_mouse_report(tip_down, barrel, False, sx, sy)
            intr_client.send(bytes([0xA1, 0x02]) + report)
            dx -= sx
            dy -= sy

    # Toggle mouse mode with barrel button double-press
    barrel_last_time = 0
    DOUBLE_PRESS_MS = 500

    while True:
        data = os.read(evdev_fd, EV_SIZE)
        if len(data) < EV_SIZE:
            continue

        _, _, ev_type, ev_code, ev_value = struct.unpack(EV_FORMAT, data)

        if ev_type == EV_ABS:
            if ev_code == ABS_X:
                cur_x = ev_value
            elif ev_code == ABS_Y:
                cur_y = ev_value
            elif ev_code == ABS_PRESSURE:
                cur_pressure = ev_value
        elif ev_type == EV_KEY:
            if ev_code == BTN_TOUCH:
                tip_down = (ev_value == 1)
            elif ev_code == BTN_STYLUS:
                new_barrel = (ev_value == 1)
                if new_barrel and not barrel:
                    # Barrel pressed — check for double-press to toggle mode
                    now = time.time() * 1000
                    if now - barrel_last_time < DOUBLE_PRESS_MS:
                        mouse_mode_active = not mouse_mode_active
                        mode_name = "MOUSE" if mouse_mode_active else "DIGITIZER"
                        print(f"Mode: {mode_name}")
                        last_x = -1
                        last_y = -1
                    barrel_last_time = now
                barrel = new_barrel
            elif ev_code == BTN_TOOL_PEN:
                in_range = (ev_value == 1)
                if not in_range:
                    last_x = -1
                    last_y = -1
        elif ev_type == EV_SYN and ev_code == SYN_REPORT:
            if mouse_mode_active:
                send_mouse()
            else:
                send_digitizer()


def main():
    parser = argparse.ArgumentParser(description="TabletPen for reMarkable Paper Pro")
    parser.add_argument('--mouse', action='store_true', help='Start in mouse mode')
    parser.add_argument('--ratio', type=str, help='Target aspect ratio (e.g., 16:10, 16:9, 3:2)')
    parser.add_argument('--floor', type=int, help='Pressure floor 0-100 (default: 80)')
    parser.add_argument('--curve', type=float, help='Pressure curve exponent (default: 0.5)')
    parser.add_argument('--sensitivity', type=float, help='Mouse sensitivity (default: 2.0)')
    args = parser.parse_args()

    settings = load_settings()

    # Apply command-line overrides
    if args.mouse:
        settings['mode'] = 'mouse'
    if args.ratio:
        settings['aspect_ratio'] = args.ratio
    if args.floor is not None:
        settings['pressure_floor'] = args.floor / 100.0
    if args.curve is not None:
        settings['pressure_curve'] = args.curve
    if args.sensitivity is not None:
        settings['mouse_sensitivity'] = args.sensitivity

    print("=== TabletPen for reMarkable Paper Pro ===")
    print(f"Mode: {settings['mode']}")
    print(f"Aspect ratio: {settings['aspect_ratio']}")
    print(f"Pressure: floor={settings['pressure_floor']:.0%} curve={settings['pressure_curve']}")
    print(f"Mouse sensitivity: {settings['mouse_sensitivity']}x")
    print(f"Tip: double-press barrel button to toggle digitizer/mouse mode")
    print()

    # Find Wacom device
    devpath = find_wacom_device()
    if not devpath:
        print("ERROR: Could not find Wacom digitizer")
        os.system("cat /proc/bus/input/devices 2>/dev/null")
        sys.exit(1)

    ranges = {}
    for axis, name, default in [(ABS_X, 'x', (0, 20966)), (ABS_Y, 'y', (0, 15725)), (ABS_PRESSURE, 'pressure', (0, 4095))]:
        r = get_axis_range(devpath, axis)
        ranges[name] = r if r else default
        print(f"  {name}: {ranges[name][0]}-{ranges[name][1]}")

    # Bluetooth setup
    setup_bluetooth()

    try:
        ctrl_sock, intr_sock = open_hid_sockets()
    except PermissionError:
        print("ERROR: Need root. Run as: sudo python3 tabletpen.py")
        sys.exit(1)
    except OSError as e:
        print(f"ERROR: {e}")
        sys.exit(1)

    running = True

    def cleanup(sig=None, frame=None):
        nonlocal running
        running = False
        print("\nShutting down...")
        sys.exit(0)

    signal.signal(signal.SIGINT, cleanup)
    signal.signal(signal.SIGTERM, cleanup)

    while running:
        # Try auto-connect first
        ctrl_client, intr_client = try_auto_connect(settings, ctrl_sock, intr_sock)

        if not ctrl_client:
            print("Waiting for laptop to connect...")
            print("On your laptop: Bluetooth settings → pair with this device")
            ctrl_client, ctrl_addr = ctrl_sock.accept()
            intr_client, intr_addr = intr_sock.accept()
            # Save for auto-reconnect
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
            print("Reconnecting in 3 seconds...")
            time.sleep(3)


if __name__ == '__main__':
    main()
