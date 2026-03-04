# TabletPen for Sony DPT-RP1 / DPT-S1

Turn a rooted Sony Digital Paper into a Bluetooth HID drawing tablet.

The Sony DPT runs Android 5.1 which lacks the `BluetoothHidDevice` API (requires Android 9+). This version bypasses Android entirely and uses raw Linux evdev + L2CAP Bluetooth sockets, which work on any Linux/Android version with root.

## Requirements

- **Sony DPT-RP1** or **DPT-S1** with root access
- Root via [dpt-rp1-py](https://github.com/janten/dpt-rp1-py) (dpt-tools)
- Python 3 deployed to the device
- Bluetooth enabled

## Device Specs

| | DPT-RP1 | DPT-S1 |
|---|---|---|
| Screen | 1404x1872, 13.3" | 1200x1600, 13.3" |
| Android | 5.1.1 (API 22) | 4.0.3 (API 15) |
| Digitizer | Wacom EMR | Wacom EMR |
| Bluetooth | Yes | Yes |
| Digitizer range | X: 0-11747, Y: 0-15891 | Similar |

## Setup

### 1. Root the DPT

Follow [dpt-rp1-py](https://github.com/janten/dpt-rp1-py) instructions:

```bash
pip install dpt-rp1-py
python3 -m dptrp1 register
python3 -m dptrp1 enable-wifi-ssh
```

### 2. Deploy Python 3

The DPT doesn't have Python pre-installed. Deploy a static ARM build:

```bash
# Option A: Use a pre-built static Python for ARM
# Option B: Cross-compile from source
# Option C: Use Termux's Python if installable

# Copy to device
scp python3 root@<dpt-ip>:/data/local/tmp/python3
ssh root@<dpt-ip> "chmod +x /data/local/tmp/python3"
```

### 3. Deploy TabletPen

```bash
scp tabletpen.py root@<dpt-ip>:/data/local/tmp/
ssh root@<dpt-ip>
/data/local/tmp/python3 /data/local/tmp/tabletpen.py
```

### 4. Pair

On your laptop: Bluetooth settings → pair with "TabletPen DPT"

## Usage

```bash
# Default: digitizer mode, 16:10 ratio
python3 tabletpen.py

# Mouse mode, custom ratio
python3 tabletpen.py --mouse --ratio 16:9

# Custom pressure settings
python3 tabletpen.py --floor 60 --curve 0.3

# Specify input device manually
python3 tabletpen.py --device /dev/input/event0
```

### Toggle Mouse/Digitizer

Double-press the barrel (side) button on the stylus to toggle between modes.

## Features

All features match the Android and reMarkable versions:

- **Digitizer mode** — absolute positioning with pressure
- **Mouse mode** — relative cursor movement
- **Pressure tuning** — configurable floor (80%) and curve (0.5)
- **Aspect ratio mapping** — DPT 3:4 → laptop 16:10/16:9/3:2
- **Auto-reconnect** — saves last paired device
- **Settings** — persisted to `/data/local/tmp/.tabletpen.conf`

## Troubleshooting

**"Address already in use" on L2CAP sockets:**
Sony's own Bluetooth service may be holding the HID ports. Kill it:
```bash
killall -9 com.sony.dp.hid 2>/dev/null
stop bluetooth 2>/dev/null
start bluetooth 2>/dev/null
```

**Can't find digitizer:**
List input devices to find the right one:
```bash
cat /proc/bus/input/devices
# Then specify manually:
python3 tabletpen.py --device /dev/input/event1
```

**Bluetooth not discoverable:**
```bash
hciconfig hci0 up
hciconfig hci0 piscan
```

## How It Works

Same architecture as the reMarkable version:

```
Sony DPT-RP1 (rooted Android 5.1)
┌──────────────────────────────────────┐
│  Wacom digitizer → /dev/input/event* │
│       ↓ evdev (raw Linux input)      │
│  tabletpen.py (Python 3)            │
│       ↓ L2CAP sockets               │
│  Linux Bluetooth → HID reports       │
└──────────────────┬───────────────────┘
                   │ Bluetooth
              ┌────▼─────┐
              │  Laptop   │
              └──────────┘
```

Bypasses Android's Bluetooth API entirely — talks directly to the Linux kernel's Bluetooth stack via raw L2CAP sockets (PSM 17 control, PSM 19 interrupt).
