# Roadmap

## Near-term

### ~~Auto-recapture screenshot after drawing~~ (Implemented v0.6.0)
After the user finishes a stroke (pen lifts), automatically re-capture the Mac screen after a short delay. This lets the user see the result of their drawing without manually tapping Screenshot.
**Depends on:** Adaptive screenshot quality (need fast enough capture to not disrupt drawing).

### ~~Tilt and rotation support~~ (Implemented v0.7.0)
Samsung S Pen and Boox stylus report tilt data. Add tilt X/Y to the HID descriptor for apps that support it (Photoshop, Krita).

### ~~Eraser tool support~~ (Implemented v0.6.0)
Map `TOOL_TYPE_ERASER` to a separate HID report or button combination. Currently eraser is treated the same as pen.

### ~~Stroke thickness based on pressure~~ (Implemented v0.6.0)
Draw pad visual feedback should show varying stroke width based on pressure, not just a fixed 3px line.

### ~~Adaptive screenshot quality based on connection speed~~ (Implemented v0.5.0)
Screenshots can appear blurry at current fixed 1280px/35% quality. Auto-detect the transport speed (WiFi vs BT, measured from actual transfer times) and dynamically choose resolution and JPEG quality:
- WiFi fast (~200ms): higher resolution (1920px), higher quality (50-60%)
- WiFi slow (~500ms): medium (1280px, 35%)
- BT only (~4s): lower resolution (960px), lower quality (25%)
Also detect the tablet's screen resolution and match the screenshot dimensions to the active drawing area, avoiding unnecessary downscaling when the tablet can display more detail.

### Windows support
The Mac screenshot-server is macOS-only (IOBluetooth, screencapture). Create a Windows equivalent using Win32 Bluetooth APIs and screen capture.

### ~~Focused screenshot~~ (Implemented v0.5.0) — capture only the focus region
When in focus mode, send the focus rectangle coordinates to the Mac server so it captures only that region of the screen. Benefits:
- **Smaller data** — 25% focus = ~75% less pixels to capture, encode, and transfer
- **Higher resolution** — same pixel budget concentrated on a smaller area = sharper detail
- **Lower latency** — smaller JPEG = faster transfer, especially over BT
Implementation: Android sends ocus:x,y,w,h\n\ over RFCOMM/WiFi before \screenshot\n\. Mac uses `screencapture -R x,y,w,h` or crops after capture. Streaming would also benefit — only encode the changing region.

## Mid-term

### ~~ScreenCaptureKit streaming~~ (Implemented v0.8.0)
Replace `screencapture` subprocess with macOS ScreenCaptureKit for lower-latency continuous capture. Could improve stream FPS from ~8 to 30+.

**Design principle:** Detect at runtime whether ScreenCaptureKit is available (macOS 12.3+). If yes, use it for ~30 FPS streaming and ~16ms screenshot latency. If no, fall back to `screencapture` subprocess (current approach, works on macOS 10.x+). The server should auto-select the best available method without user configuration.

**Benefits over screencapture:**
- In-process callback vs subprocess spawn per frame
- Direct memory buffer vs disk I/O (write + read JPEG)
- 30-60 FPS vs 5-8 FPS for streaming
- ~16ms vs ~150ms capture latency
- Per-window capture possible (not just full screen)

### Delta screenshot compression
Only send pixels that changed since the last screenshot. Reduces transfer size dramatically for apps where only a small region changes.

### Multi-monitor support
Let user select which Mac screen to capture. Currently captures the main display only.

### ~~Keyboard shortcuts on tablet~~ (Implemented v0.7.0)
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
