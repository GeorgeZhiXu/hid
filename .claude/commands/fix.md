# Diagnose and fix a bug

Investigate a bug report, find the root cause, fix it, and verify.

## Steps

1. **Understand the symptom** — What's the user seeing? Get logs if available:
   - Android: `adb logcat -s BtScreenshot,BtHidManager`
   - Mac: server stdout
   - Both: timing, connection state

2. **Reproduce** — Identify the conditions that trigger the bug

3. **Root cause** — Read the relevant code, trace the execution path

4. **Fix** — Minimal change that addresses the root cause:
   - Don't refactor surrounding code
   - Don't add features
   - Add defensive checks if the bug was a missing guard

5. **Test** — Run unit tests + verify the specific scenario

6. **Commit** — Descriptive message: "Fix [symptom]: [root cause explanation]"

## Bug description
$ARGUMENTS
