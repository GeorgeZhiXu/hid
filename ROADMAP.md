# Roadmap

## Near-term

### Auto-recapture screenshot after drawing
After the user finishes a stroke (pen lifts), automatically re-capture the Mac screen after a short delay. This lets the user see the result of their drawing without manually tapping Screenshot.

### Tilt and rotation support
Samsung S Pen and Boox stylus report tilt data. Add tilt X/Y to the HID descriptor for apps that support it (Photoshop, Krita).

### Eraser tool support
Map `TOOL_TYPE_ERASER` to a separate HID report or button combination. Currently eraser is treated the same as pen.

### Stroke thickness based on pressure
Draw pad visual feedback should show varying stroke width based on pressure, not just a fixed 3px line.

### Windows support
The Mac screenshot-server is macOS-only (IOBluetooth, screencapture). Create a Windows equivalent using Win32 Bluetooth APIs and screen capture.

## Mid-term

### ScreenCaptureKit streaming
Replace `screencapture` subprocess with macOS ScreenCaptureKit for lower-latency continuous capture. Could improve stream FPS from ~8 to 30+.

### Delta screenshot compression
Only send pixels that changed since the last screenshot. Reduces transfer size dramatically for apps where only a small region changes.

### Multi-monitor support
Let user select which Mac screen to capture. Currently captures the main display only.

### Keyboard shortcuts on tablet
Add configurable shortcut buttons on the tablet (Undo, Redo, Brush size +/-) that send keyboard HID reports to the Mac.

### Battery status reporting
Report the tablet's battery level to the Mac via HID. Some apps/OS can display peripheral battery.

## Long-term

### Full USB HID gadget mode
On rooted Android devices, use USB gadget mode to present as a USB HID device. Eliminates Bluetooth latency entirely. Requires root + custom kernel module.

### iPad support (Sidecar alternative)
If Apple ever opens BluetoothHidDevice-equivalent APIs on iOS, or via a companion Mac app that bridges the connection.

### Collaborative drawing
Multiple tablets connected to the same Mac, each with their own cursor/color. Useful for teaching or pair drawing.

### Handwriting recognition integration
Capture pen strokes locally, run handwriting recognition, and send the recognized text to the Mac as keyboard input.

### Pressure-sensitive scroll
Use pen pressure while scrolling to control scroll speed. Light pressure = slow scroll, heavy = fast.

## Infrastructure

### CI/CD pipeline
Set up GitHub Actions to:
- Build APK + Mac binary on every push
- Run unit tests automatically
- Run E2E tests on a dedicated test device (requires self-hosted runner)
- Auto-publish releases when version is bumped

### Multi-device test matrix
Test on Samsung Galaxy Tab, Boox, and other devices to catch device-specific issues (like the Boox BT toggle bug).

### Performance benchmarks
Track screenshot latency, HID round-trip time, and stream FPS across releases. Alert on regressions.

### Localization
Support multiple languages for the settings UI. Currently English-only.
