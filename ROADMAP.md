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
Only send pixels that changed since the last screenshot. With ScreenCaptureKit frames in memory, pixel-level diffing between consecutive frames is straightforward. For drawing apps where only a small brush area changes per stroke, delta frames could be **1-5KB instead of 70KB** — enabling near-real-time feedback.

Implementation approach:
- Keep previous frame in memory
- Diff against new frame: find changed rectangles
- Send only changed regions as small JPEG tiles
- Client composites tiles onto the previous frame
- Similar to VNC's Tight encoding but simpler (JPEG tiles only)

### ~~Local cursor prediction~~ (Implemented v0.9.0)
Render the pen/mouse cursor locally on the tablet with zero latency, before the Mac screen update arrives. The actual pixel data follows behind but the user sees immediate cursor response. This is VNC's key trick for perceived responsiveness.

### WebP encoding
Replace JPEG with WebP for 30% smaller files at same visual quality, or same size at better quality. Both Android and macOS support WebP natively.

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

## New Ideas (Brainstorm)

### Performance
- **H.264/HEVC hardware encoding** — replace JPEG with hardware video codec for streaming. Mac's VideoToolbox encodes H.264 at 60 FPS with ~1ms latency. 10x smaller than JPEG at same quality. Android's MediaCodec decodes hardware-accelerated.
- **Partial screen invalidation** — Mac tracks dirty regions via ScreenCaptureKit's content rect change notifications. Only capture + send the changed rectangle.
- **Frame skipping under load** — if WiFi is congested, skip frames rather than buffering. Keep the latest frame, discard intermediate ones. Prevents latency buildup.
- **Bluetooth LE for HID** — BLE has lower connection overhead and better power efficiency than Classic. Could reduce HID latency from ~15ms to ~7.5ms on BLE 5.0+.
- **Pre-rendered stroke prediction** — predict the next cursor position based on velocity and render a ghost stroke locally before the Mac confirms it. Common in drawing apps for perceived responsiveness.

### Reliability
- **Connection health indicator** — show real-time connection quality on the toolbar (green/yellow/red based on latency and packet loss). User knows immediately if something is wrong.
- **Automatic reconnection with backoff** — unified reconnection strategy across HID, RFCOMM, and WiFi with exponential backoff + jitter. Currently each has its own retry logic.
- **Offline mode** — when Mac is not connected, tablet works as a standalone drawing pad. Strokes are saved and can be exported as SVG/PNG.
- **Connection diagnostics** — long-press on connection status shows: BT signal strength, WiFi speed, HID latency, RFCOMM state, last error. Helps troubleshooting.
- **Crash recovery** — if the app crashes during a session, auto-restore the last screenshot, focus rect, and settings on relaunch.

### Intelligence
- **Smart screenshot timing** — detect when the Mac screen actually changes (via frame diff) and only send updates then. Saves bandwidth when the screen is static.
- **Adaptive FPS** — dynamically adjust streaming FPS based on screen change rate. Static screen = 1 FPS, active drawing = 30 FPS. Saves battery on both devices.
- **Auto-detect drawing app** — Mac server detects which app is in foreground (Photoshop, Krita, OneNote) and auto-configures pressure curve, shortcuts, and eraser behavior.
- **Gesture learning** — track which gestures the user actually uses and surface them more prominently. Hide unused buttons to reduce toolbar clutter.
- **Smart focus** — auto-detect the active drawing canvas area on the Mac screen and focus on it, instead of requiring manual focus selection.

### Usability
- **Customizable shortcut buttons** — let users configure what each toolbar button does (any modifier + keycode combination). Currently hardcoded to Undo/Redo/Brush.
- **Color picker mode** — tap a point on the screenshot to pick the color at that pixel. Send the appropriate shortcut to the drawing app to select that color.
- **Radial menu** — long-press on the drawing area to show a radial menu with common actions (brush size, color, undo, eraser toggle). Faster than toolbar buttons.
- **Split screen** — show the Mac screenshot on one half of the tablet and the drawing area on the other half. No need to toggle between drawing and viewing.
- **Pinch-to-zoom on screenshot** — zoom into the screenshot for detailed reference viewing, independent of the drawing focus area.
- **Undo on tablet** — maintain a local stroke history. Undo removes the last stroke visually AND sends Ctrl+Z to the Mac. Currently strokes are visual-only on tablet.
- **Multi-language shortcuts** — keyboard shortcuts differ by locale (Ctrl+Z is universal but other shortcuts vary). Detect Mac keyboard layout and adjust.
- **Quick settings gestures** — three-finger swipe up/down to adjust pressure sensitivity on the fly. Three-finger tap to toggle pen/mouse mode.

### New Features
- **Screen recording** — record the Mac screen as a video file on the tablet while drawing. Useful for tutorials and time-lapses.
- **Annotation mode** — draw on top of the screenshot with different colors/tools, save as an annotated image. Like a whiteboard overlay.
- **Voice commands** — "Undo", "Zoom in", "Switch to eraser" via tablet microphone. Hands-free while drawing.
- **Macro recording** — record a sequence of actions (brush strokes, shortcuts) and replay them. Useful for repetitive tasks.
- **Remote presentation** — use the tablet as a laser pointer / annotation tool during presentations. Draw on slides in real-time.
- **Multi-Mac switching** — connect to multiple Macs simultaneously and switch between them. Like a KVM switch for drawing tablets.
- **Pressure calibration wizard** — guided calibration that has the user draw light/medium/heavy strokes and auto-computes optimal floor and curve values.
- **Template overlays** — load grid, perspective, or proportion guide overlays on the drawing area. Visible on tablet only, doesn't affect Mac.
- **Clipboard sharing** — copy an image or text on the Mac, paste it on the tablet's drawing area as a reference. Or copy from tablet and paste on Mac.
- **Touch bar emulation** — show a virtual Touch Bar at the bottom of the tablet screen with context-sensitive controls from the active Mac app.
