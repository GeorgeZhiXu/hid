# TabletPen — Claude Code Project Instructions

## Project Overview
Android app that turns tablets with Wacom digitizers into wireless Bluetooth HID drawing tablets. Includes Mac screenshot server for screen mirroring over BT/WiFi.

## Architecture
- `app/` — Android Kotlin app (BluetoothHidDevice API)
- `mac/` — Swift screenshot server (IOBluetooth RFCOMM + WiFi TCP)
- `sony-dpt/` — C implementation for Sony DPT (raw L2CAP)
- `remarkable/` — reMarkable Paper Pro support
- `test/` — E2E test scripts + Swift helpers

## Development Process

Follow these 5 phases for every task:

### Phase 1: Requirement Analysis
- Clarify the user's goal and constraints
- Ask questions if the scope is ambiguous
- **Review ROADMAP.md** — check if the request relates to any planned features
- Look for organically related roadmap items that can be designed and implemented together (e.g., adaptive quality + focused screenshot are natural companions)
- Identify which components are affected (Android app, Mac server, both)
- Check ISSUES.md for any known issues related to the change

### Phase 2: Design & Planning
- Use `EnterPlanMode` for non-trivial changes
- Write a plan covering: what changes, which files, verification steps
- Consider edge cases: sleep/wake, reconnection, different networks
- Get user approval before implementing

### Phase 3: Implementation & Documentation
- Read existing code before modifying
- Keep changes minimal — don't refactor unrelated code
- Extract pure functions to `PenMath.kt` for testability
- **Add/update tests for every change** — don't wait for the user to ask:
  - Add E2E tests for new features (pytest in `test/`)
  - Add instrumented tests for gesture/UI changes
  - Add unit tests for logic changes
- **Update documentation** for every user-facing change:
  - `README.md` — user-facing features, settings, connection flow
  - `CHANGELOG.md` — add entry under current version
  - `ISSUES.md` — add new issues discovered, update resolved ones
  - `ROADMAP.md` — mark implemented items, add new ideas that emerge

### Phase 4: Testing
- **Run ALL tests before asking user to test manually:**
  1. Unit tests: `./gradlew test`
  2. Instrumented tests: `./gradlew connectedDebugAndroidTest` (if tablet connected)
  3. E2E tests: `python3 -m pytest test/ -v` (if BT paired with Mac)
  4. For Mac changes: rebuild with `cd mac && ./build.sh`
- **Never claim code works without running tests and seeing results**
- **Use the connected device for automated testing** — don't ask user to test manually when adb/E2E tests can verify
- **Install APK on device after building** — verify via adb install, not just compilation
- When tests fail, investigate logs (logcat, server output) before asking user

### Phase 5: Commit, Push & Publish
- Bump `versionCode` and `versionName` in `app/build.gradle.kts`
- Verify all docs are updated (README, CHANGELOG, ISSUES, ROADMAP)
- Commit with descriptive message explaining WHY, not just WHAT
- Push to main branch
- Update GitHub release if APK or Mac binary changed:
  ```bash
  gh release create vX.Y.Z app/build/outputs/apk/debug/app-debug.apk mac/screenshot-server#screenshot-server-macos-arm64
  ```

## Project Documentation
- `README.md` — User-facing guide: features, setup, connection flow, settings
- `CHANGELOG.md` — All changes by version, newest first
- `ISSUES.md` — Known issues (open + resolved) with root causes and workarounds
- `ROADMAP.md` — Future ideas organized by near/mid/long-term + infrastructure
- `CLAUDE.md` — This file: dev process, build commands, technical details

## Build Commands

```bash
# Android APK
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew assembleDebug

# Unit tests
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew test

# Mac screenshot server
cd mac && swiftc -framework IOBluetooth -framework Foundation -framework ImageIO screenshot-server.swift -o screenshot-server

# E2E tests (requires tablet via USB + BT paired with Mac)
python3 -m pytest test/ -v

# Instrumented tests (requires tablet via USB)
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew connectedDebugAndroidTest
```

## Key Technical Details

### Bluetooth Connection Flow
1. App opens → `BluetoothHidDevice.registerApp()` (HID registration)
2. User taps "Pair" → tablet becomes discoverable
3. Laptop pairs from Bluetooth settings → HID connection established
4. Mac screenshot-server connects via RFCOMM → exchanges WiFi info → WiFi TCP established
5. HID and Screenshot always target the same device

### HID Reports (sent over Bluetooth)
- Report ID 1: Digitizer (7 bytes) — X, Y, pressure, tip/barrel/in-range
- Report ID 2: Mouse (4 bytes) — dx, dy, buttons, scroll
- Report ID 3: Keyboard (8 bytes) — modifiers + keycodes

### Screenshot Protocol
- BT RFCOMM: `"screenshot\n"` → `[4-byte BE size][JPEG data]`
- WiFi TCP: Same protocol, bidirectional connection (Mac-as-server preferred, tablet-as-server fallback)
- Streaming: `"stream\n"` → continuous `[4-byte size][JPEG]` frames

### File Conventions
- Pure math/logic → `PenMath.kt` (tested in `PenMathTest.kt`)
- HID descriptor/reports → `HidDescriptor.kt` (tested in `HidDescriptorTest.kt`)
- BT HID management → `BluetoothHidManager.kt`
- Screenshot + WiFi → `BluetoothScreenshot.kt`
- UI + event routing → `MainActivity.kt`
- Settings persistence → `AppSettings.kt`
- Auto-update → `UpdateChecker.kt`

## Testing

### Unit Tests (62 tests, no hardware needed)
- `HidDescriptorTest` — report byte encoding, clamping
- `PenMathTest` — pressure curve, mouse chunking, WiFi parsing
- `AppSettingsTest` — enums, aspect ratios
- `UpdateCheckerTest` — semver comparison

### Instrumented Tests (21 tests, requires tablet via USB)
- `GestureTest` (11) — crash tests for drag, tap, scroll, long-press, radial menu, shortcuts
- `TwoFingerTapTest` (10) — functional assertions: two-finger tap→right-click, single tap→left-click, drag→mouse move, scroll, jitter tolerance

### E2E Tests (26 tests, requires tablet USB + BT paired with Mac)
Python pytest suite in `test/`:
- `test_hid_input.py` — cursor movement, tap-click, latency measurement
- `test_screenshot.py` — BT/WiFi screenshot, streaming, delta compression
- `test_resilience.py` — server kill/restart, BT toggle, app kill, sleep/wake
- `test_stress.py` — rapid screenshots, adaptive quality, ghost stroke, shortcuts, radial menu

Legacy: `test/e2e.sh` (deprecated bash script, same coverage)

## Common Pitfalls
- `socket.isConnected` is unreliable on BT after sleep — always handle broken pipe
- `input.available()` returns 0 on BT sockets — use blocking reads with timeout threads
- macOS `IOBluetoothDevice.isConnected()` doesn't reflect HID connections
- `openRFCOMMChannelSync` often fails synchronously but succeeds via async delegate
- `ensureConnected()` must NEVER call `unregisterApp()` — kills active HID session
- After server restart, first BT screenshot hits stale socket — retry logic needed
