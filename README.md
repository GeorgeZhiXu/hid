# TabletPen

Turn a Samsung Galaxy Tab (or any Android tablet with a Wacom digitizer) into a wireless Bluetooth drawing tablet for your laptop — **no software install required on the laptop**.

The app uses Android's `BluetoothHidDevice` API to register the tablet as a standard Bluetooth HID digitizer. Your laptop sees it exactly like a Wacom tablet: absolute positioning, pressure sensitivity, barrel button — all over Bluetooth.

## How It Works

```
┌─────────────────────┐        Bluetooth HID        ┌──────────────┐
│  Android Tablet      │  ────────────────────────►  │  Laptop       │
│  S Pen / Stylus      │   Digitizer reports:        │  (macOS/Win)  │
│  Wacom digitizer     │   X, Y, pressure,           │               │
│                      │   tip, barrel, in-range      │  Sees a pen   │
│  TabletPen app       │                              │  tablet (HID) │
└─────────────────────┘                              └──────────────┘
```

1. App registers as a Bluetooth HID device with a digitizer pen descriptor
2. Tablet becomes discoverable, you pair from your laptop's Bluetooth settings
3. Laptop recognizes it as a native pen tablet — no drivers, no host software
4. Stylus input on the tablet sends HID reports: absolute X/Y, pressure, buttons

## HID Report Format

Report ID 1 — Digitizer (7 bytes):

| Byte | Field | Range |
|------|-------|-------|
| 0 | Buttons: `[0]` Tip Switch, `[1]` Barrel Switch, `[2]` In Range | 0/1 each |
| 1–2 | X (uint16 LE) | 0–32767 |
| 3–4 | Y (uint16 LE) | 0–32767 |
| 5–6 | Tip Pressure (uint16 LE) | 0–4095 |

## Requirements

- Android tablet with Wacom digitizer and Bluetooth (e.g., Samsung Galaxy Tab S6/S7/S8/S9)
- Android 9+ (API 28) — required for `BluetoothHidDevice` API
- Laptop with Bluetooth (macOS or Windows)

## Setup

1. Open in Android Studio
2. Build and install on your tablet
3. Grant Bluetooth permissions when prompted
4. Tap **Make Discoverable**
5. On your laptop: Bluetooth settings → pair with **TabletPen**
6. Draw on the pad — laptop cursor follows the stylus

## Project Structure

```
app/src/main/java/com/hid/tabletpen/
├── HidDescriptor.kt       # HID report descriptor + report builder
├── BluetoothHidManager.kt # BT HID device registration & report sending
├── DrawPadView.kt         # Stylus/touch capture with visual feedback
└── MainActivity.kt        # Permissions, UI, wiring
```

## Status

Prototype — validates the Bluetooth HID digitizer approach. Known areas for improvement:

- Tilt support (S Pen provides it; needs HID descriptor extension)
- Eraser tool type
- Aspect ratio mapping (tablet vs screen proportions)
- Landscape orientation
- Reconnection to previously paired devices

## License

MIT
