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
│  TabletPen app       │◄──── Screenshot (ADB) ──────│  tablet (HID) │
└─────────────────────┘                              └──────────────┘
```

1. App registers as a Bluetooth HID device with digitizer + mouse descriptors
2. Tablet becomes discoverable, you pair from your laptop's Bluetooth settings
3. Laptop recognizes it as a native pen tablet — no drivers needed
4. Stylus input on the tablet sends HID reports to the laptop
5. Optionally, take screenshots of the laptop screen to use as a drawing reference

## Features

- **Digitizer mode** — absolute pen positioning with pressure sensitivity (0–4095 levels)
- **Mouse mode** — relative cursor movement via pen hover, left click, long-press right click
- **Palm rejection** — finger input moves mouse only (no clicks), palm is ignored, only stylus draws
- **Pressure tuning** — configurable pressure floor and curve to work with apps like OneNote
- **Aspect ratio mapping** — drawing area matches laptop screen ratio (16:10, 16:9, 3:2)
- **Auto orientation** — landscape/portrait based on target aspect ratio, with manual override
- **Focus mode** — zoom into a region of the screenshot, remap drawing area to that region
- **Screenshot** — capture laptop screen and display as drawing background (via ADB tunnel)
- **Auto-connect** — remembers last paired laptop and reconnects on app launch
- **Settings** — all preferences saved and persisted across sessions

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

## Requirements

### Android Tablet
- Android 9+ (API 28) — required for `BluetoothHidDevice` API
- Bluetooth
- Stylus with pressure sensitivity (e.g., Samsung S Pen, Wacom EMR digitizer)
- Tested on: Samsung Galaxy Tab S8 Ultra, Bigme Tab Ultra C Pro

### Laptop
- Bluetooth
- macOS or Windows (any version with Bluetooth HID support)
- For screenshot feature: ADB (Android SDK platform-tools) and Python 3

## Setup

### Basic (pen tablet only)

1. Build and install the app on your tablet:
   ```bash
   ./gradlew installDebug
   ```
2. Open TabletPen, grant Bluetooth permissions
3. Tap **Make Discoverable**
4. On your laptop: Bluetooth settings → pair with the tablet
5. Draw — laptop cursor follows the stylus

The app auto-connects to the last paired laptop on subsequent launches.

### Screenshot Feature (optional)

The screenshot feature lets you see your laptop screen on the tablet while drawing. It uses ADB to tunnel the connection — no WiFi required, works over USB or wireless debug.

**On the Mac:**
```bash
# Set up the ADB tunnel (USB or wireless debug)
adb reverse tcp:9877 tcp:9877

# Start the screenshot server
./mac/screenshot-server.sh
```

**On the tablet:**
- Tap **Screenshot** to capture and display the laptop screen
- Tap **Focus** to zoom into a specific area

The screenshot server uses only built-in macOS tools (`screencapture` + Python 3). No installs needed.

To compile the (optional, unused) Swift Bluetooth server:
```bash
cd mac && ./build.sh
```

## Settings

Open via the **Settings** button:

| Setting | Description | Default |
|---------|-------------|---------|
| Input Mode | Digitizer (absolute pen) or Mouse (relative) | Digitizer |
| Orientation | Auto, Portrait, or Landscape | Auto |
| Rotation | 0°, 90°, 180°, 270° (which edge is up) | 0° |
| Aspect Ratio | 16:10, 16:9, or 3:2 (match your laptop) | 16:10 |
| Clear on Screenshot | Auto-clear strokes when new screenshot arrives | On |
| Pressure Floor | Minimum pressure when pen touches (0–100%) | 80% |
| Pressure Curve | Exponent for pressure ramp (lower = more sensitive) | 0.50 |
| Mouse Sensitivity | Speed multiplier for mouse mode | 2.0x |

## Project Structure

```
app/src/main/java/com/hid/tabletpen/
├── HidDescriptor.kt        # HID report descriptors (digitizer + mouse) and report builders
├── BluetoothHidManager.kt  # BT HID device registration, auto-connect, report sending
├── BluetoothScreenshot.kt  # Screenshot requests over HTTP (ADB tunnel)
├── DrawPadView.kt          # Stylus/touch capture, aspect ratio boundary, focus mode
├── AppSettings.kt          # Settings data class with SharedPreferences persistence
├── MainActivity.kt         # UI, pressure curve, orientation, settings dialog
└── StreamReceiver.kt       # (Legacy) H.264 video stream receiver

mac/
├── screenshot-server.sh    # Screenshot server (Python HTTP + screencapture)
├── screenshot-server.swift # (Experimental) Swift Bluetooth RFCOMM server
└── build.sh                # Compile the Swift server
```

## Technology

- **Kotlin** — Android app
- **Android BluetoothHidDevice API** (API 28+) — registers as Bluetooth HID peripheral
- **USB HID Descriptor** — standard digitizer pen + mouse composite device
- **ADB reverse tunnel** — routes HTTP over USB/wireless debug for screenshots
- **Python 3 + screencapture** — macOS screenshot server (zero dependencies)
- **Swift + IOBluetooth** — experimental Bluetooth RFCOMM (macOS interop issues)

## License

MIT
