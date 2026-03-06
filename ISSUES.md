# Known Issues

## Open

### RFCOMM does not reconnect after Bluetooth disable/enable on Boox devices
**Status:** Open — workaround available
**Device:** Boox TabUltraCPro (Android 12, custom ROM)
**Symptoms:** After toggling Bluetooth off and back on, HID reconnects but the RFCOMM screenshot connection does not. Mac server keeps retrying but `openRFCOMMChannelSync` always fails.
**Root cause:** Boox's `BluetoothServerSocket.accept()` blocks indefinitely when BT is disabled — doesn't throw an IOException. The BroadcastReceiver fix closes the socket to unblock it, but the server loop doesn't successfully re-establish `listenUsingInsecureRfcommWithServiceRecord` after BT comes back. The server thread may be exiting or the BT stack state is inconsistent on Boox.
**Workaround:** Kill and relaunch the TabletPen app after BT toggle.
**Investigation notes:** Android logs show "BT turning off — closing server socket" and "Screenshot target device" but no "Starting RFCOMM server" after BT re-enable. The server loop's retry mechanism may not be re-entering properly.


### WiFi stream fails after single screenshot ("Broken pipe")
**Status:** Resolved
**Symptoms:** After taking a WiFi screenshot, tapping Stream fails with "Broken pipe" or "no frames received". Stream works fine on a fresh WiFi connection (without prior screenshot).
**Root cause:** The Mac's `handleWifiClient()` keeps the TCP connection open in a command loop after processing "screenshot". Android reuses the same `wifiSocket` for "stream". But the socket state becomes inconsistent — possibly because the Mac's read loop doesn't properly transition between single-shot and streaming modes, or the screenshot response leaves partial data in the buffer.
**Fix:** `startStream()` now opens a FRESH TCP connection instead of reusing the screenshot socket. Streaming and screenshots use separate TCP connections, avoiding socket state confusion when switching between request-response and push modes.

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

## Resolved

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
