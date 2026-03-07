# Changelog

## v1.2.0 (2026-03-07)

### Features
- **SCK push-model streaming** — 29 FPS at native resolution (was 6 FPS with legacy `screencapture` subprocess). ScreenCaptureKit callback directly encodes and sends frames with back-pressure. 3ms encode vs 140ms legacy. Automatic fallback to legacy on macOS < 12.3 or when SCK is unavailable.
- **Configurable screenshot/stream quality** — new Settings: Auto, Low, Medium, Good, Best. Separate presets for screenshots and streaming. Best streams at native display resolution (1920x1080) at 80% JPEG quality.
- **Auto quality defaults to Best on WiFi** — starts at full quality, degrades only on slow/BT connections. No more unnecessary downscaling on fast networks.

### Improvements
- **SCK captures at native display resolution** — no resize overhead for push-model streaming. Direct JPEG encode at 11ms vs 57ms with resize.
- **SCK queueDepth increased to 8** — fixes buffer pool exhaustion that caused SCK callbacks to stop after 3 frames.
- **WiFi socket cleanup before streaming** — closes screenshot socket before opening stream connection to avoid ECONNREFUSED.

### Testing
- **SCK push-model E2E tests** — verifies push frames are delivered at 5+ FPS, tablet receives frames with changing content
- **Cursor-trigger test** — verifies HID cursor movement triggers SCK streaming frames
- **SCK standalone diagnostic** — `test/sck-test.swift` verifies SCK callback delivery independently

## v1.1.3 (2026-03-06)

### Fixes
- **Fix input lag on Mac** — BT HID reports now sent on background thread instead of blocking the UI thread. Historical pen events used for local drawing only (not flooded over BT). Reduces BT report rate from ~240/s to ~60-120/s.
- **Fix two-finger tap right-click** — single two-finger tap now reliably triggers right-click. Fixed: distance check used wrong finger's position, and tiny finger jitter during tap was falsely detected as scroll.
- **Fix streaming not updating** — SCK `grabFrame()` returned stale buffers during streaming. Forced legacy `screencapture` for streaming; added `streamingMode` flag to bypass SCK adaptive FPS filter.
- **Fix streaming after screenshot** — `startStream()` now closes existing WiFi socket before opening stream connection (Mac server only accepts one WiFi client).
- **Reject non-target BT connections** — incoming HID connections from non-target devices are immediately disconnected, preventing session hijacking.
- **Auto-dismiss unwanted pairing popups** — intercepts `ACTION_PAIRING_REQUEST` for non-target devices and cancels bonding before system UI appears.

### Improvements
- **Version shown in Settings dialog** — "TabletPen v1.1.3" at bottom of settings
- **BT QoS hint** — HID registration requests guaranteed 8ms latency for responsive pen input
- **View rendering coalesced to vsync** — `postInvalidateOnAnimation()` prevents redundant redraws
- **Removed excessive logging** — no more per-event Log.d during pen/mouse movement

### Testing
- **E2E tests rewritten in Python pytest** — 26 tests across 4 modules replacing 830-line bash script
- **10 functional instrumented gesture tests** — two-finger tap→right-click, single tap→left-click, drag→mouse move, scroll, jitter tolerance
- **Stream content verification** — E2E test opens/closes TextEdit during streaming, asserts frame hashes change

## v1.1.0 (2026-03-06)

### Features
- **Customizable keyboard shortcuts** — 8 configurable shortcut slots, first 4 shown on toolbar, all 8 in radial menu
- **Radial menu** — long-press on draw pad to show circular shortcut menu. Drag to select, release to execute. Professional drawing tablet UX.
- **App presets** — Default, Photoshop, Krita shortcut profiles. Select in Settings.
- 10 new HID keycodes: Save, Copy, Paste, Cut, Select All, Eraser, Brush, Space, Tab, New
- **Whiteboard app presets** — OneNote, Microsoft Whiteboard, Excalidraw, macOS Preview

## v1.0.0 (2026-03-06)

### Features
- **Delta compression** — streaming sends only changed 64x64 tiles instead of full frames. Typical brush stroke: ~5KB vs 70KB (93% reduction). Makes BT streaming viable at 10+ FPS.
- Key frames every 30 frames (1s) prevent visual drift
- Automatic fallback to full frames when >50% of screen changes
- Protocol extended: type byte prefix (0x00=full, 0x01=delta, 0x02=key)

## v0.9.0 (2026-03-06)

### Features
- **Ghost stroke prediction** — semi-transparent stroke trail appears instantly while drawing, cleared when Mac screenshot confirms
- **Predicted cursor** — ghost cursor drawn 30ms ahead of actual position for perceived zero-latency
- **Adaptive FPS streaming** — Mac server skips unchanged frames (16-pixel sample diff). Active drawing: full FPS. Static screen: ~1 FPS. Saves bandwidth + battery.
- **Show ghost stroke setting** — toggle in Settings (on by default)

## v0.8.0 (2026-03-06)

### Features
- **ScreenCaptureKit streaming** — 22+ FPS (was 8 FPS) on macOS 12.3+, ~100ms screenshot (was ~300ms)
- Automatic fallback to `screencapture` subprocess on older macOS
- Persistent SCStream keeps latest frame buffered for instant grab

## v0.7.0 (2026-03-06)

### Features
- **Pen tilt support** — X/Y tilt data sent to host for brush angle in Photoshop, Krita, etc.
- **Keyboard shortcuts** — Undo, Redo, Brush [-]/[+] buttons on the tablet toolbar
- HID keyboard report enhanced to support keycodes (was modifiers-only)

## v0.6.0 (2026-03-06)

### Features
- **Auto-recapture** — automatically re-captures Mac screen 1s after pen lifts (opt-in via Settings)
- **Pressure-based stroke width** — visual feedback on tablet shows varying thickness based on pen pressure (1-8px)
- **Eraser tool support** — flip pen to eraser mode, sends HID eraser events to Mac drawing apps

## v0.5.0 (2026-03-06)

### Features
- **Adaptive screenshot quality** — auto-detects connection speed and adjusts resolution/quality: WiFi fast (1920px/60%), WiFi slow (1280px/40%), BT (960px/25%)
- **Focused screenshot capture** — when in focus mode, Mac captures only the focused screen region instead of full screen. Sharper detail, less data, lower latency.

## v0.4.0 (2026-03-05)

### Features
- **WiFi screenshot transport** — 10x faster than Bluetooth (~200ms vs ~4s), auto-discovered via BT
- **Bidirectional WiFi** — tries Mac-as-server first, falls back to tablet-as-server (bypasses corporate firewalls/CrowdStrike)
- **Screen streaming** — continuous live screen capture over WiFi (~8 FPS)
- **Auto-update** — app checks GitHub Releases on startup, downloads and installs with one tap
- **Multi-device dropdown** — switch between multiple paired laptops from the toolbar
- **Configurable stroke color** — Auto (detects from screenshot brightness), White, Black, Red, Blue
- **Configurable cursor style** — None, Crosshair, Dot, Circle
- **Device class filtering** — spinner shows only computers/phones, not speakers or headphones
- **Unified connection flow** — HID and Screenshot always target the same device

### Improvements
- **Click detection fix** — deferred click with 8px slop prevents taps from misfiring as drags
- **Digitizer stabilization** — 5px dead zone on pen-down prevents jitter during taps
- **Optimized screenshot pipeline** — direct JPEG capture, ImageIO resize, configurable quality/resolution
- **Exponential backoff** — Mac server connection retries start at 0.5s, cap at 15s (was fixed 15s)
- **Saved last device** — Mac server remembers last connected tablet for instant reconnect
- **RFCOMM server stays open** — instant reconnection after server restart (no SDP re-registration delay)
- **Broken pipe recovery** — closes dead BT sockets after sleep, server loop restarts automatically
- **BT state receiver** — detects BT disable on Boox devices, unblocks stuck `accept()` call
- **Streamlined toolbar** — merged status into buttons, animated "Registering..." indicator
- **Focus aspect ratio fix** — no longer stretches on orientation/settings changes

### Testing
- **62 unit tests** — HidDescriptor, PenMath, AppSettings, UpdateChecker
- **8 instrumented gesture tests** — drag, tap, scroll, long-press, rapid taps, edge cases
- **13-phase E2E test suite** — HID input, BT/WiFi screenshot, streaming, server kill/restart, BT toggle, app kill, sleep/wake, stress test, latency measurement
- **Phase filtering** — `./test/e2e.sh 9,10` runs only specified phases

### Infrastructure
- **CLAUDE.md** — project instructions for Claude Code (5-phase dev process)
- **Slash commands** — `/plan`, `/fix`, `/test`, `/release`, `/explore`
- **PenMath.kt** — extracted pure functions for testability

## v0.1.0 (2026-03-05)

### Initial Release
- Bluetooth HID digitizer (pen absolute positioning, pressure 0-4095)
- Mouse mode (relative cursor movement)
- Trackpad finger gestures (1-finger move, 2-finger scroll/pinch, tap-click)
- Screenshot over Bluetooth RFCOMM
- Pressure tuning (floor + exponential curve)
- Aspect ratio mapping (16:10, 16:9, 3:2)
- Focus mode (zoom into screen region)
- Settings persistence
- Supported: Samsung Galaxy Tab S, Boox, reMarkable Paper Pro, Sony DPT
