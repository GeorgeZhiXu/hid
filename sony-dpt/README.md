# TabletPen for Sony DPT-RP1 / DPT-S1

Turn a rooted Sony Digital Paper into a Bluetooth HID drawing tablet.

The DPT runs Android 5.1 which lacks the `BluetoothHidDevice` API (requires Android 9+). This version bypasses Android entirely — a **single statically-compiled C binary** with zero dependencies. It talks directly to the Linux kernel's evdev and Bluetooth L2CAP interfaces.

## Requirements

- **Sony DPT-RP1** or **DPT-S1** with root access
- Root via [dpt-rp1-py](https://github.com/janten/dpt-rp1-py)

No Python, no Java, no libraries needed on the device. Just the binary.

## Build

Cross-compile for ARM on your Mac/Linux:

```bash
# Install Android NDK first:
sdkmanager "ndk;27.0.12077973"

# Build:
cd sony-dpt
./build.sh
```

Or with Docker (no NDK needed):
```bash
docker run --rm -v $PWD:/src -w /src arm32v7/gcc gcc -static -o tabletpen tabletpen.c -lm
```

## Deploy & Run

```bash
# Via SSH (after rooting with dpt-tools):
scp tabletpen root@<dpt-ip>:/data/local/tmp/
ssh root@<dpt-ip>
chmod +x /data/local/tmp/tabletpen
/data/local/tmp/tabletpen

# Or via ADB:
adb push tabletpen /data/local/tmp/
adb shell chmod +x /data/local/tmp/tabletpen
adb shell /data/local/tmp/tabletpen
```

Then on your laptop: Bluetooth settings → pair with "TabletPen DPT"

## Usage

```bash
tabletpen                            # digitizer mode, 16:10 ratio
tabletpen --mouse                    # mouse mode
tabletpen --ratio 16:9               # different aspect ratio
tabletpen --floor 60 --curve 0.3     # pressure tuning
tabletpen --device /dev/input/event1 # manual device selection
tabletpen --help                     # show all options
```

**Toggle mouse/digitizer**: double-press the barrel (side) button.

## Features

| Feature | Status |
|---|---|
| Digitizer pen (absolute) | Yes |
| Mouse mode (relative) | Yes (barrel double-press toggle) |
| Pressure floor + curve | Yes (80% / 0.5 default) |
| Aspect ratio mapping | Yes (16:10, 16:9, 3:2) |
| Auto-reconnect | Yes |
| Settings persistence | Yes (`/data/local/tmp/.tabletpen.conf`) |

## Device Specs

| | DPT-RP1 | DPT-S1 |
|---|---|---|
| Screen | 1404x1872, 13.3" | 1200x1600, 13.3" |
| Android | 5.1.1 | 4.0.3 |
| Digitizer | Wacom EMR | Wacom EMR |
| Pen range | X: 0-11747, Y: 0-15891 | Similar |

## Troubleshooting

**"Cannot open L2CAP sockets"**: Sony's BT service is using the ports. Kill it:
```bash
killall -9 com.sony.dp.hid 2>/dev/null
```

**Can't find digitizer**: List devices and specify manually:
```bash
cat /proc/bus/input/devices
tabletpen --device /dev/input/event0
```

## How It Works

```
Sony DPT-RP1 (rooted)
┌─────────────────────────────────────┐
│  Wacom → /dev/input/event* (evdev)  │
│       ↓                             │
│  tabletpen (static C binary)        │
│       ↓                             │
│  L2CAP PSM 17/19 → Bluetooth HID   │
└──────────────────┬──────────────────┘
                   │ Bluetooth
              ┌────▼─────┐
              │  Laptop   │
              └──────────┘
```

Pure C, statically compiled, zero runtime dependencies. Talks directly to Linux kernel interfaces — no Android framework involved.
