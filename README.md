# TabletPen

Turn a Samsung Galaxy Tab (or any Android tablet with a Wacom digitizer) into a wireless Bluetooth drawing tablet for your laptop — **no drivers or host software required**.

The app uses Android's `BluetoothHidDevice` API to register the tablet as a standard Bluetooth HID digitizer. Your laptop sees it exactly like a Wacom tablet: absolute positioning, pressure sensitivity, barrel button — all over Bluetooth.

## How It Works

```
┌─────────────────────┐        Bluetooth HID        ┌──────────────┐
│  Android Tablet      │  ────────────────────────►  │  Laptop       │
│  S Pen / Stylus      │   Digitizer + Mouse reports │  (macOS/Win)  │
│  Wacom digitizer     │   X, Y, pressure,           │               │
│                      │   tip, barrel, in-range      │  Sees a pen   │
│  TabletPen app       │◄─── Screenshot (BT RFCOMM) ─│  tablet (HID) │
└─────────────────────┘                              └──────────────┘
```

1. App registers as a Bluetooth HID device with digitizer + mouse + keyboard descriptors
2. Tablet becomes discoverable, you pair from your laptop's Bluetooth settings
3. Laptop recognizes it as a native pen tablet — no drivers needed
4. Stylus input on the tablet sends HID reports to the laptop
5. Optionally, take screenshots of the laptop screen over Bluetooth (no WiFi needed)

## Features

- **Digitizer mode** — absolute pen positioning with pressure sensitivity (0–4095 levels)
- **Mouse mode** — relative cursor movement via pen hover, left click, long-press right click
- **Trackpad finger gestures** — 1-finger move/click, 2-finger scroll/right-click, pinch zoom
- **Palm rejection** — finger = trackpad, stylus = pen input, palm = ignored
- **Pressure tuning** — configurable floor and curve to work with apps like OneNote
- **Aspect ratio mapping** — drawing area matches laptop screen ratio (16:10, 16:9, 3:2)
- **Auto orientation** — landscape/portrait based on target aspect ratio, with manual override
- **Focus mode** — zoom into a screen region, remap drawing area to that region
- **Screenshot over Bluetooth** — captures laptop screen via RFCOMM, no WiFi or USB needed
- **Adaptive screenshot quality** — auto-adjusts resolution and compression based on connection speed
- **Focused screenshot** — captures only the focused region for sharper detail and lower latency
- **Auto-recapture** — automatically re-captures screen after drawing (opt-in)
- **Pressure-sensitive strokes** — visual feedback varies with pen pressure
- **Eraser support** — pen eraser tool sends HID eraser events to drawing apps
- **Pen tilt** — X/Y tilt data for brush angle in Photoshop, Krita
- **Customizable shortcuts** — 8 configurable slots with presets: Photoshop, Krita, OneNote, Whiteboard, Excalidraw, Preview
- **Radial menu** — long-press for instant shortcut access without looking at toolbar
- **ScreenCaptureKit** — 22+ FPS streaming on macOS 12.3+ (auto-fallback to screencapture on older macOS)
- **Ghost stroke prediction** — instant visual feedback while drawing, before Mac confirms
- **Adaptive FPS** — streaming automatically drops to 1 FPS when screen is static
- **Delta compression** — only sends changed screen tiles during streaming (93% bandwidth reduction for typical drawing)
- **Auto-connect** — remembers last paired laptop and reconnects on app launch
- **Settings** — all preferences saved and persisted across sessions

## Supported Platforms

### Android Tablets (primary)
Any Android 9+ tablet with Bluetooth and stylus:
- Samsung Galaxy Tab S6/S7/S8/S9 (S Pen)
- Samsung Galaxy Z Fold with S Pen
- Boox e-ink tablets
- Bigme e-ink tablets
- Lenovo Tab P series with stylus

### reMarkable Paper Pro (experimental)
Linux-based e-ink tablet. Cross-compiled C binary, zero dependencies.
Status: Bluetooth pairing and SDP working. L2CAP HID data send pending BlueZ D-Bus fix.

### Sony DPT-RP1/S1 (experimental)
Rooted Android 5.1 e-ink reader. Zero-dependency C binary using raw Linux Bluetooth.

## HID Report Format

**Report ID 1 — Digitizer (7 bytes):**

| Byte | Field | Range |
|------|-------|-------|
| 0 | Buttons: `[0]` Tip, `[1]` Barrel, `[2]` In Range | 0/1 each |
| 1–2 | X (uint16 LE) | 0–32767 |
| 3–4 | Y (uint16 LE) | 0–32767 |
| 5–6 | Tip Pressure (uint16 LE) | 0–4095 |

**Report ID 2 — Mouse (4 bytes):**

| Byte | Field | Range |
|------|-------|-------|
| 0 | Buttons: `[0]` Left, `[1]` Right, `[2]` Middle | 0/1 each |
| 1 | X delta (int8) | -127 to 127 |
| 2 | Y delta (int8) | -127 to 127 |
| 3 | Scroll wheel (int8) | -127 to 127 |

**Report ID 3 — Keyboard (8 bytes):**

| Byte | Field | Description |
|------|-------|-------------|
| 0 | Modifiers | Bit 0: L-Ctrl, 1: L-Shift, 2: L-Alt, 3: L-GUI |
| 1 | Reserved | 0x00 |
| 2–7 | Key codes | Up to 6 simultaneous keys |

## Quick Start

### 1. Install the Android App

Download `app-debug.apk` from [Releases](https://github.com/GeorgeZhiXu/hid/releases) and install:
```bash
adb install app-debug.apk
```
Or transfer the APK to the tablet and open it.

### 2. Connect to Laptop

**Important:** The TabletPen app must be running BEFORE you pair, so the tablet registers as an HID input device.

**First-time pairing:**
1. Open **TabletPen** on the tablet
2. Grant Bluetooth permissions when prompted
3. Wait for the **Pair** button to appear (means HID is registered)
4. Tap **Pair** (makes the tablet discoverable for 5 minutes)
5. On your laptop: **System Settings → Bluetooth** → the tablet appears → click **Connect**
6. The laptop now sees the tablet as a pen/mouse input device
7. The tablet's dropdown shows the laptop name with `[Pen]` or `[Mouse]` tag
8. Draw with the stylus — laptop cursor follows

**Subsequent launches:**
1. Turn on Bluetooth on both devices
2. Open **TabletPen** — it auto-connects to the last paired laptop
3. If the laptop doesn't appear in the dropdown, tap **Pair** to re-discover

**Switching between laptops:**
- The dropdown shows all bonded computers (filtered by Bluetooth device class)
- Select a different laptop to disconnect from the current one and connect to the new one

**What the dropdown shows:**
- Only computers and phones (not speakers, headphones, mice, etc.)
- Currently connected device is marked with `[Pen]` or `[Mouse]`
- `"No computers paired"` if no eligible devices are bonded

### 3. Screenshot Feature (Optional)

See your laptop screen on the tablet while drawing.

**On the Mac:**
```bash
# Download both from Releases, or compile:
cd mac && ./build.sh
./screenshot-server

# Or with device filter (faster if you have many BT devices):
./screenshot-server TabUltra
```

The server connects to the tablet via Bluetooth RFCOMM. If both devices are on the same WiFi, it also establishes a fast WiFi link (~376ms vs ~4s over Bluetooth).

**On the tablet:**
- Tap **Screenshot** — captures and displays the laptop screen
- Tap **Stream** (visible when WiFi connected) — continuous live screen mirroring
- Tap **Focus** — draw a rectangle to zoom into a screen region
- Tap **Reset Focus** — go back to full screen

**Connection priority:**
1. WiFi TCP (fastest, ~100-400ms) — auto-discovered via Bluetooth
2. Bluetooth RFCOMM (fallback, ~3-8s) — always available
3. Screenshot and HID always target the same laptop (tied together)

## Settings

Tap **Settings** in the app to configure:

| Setting | Default | Description |
|---------|---------|-------------|
| Input Mode | Digitizer | Digitizer (absolute pen) or Mouse (relative) |
| Orientation | Auto | Auto, Portrait, or Landscape |
| Rotation | 0° | Which edge is up (0/90/180/270) |
| Aspect Ratio | 16:10 | Match your laptop (16:10, 16:9, 3:2) |
| Clear on Screenshot | On | Auto-clear strokes on new screenshot |
| Pressure Floor | 80% | Min pressure when pen touches |
| Pressure Curve | 0.50 | Exponent (lower = more sensitive) |
| Mouse Sensitivity | 2.0x | Cursor speed in mouse mode |
| Scroll Sensitivity | 2.0x | Two-finger scroll speed |
| Pinch Sensitivity | 30 | Pinch-to-zoom multiplier |
| Pinch Threshold | 1.0% | Lower = easier to pinch vs scroll |

## Finger Trackpad Gestures

| Gesture | Action |
|---------|--------|
| 1 finger drag | Move mouse cursor |
| 1 finger tap | Left click |
| 2 finger tap | Right click |
| 2 finger drag | Scroll (vertical + horizontal) |
| 2 finger pinch | Zoom (Ctrl+scroll on laptop) |

## Building from Source

### Android App

**Requirements:**
- Java 17+
- Android SDK (API 34)
- Android Build Tools 34.0.0
- Gradle 8.5 (wrapper included)
- Kotlin 1.9.22

```bash
# Build
./gradlew assembleDebug

# Install directly
./gradlew installDebug
```

**Dependencies:** AndroidX Core KTX 1.12.0, AppCompat 1.6.1, Material 1.11.0. No third-party libraries.

### Mac Screenshot Server

**Requirements:**
- macOS with Xcode Command Line Tools (`xcode-select --install`)
- Swift compiler (`swiftc`) — included with Xcode CLI tools
- IOBluetooth framework — included with macOS

```bash
cd mac
./build.sh    # compiles screenshot-server from screenshot-server.swift
./screenshot-server
```

Also includes `screenshot-server.sh` — a Python-based HTTP fallback that works over ADB reverse tunnel:
```bash
# Requires: Python 3 (pre-installed on macOS via Homebrew/pyenv)
adb reverse tcp:9877 tcp:9877
./screenshot-server.sh
```

### reMarkable Paper Pro

**Requirements:**
- [Zig](https://ziglang.org/) compiler for cross-compilation (`brew install zig`)
- SSH access to reMarkable (Developer Mode enabled)

```bash
# Cross-compile for aarch64 Linux
cd sony-dpt
zig cc -target aarch64-linux-musl -static -O2 -o tabletpen-arm64 tabletpen.c -lm

# Deploy
scp tabletpen-arm64 root@10.11.99.1:/home/root/tabletpen
ssh root@10.11.99.1 chmod +x /home/root/tabletpen

# Run
ssh root@10.11.99.1
./start-tabletpen.sh    # or: ./tabletpen --device /dev/input/event2
```

The C binary is statically linked with zero runtime dependencies (musl libc only).

### Sony DPT-RP1/S1

Same C source as reMarkable but cross-compiled for ARM 32-bit:
```bash
# Using Android NDK
$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi21-clang \
  -static -o tabletpen tabletpen.c -lm

# Or using Docker
docker run --rm -v $PWD:/src arm32v7/gcc gcc -static -O2 -o /src/tabletpen /src/tabletpen.c -lm
```

## Project Structure

```
app/src/main/java/com/hid/tabletpen/
├── HidDescriptor.kt        # HID report descriptors (digitizer + mouse + keyboard)
├── BluetoothHidManager.kt  # BT HID device registration, auto-connect, report sending
├── BluetoothScreenshot.kt  # Bluetooth RFCOMM server for screenshot transfer
├── DrawPadView.kt          # Stylus/touch capture, aspect ratio, focus, trackpad gestures
├── AppSettings.kt          # Settings with SharedPreferences persistence
├── MainActivity.kt         # UI, pressure curve, orientation, settings dialog
└── StreamReceiver.kt       # (Legacy) H.264 video stream receiver

mac/
├── screenshot-server.swift # Bluetooth RFCOMM screenshot client (connects to tablet)
├── screenshot-server.sh    # HTTP fallback screenshot server (ADB tunnel)
└── build.sh                # Compile the Swift server

remarkable/
├── start-tabletpen.sh      # Startup script (Bluetooth + SDP + agent + tabletpen)
├── register_hid.c          # SDP HID record registration via BlueZ Unix socket
├── register_hid_sdp.c      # Alternative SDP registration tool
├── sdp_sniff.c             # SDP socket proxy for capturing registration bytes
├── bt_agent.c              # Auto-accept Bluetooth pairing agent
├── patch_sdp.c             # SDP record attribute patcher
├── auto_pair.sh            # Shell-based auto-accept pairing
└── tabletpen.py            # Python implementation (alternative, needs Python 3)

sony-dpt/
├── tabletpen.c             # C implementation (shared with reMarkable)
├── tabletpen.py            # Python implementation (alternative)
├── build.sh                # Cross-compilation script
├── setup.sh                # Deployment script
└── README.md               # Sony DPT-specific documentation
```

## Technology

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Android app | Kotlin, Android SDK | Main tablet app |
| BT HID | `BluetoothHidDevice` API (Android 9+) | Register as HID peripheral |
| HID Descriptor | USB HID standard | Digitizer + mouse + keyboard composite |
| Screenshot transport | Bluetooth RFCOMM | Wireless screenshot transfer |
| Screenshot capture | `screencapture` + `sips` (macOS) | Screen capture + JPEG compression |
| Mac server | Swift + IOBluetooth | RFCOMM client, connects to tablet |
| reMarkable | C + Linux evdev + BlueZ L2CAP | Pen input + Bluetooth HID |
| Cross-compilation | Zig (`aarch64-linux-musl`) | Static binary for reMarkable |
| Build system | Gradle 8.5 + AGP 8.3.0 | Android build |

## System Requirements

| Platform | Requirements |
|----------|-------------|
| **Android tablet** | Android 9+ (API 28), Bluetooth, stylus |
| **Laptop** | macOS or Windows, Bluetooth |
| **Screenshot (Mac)** | Xcode CLI tools (for `swiftc`), or Python 3 for HTTP fallback |
| **reMarkable** | Developer Mode, SSH via USB, Zig compiler on build machine |
| **Sony DPT** | Root access, ADB or SSH, Android NDK or Docker for compilation |

## Known Issues

- reMarkable Paper Pro: macOS resets L2CAP connection on HID report send. Needs BlueZ D-Bus Profile1 API instead of raw sockets.
- Screenshot on first connect may take 5-10 seconds (Bluetooth RFCOMM negotiation). Subsequent screenshots take ~1 second.
- Some macOS apps (OneNote) require high pressure floor (80%+) to register pen input.
- After changing the HID descriptor (app update), you must unpair and re-pair the tablet.

## License

MIT
