#!/usr/bin/env python3
"""
TabletPen for reMarkable Paper Pro

Turns the reMarkable into a Bluetooth HID digitizer tablet.
Reads Wacom pen input via evdev, sends HID reports over Bluetooth.

Requirements:
  - reMarkable Paper Pro with Developer Mode enabled
  - Bluetooth module loaded: modprobe btnxpuart
  - BlueZ running: systemctl start bluetooth
  - Python 3 (deploy via SCP or bundle)

Usage:
  ssh root@10.11.99.1
  modprobe btnxpuart
  systemctl start bluetooth
  python3 /home/root/tabletpen.py
"""

import struct
import os
import sys
import glob
import subprocess
import time
import socket
import signal

# ---- evdev constants ----
EV_SYN = 0x00
EV_KEY = 0x01
EV_ABS = 0x03
SYN_REPORT = 0x00

ABS_X = 0x00
ABS_Y = 0x01
ABS_PRESSURE = 0x18  # ABS_PRESSURE
ABS_TILT_X = 0x1a
ABS_TILT_Y = 0x1b

BTN_TOUCH = 0x14a
BTN_STYLUS = 0x14b
BTN_TOOL_PEN = 0x140

# evdev event struct: time_sec(L) time_usec(L) type(H) code(H) value(i)
EV_FORMAT = 'llHHi'
EV_SIZE = struct.calcsize(EV_FORMAT)

# ---- HID descriptor (same as Android app) ----
# Report ID 1: Digitizer pen
# Byte 0: [Tip][Barrel][InRange][pad*5]
# Byte 1-2: X (uint16 LE, 0-32767)
# Byte 3-4: Y (uint16 LE, 0-32767)
# Byte 5-6: Pressure (uint16 LE, 0-4095)

HID_DESCRIPTOR = bytes([
    # Digitizer Pen (Report ID 1)
    0x05, 0x0D,        # Usage Page (Digitizer)
    0x09, 0x02,        # Usage (Pen)
    0xA1, 0x01,        # Collection (Application)
    0x85, 0x01,        # Report ID (1)
    0x09, 0x20,        # Usage (Stylus)
    0xA1, 0x00,        # Collection (Physical)
    0x09, 0x42,        # Usage (Tip Switch)
    0x09, 0x44,        # Usage (Barrel Switch)
    0x09, 0x32,        # Usage (In Range)
    0x15, 0x00,        # Logical Minimum (0)
    0x25, 0x01,        # Logical Maximum (1)
    0x75, 0x01,        # Report Size (1)
    0x95, 0x03,        # Report Count (3)
    0x81, 0x02,        # Input (Data, Variable, Absolute)
    0x75, 0x05,        # Report Size (5)
    0x95, 0x01,        # Report Count (1)
    0x81, 0x03,        # Input (Constant)
    0x05, 0x01,        # Usage Page (Generic Desktop)
    0x09, 0x30,        # Usage (X)
    0x15, 0x00,        # Logical Minimum (0)
    0x26, 0xFF, 0x7F,  # Logical Maximum (32767)
    0x75, 0x10,        # Report Size (16)
    0x95, 0x01,        # Report Count (1)
    0x81, 0x02,        # Input (Data, Variable, Absolute)
    0x09, 0x31,        # Usage (Y)
    0x81, 0x02,        # Input (Data, Variable, Absolute)
    0x05, 0x0D,        # Usage Page (Digitizer)
    0x09, 0x30,        # Usage (Tip Pressure)
    0x26, 0xFF, 0x0F,  # Logical Maximum (4095)
    0x81, 0x02,        # Input (Data, Variable, Absolute)
    0xC0,              # End Collection
    0xC0,              # End Collection
    # Mouse (Report ID 2)
    0x05, 0x01,        # Usage Page (Generic Desktop)
    0x09, 0x02,        # Usage (Mouse)
    0xA1, 0x01,        # Collection (Application)
    0x85, 0x02,        # Report ID (2)
    0x09, 0x01,        # Usage (Pointer)
    0xA1, 0x00,        # Collection (Physical)
    0x05, 0x09,        # Usage Page (Button)
    0x19, 0x01,        # Usage Minimum (1)
    0x29, 0x03,        # Usage Maximum (3)
    0x15, 0x00,        # Logical Minimum (0)
    0x25, 0x01,        # Logical Maximum (1)
    0x75, 0x01,        # Report Size (1)
    0x95, 0x03,        # Report Count (3)
    0x81, 0x02,        # Input (Data, Variable, Absolute)
    0x75, 0x05,        # Report Size (5)
    0x95, 0x01,        # Report Count (1)
    0x81, 0x03,        # Input (Constant)
    0x05, 0x01,        # Usage Page (Generic Desktop)
    0x09, 0x30,        # Usage (X)
    0x09, 0x31,        # Usage (Y)
    0x15, 0x81,        # Logical Minimum (-127)
    0x25, 0x7F,        # Logical Maximum (127)
    0x75, 0x08,        # Report Size (8)
    0x95, 0x02,        # Report Count (2)
    0x81, 0x06,        # Input (Data, Variable, Relative)
    0x09, 0x38,        # Usage (Wheel)
    0x15, 0x81,        # Logical Minimum (-127)
    0x25, 0x7F,        # Logical Maximum (127)
    0x75, 0x08,        # Report Size (8)
    0x95, 0x01,        # Report Count (1)
    0x81, 0x06,        # Input (Data, Variable, Relative)
    0xC0,              # End Collection
    0xC0,              # End Collection
])

X_MAX = 32767
Y_MAX = 32767
P_MAX = 4095

# ---- BlueZ D-Bus HID Profile ----

DBUS_PROFILE_XML = """
<?xml version="1.0" encoding="UTF-8" ?>
<node>
  <interface name="org.bluez.Profile1">
    <method name="Release" />
    <method name="NewConnection">
      <arg type="o" direction="in" name="device" />
      <arg type="h" direction="in" name="fd" />
      <arg type="a{sv}" direction="in" name="fd_properties" />
    </method>
    <method name="RequestDisconnection">
      <arg type="o" direction="in" name="device" />
    </method>
  </interface>
</node>
"""

# SDP record for HID device
SDP_RECORD = f"""<?xml version="1.0" encoding="UTF-8" ?>
<record>
  <attribute id="0x0001">
    <sequence>
      <uuid value="0x1124" />
    </sequence>
  </attribute>
  <attribute id="0x0004">
    <sequence>
      <sequence>
        <uuid value="0x0100" />
        <uint16 value="0x0011" />
      </sequence>
      <sequence>
        <uuid value="0x0011" />
      </sequence>
    </sequence>
  </attribute>
  <attribute id="0x0005">
    <sequence>
      <uuid value="0x1002" />
    </sequence>
  </attribute>
  <attribute id="0x0006">
    <sequence>
      <uint16 value="0x656e" />
      <uint16 value="0x006a" />
      <uint16 value="0x0100" />
    </sequence>
  </attribute>
  <attribute id="0x0009">
    <sequence>
      <sequence>
        <uuid value="0x1124" />
        <uint16 value="0x0100" />
      </sequence>
    </sequence>
  </attribute>
  <attribute id="0x000d">
    <sequence>
      <sequence>
        <sequence>
          <uuid value="0x0100" />
          <uint16 value="0x0013" />
        </sequence>
        <sequence>
          <uuid value="0x0011" />
        </sequence>
      </sequence>
    </sequence>
  </attribute>
  <attribute id="0x0100">
    <text value="TabletPen" />
  </attribute>
  <attribute id="0x0101">
    <text value="reMarkable HID Digitizer" />
  </attribute>
  <attribute id="0x0102">
    <text value="TabletPen" />
  </attribute>
  <attribute id="0x0200">
    <uint16 value="0x0100" />
  </attribute>
  <attribute id="0x0201">
    <uint16 value="0x0005" />
  </attribute>
  <attribute id="0x0202">
    <uint16 value="0x00c5" />
  </attribute>
  <attribute id="0x0203">
    <boolean value="true" />
  </attribute>
  <attribute id="0x0204">
    <boolean value="true" />
  </attribute>
  <attribute id="0x0205">
    <boolean value="false" />
  </attribute>
  <attribute id="0x0206">
    <sequence>
      <sequence>
        <uint8 value="0x22" />
        <text encoding="hex" value="{HID_DESCRIPTOR.hex()}" />
      </sequence>
    </sequence>
  </attribute>
  <attribute id="0x0207">
    <sequence>
      <sequence>
        <uint16 value="0x0409" />
        <uint16 value="0x0100" />
      </sequence>
    </sequence>
  </attribute>
  <attribute id="0x020b">
    <uint16 value="0x0100" />
  </attribute>
  <attribute id="0x020c">
    <uint16 value="0x0c80" />
  </attribute>
  <attribute id="0x020d">
    <boolean value="false" />
  </attribute>
  <attribute id="0x020e">
    <boolean value="true" />
  </attribute>
</record>
"""


def find_wacom_device():
    """Find the Wacom digitizer event device."""
    for devpath in sorted(glob.glob('/dev/input/event*')):
        try:
            with open(devpath, 'rb') as f:
                # Read device name via ioctl EVIOCGNAME
                import fcntl
                import array
                buf = array.array('B', [0] * 256)
                fcntl.ioctl(f, 0x80FF0106, buf)  # EVIOCGNAME(256)
                name = buf.tobytes().split(b'\x00')[0].decode('utf-8', errors='ignore').lower()
                if 'wacom' in name or 'pen' in name or 'digitizer' in name:
                    print(f"Found digitizer: {devpath} ({name})")
                    return devpath
        except (PermissionError, OSError):
            continue
    return None


def find_wacom_ranges(devpath):
    """Get ABS axis ranges from the device."""
    ranges = {}
    try:
        with open(devpath, 'rb') as f:
            import fcntl
            for axis, name in [(ABS_X, 'x'), (ABS_Y, 'y'), (ABS_PRESSURE, 'pressure')]:
                # EVIOCGABS(axis) = 0x80184040 + axis
                buf = struct.pack('iiiii', 0, 0, 0, 0, 0)
                try:
                    result = fcntl.ioctl(f, 0x80184040 + axis, buf)
                    value, minimum, maximum, fuzz, flat = struct.unpack('iiiii', result)
                    ranges[name] = (minimum, maximum)
                    print(f"  {name}: {minimum}-{maximum}")
                except OSError:
                    pass
    except (PermissionError, OSError):
        pass
    return ranges


def build_report(tip, barrel, in_range, x, y, pressure):
    """Build a 7-byte HID digitizer report."""
    buttons = 0
    if tip: buttons |= 0x01
    if barrel: buttons |= 0x02
    if in_range: buttons |= 0x04

    x = max(0, min(X_MAX, x))
    y = max(0, min(Y_MAX, y))
    pressure = max(0, min(P_MAX, pressure))

    return struct.pack('<BHHh', buttons, x, y, pressure)


def setup_bluetooth():
    """Set up BlueZ for HID device mode using bluetoothctl."""
    print("Setting up Bluetooth...")

    # Ensure Bluetooth is running
    os.system("modprobe btnxpuart 2>/dev/null")
    os.system("systemctl start bluetooth 2>/dev/null")
    time.sleep(2)

    # Make discoverable
    os.system("bluetoothctl power on")
    time.sleep(1)
    os.system("bluetoothctl discoverable on")
    os.system("bluetoothctl pairable on")
    os.system("bluetoothctl agent NoInputNoOutput")
    os.system("bluetoothctl default-agent")

    print("Bluetooth is discoverable. Pair from your laptop.")


def register_hid_profile():
    """Register HID profile via D-Bus using bluetoothctl/sdptool."""
    # Write SDP record
    sdp_path = "/tmp/tabletpen_sdp.xml"
    with open(sdp_path, 'w') as f:
        f.write(SDP_RECORD)

    # Try to register via sdptool
    result = os.system(f"sdptool add --handle=0x10001 --channel=1 SP 2>/dev/null")
    if result != 0:
        print("Note: sdptool not available, using manual Bluetooth setup")

    return sdp_path


def open_bt_hid_socket():
    """Open Bluetooth L2CAP sockets for HID control and interrupt channels."""
    # HID uses L2CAP PSM 17 (control) and PSM 19 (interrupt)
    PSM_CTRL = 0x11  # 17
    PSM_INTR = 0x13  # 19

    ctrl_sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    intr_sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)

    ctrl_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    intr_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    ctrl_sock.bind(("", PSM_CTRL))
    intr_sock.bind(("", PSM_INTR))

    ctrl_sock.listen(1)
    intr_sock.listen(1)

    print(f"Listening on L2CAP PSM {PSM_CTRL} (control) and {PSM_INTR} (interrupt)")
    return ctrl_sock, intr_sock


def main():
    print("=== TabletPen for reMarkable Paper Pro ===")
    print()

    # Find Wacom device
    devpath = find_wacom_device()
    if not devpath:
        print("ERROR: Could not find Wacom digitizer device")
        print("Available input devices:")
        os.system("cat /proc/bus/input/devices 2>/dev/null")
        sys.exit(1)

    ranges = find_wacom_ranges(devpath)
    x_min, x_max = ranges.get('x', (0, 20966))
    y_min, y_max = ranges.get('y', (0, 15725))
    p_min, p_max = ranges.get('pressure', (0, 4095))

    print(f"Input ranges: X={x_min}-{x_max} Y={y_min}-{y_max} P={p_min}-{p_max}")

    # Set up Bluetooth
    setup_bluetooth()
    register_hid_profile()

    # Open L2CAP sockets for HID
    try:
        ctrl_sock, intr_sock = open_bt_hid_socket()
    except PermissionError:
        print("ERROR: Need root to open L2CAP sockets")
        print("Run as: sudo python3 tabletpen.py")
        sys.exit(1)
    except OSError as e:
        print(f"ERROR: Could not open L2CAP sockets: {e}")
        print("Make sure Bluetooth is enabled and no other HID service is running")
        sys.exit(1)

    print()
    print("Waiting for laptop to connect...")
    print("On your laptop: Bluetooth settings → pair with this device")
    print()

    ctrl_client, ctrl_addr = ctrl_sock.accept()
    print(f"Control channel connected: {ctrl_addr}")
    intr_client, intr_addr = intr_sock.accept()
    print(f"Interrupt channel connected: {intr_addr}")
    print("Connected! Reading pen input...")

    # Open evdev device
    evdev_fd = os.open(devpath, os.O_RDONLY)

    # State
    tip_down = False
    barrel = False
    in_range = False
    cur_x = 0
    cur_y = 0
    cur_pressure = 0

    def send_report():
        # Normalize to HID ranges
        nx = int((cur_x - x_min) / (x_max - x_min) * X_MAX) if x_max > x_min else 0
        ny = int((cur_y - y_min) / (y_max - y_min) * Y_MAX) if y_max > y_min else 0
        np = int((cur_pressure - p_min) / (p_max - p_min) * P_MAX) if p_max > p_min else 0

        report = build_report(tip_down, barrel, in_range, nx, ny, np)
        # HID interrupt report: 0xA1 (DATA | INPUT) + report_id + data
        packet = bytes([0xA1, 0x01]) + report
        try:
            intr_client.send(packet)
        except (BrokenPipeError, OSError):
            print("Connection lost")
            raise

    def cleanup(sig=None, frame=None):
        print("\nShutting down...")
        try: ctrl_client.close()
        except: pass
        try: intr_client.close()
        except: pass
        try: ctrl_sock.close()
        except: pass
        try: intr_sock.close()
        except: pass
        os.close(evdev_fd)
        sys.exit(0)

    signal.signal(signal.SIGINT, cleanup)
    signal.signal(signal.SIGTERM, cleanup)

    try:
        while True:
            data = os.read(evdev_fd, EV_SIZE)
            if len(data) < EV_SIZE:
                continue

            tv_sec, tv_usec, ev_type, ev_code, ev_value = struct.unpack(EV_FORMAT, data)

            if ev_type == EV_ABS:
                if ev_code == ABS_X:
                    cur_x = ev_value
                elif ev_code == ABS_Y:
                    cur_y = ev_value
                elif ev_code == ABS_PRESSURE:
                    cur_pressure = ev_value
            elif ev_type == EV_KEY:
                if ev_code == BTN_TOUCH:
                    tip_down = ev_value == 1
                elif ev_code == BTN_STYLUS:
                    barrel = ev_value == 1
                elif ev_code == BTN_TOOL_PEN:
                    in_range = ev_value == 1
            elif ev_type == EV_SYN and ev_code == SYN_REPORT:
                send_report()

    except (BrokenPipeError, OSError):
        print("Connection lost. Restarting...")
    finally:
        cleanup()


if __name__ == '__main__':
    main()
