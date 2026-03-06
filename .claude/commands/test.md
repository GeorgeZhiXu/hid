# Run tests

Run the appropriate test suite based on what's available.

## Steps

1. **Unit tests** — Always run first (fast, no hardware needed):
   ```bash
   ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}" ./gradlew test
   ```

2. **E2E tests** — Run if tablet is connected via USB:
   ```bash
   PATH="$PATH:/opt/homebrew/share/android-commandlinetools/platform-tools" ./test/e2e.sh
   ```

3. **Check for connected device** first:
   ```bash
   adb devices | grep device$
   ```
   If no device, only run unit tests and report that E2E was skipped.

4. **Report results** — Summarize pass/fail counts and any failures that need investigation.

## Scope
$ARGUMENTS
