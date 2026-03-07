# Known Issues

## Open

### H.264 decode produces bitmaps for ~25% of frames
**Status:** Open — functional but suboptimal
**Symptoms:** Of 132 H.264 frames received, only 31 decode to Bitmap. The rest have null output from MediaCodec.
**Root cause:** MediaCodec's hardware decode pipeline buffers 2-3 frames before producing output. Small P-frames (0KB skip frames) don't generate output. The `dequeueOutputBuffer` timeout of 10ms is sometimes not enough.
**Impact:** Tablet displays ~25% of frames — enough to show screen changes but with lower effective FPS than the 14 FPS encode rate. JPEG push-model at 29 FPS is smoother.
**Future fix:** Use MediaCodec async callback mode or a dedicated drain thread that continuously polls output. Or render to Surface directly (zero-copy, bypasses Bitmap conversion).

### RFCOMM does not reconnect after Bluetooth disable/enable on Boox devices
**Status:** Open — workaround available
**Device:** Boox TabUltraCPro (Android 12, custom ROM)
**Symptoms:** After toggling Bluetooth off and back on, HID reconnects but the RFCOMM screenshot connection does not. Mac server keeps retrying but `openRFCOMMChannelSync` always fails.
**Root cause:** Boox's `BluetoothServerSocket.accept()` blocks indefinitely when BT is disabled — doesn't throw an IOException. The BroadcastReceiver fix closes the socket to unblock it, but the server loop doesn't successfully re-establish `listenUsingInsecureRfcommWithServiceRecord` after BT comes back. The server thread may be exiting or the BT stack state is inconsistent on Boox.
**Workaround:** Kill and relaunch the TabletPen app after BT toggle.
**Investigation notes:** Android logs show "BT turning off — closing server socket" and "Screenshot target device" but no "Starting RFCOMM server" after BT re-enable. The server loop's retry mechanism may not be re-entering properly.


### WiFi stream ECONNREFUSED after screenshot
**Status:** Resolved in v1.1.3
**Symptoms:** After taking a WiFi screenshot, tapping Stream fails with ECONNREFUSED. Mac server only accepts one WiFi client; the screenshot socket was still open when streaming tried to connect.
**Root cause:** `startStream()` opened a fresh TCP connection but didn't close the existing `wifiSocket` first. Mac server only accepts one client on port 9877.
**Fix:** `startStream()` now closes `wifiSocket` before opening the stream connection.

### SCK push-model requires screen content changes to activate
**Status:** Resolved — queueDepth fix + fallback
**Symptoms:** SCK push-model got 0 frames during 3s probe, fell back to legacy.
**Root cause:** `queueDepth = 3` caused CVPixelBuffer pool exhaustion — SCK had no free buffers to deliver new frames. After increasing to 8, SCK delivers 24+ FPS continuously.
**Note:** SCK is compositor-event-driven. On a completely static desktop with no cursor/window activity, frames may not be delivered. The 3-second probe with TextEdit window open ensures activation. Cursor movement via HID also triggers frames.
**Fix:** queueDepth 3→8, automatic fallback to legacy if probe fails.

### macOS `openRFCOMMChannelSync` always fails synchronously
**Status:** Accepted — workaround in place
**Symptoms:** Every call to `openRFCOMMChannelSync` returns failure, but the async delegate `rfcommChannelOpenComplete` fires success 1-15s later.
**Root cause:** IOBluetooth framework quirk on Apple Silicon Macs. The sync call returns before the connection is fully established.
**Workaround:** Exponential backoff (0.5s→15s) waiting for the async delegate callback.

### macOS Bluetooth permission per-binary
**Status:** Accepted — documented
**Symptoms:** Recompiling the Mac server binary invalidates the macOS Bluetooth permission. User must re-allow in System Settings.
**Workaround:** Don't recompile unless source changed. E2E test skips recompile if binary is newer than source.

### CrowdStrike blocks incoming TCP to Mac
**Status:** Resolved — bidirectional WiFi
**Symptoms:** WiFi Mac-as-server fails because CrowdStrike's `pf` rules block incoming TCP on port 9877.
**Resolution:** Bidirectional WiFi — if Mac-as-server fails, tablet starts its own server and Mac connects outbound (outbound is never blocked).

### Slow pen input on certain Macs (not an app issue)
**Status:** Not a bug — Mac hardware/OS issue
**Symptoms:** Pen drawing feels slow/laggy, even with hardware Wacom/Huion digitizer connected directly to Mac.
**Root cause:** Some Mac minis have system-level input processing overhead that affects ALL pen input, not just Bluetooth HID. Confirmed by testing with a physical Huion digitizer on the same Mac — equally slow.
**Workaround:** Test on a different Mac, or check macOS accessibility features, display scaling, or background processes.

### Android InputDispatcher dropping pen events — "stylus device already active"
**Status:** Resolved
**Symptoms:** Pen input laggy, curves become angles, pressure=0. Android system log: "Dropping event because a pointer for a stylus device is already active"
**Root cause:** `BluetoothHidDevice.registerApp()` with `SUBCLASS2_DIGITIZER_TABLET` causes Android to create a local virtual digitizer device. When the real Wacom pen touches the screen, Android sees two active stylus devices and drops events from one.
**Fix:** Changed subclass to `SUBCLASS1_NONE`. Android no longer creates a local digitizer device, so the real pen has exclusive access. The HID descriptor still describes a digitizer to the REMOTE host (Mac) — only the local Android-side classification changed.

### Unwanted pairing popups from previously paired devices
**Status:** Mitigated — partial fix
**Symptoms:** After unpairing a MacBook Air from both sides, the tablet still receives pairing requests when the HID app is running. The MacBook Air shows "Connection Request from TabUltraCPro."
**Root cause:** Android's BT stack retains device entries in `bt_config.conf` even after unpairing. When `registerApp()` makes the tablet connectable, the BT stack may page previously known HID hosts at the system level, outside app control.
**Mitigation:** App intercepts `ACTION_PAIRING_REQUEST` and auto-cancels bonding for non-target devices. This suppresses the popup in most cases but can't prevent the underlying BT page from the OS.
**Full fix:** Toggle BT off/on on both devices, or clear BT cache on the Mac (`sudo defaults delete /Library/Preferences/com.apple.Bluetooth`).

## Resolved

### Input lag on Mac — pen and finger trackpad
**Status:** Resolved in v1.1.3
**Symptoms:** Visible lag when drawing with pen or moving cursor with finger on Mac.
**Root cause:** Three compounding issues: (1) `BluetoothHidDevice.sendReport()` blocked the UI thread, stalling touch event processing when BT buffer backed up; (2) historical pen events (~240Hz from Wacom digitizer) each generated a separate BT report, flooding the link; (3) `invalidate()` on every touch event forced redundant full-view redraws.
**Fix:** BT reports sent on dedicated background thread; historical events used for local drawing only; view redraws coalesced to vsync.

### Two-finger tap requires double-tap for right-click
**Status:** Resolved in v1.1.3
**Symptoms:** Two-finger tap on trackpad rarely triggers right-click on first attempt, requires double-tap.
**Root cause:** Two issues: (1) `ACTION_UP` distance check compared first finger's down position with last finger's up position — always ~300-400px apart, exceeding `TAP_SLOP`; (2) tiny finger movement during tap (~1px average) accumulated into scroll, setting `trackMoved = true`.
**Fix:** Skip distance check for multi-finger taps; added `twoFingerDisplacement` threshold (15px) before marking as moved.

### Non-target device hijacks HID session
**Status:** Resolved in v1.1.3
**Symptoms:** When a previously paired MacBook Air connects while tablet is paired to Mac mini, HID reports get sent to wrong device, input stops working.
**Fix:** `onConnectionStateChanged` rejects connections from devices that don't match `autoConnectAddress`.

### Screenshot button disappearing after first capture
**Cause:** `updateScreenshotBtn()` had a guard `if (!screenshotBtn.isEnabled) return` that prevented re-enabling.
**Fix:** Callbacks reset button state directly.

### HID lag/freezing after sleep
**Cause:** `ensureConnected()` called `unregisterApp()` which killed the active HID session.
**Fix:** `ensureConnected()` now just tries `tryAutoConnect()` without touching registration.

### Focus area aspect ratio halved
**Cause:** `targetAspectRatio` setter double-applied the focus transformation.
**Fix:** `settingFocusRatio` flag prevents recomputation when called from `confirmFocusSelection()`.

### Device spinner showing stale devices
**Cause:** Spinner was populated from a persisted JSON map instead of live `bondedDevices`.
**Fix:** Spinner now reads `BluetoothAdapter.bondedDevices` filtered by `BluetoothClass.Device.Major.COMPUTER`.

### Mac server stuck on wrong device (Z Fold5 loop)
**Cause:** `openRFCOMMChannelSync` failed on Z Fold5, delegate also fired error and called `connectToTablet()` which restarted scan from the beginning.
**Fix:** Delegate error handler doesn't retry independently. `tablet` only set on successful connection. Device deduplication and saved-device-first ordering.

### Broken pipe loop after tablet sleep
**Cause:** `socket.isConnected` returns true for dead BT sockets. `doRequestBT` kept writing to dead socket.
**Fix:** On IOException, close socket and set `connectedSocket = null` so server loop restarts.

### WiFi info reader blocking BT screenshots
**Cause:** `readWifiInfoAndConnect` thread blocks on `inputStream.read()`. Concurrent `doRequestBT` reads from same stream, corrupting the protocol.
**Fix:** `btReadBusy` flag prevents `doRequestBT` while WiFi info reader is active. Flag clears after 5s timeout.
